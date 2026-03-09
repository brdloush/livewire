(ns net.brdloush.livewire.introspect
  "Introspection tools: interrogate the live application structure without reading source files."
  (:require [net.brdloush.livewire.core :as core]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Endpoints

(defn list-endpoints
  "Introspects RequestMappingHandlerMapping.
   Returns a data structure of all HTTP endpoints: method, path, controller class,
   handler method name, parameters, and produces/consumes media types."
  []
  (let [rmhm (core/bean "requestMappingHandlerMapping")
        handler-methods (.getHandlerMethods rmhm)]
    (for [[rmi hm] handler-methods]
      (let [methods (seq (.getMethods (.getMethodsCondition rmi)))
            paths (or (when-let [ppc (try (.getPathPatternsCondition rmi) (catch Exception _ nil))]
                        (seq (.getPatterns ppc)))
                      (when-let [pc (try (.getPatternsCondition rmi) (catch Exception _ nil))]
                        (seq (.getPatterns pc))))
            produces (seq (.getExpressions (.getProducesCondition rmi)))
            consumes (seq (.getExpressions (.getConsumesCondition rmi)))
            m (.getMethod hm)]
        {:methods (mapv str methods)
         :paths (mapv str paths)
         :produces (mapv str produces)
         :consumes (mapv str consumes)
         :controller (.getName (.getBeanType hm))
         :handler-method (.getName m)
         :parameters (mapv (fn [p]
                             {:name (.getParameterName p)
                              :type (.getName (.getParameterType p))})
                           (.getMethodParameters hm))}))))

;;; ---------------------------------------------------------------------------
;;; Hibernate Metamodel

(defn list-entities
  "Returns a list of all Hibernate-managed entities (their simple names and FQNs)."
  []
  (let [emf (core/bean "entityManagerFactory")
        mm (.getMetamodel emf)]
    (->> (.getEntities mm)
         (map (fn [e]
                {:name (.getName e)
                 :class (.getName (.getJavaType e))}))
         (sort-by :name)
         vec)))

(defn- resolve-entity-name
  "Resolves a simple name or FQN to the Hibernate entity name."
  [emf entity-class]
  (let [mm (.getMetamodel emf)
        entities (.getEntities mm)
        target-name (if (class? entity-class) (.getName entity-class) entity-class)
        simple-name? (not (str/includes? target-name "."))]
    (if simple-name?
      (->> entities
           (filter #(= target-name (.getName %)))
           first
           (#(when % (.getName (.getJavaType %)))))
      target-name)))

;;; Hibernate 6 / 7 cross-compatibility helpers

(defn- safe-get-entity-persister [mm entity-name]
  (try
    (clojure.lang.Reflector/invokeInstanceMethod mm "getEntityDescriptor" (object-array [entity-name]))
    (catch Exception _
      (clojure.lang.Reflector/invokeInstanceMethod mm "entityPersister" (object-array [entity-name])))))

(defn- safe-get-collection-persister [mm role]
  (try
    (clojure.lang.Reflector/invokeInstanceMethod mm "getCollectionDescriptor" (object-array [role]))
    (catch Exception _
      (clojure.lang.Reflector/invokeInstanceMethod mm "collectionPersister" (object-array [role])))))

(defn- safe-get-cascade-style [ep idx]
  (try
    (clojure.lang.Reflector/invokeInstanceMethod ep "getCascadeStyle" (object-array [(int idx)]))
    (catch Exception _
      (aget (clojure.lang.Reflector/invokeInstanceMethod ep "getPropertyCascadeStyles" (object-array [])) idx))))

(defn inspect-entity
  "Reads Hibernate's metamodel for a given entity (by Class or simple/FQN String).
   Returns mapped table name, column mappings, relation definitions, etc.
   No annotation reading required — the live metamodel is the source of truth."
  [entity-class]
  (let [emf (core/bean "entityManagerFactory")
        sf (.unwrap emf org.hibernate.SessionFactory)
        mm (.getMetamodel sf)
        entity-name (resolve-entity-name emf entity-class)]
    (if-let [ep (try (safe-get-entity-persister mm entity-name) (catch Exception _ nil))]
      (let [prop-names (seq (.getPropertyNames ep))]
        {:entity-name entity-name
         :table-name (.getTableName ep)
         :identifier {:name (.getIdentifierPropertyName ep)
                      :columns (vec (.getIdentifierColumnNames ep))
                      :type (.getName (.getIdentifierType ep))}
         :properties (mapv (fn [pn]
                             (let [idx (.getPropertyIndex ep pn)
                                   prop-type (.getPropertyType ep pn)
                                   cascade (str (safe-get-cascade-style ep idx))
                                   fetch (str (.getFetchMode ep idx))
                                   cols (seq (.getPropertyColumnNames ep pn))
                                   is-collection (.isCollectionType prop-type)
                                   is-entity (.isEntityType prop-type)]
                               {:name pn
                                :columns (vec cols)
                                :type (.getName prop-type)
                                :is-association (boolean (or is-collection is-entity))
                                :collection is-collection
                                :target-entity (cond
                                                 is-collection
                                                 (let [role (.getRole prop-type)
                                                       cp (safe-get-collection-persister mm role)]
                                                   (.getName (.getElementType cp)))
                                                 is-entity
                                                 (.getName prop-type)
                                                 :else nil)
                                :cascade (when (not= cascade "STYLE_NONE") cascade)
                                :fetch (when (not= fetch "DEFAULT") fetch)}))
                           prop-names)})
      {:error (str "Entity not found: " entity-class)})))
