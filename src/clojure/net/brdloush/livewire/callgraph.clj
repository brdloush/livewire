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
            [clojure.set :as set]
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
  (let [c (core/ctx)
        bf (.getBeanFactory c)
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
        stream (class->bytecode-stream real-cls)
        calls (atom [])]
    (when stream
      (.accept (ClassReader. stream)
               (proxy [ClassVisitor] [Opcodes/ASM9]
                 (visitMethod [_access method-name _desc _sig _exns]
                   (let [caller method-name]
                     (proxy [MethodVisitor] [Opcodes/ASM9]
                       (visitMethodInsn [_opcode owner callee-method _desc _itf]
                         (swap! calls conj {:caller-method caller
                                            :callee-owner (str/replace owner "/" ".")
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
    (let [b (core/bean bean-name)
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
                    {:type :http-endpoint
                     :paths (:paths ep)
                     :http-methods (:methods ep)
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
  "Returns {[bean-name method-name] -> {:type :scheduler :cron|:fixed-delay|:fixed-rate ...}}.

   Primary strategy: static annotation scanning — walks getDeclaredMethods of every app bean
   class and inspects @Scheduled directly. Works even when @EnableScheduling is not active
   (the common case in local dev profiles).

   Fallback: if the primary scan returns nothing, tries the runtime
   ScheduledAnnotationBeanPostProcessor (for programmatic task registration via @Bean).

   The two approaches are complementary: @Scheduled catches annotation-driven tasks,
   the processor catches programmatic tasks. Running both and merging is safe — duplicate
   keys from the same method are idempotent."
  []
  (let [scheduled-cls (try (Class/forName "org.springframework.scheduling.annotation.Scheduled")
                           (catch Exception _ nil))
        ;; --- primary: static annotation scan ---
        static-index
        (when scheduled-cls
          (->> (app-bean-deps)
               (mapcat (fn [{:keys [bean]}]
                         (try
                           (let [b (core/bean bean)
                                 cls (AopUtils/getTargetClass b)]
                             (->> (seq (.getDeclaredMethods cls))
                                  (keep (fn [m]
                                          (when-let [ann (.getAnnotation m scheduled-cls)]
                                            (let [cron (.cron ann)
                                                  fdly (.fixedDelay ann)
                                                  frate (.fixedRate ann)]
                                              [[bean (.getName m)]
                                               (cond
                                                 (seq cron) {:type :scheduler :cron cron}
                                                 (pos? fdly) {:type :scheduler :fixed-delay fdly}
                                                 (pos? frate) {:type :scheduler :fixed-rate frate}
                                                 :else {:type :scheduler})]))))))
                           (catch Exception _ []))))
               (into {})))
        ;; --- fallback: runtime processor (programmatic task registration) ---
        runtime-index
        (try
          (let [processor (core/bean org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor)
                all-beans (core/bean-names)
                smr-cls (Class/forName "org.springframework.scheduling.support.ScheduledMethodRunnable")]
            (->> (.getScheduledTasks processor)
                 (keep (fn [st]
                         (let [task (.getTask st)
                               runnable (unwrap-scheduled-runnable (.getRunnable task))]
                           (when (instance? smr-cls runnable)
                             (let [target (.getTarget runnable)
                                   method-name (.getName (.getMethod runnable))
                                   bean-name (bean-name-for-target target all-beans)
                                   task-info (condp instance? task
                                               org.springframework.scheduling.config.CronTask
                                               {:type :scheduler :cron (.getExpression task)}
                                               org.springframework.scheduling.config.FixedDelayTask
                                               {:type :scheduler :fixed-delay (.getInterval task)}
                                               org.springframework.scheduling.config.FixedRateTask
                                               {:type :scheduler :fixed-rate (.getInterval task)}
                                               {:type :scheduler})]
                               (when bean-name [[bean-name method-name] task-info]))))))
                 (into {})))
          (catch Exception _ {}))]
    (merge static-index runtime-index)))

(defn- find-event-listener-methods
  "Returns seq of {:bean :method :event-types} for every @EventListener-annotated
   method on bean-name's real class."
  [bean-name bean-obj]
  (let [cls (AopUtils/getTargetClass bean-obj)]
    (->> (seq (.getMethods cls))
         (filter #(.isAnnotationPresent % org.springframework.context.event.EventListener))
         (map (fn [m] {:bean bean-name
                       :method (.getName m)
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
;;; Internal: field-access + invoke-call extraction and bean resolution for method-dep-map

(defn- extract-intra-class-calls
  "Walks the bytecode of bean-instance's real class and returns a map of
   method-name -> #{called-method-names} for calls made within the same class.
   Used by method-dep-map to expand private helper call sites."
  [bean-instance]
  (let [real-cls (AopUtils/getTargetClass bean-instance)
        class-internal (str/replace (.getName real-cls) "." "/")
        resource-path (str class-internal ".class")
        cl (or (.getClassLoader real-cls) (ClassLoader/getSystemClassLoader))
        stream (.getResourceAsStream cl resource-path)
        calls (atom [])]
    (when stream
      (.accept (ClassReader. stream)
               (proxy [ClassVisitor] [Opcodes/ASM9]
                 (visitMethod [_access method-name _desc _sig _exns]
                   (proxy [MethodVisitor] [Opcodes/ASM9]
                     (visitMethodInsn [_opcode owner callee _desc _itf]
                       (when (= owner class-internal)
                         (swap! calls conj {:caller method-name :callee callee}))))))
               0))
    (->> @calls
         (group-by :caller)
         (map (fn [[caller cs]] [caller (set (map :callee cs))]))
         (into {}))))

(defn- extract-field-and-invoke-accesses
  "Single-pass bytecode walk over bean-instance's real class. Collects:
   - GETFIELD instructions  → :field-accesses [{:method :field}]
   - INVOKEVIRTUAL / INVOKEINTERFACE instructions → :invoke-calls [{:method :owner :callee}]

   Combining both in one pass avoids reading the .class bytes twice."
  [bean-instance]
  (let [real-cls (AopUtils/getTargetClass bean-instance)
        resource-path (str (.replace (.getName real-cls) "." "/") ".class")
        cl (or (.getClassLoader real-cls) (ClassLoader/getSystemClassLoader))
        stream (.getResourceAsStream cl resource-path)
        field-acc (atom [])
        invoke-calls (atom [])]
    (when stream
      (.accept (ClassReader. stream)
               (proxy [ClassVisitor] [Opcodes/ASM9]
                 (visitMethod [_access method-name _desc _sig _exns]
                   (proxy [MethodVisitor] [Opcodes/ASM9]
                     (visitFieldInsn [opcode _owner field-name _desc]
                       (when (= opcode Opcodes/GETFIELD)
                         (swap! field-acc conj {:method method-name :field field-name})))
                     (visitMethodInsn [opcode owner callee _desc _itf]
                       (when (#{Opcodes/INVOKEVIRTUAL Opcodes/INVOKEINTERFACE} opcode)
                         (swap! invoke-calls conj {:method method-name
                                                   :owner (str/replace owner "/" ".")
                                                   :callee callee}))))))
               0))
    {:field-accesses @field-acc
     :invoke-calls @invoke-calls}))

(defn- find-field
  "Returns the java.lang.reflect.Field for field-name declared on cls or any
   of its superclasses. Returns nil if not found."
  [cls field-name]
  (or (try (.getDeclaredField cls field-name)
           (catch NoSuchFieldException _ nil))
      (loop [c (.getSuperclass cls)]
        (when c
          (or (try (.getDeclaredField c field-name)
                   (catch NoSuchFieldException _ nil))
              (recur (.getSuperclass c)))))))

(defn- field->bean-name
  "Given the real (unwrapped) class and a field name, returns the Spring bean
   name whose type matches the field's declared type — or nil if the field is
   not a Spring bean. Walks the superclass chain to find inherited fields."
  [real-cls field-name]
  (when-let [field (find-field real-cls field-name)]
    (let [field-type (.getType field)]
      (some (fn [bn]
              (try
                (when (instance? field-type (core/bean bn)) bn)
                (catch Exception _ nil)))
            (core/bean-names)))))

(defn- add-orchestrator-flag
  "Marks a method as :orchestrator? true when any of these is true:

   1. Wide dep footprint — its dep-set is a proper superset of at least two
      other methods' dep-sets. The method touches more beans than any single
      cohesive sub-operation needs; it is sequencing work on behalf of others.
      Example: a method on a loan-processing service that uses bookRepository,
      loanRecordRepository, and memberRepository, while no other single method
      needs all three.

   2. Deep delegation — it makes 3+ intra-class calls to sibling methods.
      Sequences sub-operations internally without accumulating its own dep breadth.
      Synthetics ($-suffixed), self-calls, and constructors are excluded.

   3. Multi-operation dep use — it calls 3+ distinct methods on the same
      injected bean. A method that calls findOverdueLoans, markLoansAsNotified,
      and archiveLoan all on loanRecordRepository is orchestrating multiple
      operations through a single dep rather than performing one cohesive action.
      Threshold matches criteria 2 to avoid flagging simple read-then-write pairs
      (e.g. findById + save on a repository)."
  [methods intra-calls]
  (let [dep-sets (mapv #(set (map :bean (:deps %))) methods)]
    (mapv (fn [{:keys [deps method] :as m} own-set]
            (let [subsumed    (->> dep-sets
                                   (remove #(= % own-set))
                                   (filter #(set/subset? % own-set))
                                   count)
                  intra-count (->> (get intra-calls method #{})
                                   (remove #(= method %))
                                   (remove #(str/includes? % "$"))
                                   (remove #(= "<init>" %))
                                   count)
                  multi-call? (boolean (some #(>= (count (:calls %)) 3) deps))]
              (assoc m :orchestrator? (boolean (or (>= subsumed 2)
                                                   (>= intra-count 3)
                                                   multi-call?)))))
          methods
          dep-sets)))

;;; ---------------------------------------------------------------------------
;;; Public API

(defn method-dep-map
  "For each method on a bean, returns the subset of the bean's injected
   dependencies that method directly references in its bytecode.

   This answers the question 'which of this bean's dependencies does each
   method actually use?' — the missing link between lw/all-bean-deps (which
   gives the full injection set) and blast-radius (which traces callers upward).
   Use it to identify natural split boundaries in a bloated bean.

   Arguments:
     bean-name — Spring bean name (string), same as accepted by lw/bean

   Returns:
     {:bean             \"adminService\"
      :class            \"com.example.AdminService\"
      :methods          [{:method \"archiveBook\"
                          :deps   [{:bean \"bookRepository\"
                                    :calls [\"findById\" \"save\"]}]
                          :orchestrator? false}
                         ...]
      :unaccounted-deps []}

   Each entry in :deps is a map:
     :bean  — the Spring bean name of the injected dependency
     :calls — distinct method names called on that bean within this method,
              sorted alphabetically. Empty when the call site is not resolvable
              (e.g. via reflection or a lambda).

   :orchestrator? is true when any of these hold:
   - The method's dep-set is a proper superset of at least two other methods'
     dep-sets — wide footprint, sequences work across many collaborators.
   - The method makes 3+ intra-class calls to sibling methods — deep delegation
     without accumulating its own dep breadth.
   - The method calls 3+ distinct methods on the same injected bean — it is
     driving multiple operations through a single dep rather than doing one
     cohesive thing. A method that calls findById, save, and deleteById all on
     bookRepository is a coordinator, not a leaf operation.

   :unaccounted-deps lists injected beans not directly referenced by any method
   — possible dead injections or deps used only in @PostConstruct / initializers.

   The field→bean resolution walks the class hierarchy and checks bean type
   compatibility via instanceof. Only fields whose type matches a registered
   Spring bean are included.

   Options:
     :expand-private?  (default false) — when true, attributes field accesses
       of private helper methods to the public methods that call them. This
       surfaces the real dep footprint of each public method rather than
       listing private methods as independent entries. Private methods that
       are only called from within the class are suppressed from :methods.

   Known limitations:
   - Reflection and lambda captures are invisible to the scanner.
   - @PostConstruct and field initialiser accesses go into :unaccounted-deps.
   - Without :expand-private?, private helper methods appear as independent
     entries; their field accesses are not attributed to their public callers.
   - :expand-private? expands one level of private calls. Chains of private
     helpers calling other private helpers are not recursed further.

   Options:
     :intra-calls?  (default false) — when true, adds an :intra-calls key to each
       method entry listing the sibling methods on the same bean that it directly
       calls. Synthetics ($default, $lambda), self-calls, and constructors are
       excluded. Essential for refactoring splits: if method A calls method B
       internally, moving A without B breaks the service.

   :callers?  (default false) — when true, adds an :intra-callers key to each
       method entry listing the sibling methods that call IT (the inverse of
       :intra-calls?). Reveals which methods are only called internally, making
       them visibility-leak candidates rather than true dead code.

   Example:
     (cg/method-dep-map \"adminService\")
     (cg/method-dep-map \"bookService\" :expand-private? true)
     (cg/method-dep-map \"bookService\" :intra-calls? true)"
  [bean-name & {:keys [expand-private? intra-calls? callers?]
                :or {expand-private? false intra-calls? false callers? false}}]
  (let [b (core/bean bean-name)
        real-cls (AopUtils/getTargetClass b)
        {:keys [field-accesses invoke-calls]} (extract-field-and-invoke-accesses b)

        ;; field-name → bean-name (existing logic, unchanged)
        field->bn (->> field-accesses
                       (map :field)
                       distinct
                       (keep (fn [f]
                               (when-let [bn (field->bean-name real-cls f)]
                                 [f bn])))
                       (into {}))

        ;; dotted-type-name → bean-name, built from field types of known dep fields.
        ;; Used to match INVOKEVIRTUAL/INVOKEINTERFACE owner names back to a bean.
        type->bn (->> field->bn
                      (keep (fn [[fname bn]]
                              (when-let [f (find-field real-cls fname)]
                                [(.getName (.getType f)) bn])))
                      (into {}))

        ;; method → #{field-names} for all methods (inc private)
        method->fields (->> field-accesses
                            (group-by :method)
                            (map (fn [[m as]] [m (set (map :field as))]))
                            (into {}))

        ;; method → [{:owner :callee}] filtered to calls on known dep types only,
        ;; excluding constructors and synthetic methods
        method->dep-calls (->> invoke-calls
                               (filter #(get type->bn (:owner %)))
                               (remove #(= "<init>" (:callee %)))
                               (remove #(str/includes? (:callee %) "$"))
                               (group-by :method)
                               (map (fn [[m cs]] [m cs]))
                               (into {}))

        public-methods (when (or expand-private? callers?)
                         (set (map #(.getName %) (.getMethods real-cls))))
        intra-calls (extract-intra-class-calls b)

        ;; intra-callees = all methods called from within the class
        ;; internal-only = those that are not public (package-private/private helpers)
        ;; These are suppressed from top-level :methods when expand-private? is true;
        ;; their deps are folded into the public methods that call them.
        intra-callees (when expand-private?
                        (->> (vals intra-calls)
                             (apply set/union #{})))
        internal-only (when expand-private?
                        (set/difference intra-callees public-methods))

        ;; Effective fields for a method: own fields + fields of called private helpers.
        ;; When expand-private? is false, only the method's own fields are used.
        effective-fields (fn [method-name]
                           (if expand-private?
                             (let [priv-callees (set/difference
                                                 (get intra-calls method-name #{})
                                                 public-methods)]
                               (->> priv-callees
                                    (map #(get method->fields % #{}))
                                    (apply set/union (get method->fields method-name #{}))))
                             (get method->fields method-name #{})))

        ;; Effective dep-call sites for a method: own invoke calls + calls from called
        ;; private helpers (when expand-private? is true).
        effective-dep-calls (fn [method-name]
                              (if expand-private?
                                (let [priv-callees (set/difference
                                                    (get intra-calls method-name #{})
                                                    public-methods)]
                                  (concat (get method->dep-calls method-name [])
                                          (mapcat #(get method->dep-calls % []) priv-callees)))
                                (get method->dep-calls method-name [])))

        ;; Build enriched deps: [{:bean "bookRepository" :calls ["findById" "save"]}]
        ;; :calls lists the distinct method names called on that dep within this method.
        build-deps (fn [method-name]
                     (let [calls-for-method (effective-dep-calls method-name)]
                       (->> (effective-fields method-name)
                            (keep field->bn)
                            distinct
                            (mapv (fn [bn]
                                    {:bean bn
                                     :calls (->> calls-for-method
                                                 (filter #(= (get type->bn (:owner %)) bn))
                                                 (map :callee)
                                                 distinct
                                                 sort
                                                 vec)})))))

        methods (->> (keys method->fields)
                     (remove (fn [m] (and expand-private? (contains? internal-only m))))
                     (keep (fn [method]
                             (let [deps (build-deps method)]
                               (when (seq deps)
                                 {:method method :deps deps}))))
                     (sort-by :method)
                     vec
                     (#(add-orchestrator-flag % intra-calls)))

        methods (if intra-calls?
                  (mapv (fn [{:keys [method] :as m}]
                          (assoc m :intra-calls
                                 (->> (get intra-calls method #{})
                                      (remove #(= method %))
                                      (remove #(str/includes? % "$"))
                                      (remove #(= "<init>" %))
                                      sort vec)))
                        methods)
                  methods)

        ;; :callers? — invert the intra-calls map to show who calls each method
        callers-of (when callers?
                     (->> intra-calls
                          (reduce (fn [acc [caller callees]]
                                    (reduce (fn [a callee]
                                              (update a callee (fnil conj []) caller))
                                            acc callees))
                                  {})))

        methods (if callers?
                  (mapv (fn [{:keys [method] :as m}]
                          (assoc m :intra-callers
                                 (->> (get callers-of method [])
                                      (remove #(= method %))
                                      (remove #(str/includes? % "$"))
                                      sort vec)))
                        methods)
                  methods)

        all-bns (set (vals field->bn))
        accounted (set (mapcat #(map :bean (:deps %)) methods))
        dep-frequency (->> methods
                           (mapcat (fn [{:keys [method deps]}]
                                     (map #(vector (:bean %) method) deps)))
                           (group-by first)
                           (map (fn [[dep ms]]
                                  {:dep dep :used-by-count (count ms)
                                   :methods (mapv second ms)}))
                           (sort-by :used-by-count >)
                           vec)]
    {:bean bean-name
     :class (.getName real-cls)
     :methods methods
     :dep-frequency dep-frequency
     :unaccounted-deps (vec (remove accounted all-bns))}))

;;; ---------------------------------------------------------------------------
;;; Internal: dep-set group merging for method-dep-clusters

(defn- merge-compatible-groups
  "Merges groups whose dep-set is a proper subset of another group's dep-set.
   Iterates until no more merges are possible. A group whose dep-set is a
   subset of another's is absorbed into the larger group — its methods and
   intra-calls-map are folded in."
  [groups]
  (loop [groups (vec groups)]
    (let [merge-pair (first
                      (for [g groups
                            absorber groups
                            :when (and (not= (:dep-set g) (:dep-set absorber))
                                       (clojure.set/subset? (:dep-set g) (:dep-set absorber)))]
                        {:subset g :absorber absorber}))]
      (if-not merge-pair
        groups
        (let [{:keys [subset absorber]} merge-pair
              merged-absorber (-> absorber
                                  (update :methods into (:methods subset))
                                  (update :intra-calls-map merge (:intra-calls-map subset)))
              new-groups (->> groups
                              (remove #(= (:dep-set %) (:dep-set subset)))
                              (mapv #(if (= (:dep-set %) (:dep-set absorber))
                                       merged-absorber
                                       %)))]
          (recur new-groups))))))

(defn method-dep-clusters
  "For a bean, partitions its non-orchestrator methods into natural extraction
   clusters based on shared dep footprint. Answers 'where would you draw the
   split boundaries?' in a single call, without reading the source file.

   Groups methods by dep-set equality, then merges groups whose dep-set is a
   proper subset of another group's dep-set (set-containment — deterministic,
   no threshold parameter). Orchestrators are excluded from clustering and
   reported separately in :orchestrators; methods with no dep access are
   set aside in :dep-free.

   For each cluster, deps are classified as :exclusive-deps (used only by this
   cluster — can move cleanly with the extracted service) or :shared-deps (also
   used by at least one other cluster — must stay or be duplicated). Cross-cluster
   intra-calls are flagged as :intra-call-violations: they indicate the split as
   drawn would break an internal method call and require visibility promotion
   or co-location before the extraction is safe.

   Arguments:
     bean-name — Spring bean name (string), same as accepted by lw/bean

   Options:
     :expand-private?   (default false) — fold private helper deps into public
       callers before clustering; same semantics as method-dep-map option.
     :min-cluster-size  (default 1) — suppress clusters with fewer methods than
       this threshold. Use 2 to hide single-method islands from the output.

   Returns:
     {:bean              \"bookService\"
      :class             \"com.example.BookService\"
      :orchestrators     [{:method :deps :intra-calls} ...]
      :dep-free          [{:method ...} ...]
      :clusters          [{:id :methods :exclusive-deps :shared-deps
                           :intra-call-violations} ...]
      :shared-deps-summary [{:dep :used-by-clusters :used-by-orchestrators} ...]
      :unaccounted-deps  [...]}

   Example:
     (cg/method-dep-clusters \"adminService\")
     (cg/method-dep-clusters \"bookService\" :expand-private? true)
     (cg/method-dep-clusters \"adminService\" :min-cluster-size 2)"
  [bean-name & {:keys [expand-private? min-cluster-size]
                :or {expand-private? false min-cluster-size 1}}]
  (let [mdm (method-dep-map bean-name
                            :expand-private? expand-private?
                            :intra-calls? true)
        {:keys [bean class methods unaccounted-deps]} mdm
        noise? #{"equals" "hashCode" "toString"
                 "wait" "notify" "notifyAll" "getClass" "finalize"}

        ;; separate orchestrators, dep-free, and clustering candidates
        orchestrators (filterv :orchestrator? methods)
        non-orchestrators (remove :orchestrator? methods)
        dep-free (filterv #(empty? (:deps %)) non-orchestrators)
        candidates (->> non-orchestrators
                        (remove #(empty? (:deps %)))
                        (remove #(contains? noise? (:method %))))

        ;; group by exact dep-set equality (dep-set is the set of bean names only)
        grouped (->> candidates
                     (group-by #(set (map :bean (:deps %))))
                     (mapv (fn [[dep-set ms]]
                             {:dep-set dep-set
                              :methods (mapv :method ms)
                              :intra-calls-map (into {}
                                                     (map (fn [m]
                                                            [(:method m)
                                                             (get m :intra-calls [])])
                                                          ms))})))

        ;; merge groups whose dep-set is a subset of another group's dep-set
        merged (merge-compatible-groups grouped)

        ;; apply min-cluster-size
        merged (filterv #(>= (count (:methods %)) min-cluster-size) merged)

        ;; assign sequential IDs
        indexed (vec (map-indexed (fn [i c] (assoc c :id i)) merged))

        ;; method → cluster-id index for violation detection
        method->cluster (->> indexed
                             (mapcat (fn [{:keys [id methods]}]
                                       (map #(vector % id) methods)))
                             (into {}))

        ;; orchestrator deps for :used-by-orchestrators annotation
        orchestrator-deps (set (mapcat #(map :bean (:deps %)) orchestrators))

        ;; finalize each cluster: classify deps, detect violations
        clusters
        (mapv (fn [{:keys [id dep-set methods intra-calls-map]}]
                (let [other-deps (->> indexed
                                      (remove #(= (:id %) id))
                                      (mapcat #(seq (:dep-set %)))
                                      set)
                      exclusive (vec (sort (clojure.set/difference dep-set other-deps)))
                      shared (vec (sort (clojure.set/intersection dep-set other-deps)))
                      violations (->> methods
                                      (mapcat
                                       (fn [m]
                                         (->> (get intra-calls-map m [])
                                              (keep (fn [callee]
                                                      (when-let [cid (get method->cluster callee)]
                                                        (when (not= cid id)
                                                          {:caller m
                                                           :callee callee
                                                           :callee-cluster cid})))))))
                                      vec)]
                  {:id id
                   :methods (vec (sort methods))
                   :exclusive-deps exclusive
                   :shared-deps shared
                   :intra-call-violations violations}))
              indexed)

        ;; shared-deps-summary: one entry per dep appearing in any cluster's :shared-deps
        shared-dep-names (distinct (mapcat :shared-deps clusters))
        shared-deps-summary (->> shared-dep-names
                                 (map (fn [dep]
                                        {:dep dep
                                         :used-by-clusters (->> clusters
                                                                (filter #(some #{dep} (:shared-deps %)))
                                                                (mapv :id)
                                                                sort
                                                                vec)
                                         :used-by-orchestrators (contains? orchestrator-deps dep)}))
                                 (sort-by :dep)
                                 vec)]

    {:bean bean
     :class class
     :orchestrators (mapv #(select-keys % [:method :deps :intra-calls]) orchestrators)
     :dep-free (mapv #(hash-map :method (:method %)) dep-free)
     :clusters clusters
     :shared-deps-summary shared-deps-summary
     :unaccounted-deps unaccounted-deps}))

(defn- blast-radius-single
  "Core single-method blast-radius logic. Accepts pre-built indexes so that
   the wildcard path can build them once and reuse across all methods."
  [target-bean target-method all-deps cg http-index sched-index event-index]
  (let [dep-set (disj (transitive-dependents target-bean all-deps) target-bean)
        target-classes (bean->target-class-names target-bean)
        warnings (atom [])
        _ (when (seq (find-event-listener-methods target-bean (core/bean target-bean)))
            (swap! warnings conj
                   (str target-bean " has @EventListener methods — callers via ApplicationEventPublisher "
                        "are not visible to static bytecode analysis; inspect manually")))
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
        ;; Kotlin default-parameter methods compile to a synthetic "method$default"
        ;; at call sites. Look up both the canonical name and the $default variant
        ;; so that callers using default args are not missed.
        target-method-keys (cond-> [target-method]
                             (not (str/ends-with? target-method "$default"))
                             (conj (str target-method "$default")))
        all-affected
        (loop [frontier (->> target-classes
                             (mapcat (fn [cls] (mapcat #(get cg [cls %] []) target-method-keys)))
                             (filter #(contains? dep-set (:bean %)))
                             distinct
                             (mapv #(assoc % :depth 1)))
               visited #{}
               result []]
          (if (or (empty? frontier) (> (:depth (first frontier) 0) 10))
            result
            (let [annotated (mapv (fn [{:keys [bean method depth]}]
                                    {:bean bean
                                     :method method
                                     :depth depth
                                     :entry-point (annotate bean method)})
                                  frontier)
                  new-visited (into visited (map #(select-keys % [:bean :method]) frontier))
                  next-depth (inc (:depth (first frontier) 1))
                  next-layer (->> frontier
                                  (mapcat (fn [{:keys [bean method]}]
                                            (->> (bean->target-class-names bean)
                                                 (mapcat #(get cg [% method] []))
                                                 (filter #(and (contains? dep-set (:bean %))
                                                               (not (contains? new-visited
                                                                               (select-keys % [:bean :method]))))))))
                                  distinct
                                  (mapv #(assoc % :depth next-depth)))]
              (recur next-layer new-visited (into result annotated)))))]
    {:target {:bean target-bean :method target-method}
     :affected (sort-by (juxt :depth :bean) all-affected)
     :warnings @warnings}))

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

   Pass \"*\" as target-method to run blast-radius for every method on the bean
   and return a unified, deduplicated result — useful for understanding the full
   inbound call graph of a bean without running separate calls per method.

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
        (cg/blast-radius \"bookService\" \"archiveBook\")
               (cg/blast-radius \"bookService\" \"*\")
               (cg/blast-radius \"bookService\" \"*\" :per-method? true)"
  [target-bean target-method & {:keys [app-only per-method?] :or {app-only true per-method? false}}]
  (let [all-deps (app-bean-deps)
        cg (call-graph-index)
        http-index (build-http-endpoint-index)
        sched-index (build-scheduled-index)
        event-index (build-event-listener-index)]
    (if (= target-method "*")
      (let [method-names (->> (method-dep-map target-bean)
                              :methods
                              (map :method)
                              distinct)
            results (map (fn [m]
                           [m (blast-radius-single target-bean m all-deps cg
                                                   http-index sched-index event-index)])
                         method-names)
            warnings (vec (distinct (mapcat #(:warnings (second %)) results)))]
        (if per-method?
          {:target {:bean target-bean :method "*"}
           :per-method (->> results
                            (map (fn [[m r]] [m {:callers (:affected r)}]))
                            (into {}))
           :warnings warnings}
          {:target {:bean target-bean :method "*"}
           :affected (->> results
                          (mapcat #(:affected (second %)))
                          (group-by (juxt :bean :method))
                          (map (fn [[_ entries]] (first entries)))
                          (sort-by (juxt :depth :bean))
                          vec)
           :warnings warnings}))
      (blast-radius-single target-bean target-method all-deps cg
                           http-index sched-index event-index))))

           ;;; ---------------------------------------------------------------------------
           ;;; Internal: messaging annotation detection for dead-methods warnings

(def ^:private messaging-ann-names
  #{"EventListener" "TransactionalEventListener"
    "KafkaListener" "RabbitListener" "JmsListener"
    "SqsListener" "NatsListener" "StreamListener"
    "ServiceActivator"})

(defn- find-messaging-methods
  "Returns a seq of {:bean :method :annotation} for every app-level bean method
              annotated with a recognised messaging annotation (event listeners, message
              consumers, etc.). Used to generate dead-code false-positive warnings."
  []
  (->> (app-bean-deps)
       (mapcat (fn [{:keys [bean]}]
                 (try
                   (let [b (core/bean bean)
                         cls (AopUtils/getTargetClass b)]
                     (->> (seq (.getDeclaredMethods cls))
                          (mapcat (fn [m]
                                    (->> (.getDeclaredAnnotations m)
                                         (keep (fn [ann]
                                                 (let [sn (.getSimpleName (.annotationType ann))]
                                                   (when (or (contains? messaging-ann-names sn)
                                                             (str/includes? sn "Listener")
                                                             (str/includes? sn "Consumer"))
                                                     {:bean bean
                                                      :method (.getName m)
                                                      :annotation sn})))))))))
                   (catch Exception _ []))))
       distinct))

           ;;; ---------------------------------------------------------------------------
(defn- find-db-scheduler-tasks
  "Returns a seq of {:bean :task-name} for all Spring beans that implement the
   kagkarlsson db-scheduler Task interface. These tasks use lambda-based execution
   handlers whose target methods are not traceable by static bytecode analysis —
   they appear as blind spots in blast-radius and dead-methods."
  []
  (try
    (let [task-cls (Class/forName "com.github.kagkarlsson.scheduler.task.Task")]
      (->> (core/bean-names)
           (keep (fn [bn]
                   (try
                     (let [b (core/bean bn)]
                       (when (instance? task-cls b)
                         {:bean bn :task-name (.getTaskName b)}))
                     (catch Exception _ nil))))))
    (catch ClassNotFoundException _ [])))

;;; Public API — dead-methods

(defn dead-methods
  "Returns the public methods on a bean that have no external callers — no HTTP
   endpoint, scheduler, or event listener transitively invokes them.

   Results are split into two distinct categories:

   :dead — methods with no external callers AND no intra-class callers.
     True dead code: nothing calls these, inside or outside the bean.
     Primary candidates for deletion.

   :internal-only — methods with no external callers but WITH at least one
     intra-class caller. Public by accident (e.g. for testability or Kotlin
     default visibility). These are refactoring candidates, not deletion
     candidates — they reveal where a bean's internal orchestration exceeds
     its public API surface.

   Uses blast-radius per-method internally; all four indexes are built once.
   Only public methods (JVM ACC_PUBLIC) are analysed.

   Arguments:
     bean-name — Spring bean name (string), same as accepted by lw/bean

   Returns:
     {:bean              \"bookService\"
      :dead              []
      :internal-only     [{:method \"archiveBook\" :intra-callers [\"someHelper\"]} ...]
      :reachable-count   4
      :dead-count        0
      :internal-only-count 1
      :warnings          [...]}

   ⚠️ False-positive caveat: methods invoked only via ApplicationEventPublisher,
   NATS, Kafka, or other messaging infrastructure will appear dead or internal-only
   even though they are actively used. The :warnings key lists any detected
   messaging beans so you can cross-reference the results manually.

   Example:
     (cg/dead-methods \"bookService\")
     (cg/dead-methods \"adminService\")"
  [bean-name]
  (let [b (core/bean bean-name)
        real-cls (AopUtils/getTargetClass b)
        pub-names (set (map #(.getName %) (.getMethods real-cls)))
        per-method (-> (blast-radius bean-name "*" :per-method? true)
                       :per-method
                       (select-keys pub-names))
        ;; build callers-of from intra-class call map
        intra (extract-intra-class-calls b)
        callers-of (->> intra
                        (reduce (fn [acc [caller callees]]
                                  (reduce (fn [a callee]
                                            (update a callee (fnil conj []) caller))
                                          acc callees))
                                {}))
        clean-callers (fn [m]
                        (->> (get callers-of m [])
                             (remove #(= m %))
                             (remove #(str/includes? % "$"))
                             sort vec))
        ;; split: no external callers → check intra-callers
        no-ext (->> per-method
                    (filter #(empty? (:callers (val %))))
                    (map (fn [[m _]]
                           {:method m :intra-callers (clean-callers m)}))
                    (sort-by :method))
        dead (filterv #(empty? (:intra-callers %)) no-ext)
        internal (filterv #(seq (:intra-callers %)) no-ext)
        reachable (->> per-method (filter #(seq (:callers (val %)))) count)
        ;; warnings
        msg-methods (find-messaging-methods)
        db-tasks (find-db-scheduler-tasks)
        warnings (cond-> []
                   (seq msg-methods)
                   (conj (let [by-ann (group-by :annotation msg-methods)
                               summary (->> by-ann (map (fn [[a ms]] (str (count ms) " @" a))) (str/join ", "))
                               examples (->> msg-methods (take 5) (map #(str (:bean %) "." (:method %))) (str/join ", "))]
                           (str summary " bean methods detected — methods invoked only via events or "
                                "messages may appear as dead code. Review: " examples
                                (when (> (count msg-methods) 5) (str " … and " (- (count msg-methods) 5) " more")))))
                   (seq db-tasks)
                   (conj (let [names (->> db-tasks (map #(str (:bean %) " (\"" (:task-name %) "\")")) (str/join ", "))]
                           (str (count db-tasks) " db-scheduler (kagkarlsson) recurring task(s) detected — "
                                "their execution handlers use lambdas and are not visible to static analysis. "
                                "Tasks: " names))))]
    {:bean bean-name
     :dead dead
     :internal-only internal
     :reachable-count reachable
     :dead-count (count dead)
     :internal-only-count (count internal)
     :warnings warnings}))
