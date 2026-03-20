(ns net.brdloush.livewire.entity-serialize
  "Shared entity serialization machinery.
   Converts live Hibernate-managed entity objects to plain Clojure maps.
   Used by both jpa-query and query/diff-entity."
  (:require [net.brdloush.livewire.core :as core]
            [net.brdloush.livewire.introspect :as intro]
            [clojure.string :as str]))

(defn build-meta-map
  "Returns a map of {entity-simple-name -> inspect-entity-result} for every
   entity known to the current persistence unit."
  []
  (into {}
        (map (fn [{:keys [name]}]
               [name (intro/inspect-entity name)])
             (intro/list-entities))))

(defn entity->map
  "Recursively converts a JPA entity object to a plain Clojure map.
   `visited` is an immutable set of System/identityHashCode values already
   seen on the *current ancestor chain* — it is threaded (not mutated) so
   that sibling reuse of the same object is rendered in full rather than
   collapsed to \"<circular>\"."
  [meta-map page-size visited obj]
  (when obj
    (let [idc (System/identityHashCode obj)]
      (if (contains? visited idc)
        "<circular>"
        (let [visited'    (conj visited idc)
              b           (core/bean->map obj)
              ;; Hibernate proxies have names like "Book$HibernateProxyXXX"
              base-name   (first (str/split (.getSimpleName (.getClass obj)) #"\$"))
              entity-meta (get meta-map base-name)]
          (if entity-meta
            (let [id-key  (keyword (get-in entity-meta [:identifier :name]))
                  scalars (->> (:properties entity-meta)
                               (remove :is-association)
                               (map #(keyword (:name %))))
                  assocs  (->> (:properties entity-meta)
                               (filter :is-association))]
              (merge
               {id-key (get b id-key)}
               (select-keys b scalars)
               (into {}
                     (for [{:keys [name collection]} assocs]
                       (let [v (get b (keyword name))]
                         [(keyword name)
                          (cond
                            (nil? v)
                            nil

                            collection
                            (if (and (instance? org.hibernate.collection.spi.PersistentCollection v)
                                     (not (.wasInitialized v)))
                              "<lazy>"
                              (mapv #(entity->map meta-map page-size visited' %)
                                    (take page-size v)))

                            :else
                            (entity->map meta-map page-size visited' v))])))))
            ;; Not a known Hibernate entity — could be a DTO/record or a plain object.
            ;; Use bean->map so Java records return a proper field map instead of "{}".
            (core/bean->map obj)))))))

(defn resolve-class
  "Resolves a Hibernate entity name (string, e.g. \"Book\") or Java class to
   the entity's Java class via the EntityManager metamodel."
  [em entity-class]
  (if (class? entity-class)
    entity-class
    (->> (-> em .getMetamodel .getEntities)
         (filter #(= entity-class (.getName %)))
         first
         .getJavaType)))

(defn load-entity
  "Loads a single entity by Hibernate entity name (string, e.g. \"Book\") or
   Java class and primary key `id`. Returns a plain Clojure map, or nil if
   not found. Runs inside a fresh read-only transaction."
  [entity-class id]
  (core/in-readonly-tx
   (let [em  (core/bean jakarta.persistence.EntityManager)
         obj (.find em (resolve-class em entity-class) id)]
     (when obj
       (entity->map (build-meta-map) 20 #{} obj)))))
