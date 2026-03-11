(ns net.brdloush.livewire.hot-queries
  "Live @Query swap engine for Spring Data JPA repositories.

  Five-layer patch required for Spring Data JPA 4.x / Hibernate 7:

  1. DeclaredQueries$JpqlQuery.jpql  — the String actually used at runtime
  2. DefaultEntityQuery.queryString  — Lazy<String> cache; replaced with an
                                       atom-backed Lazy so re-reads are live
  3. DefaultEntityQuery.queryEnhancer — JpaQueryEnhancer holds a pre-parsed ANTLR
                                        AST of the original HQL. Used for
                                        createCountQueryFor, detectAlias, getProjection,
                                        and rewrite (pagination/sorting). Must be
                                        rebuilt via JpaQueryEnhancer/forHql(new-jpql)
                                        so any sort/page/count paths see the new query.
  4. UnsortedCachingQuerySortRewriter.cachedQuery — PreprocessedQuery baked on the
                                        first call after startup. Spring Data serves
                                        this directly, bypassing queryString entirely.
                                        Must be nulled out so it is re-derived from
                                        the new queryString on the next execution.
                                        Restored to nil on reset (re-derives from
                                        the restored original queryString).
  5. Hibernate interpretation caches — hqlInterpretationCache + queryPlanCache
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
;; Shared state
;;
;; registry   — rich per-swap state; populated only for currently-active swaps
;;              { [bean-name method-name] ->
;;                  {:atom              <jpql-atom>
;;                   :manual?           true  ; false when initiated by query-watcher
;;                   :original-lazy     <original Lazy<String>>
;;                   :original-jpql     <original String>
;;                   :original-enhancer <original JpaQueryEnhancer>
;;                   :qs-field          <Field queryString on DefaultEntityQuery>
;;                   :jpql-field        <Field jpql on DeclaredQueries$JpqlQuery>
;;                   :enh-field         <Field queryEnhancer on DefaultEntityQuery>
;;                   :cached-q-field    <Field cachedQuery on UnsortedCachingQuerySortRewriter>
;;                   :sort-rewriter     <UnsortedCachingQuerySortRewriter instance>
;;                   :entity-q          <DefaultEntityQuery instance>
;;                   :jpql-source       <DeclaredQueries$JpqlQuery instance>} }
;;
;; disk-state — single source of truth for "what is currently on disk";
;;              seeded at watcher startup; updated by watcher-apply! on every
;;              .class change; reset to original-jpql by reset-query!/reset-all!
;;              so the watcher re-fires if the disk actually differs.
;;              { [bean-name method-name] -> jpql-string }
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

;;; Current on-disk JPQL per [bean-name method-name].
;;; Seeded at query-watcher startup via seed-disk-state!; kept in sync by
;;; watcher-apply! and reset-query!/reset-all!.  The query-watcher diffs
;;; against this atom instead of maintaining its own known-state.
(defonce disk-state (atom {}))

(defn seed-disk-state!
  "Initialises disk-state from `state-map` ({[bean method] -> jpql}).
  Called once by query-watcher/start-watcher! at startup."
  [state-map]
  (reset! disk-state state-map))

(defn- trigger-rescan!
  "Calls query-watcher/force-rescan! if the watcher is loaded, via late-bound
  resolve to avoid a circular dependency between hot-queries and query-watcher."
  []
  (when-let [f (resolve 'net.brdloush.livewire.query-watcher/force-rescan!)]
    (f)))

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

(defn- get-query-enhancer-field
  "Returns the accessible `queryEnhancer` field on a DefaultEntityQuery."
  [entity-q]
  (doto (.getDeclaredField (class entity-q) "queryEnhancer")
    (.setAccessible true)))

(defn- get-sort-rewriter
  "Returns the UnsortedCachingQuerySortRewriter held by an AbstractStringBasedJpaQuery."
  [rq]
  (.get (doto (.getDeclaredField @abstract-string-based-query-class "querySortRewriter")
          (.setAccessible true))
        rq))

(defn- get-cached-query-field
  "Returns the accessible `cachedQuery` field on an UnsortedCachingQuerySortRewriter."
  [sort-rewriter]
  (doto (.getDeclaredField (class sort-rewriter) "cachedQuery")
    (.setAccessible true)))

(def ^:private jpa-query-enhancer-class
  (delay (Class/forName "org.springframework.data.jpa.repository.query.JpaQueryEnhancer")))

(defn- build-hql-enhancer
  "Creates a fresh JpaQueryEnhancer$HqlQueryParser by parsing `jpql` via
  the JpaQueryEnhancer/forHql(String) static factory method."
  [jpql]
  (let [method (doto (.getDeclaredMethod @jpa-query-enhancer-class "forHql"
                                         (into-array Class [String]))
                 (.setAccessible true))]
    (.invoke method nil (object-array [jpql]))))

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
  "Returns all currently hot-swapped queries from the global registry.
  Each entry includes :manual? true for REPL-initiated swaps, false for
  watcher-initiated swaps."
  []
  (mapv (fn [[[bean-name method-name] entry]]
          {:bean    bean-name
           :method  method-name
           :manual? (:manual? entry)
           :jpql    (deref (:atom entry))})
        @registry))

(defn hot-swap-query!
  "Swaps the JPQL for `method-name` on `repo-bean-name` to `new-jpql`.
  Marks the registry entry as `:manual? true` (REPL-initiated).
  The query-watcher will not override a manually-pinned query when it detects
  a .class change — it tracks the new disk JPQL but leaves the live query alone
  until the user calls reset-query! or reset-all!.

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
      ;; Already swapped — update atom (-> Lazy), raw jpql String, queryEnhancer AST,
      ;; and null cachedQuery; mark as manual (re-pinning overrides any prior watcher entry)
      (do (reset! (:atom entry) new-jpql)
          (.set (:jpql-field entry) (:jpql-source entry) new-jpql)
          (.set (:enh-field entry) (:entity-q entry) (build-hql-enhancer new-jpql))
          (.set (:cached-q-field entry) (:sort-rewriter entry) nil)
          (swap! registry assoc-in [reg-key :manual?] true)
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
        (let [entity-q          (get-entity-query rq)
              preprocessed      (get-preprocessed-query entity-q)
              jpql-source       (get-jpql-source preprocessed)
              jpql-field        (get-jpql-field jpql-source)
              qs-field          (get-query-string-field entity-q)
              enh-field         (get-query-enhancer-field entity-q)
              sort-rewriter     (get-sort-rewriter rq)
              cached-q-field    (get-cached-query-field sort-rewriter)
              original-lazy     (.get qs-field entity-q)
              original-jpql     (.get jpql-field jpql-source)
              original-enhancer (.get enh-field entity-q)
              jpql-atom         (atom new-jpql)
              live-lazy         (org.springframework.data.util.Lazy/of
                                  (reify java.util.function.Supplier
                                    (get [_] @jpql-atom)))]
          ;; Layer 1: patch the raw JPQL string used at runtime
          (.set jpql-field jpql-source new-jpql)
          ;; Layer 2: replace the Lazy<String> cache with an atom-backed one
          (.set qs-field entity-q live-lazy)
          ;; Layer 3: rebuild the queryEnhancer (holds a pre-parsed ANTLR AST) so that
          ;; sort/page/count paths see the new JPQL, not the original AST
          (.set enh-field entity-q (build-hql-enhancer new-jpql))
          ;; Layer 4: null out the sort-rewriter's cachedQuery so it re-derives
          ;; from the new queryString on next execution
          (.set cached-q-field sort-rewriter nil)
          ;; Layer 5: clear Hibernate's parse caches
          (clear-hibernate-caches!)
          (swap! registry assoc reg-key {:atom              jpql-atom
                                         :manual?           true
                                         :original-lazy     original-lazy
                                         :original-jpql     original-jpql
                                         :original-enhancer original-enhancer
                                         :qs-field          qs-field
                                         :jpql-field        jpql-field
                                         :enh-field         enh-field
                                         :cached-q-field    cached-q-field
                                         :sort-rewriter     sort-rewriter
                                         :entity-q          entity-q
                                         :jpql-source       jpql-source})
          (println (str "[hot-queries] hot-swapped " repo-bean-name "#" method-name))
          {:swapped reg-key :query new-jpql})))))

