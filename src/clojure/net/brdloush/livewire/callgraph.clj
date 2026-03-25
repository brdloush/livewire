(ns net.brdloush.livewire.callgraph
  "Method-level call graph analysis and blast-radius impact detection.

   Given a bean name and a method name, `blast-radius` walks the bean
   dependency graph upward (from dependency toward dependents), intersects
   with a bytecode-level call graph extracted at runtime via ASM, and returns
   the set of bean methods that transitively invoke the target — annotated
   with their distance from the target and, for entry-point beans (controllers,
   schedulers, event listeners), their observable surface.

   The primary consumer is an AI agent about to modify a query, service method,
   or repository — and needs to know the full blast radius before acting.

   Usage:
     (require '[net.brdloush.livewire.callgraph :as cg])

     ;; Which HTTP endpoints and schedulers call bookRepository/findAll?
     (cg/blast-radius \"bookRepository\" \"findAll\")

     ;; What is affected if I change bookService/archiveBook?
     (cg/blast-radius \"bookService\" \"archiveBook\")

     ;; Clear the cached call-graph index (e.g. after a hot-patch)
     (cg/reset-blast-radius-cache!)"
  (:require [net.brdloush.livewire.core :as core]
            [net.brdloush.livewire.introspect :as intro]
            [clojure.string :as str])
  (:import [org.springframework.asm ClassReader ClassVisitor MethodVisitor Opcodes]
           [org.springframework.aop.support AopUtils]))

;;; ---------------------------------------------------------------------------
;;; Internal: app-bean? predicate (improved over core/all-bean-deps)
;;;
;;; core/all-bean-deps filters by BeanClassName, which misses Spring Data JPA
;;; repositories (whose BeanClassName is JpaRepositoryFactoryBean — a Spring
;;; class). We fall back to checking the proxy's interfaces, which include the
;;; app-defined repository interface.

(defn- app-bean?
  "Returns true when bean-name belongs to the application's own root package.
   Checks BeanClassName first; falls back to the bean instance's proxy interfaces
   so that Spring Data JPA repository beans (whose BeanClassName is
   JpaRepositoryFactoryBean) are correctly included."
  [bean-name pkg bf ctx]
  (when-let [bd (try (.getBeanDefinition bf bean-name) (catch Exception _ nil))]
    (and (< (.getRole bd) 2)
         (not (str/includes? bean-name "."))
         (if pkg
           (or (some-> (.getBeanClassName bd) (str/starts-with? pkg))
               (some #(str/starts-with? (.getName %) pkg)
                     (.getInterfaces (.getClass (.getBean ctx bean-name)))))
           true))))

(defn- app-bean-deps
  "Like core/all-bean-deps but uses the improved app-bean? predicate so that
   Spring Data JPA repository beans are included when :app-only true."
  [& {:keys [app-only] :or {app-only true}}]
  (let [c   (core/ctx)
        bf  (.getBeanFactory c)
        pkg (when app-only
              (let [names (.getBeanNamesForAnnotation
                            c org.springframework.boot.autoconfigure.SpringBootApplication)]
                (when (seq names)
                  (-> c (.getBean (first names)) .getClass .getPackage .getName))))]
    (->> (.getBeanDefinitionNames c)
         (filter #(app-bean? % pkg bf c))
         (mapv core/bean-deps))))

;;; ---------------------------------------------------------------------------
;;; Internal: BFS over bean dependency reverse graph

(defn- transitive-dependents
  "BFS over the reverse bean-dependency graph. Returns the set of all bean names
   that transitively depend on bean-name (inclusive of bean-name itself)."
  [bean-name all-deps]
  (let [dep-map (into {} (map (juxt :bean :dependents) all-deps))]
    (loop [frontier #{bean-name} visited #{}]
      (if (empty? frontier)
        visited
        (let [next-layer (->> frontier
                              (mapcat #(get dep-map % []))
                              (remove visited)
                              set)]
          (recur next-layer (into visited frontier)))))))

;;; ---------------------------------------------------------------------------
;;; Internal: ASM bytecode call-site extractor

(defn- class->bytecode-stream
  "Returns an InputStream for the bytecode of cls, using the system classloader
   as a fallback when the class was loaded by the bootstrap classloader (null)."
  [^Class cls]
  (let [resource-path (str (.replace (.getName cls) "." "/") ".class")
        cl (or (.getClassLoader cls) (ClassLoader/getSystemClassLoader))]
    (.getResourceAsStream cl resource-path)))

(defn- extract-call-sites
  "Walks the bytecode of bean-instance's real class (unwrapping AOP proxies) and
   returns a seq of {:caller-method :callee-owner :callee-method} for every
   method-call instruction found."
  [bean-instance]
  (let [real-cls (AopUtils/getTargetClass bean-instance)
        stream   (class->bytecode-stream real-cls)
        calls    (atom [])]
    (when stream
      (.accept (ClassReader. stream)
        (proxy [ClassVisitor] [Opcodes/ASM9]
          (visitMethod [_access method-name _desc _sig _exns]
            (let [caller method-name]
              (proxy [MethodVisitor] [Opcodes/ASM9]
                (visitMethodInsn [_opcode owner callee-method _desc _itf]
                  (swap! calls conj {:caller-method caller
                                     :callee-owner  (str/replace owner "/" ".")
                                     :callee-method callee-method}))))))
        0))
    @calls))

(defn- build-call-graph-index*
  "Builds the full {[callee-class callee-method] -> [{:bean :method}]} index
   by walking the bytecode of every app-level bean class."
  []
  (let [all-deps (app-bean-deps)]
    (reduce
      (fn [idx {:keys [bean]}]
        (try
          (reduce (fn [idx {:keys [caller-method callee-owner callee-method]}]
                    (update idx [callee-owner callee-method]
                            (fnil conj []) {:bean bean :method caller-method}))
                  idx
                  (extract-call-sites (core/bean bean)))
          (catch Exception e
            (println (str "[callgraph] skip " bean ": " (.getMessage e)))
            idx)))
      {}
      all-deps)))

;;; ---------------------------------------------------------------------------
;;; Call-graph index cache

(defonce ^:private -cache (atom nil))

(defn reset-blast-radius-cache!
  "Clears the cached call-graph index. The next call to blast-radius will
   rebuild it. Use this after hot-patching a class or recompiling sources
   during a session."
  []
  (reset! -cache nil)
  (println "[callgraph] blast-radius cache cleared"))

(defn- call-graph-index
  "Returns the cached call-graph index, building it on first access."
  []
  (or @-cache
      (let [idx (build-call-graph-index*)]
        (reset! -cache idx)
        idx)))

;;; ---------------------------------------------------------------------------
;;; Internal: bean class name resolution

(defn- bean->target-class-names
  "Returns the set of dotted class/interface names for a bean's real class and
   all its interfaces — including interfaces on the proxy class itself, which
   is necessary for Spring Data JPA repositories (the app-defined interface is
   on the JDK proxy, not on SimpleJpaRepository)."
  [bean-name]
  (try
    (let [b        (core/bean bean-name)
          real-cls (AopUtils/getTargetClass b)
          proxy-ifaces (.getInterfaces (.getClass b))]
      (-> #{(.getName real-cls)}
          (into (map #(.getName %) (.getInterfaces real-cls)))
          (into (map #(.getName %) proxy-ifaces))))
    (catch Exception _ #{})))

;;; ---------------------------------------------------------------------------
;;; Internal: entry-point index builders

(defn- build-http-endpoint-index
  "Returns {[bean-name method-name] -> {:type :http-endpoint :paths :http-methods :pre-authorize}}
   by cross-referencing intro/list-endpoints against app beans."
  []
  (let [fqcn->bean (->> (app-bean-deps)
                        (keep (fn [{:keys [bean class]}] (when class [class bean])))
                        (into {}))]
    (->> (intro/list-endpoints)
         (keep (fn [{:keys [controller handler-method] :as ep}]
                 (when-let [bn (get fqcn->bean controller)]
                   [[bn handler-method]
                    {:type          :http-endpoint
                     :paths         (:paths ep)
                     :http-methods  (:methods ep)
                     :pre-authorize (:pre-authorize ep)}])))
         (into {}))))

(defn- unwrap-scheduled-runnable
  "Peels off Spring 6's Task$OutcomeTrackingRunnable wrapper to get the
   inner ScheduledMethodRunnable."
  [runnable]
  (if (= "org.springframework.scheduling.config.Task$OutcomeTrackingRunnable"
         (.getName (.getClass runnable)))
    (let [f (doto (.getDeclaredField (.getClass runnable) "runnable")
               (.setAccessible true))]
      (.get f runnable))
    runnable))

(defn- bean-name-for-target
  "Finds the Spring bean name whose real class matches target-obj's real class."
  [target-obj all-bean-names]
  (some (fn [bn]
          (try
            (let [b (core/bean bn)]
              (when (= (AopUtils/getTargetClass b)
                       (AopUtils/getTargetClass target-obj))
                bn))
            (catch Exception _ nil)))
        all-bean-names))

(defn- build-scheduled-index
  "Returns {[bean-name method-name] -> {:type :scheduler :cron|:fixed-delay|:fixed-rate ...}}
   by reflecting over ScheduledAnnotationBeanPostProcessor."
  []
  (try
    (let [processor (core/bean org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor)
          all-beans (core/bean-names)
          smr-cls   (Class/forName "org.springframework.scheduling.support.ScheduledMethodRunnable")]
      (->> (.getScheduledTasks processor)
           (keep (fn [st]
                   (let [task     (.getTask st)
                         runnable (unwrap-scheduled-runnable (.getRunnable task))]
                     (when (instance? smr-cls runnable)
                       (let [target      (.getTarget runnable)
                             method-name (.getName (.getMethod runnable))
                             bean-name   (bean-name-for-target target all-beans)
                             task-info   (condp instance? task
                                           org.springframework.scheduling.config.CronTask
                                           {:type :scheduler :cron (.getExpression task)}
                                           org.springframework.scheduling.config.FixedDelayTask
                                           {:type :scheduler :fixed-delay (.getInterval task)}
                                           org.springframework.scheduling.config.FixedRateTask
                                           {:type :scheduler :fixed-rate (.getInterval task)}
                                           {:type :scheduler})]
                         (when bean-name [[bean-name method-name] task-info]))))))
           (into {})))
    (catch Exception e
      (println "[callgraph] @Scheduled index error:" (.getMessage e))
      {})))

(defn- find-event-listener-methods
  "Returns seq of {:bean :method :event-types} for every @EventListener-annotated
   method on bean-name's real class."
  [bean-name bean-obj]
  (let [cls (AopUtils/getTargetClass bean-obj)]
    (->> (seq (.getMethods cls))
         (filter #(.isAnnotationPresent % org.springframework.context.event.EventListener))
         (map (fn [m] {:bean        bean-name
                       :method      (.getName m)
                       :event-types (mapv #(.getSimpleName %) (.getParameterTypes m))})))))

(defn- build-event-listener-index
  "Returns {[bean-name method-name] -> {:type :event-listener :event-types [...]}}
   by scanning all app beans for @EventListener annotations."
  []
  (->> (app-bean-deps)
       (mapcat (fn [{:keys [bean]}]
                 (try (find-event-listener-methods bean (core/bean bean))
                      (catch Exception _ []))))
       (map (fn [{:keys [bean method event-types]}]
              [[bean method]
               {:type :event-listener :event-types event-types}]))
       (into {})))

;;; ---------------------------------------------------------------------------
;;; Public API

(defn blast-radius
  "Returns the set of bean methods that transitively invoke target-bean/target-method,
   annotated with their distance from the target and (for entry-point beans) their
   observable surface.

   Arguments:
     target-bean   — Spring bean name (string), same as accepted by lw/bean
     target-method — simple method name string; all overloads are included if
                     the name is ambiguous (a warning is emitted)

   Options:
     :app-only  (default true) — restrict analysis to app-level beans

   Returns:
     {:target   {:bean \"bookRepository\" :method \"findAll\"}
      :affected [{:bean    \"bookService\"
                  :method  \"getAllBooks\"
                  :depth   1
                  :entry-point nil}
                 {:bean    \"bookController\"
                  :method  \"getBooks\"
                  :depth   2
                  :entry-point {:type        :http-endpoint
                                :paths       [\"/api/books\"]
                                :http-methods [\"GET\"]
                                :pre-authorize \"hasRole('MEMBER')\"}}
                 {:bean    \"bookStatsReporter\"
                  :method  \"reportNightlyStats\"
                  :depth   2
                  :entry-point {:type :scheduler :cron \"0 0 2 * * *\"}}]
      :warnings [...]}

   :depth is the hop count from the target bean. Depth 1 = direct caller of target.
   :entry-point is present for HTTP endpoints, @Scheduled methods, and @EventListener methods.

   Known limitations (also documented in SKILL.md):
   - ApplicationEventPublisher calls are invisible to static bytecode analysis.
   - @Async wrappers: the direct call site is found but async dispatch chains are not.
   - Reflection and lambda-based dispatch cannot be traced.
   - At depth > 1, method attribution is best-effort (bean-level BFS, not full
     recursive method-level BFS).

   The call-graph index is built once and cached. Call reset-blast-radius-cache!
   after hot-patching a class or recompiling sources.

   Examples:
     (cg/blast-radius \"bookRepository\" \"findAll\")
     (cg/blast-radius \"bookService\" \"archiveBook\")"
  [target-bean target-method & {:keys [app-only] :or {app-only true}}]
  (let [all-deps       (app-bean-deps)
        dep-set        (disj (transitive-dependents target-bean all-deps) target-bean)
        target-classes (bean->target-class-names target-bean)
        cg             (call-graph-index)
        http-index     (build-http-endpoint-index)
        sched-index    (build-scheduled-index)
        event-index    (build-event-listener-index)
        warnings       (atom [])

        ;; Warn if target bean has @EventListener methods (callers via events are invisible)
        _ (when (seq (find-event-listener-methods target-bean (core/bean target-bean)))
            (swap! warnings conj
              (str target-bean " has @EventListener methods — callers via ApplicationEventPublisher "
                   "are not visible to static bytecode analysis; inspect manually")))

        ;; Warn if the target method name matches multiple callee entries (overloads)
        _ (when (> (->> target-classes
                        (mapcat (fn [cls] (filter #(= % [cls target-method]) (keys cg))))
                        distinct count)
                   1)
            (swap! warnings conj
              (str "Method name '" target-method
                   "' matched multiple signatures — all overloads are included")))

        annotate (fn [bean method]
                   (or (get http-index [bean method])
                       (get sched-index [bean method])
                       (get event-index [bean method])))

        all-affected
        (loop [frontier (->> target-classes
                              (mapcat #(get cg [% target-method] []))
                              (filter #(contains? dep-set (:bean %)))
                              distinct
                              (mapv #(assoc % :depth 1)))
               visited  #{}
               result   []]
          (if (or (empty? frontier) (> (:depth (first frontier) 0) 10))
            result
            (let [annotated   (mapv (fn [{:keys [bean method depth]}]
                                      {:bean        bean
                                       :method      method
                                       :depth       depth
                                       :entry-point (annotate bean method)})
                                    frontier)
                  new-visited (into visited (map #(select-keys % [:bean :method]) frontier))
                  next-depth  (inc (:depth (first frontier) 1))
                  next-layer  (->> frontier
                                   (mapcat (fn [{:keys [bean method]}]
                                             (->> (bean->target-class-names bean)
                                                  (mapcat #(get cg [% method] []))
                                                  (filter #(and (contains? dep-set (:bean %))
                                                                (not (contains? new-visited
                                                                                (select-keys % [:bean :method]))))))))
                                   distinct
                                   (mapv #(assoc % :depth next-depth)))]
              (recur next-layer new-visited (into result annotated)))))]

    {:target   {:bean target-bean :method target-method}
     :affected (sort-by (juxt :depth :bean) all-affected)
     :warnings @warnings}))
