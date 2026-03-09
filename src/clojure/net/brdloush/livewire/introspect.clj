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
    (if-let [ep (try (.entityPersister mm entity-name) (catch Exception _ nil))]
      (let [prop-names (seq (.getPropertyNames ep))]
        {:entity-name entity-name
         :table-name (.getTableName ep)
         :identifier {:name (.getIdentifierPropertyName ep)
                      :columns (vec (.getIdentifierColumnNames ep))
                      :type (.getName (.getIdentifierType ep))}
         :properties (mapv (fn [pn]
                             (let [idx (.getPropertyIndex ep pn)
                                   prop-type (.getPropertyType ep pn)
                                   cascade (str (.getCascadeStyle ep idx))
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
                                                       cp (.collectionPersister mm role)]
                                                   (.getName (.getElementType cp)))
                                                 is-entity
                                                 (.getName prop-type)
                                                 :else nil)
                                :cascade (when (not= cascade "STYLE_NONE") cascade)
                                :fetch (when (not= fetch "DEFAULT") fetch)}))
                           prop-names)})
      {:error (str "Entity not found: " entity-class)})))
