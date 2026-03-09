(ns net.brdloush.livewire.hot-queries
  "Live @Query swap engine for Spring Data JPA repositories.

  Mutates the `queryString` Lazy field inside DefaultEntityQuery (held by
  AbstractStringBasedJpaQuery) so that the original SimpleJpaQuery — with all
  of Spring Data's result-type coercion intact — re-executes a new JPQL string
  on every call. No ClassLoader manipulation, no reify wrappers, no reflection
  on the hot path after the first swap.

  Usage:
    (require '[net.brdloush.livewire.hot-queries :as hq])

    ;; See all @Query methods on a repo
    (hq/list-queries \"bookRepository\")

    ;; Swap a query live
    (hq/hot-swap-query! \"bookRepository\" \"findByIdWithDetails\"
      \"select b from Book b where b.id = :id\")

    ;; See everything currently swapped
    (hq/list-swapped)

    ;; Restore original
    (hq/reset-query! \"bookRepository\" \"findByIdWithDetails\")"
  (:require [net.brdloush.livewire.core :as core]))

;; ---------------------------------------------------------------------------
;; Global registry  { [bean-name method-name] -> {:atom <jpql-atom> :original-lazy <Lazy>
;;                                                 :qs-field <Field> :entity-q <DefaultEntityQuery>} }
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

;; ---------------------------------------------------------------------------
;; Private reflection helpers
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
  "Returns the EntityQuery (DefaultEntityQuery) from an AbstractStringBasedJpaQuery."
  [rq]
  (.get (doto (.getDeclaredField @abstract-string-based-query-class "query")
          (.setAccessible true))
        rq))

(defn- get-query-string-field
  "Returns the (accessible) `queryString` Lazy field from a DefaultEntityQuery."
  [entity-q]
  (doto (.getDeclaredField (class entity-q) "queryString")
    (.setAccessible true)))

(defn- make-lazy-string
  "Wraps `s` in a Spring Data Lazy<String>."
  [s]
  (org.springframework.data.util.Lazy/of
    (reify java.util.function.Supplier (get [_] s))))

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

  First call: reflects into the interceptor's queries map, finds the
  SimpleJpaQuery, and replaces its internal `queryString` Lazy with an
  atom-backed one. Registers original in the global registry for rollback.

  Subsequent calls for the same method: resets the atom only — no reflection.

  Returns {:swapped [bean-name method-name] :query new-jpql}"
  [repo-bean-name method-name new-jpql]
  (let [reg-key [repo-bean-name method-name]]
    (if-let [entry (get @registry reg-key)]
      ;; Already swapped — update atom; Lazy supplier reads it on next execute
      (do (reset! (:atom entry) new-jpql)
          {:swapped reg-key :query new-jpql})
      ;; First swap — reflect and wire up the atom-backed Lazy
      (let [repo        (core/bean repo-bean-name)
            interceptor (get-interceptor repo)
            qmap        (get-queries-map interceptor)
            [_ rq]      (->> qmap
                              (filter #(= method-name (.getName (key %))))
                              first)]
        (when-not rq
          (throw (IllegalArgumentException.
                   (str "No @Query method '" method-name "' found on bean '" repo-bean-name "'"))))
        (let [entity-q      (get-entity-query rq)
              qs-field      (get-query-string-field entity-q)
              original-lazy (.get qs-field entity-q)
              jpql-atom     (atom new-jpql)
              live-lazy     (org.springframework.data.util.Lazy/of
                              (reify java.util.function.Supplier
                                (get [_] @jpql-atom)))]
          (.set qs-field entity-q live-lazy)
          (swap! registry assoc reg-key {:atom          jpql-atom
                                         :original-lazy original-lazy
                                         :qs-field      qs-field
                                         :entity-q      entity-q})
          (println (str "[hot-queries] hot-swapped " repo-bean-name "#" method-name))
          {:swapped reg-key :query new-jpql})))))

(defn reset-query!
  "Restores the original JPQL for `method-name` on `repo-bean-name`.
  No-ops if the method was never swapped.
  Returns :restored or :not-swapped."
  [repo-bean-name method-name]
  (let [reg-key [repo-bean-name method-name]]
    (if-let [{:keys [original-lazy qs-field entity-q]} (get @registry reg-key)]
      (do (.set qs-field entity-q original-lazy)
          (swap! registry dissoc reg-key)
          (println (str "[hot-queries] restored " repo-bean-name "#" method-name))
          :restored)
      :not-swapped)))