(defn watcher-apply!
  "Called by query-watcher when a .class change is detected with a new JPQL.

  Always updates disk-state to record what is currently on disk.

  If the query has been manually pinned by the user (`:manual? true` in the
  registry), the live query is left alone — the user's REPL experiment takes
  precedence.  The disk change is tracked silently; when the user later calls
  reset-query! or reset-all!, disk-state is written back to original-jpql,
  the watcher sees the diff on the next poll, and re-applies the disk JPQL.

  If the query is not manually pinned, swaps the live query exactly as
  hot-swap-query! would, marking the registry entry as `:manual? false`.

  Returns :tracked (manually pinned, live query unchanged) or the swap result map."
  [repo-bean-name method-name new-jpql]
  (let [reg-key [repo-bean-name method-name]]
    ;; Always record what is currently on disk regardless of swap outcome.
    (swap! disk-state assoc reg-key new-jpql)
    (if (-> @registry (get reg-key) :manual?)
      (do (println (str "[hot-queries] watcher tracked (manual pin active) "
                        repo-bean-name "#" method-name))
          :tracked)
      ;; Not manually pinned — apply the disk JPQL live, mark as watcher-initiated.
      (let [result (if-let [entry (get @registry reg-key)]
                     ;; Re-swap path (watcher had swapped before, now updating)
                     (do (reset! (:atom entry) new-jpql)
                         (.set (:jpql-field entry) (:jpql-source entry) new-jpql)
                         (.set (:enh-field entry) (:entity-q entry) (build-hql-enhancer new-jpql))
                         (.set (:cached-q-field entry) (:sort-rewriter entry) nil)
                         (swap! registry assoc-in [reg-key :manual?] false)
                         (clear-hibernate-caches!)
                         (println (str "[hot-queries] watcher re-swapped " repo-bean-name "#" method-name))
                         {:swapped reg-key :query new-jpql})
                     ;; First-swap path — delegate to hot-swap-query! then correct the :manual? flag
                     (let [r (hot-swap-query! repo-bean-name method-name new-jpql)]
                       (swap! registry assoc-in [reg-key :manual?] false)
                       r))]
        result))))

