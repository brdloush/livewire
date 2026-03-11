(ns net.brdloush.livewire.hot-queries
  "Live @Query swap engine for Spring Data JPA repositories.

  Three-layer patch required for Spring Data JPA 4.x / Hibernate 7:

  1. DeclaredQueries$JpqlQuery.jpql  — the String actually used at runtime
  2. DefaultEntityQuery.queryString  — Lazy<String> cache; replaced with an
                                       atom-backed Lazy so re-reads are live
  3. Hibernate interpretation caches — hqlInterpretationCache + queryPlanCache
                                        on QueryInterpretationCacheStandardImpl
                                        must be cleared so Hibernate re-parses
                                        the new JPQL on next execution

  Usage:
    (require '[net.brdloush.livewire.hot-queries :as hq])

    (hq/list-queries \"bookRepository\")
    (hq/hot-swap-query! \"bookRepository\" \"findByIdWithDetails\" \"select b from Book b where 1=2\")
    (hq/list-swapped)
    (hq/reset-query! \"bookRepository\" \"findByIdWithDetails\")
    (hq/reset-all!)                          ; restore every swapped query at once"
  (:require [net.brdloush.livewire.core :as core]))

;; ---------------------------------------------------------------------------
;; Global registry
;; { [bean-name method-name] -> {:atom            <jpql-atom>
;;                                :original-lazy   <original Lazy>
;;                                :original-jpql   <original String>
;;                                :qs-field        <Field queryString on DefaultEntityQuery>
;;                                :jpql-field      <Field jpql on DeclaredQueries$JpqlQuery>
;;                                :entity-q        <DefaultEntityQuery instance>
;;                                :jpql-source     <DeclaredQueries$JpqlQuery instance>} }
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

;; ---------------------------------------------------------------------------
;; Private reflection helpers — Spring Data layer
;; ---------------------------------------------------------------------------

(defn- get-interceptor
  "Walks the AOP proxy chain of `repo` and returns the
  QueryExecutorMethodInterceptor instance."
  [repo]
  (let [advised (cast org.springframework.aop.framework.Advised repo)]
    (->> (.getAdvisors advised)
         (keep #(when (instance? org.springframework.aop.support.DefaultPointcutAdvisor %)
                  (.getAdvice ^org.springframework.aop.support.DefaultPointcutAdvisor %)))
         (filter #(-> % class .getName (.contains "QueryExecutorMethodInterceptor")))
         first)))

(defn- get-queries-map
  "Reflectively extracts the private `queries` Map<Method,RepositoryQuery>
  from a QueryExecutorMethodInterceptor."
  [interceptor]
  (.get (doto (.getDeclaredField (class interceptor) "queries")
          (.setAccessible true))
        interceptor))

(def ^:private abstract-string-based-query-class
  (delay (Class/forName "org.springframework.data.jpa.repository.query.AbstractStringBasedJpaQuery")))

(defn- get-entity-query
  "Returns the DefaultEntityQuery held by an AbstractStringBasedJpaQuery."
  [rq]
  (.get (doto (.getDeclaredField @abstract-string-based-query-class "query")
          (.setAccessible true))
        rq))

(defn- get-preprocessed-query
  "Returns the PreprocessedQuery held by a DefaultEntityQuery."
  [entity-q]
  (.get (doto (.getDeclaredField (class entity-q) "query")
          (.setAccessible true))
        entity-q))

(defn- get-jpql-source
  "Returns the DeclaredQueries$JpqlQuery held as `source` inside a PreprocessedQuery."
  [preprocessed-q]
  (.get (doto (.getDeclaredField (class preprocessed-q) "source")
          (.setAccessible true))
        preprocessed-q))

(defn- get-jpql-field
  "Returns the accessible `jpql` String field on a DeclaredQueries$JpqlQuery."
  [jpql-source]
  (doto (.getDeclaredField (class jpql-source) "jpql")
    (.setAccessible true)))

(defn- get-query-string-field
  "Returns the accessible `queryString` Lazy field on a DefaultEntityQuery."
  [entity-q]
  (doto (.getDeclaredField (class entity-q) "queryString")
    (.setAccessible true)))

;; ---------------------------------------------------------------------------
;; Private reflection helpers — Hibernate cache layer
;; ---------------------------------------------------------------------------

(defn- clear-hibernate-caches!
  "Clears Hibernate's hqlInterpretationCache and queryPlanCache so the new
  JPQL string is re-parsed on the next execution."
  []
  (let [emf (core/bean "entityManagerFactory")
        sfi (.unwrap emf org.hibernate.engine.spi.SessionFactoryImplementor)
        ic  (.getInterpretationCache (.getQueryEngine sfi))
        ic-class (class ic)]
    (doto (.getDeclaredField ic-class "hqlInterpretationCache")
      (.setAccessible true)
      (-> (.get ic) .clear))
    (doto (.getDeclaredField ic-class "queryPlanCache")
      (.setAccessible true)
      (-> (.get ic) .clear))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn list-queries
  "Lists all @Query-annotated methods registered for `repo-bean-name`.
  Returns a vector of maps: {:method :jpql :query-class}"
  [repo-bean-name]
  (let [repo        (core/bean repo-bean-name)
        interceptor (get-interceptor repo)
        qmap        (get-queries-map interceptor)]
    (->> qmap
         (mapv (fn [[m rq]]
                 {:method      (.getName m)
                  :query-class (-> rq class .getSimpleName)
                  :jpql        (try (-> rq .getQueryMethod .getAnnotatedQuery)
                                    (catch Exception _ nil))}))
         (sort-by :method))))

(defn list-swapped
  "Returns all currently hot-swapped queries from the global registry."
  []
  (mapv (fn [[[bean-name method-name] entry]]
          {:bean   bean-name
           :method method-name
           :jpql   (deref (:atom entry))})
        @registry))

(defn hot-swap-query!
  "Swaps the JPQL for `method-name` on `repo-bean-name` to `new-jpql`.

  First call: reflects into the Spring Data + Hibernate internals to wire up
  an atom-backed Lazy (so re-reads are live) and patches the DeclaredQueries$JpqlQuery
  string. Also clears Hibernate's interpretation caches so the new JPQL is
  re-parsed immediately.

  Subsequent calls for the same method: resets the atom and clears caches only
  — no structural reflection.

  Returns {:swapped [bean-name method-name] :query new-jpql}"
  [repo-bean-name method-name new-jpql]
  (let [reg-key [repo-bean-name method-name]]
    (if-let [entry (get @registry reg-key)]
      ;; Already swapped — update both the atom (-> Lazy) and the raw jpql String field
      (do (reset! (:atom entry) new-jpql)
          (.set (:jpql-field entry) (:jpql-source entry) new-jpql)
          (clear-hibernate-caches!)
          (println (str "[hot-queries] re-swapped " repo-bean-name "#" method-name))
          {:swapped reg-key :query new-jpql})
      ;; First swap — wire everything up
      (let [repo          (core/bean repo-bean-name)
            interceptor   (get-interceptor repo)
            qmap          (get-queries-map interceptor)
            [_ rq]        (->> qmap
                                (filter #(= method-name (.getName (key %))))
                                first)]
        (when-not rq
          (throw (IllegalArgumentException.
                   (str "No @Query method '" method-name "' found on bean '" repo-bean-name "'"))))
        (let [entity-q      (get-entity-query rq)
              preprocessed  (get-preprocessed-query entity-q)
              jpql-source   (get-jpql-source preprocessed)
              jpql-field    (get-jpql-field jpql-source)
              qs-field      (get-query-string-field entity-q)
              original-lazy (.get qs-field entity-q)
              original-jpql (.get jpql-field jpql-source)
              jpql-atom     (atom new-jpql)
              live-lazy     (org.springframework.data.util.Lazy/of
                              (reify java.util.function.Supplier
                                (get [_] @jpql-atom)))]
          ;; Layer 1: patch the raw JPQL string used at runtime
          (.set jpql-field jpql-source new-jpql)
          ;; Layer 2: replace the Lazy<String> cache with an atom-backed one
          (.set qs-field entity-q live-lazy)
          ;; Layer 3: clear Hibernate's parse caches
          (clear-hibernate-caches!)
          (swap! registry assoc reg-key {:atom          jpql-atom
                                         :original-lazy original-lazy
                                         :original-jpql original-jpql
                                         :qs-field      qs-field
                                         :jpql-field    jpql-field
                                         :entity-q      entity-q
                                         :jpql-source   jpql-source})
          (println (str "[hot-queries] hot-swapped " repo-bean-name "#" method-name))
          {:swapped reg-key :query new-jpql})))))

(defn reset-query!
  "Restores the original JPQL for `method-name` on `repo-bean-name`.
  Clears Hibernate's interpretation caches so the original query is re-parsed.
  Returns :restored or :not-swapped."
  [repo-bean-name method-name]
  (let [reg-key [repo-bean-name method-name]]
    (if-let [{:keys [original-lazy original-jpql qs-field jpql-field entity-q jpql-source]} (get @registry reg-key)]
      (do (.set jpql-field jpql-source original-jpql)
          (.set qs-field entity-q original-lazy)
          (clear-hibernate-caches!)
          (swap! registry dissoc reg-key)
          (println (str "[hot-queries] restored " repo-bean-name "#" method-name))
          :restored)
      :not-swapped)))

(defn reset-all!
  "Restores the original JPQL for every currently hot-swapped query.
  Clears Hibernate's interpretation caches once after all restores.
  Returns a vector of the restored [bean-name method-name] keys, or [] if nothing was swapped."
  []
  (let [keys (keys @registry)]
    (doseq [[bean-name method-name] keys]
      (let [{:keys [original-lazy original-jpql qs-field jpql-field entity-q jpql-source]}
            (get @registry [bean-name method-name])]
        (.set jpql-field jpql-source original-jpql)
        (.set qs-field entity-q original-lazy)
        (swap! registry dissoc [bean-name method-name])
        (println (str "[hot-queries] restored " bean-name "#" method-name))))
    (when (seq keys)
      (clear-hibernate-caches!))
    (mapv vec keys)))
