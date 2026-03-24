(ns net.brdloush.livewire.introspect
  "Introspection tools: interrogate the live application structure without reading source files."
  (:require [net.brdloush.livewire.core :as core]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Annotation helpers (used by inspect-entity for @Column / @ManyToOne metadata)

(defn- find-field
  "Walks the class hierarchy (including superclasses / @MappedSuperclass) to find
   a declared field by name. Returns nil if not found anywhere in the hierarchy."
  [^Class cls ^String field-name]
  (when cls
    (or (try
          (let [f (.getDeclaredField cls field-name)]
            (.setAccessible f true)
            f)
          (catch NoSuchFieldException _ nil))
        (recur (.getSuperclass cls) field-name))))

(defn- find-getter
  "Looks for a public getter method for the given field name (getXxx convention)."
  [^Class cls ^String field-name]
  (let [getter-name (str "get"
                         (str/upper-case (subs field-name 0 1))
                         (subs field-name 1))]
    (->> (.getMethods cls)
         (filter #(= getter-name (.getName %)))
         first)))

(defn- column-annotation-meta
  "Reads @Column and @ManyToOne annotations for a single property, returning a map with
   :nullable, :length (strings only), :unique, and :column-definition.
   Returns all nils when neither annotation is present.

   Rules:
     - For @ManyToOne: :nullable = (.optional m2o)  (optional=false => nullable=false)
     - For @Column:    :nullable = (.nullable col)   (nullable=false => :nullable false)
     - :length is only populated for string properties (type = \"string\").
     - :column-definition is nil when the annotation value is blank.
     - :unique is nil (not false) when the column is not unique."
  [^Class entity-cls ^String prop-name ^String prop-type]
  (let [col-cls (Class/forName "jakarta.persistence.Column")
        m2o-cls (Class/forName "jakarta.persistence.ManyToOne")
        field   (find-field entity-cls prop-name)
        getter  (find-getter entity-cls prop-name)
        col     (or (when field (.getAnnotation field col-cls))
                    (when getter (.getAnnotation getter col-cls)))
        m2o     (or (when field (.getAnnotation field m2o-cls))
                    (when getter (.getAnnotation getter m2o-cls)))]
    {:nullable          (cond
                          m2o  (.optional m2o)
                          col  (.nullable col)
                          :else nil)
     :length            (when (and col (= "string" prop-type))
                          (.length col))
     :unique            (when (and col (.unique col)) true)
     :column-definition (when (and col (not= "" (.columnDefinition col)))
                          (.columnDefinition col))}))

;;; ---------------------------------------------------------------------------
;;; Endpoints

(def ^:private pv-cls
  (delay (Class/forName "org.springframework.web.bind.annotation.PathVariable")))
(def ^:private rp-cls
  (delay (Class/forName "org.springframework.web.bind.annotation.RequestParam")))
(def ^:private rb-cls
  (delay (Class/forName "org.springframework.web.bind.annotation.RequestBody")))
(def ^:private rh-cls
  (delay (Class/forName "org.springframework.web.bind.annotation.RequestHeader")))
(def ^:private pa-cls
  (delay (Class/forName "org.springframework.security.access.prepost.PreAuthorize")))
(def ^:private default-none-sentinel
  "The sentinel string Spring uses for @RequestParam/@RequestHeader defaultValue
   when no default has been specified (ValueConstants/DEFAULT_NONE)."
  (delay (.get (.getField (Class/forName "org.springframework.web.bind.annotation.ValueConstants")
                          "DEFAULT_NONE")
               nil)))

(defn- param-source-info
  "Returns a map with :source (:path/:query/:body/:header/:unknown), :required, and
   optionally :default-value for a Spring MethodParameter."
  [p]
  (cond
    (.hasParameterAnnotation p @pv-cls)
    (let [ann (.getParameterAnnotation p @pv-cls)]
      {:source :path :required (.required ann)})

    (.hasParameterAnnotation p @rp-cls)
    (let [ann (.getParameterAnnotation p @rp-cls)
          dv  (.defaultValue ann)]
      (merge {:source :query :required (.required ann)}
             (when (not= dv @default-none-sentinel) {:default-value dv})))

    (.hasParameterAnnotation p @rb-cls)
    {:source :body :required (.required (.getParameterAnnotation p @rb-cls))}

    (.hasParameterAnnotation p @rh-cls)
    (let [ann (.getParameterAnnotation p @rh-cls)
          dv  (.defaultValue ann)]
      (merge {:source :header :required (.required ann)}
             (when (not= dv @default-none-sentinel) {:default-value dv})))

    :else {:source :unknown :required nil}))

(defn- parse-pre-authorize
  "Parses a @PreAuthorize SpEL string into resolved :required-roles and
   :required-authorities vectors. Returns nil when spel-str is nil.
   Returns an empty map when the expression uses none of the recognised patterns."
  [spel-str]
  (when spel-str
    (let [roles-single (->> (re-seq #"hasRole\('([^']+)'\)" spel-str) (mapv second))
          roles-any    (->> (re-seq #"hasAnyRole\(([^)]+)\)" spel-str)
                            (mapcat #(re-seq #"'([^']+)'" (second %)))
                            (mapv second))
          auths-single (->> (re-seq #"hasAuthority\('([^']+)'\)" spel-str) (mapv second))
          auths-any    (->> (re-seq #"hasAnyAuthority\(([^)]+)\)" spel-str)
                            (mapcat #(re-seq #"'([^']+)'" (second %)))
                            (mapv second))
          roles (vec (distinct (concat roles-single roles-any)))
          auths (vec (distinct (concat auths-single auths-any)))]
      (cond-> {}
        (seq roles) (assoc :required-roles roles)
        (seq auths) (assoc :required-authorities auths)))))

(defn list-endpoints
  "Introspects RequestMappingHandlerMapping.
   Returns a data structure of all HTTP endpoints: method, path, controller class,
   handler method name, parameters, and produces/consumes media types.

   Each parameter map includes:
     :name          — parameter name (may be nil when debug info is absent)
     :type          — fully-qualified type name
     :source        — :path | :query | :body | :header | :unknown
     :required      — true/false (nil for :unknown)
     :default-value — (query/header only) present when a default has been declared

   Each endpoint map may also include:
     :required-roles        — vector of role strings parsed from @PreAuthorize hasRole/hasAnyRole
     :required-authorities  — vector of authority strings parsed from @PreAuthorize hasAuthority/hasAnyAuthority"
  []
  (let [rmhm (core/bean "requestMappingHandlerMapping")
        handler-methods (.getHandlerMethods rmhm)]
    (for [[rmi hm] handler-methods]
      (let [methods  (seq (.getMethods (.getMethodsCondition rmi)))
            paths    (or (when-let [ppc (try (.getPathPatternsCondition rmi) (catch Exception _ nil))]
                           (seq (.getPatterns ppc)))
                         (when-let [pc (try (.getPatternsCondition rmi) (catch Exception _ nil))]
                           (seq (.getPatterns pc))))
            produces (seq (.getExpressions (.getProducesCondition rmi)))
            consumes (seq (.getExpressions (.getConsumesCondition rmi)))
            m        (.getMethod hm)
            pa-str   (or (some-> (.getAnnotation m @pa-cls) .value)
                         (some-> (.getAnnotation (.getDeclaringClass m) @pa-cls) .value))
            auth-map (parse-pre-authorize pa-str)]
        (merge
          {:methods        (mapv str methods)
           :paths          (mapv str paths)
           :produces       (mapv str produces)
           :consumes       (mapv str consumes)
           :controller     (.getName (.getBeanType hm))
           :handler-method (.getName m)
           :pre-authorize  pa-str
           :parameters     (mapv (fn [p]
                                   (merge {:name (.getParameterName p)
                                           :type (.getName (.getParameterType p))}
                                          (param-source-info p)))
                                 (.getMethodParameters hm))}
          auth-map))))); ---------------------------------------------------------------------------
;;; Hibernate Metamodel — cross-compatibility helpers (must precede callers)

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

;;; ---------------------------------------------------------------------------

(defn list-entities
  "Returns a list of all Hibernate-managed entities: simple name, FQN, and DB table name."
  []
  (let [emf (core/bean "entityManagerFactory")
        sf  (.unwrap emf org.hibernate.SessionFactory)
        mm  (.getMetamodel sf)]
    (->> (.getEntities mm)
         (map (fn [e]
                (let [entity-name (.getName e)
                      fqn         (.getName (.getJavaType e))
                      ep          (try (safe-get-entity-persister mm fqn)
                                       (catch Exception _ nil))]
                  {:name       entity-name
                   :class      fqn
                   :table-name (when ep (try (.getTableName ep) (catch Exception _ nil)))})))
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
      (let [prop-names  (seq (.getPropertyNames ep))
            entity-cls  (try (Class/forName entity-name) (catch Exception _ nil))]
        {:entity-name entity-name
         :table-name (.getTableName ep)
         :identifier {:name (.getIdentifierPropertyName ep)
                      :columns (vec (.getIdentifierColumnNames ep))
                      :type (.getName (.getIdentifierType ep))}
         :properties (mapv (fn [pn]
                             (let [idx (.getPropertyIndex ep pn)
                                   prop-type (.getPropertyType ep pn)
                                   prop-type-name (.getName prop-type)
                                   cascade (str (safe-get-cascade-style ep idx))
                                   fetch (str (.getFetchMode ep idx))
                                   cols (seq (.getPropertyColumnNames ep pn))
                                   is-collection (.isCollectionType prop-type)
                                   is-entity (.isEntityType prop-type)
                                   ann-meta (when entity-cls
                                              (try
                                                (column-annotation-meta entity-cls pn prop-type-name)
                                                (catch Exception _ {:nullable nil :length nil :unique nil :column-definition nil})))]
                               (merge
                                 {:name pn
                                  :columns (vec cols)
                                  :type prop-type-name
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
                                  :fetch (when (not= fetch "DEFAULT") fetch)}
                                 ann-meta)))
                           prop-names)})
      {:error (str "Entity not found: " entity-class)})))

(defn inspect-all-entities
  "Returns a map of {entity-simple-name -> inspect-entity result} for every
   entity known to the current persistence unit. Equivalent to calling
   inspect-entity once per entry returned by list-entities, but in a single
   call — useful for agents building ER diagrams or reasoning about the full
   domain model."
  []
  (into {} (map (fn [{:keys [name]}] [name (inspect-entity name)])
                (list-entities))))