(defn reset-query!
  "Restores the original JPQL for `method-name` on `repo-bean-name`.
  Clears Hibernate's interpretation caches so the original query is re-parsed.
  Returns :restored or :not-swapped."
  [repo-bean-name method-name]
  (let [reg-key [repo-bean-name method-name]]
    (if-let [{:keys [original-lazy original-jpql original-enhancer
                     qs-field jpql-field enh-field cached-q-field sort-rewriter
                     entity-q jpql-source]}
             (get @registry reg-key)]
      (do (.set jpql-field jpql-source original-jpql)
          (.set qs-field entity-q original-lazy)
          (.set enh-field entity-q original-enhancer)
          ;; Null cachedQuery so it re-derives from the restored original queryString
          (.set cached-q-field sort-rewriter nil)
          (clear-hibernate-caches!)
          (swap! registry dissoc reg-key)
          ;; Reset disk-state to the original so the watcher re-fires on next poll
          ;; if the .class file on disk actually has a different (newer) JPQL.
          (swap! disk-state assoc reg-key original-jpql)
          (println (str "[hot-queries] restored " repo-bean-name "#" method-name))
          (trigger-rescan!)
          :restored)
      :not-swapped)))

(defn reset-all!
  "Restores the original JPQL for every currently hot-swapped query.
  Clears Hibernate's interpretation caches once after all restores.
  Returns a vector of the restored [bean-name method-name] keys, or [] if nothing was swapped."
  []
  (let [keys (keys @registry)]
    (doseq [[bean-name method-name] keys]
      (let [reg-key [bean-name method-name]
            {:keys [original-lazy original-jpql original-enhancer
                    qs-field jpql-field enh-field cached-q-field sort-rewriter
                    entity-q jpql-source]}
            (get @registry reg-key)]
        (.set jpql-field jpql-source original-jpql)
        (.set qs-field entity-q original-lazy)
        (.set enh-field entity-q original-enhancer)
        ;; Null cachedQuery so it re-derives from the restored original queryString
        (.set cached-q-field sort-rewriter nil)
        (swap! registry dissoc reg-key)
        ;; Reset disk-state to the original so the watcher re-fires on next poll
        ;; if the .class file on disk actually has a different (newer) JPQL.
        (swap! disk-state assoc reg-key original-jpql)
        (println (str "[hot-queries] restored " bean-name "#" method-name))))
    (when (seq keys)
      (clear-hibernate-caches!)
      (trigger-rescan!))
    (mapv vec keys)))
