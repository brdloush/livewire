(ns net.brdloush.livewire.jpa-query
  "Speculative JPQL execution with smart entity serialization.
   Runs a JPQL query against the live EntityManager inside a read-only
   transaction and returns a vector of plain Clojure maps, with lazy
   collections rendered as \"<lazy>\" and ancestor-chain cycles as \"<circular>\"."
  (:require [net.brdloush.livewire.core :as core]
            [net.brdloush.livewire.introspect :as intro]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Entity serializer
;; ---------------------------------------------------------------------------

(defn- build-meta-map
  "Returns a map of {entity-simple-name -> inspect-entity-result} for every
   entity known to the current persistence unit."
  []
  (into {}
        (map (fn [{:keys [name]}]
               [name (intro/inspect-entity name)])
             (intro/list-entities))))

(defn- entity->map
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
        (let [visited'   (conj visited idc)
              b          (clojure.core/bean obj)
              ;; Hibernate proxies have names like "Book$HibernateProxyXXX"
              base-name  (first (str/split (.getSimpleName (.getClass obj)) #"\$"))
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
            ;; Not a known entity — fall back to string representation
            (str obj)))))))

;; ---------------------------------------------------------------------------
;; Scalar projection helpers
;; ---------------------------------------------------------------------------

(defn- extract-select-aliases
  "Extracts AS <alias> names from the SELECT clause of `jpql` in order,
   returning a seq of keywords, or nil if none are found.
   E.g. \"SELECT b.title AS title, COUNT(lr) AS cnt FROM ...\"
        => [:title :cnt]"
  [jpql]
  (when jpql
    (let [select-clause (-> jpql
                            (str/replace #"(?i)\s+" " ")
                            (str/replace #"(?i)^.*?SELECT\s+" "")
                            (str/replace #"(?i)\s+FROM\s+.*$" ""))]
      (when-let [aliases (seq (map (comp keyword str/lower-case second)
                                   (re-seq #"(?i)\bAS\s+(\w+)" select-clause)))]
        aliases))))

(defn- unpack-scalar-row
  "Converts a single scalar-projection row to a Clojure map.
   If the row is an Object[], each element is mapped to the corresponding
   alias keyword (or :col0, :col1, … when no aliases are present).
   A bare scalar (single-column projection) is returned as-is."
  [aliases row]
  (if (.isArray (.getClass row))
    (let [items (object-array row)]
      (if aliases
        (zipmap aliases items)
        (into {} (map-indexed (fn [i v] [(keyword (str "col" i)) v]) items))))
    ;; Single-column projection — wrap for consistency
    (if aliases
      {(first aliases) row}
      {:col0 row})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn jpa-query
  "Executes `jpql` against the live EntityManager inside a read-only
   transaction and returns a vector of serialized entity maps.

   Options (keyword args):
     :page       0-based page index  (default 0)
     :page-size  rows per page        (default 20)

   Collections within entities are capped at `:page-size` items.
   Uninitialized lazy collections render as \"<lazy>\".
   Ancestor-chain cycles render as \"<circular>\"."
  [jpql & {:keys [page page-size] :or {page 0 page-size 20}}]
  (core/in-readonly-tx
   (let [meta-map (build-meta-map)
         em       (core/bean jakarta.persistence.EntityManager)
         q        (doto (.createQuery em jpql)
                    (.setFirstResult (* page page-size))
                    (.setMaxResults page-size))]
     (let [results (.getResultList q)]
       (if (and (seq results)
                (let [first-row (first results)]
                  (or (.isArray (.getClass first-row))
                      ;; single scalar: not an entity known to meta-map
                      (nil? (get meta-map (first (str/split (.getSimpleName (.getClass first-row)) #"\$")))))))
         ;; Scalar projection — unpack each row using AS aliases or :col0/:col1/...
         (let [aliases (extract-select-aliases jpql)]
           (mapv #(unpack-scalar-row aliases %) results))
         ;; Entity projection — use the full entity serializer
         (mapv #(entity->map meta-map page-size #{} %) results))))))
