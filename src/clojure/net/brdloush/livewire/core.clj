(ns net.brdloush.livewire.core
  "Core namespace: Spring ApplicationContext access, bean lookup,
   property inspection, and transactional exploration macros.

   This namespace is populated by the Spring bootstrap bean at startup.
   All functions require the context to be set first (happens automatically
   when the nREPL server starts)."
  (:import [org.springframework.core.env ConfigurableEnvironment EnumerablePropertySource]
           [org.springframework.transaction PlatformTransactionManager TransactionDefinition]
           [org.springframework.transaction.support TransactionTemplate TransactionCallback]
           [org.springframework.security.core.context SecurityContextHolder SecurityContextImpl]
           [org.springframework.security.authentication UsernamePasswordAuthenticationToken]
           [org.springframework.security.core.authority SimpleGrantedAuthority]
           [org.springframework.beans.factory.config ConfigurableListableBeanFactory]
           [org.springframework.transaction TransactionDefinition]
           [org.springframework.transaction.interceptor RollbackRuleAttribute NoRollbackRuleAttribute]
           [org.springframework.aop.support AopUtils]))

;;; ---------------------------------------------------------------------------
;;; Context atom — populated by boot.clj on startup

(defonce ^:private -ctx (atom nil))

(defn set-context!
  "Called by the Spring bootstrap bean to inject the ApplicationContext.
   Not intended for direct use."
  [app-ctx]
  (reset! -ctx app-ctx))

(defn ctx
  "Returns the live Spring ApplicationContext.
   Throws if the context has not been injected yet."
  []
  (or @-ctx
      (throw (IllegalStateException.
               "Livewire: ApplicationContext not set. Is the nREPL server running?"))))

;;; ---------------------------------------------------------------------------
;;; Bean access

(defn bean
  "Retrieves a Spring bean by name (String) or type (Class).

   Examples:
     (bean \"userService\")
     (bean UserService)"
  [name-or-class]
  (let [c (ctx)]
    (if (string? name-or-class)
      (.getBean c ^String name-or-class)
      (.getBean c ^Class name-or-class))))

(defn beans-of-type
  "Returns a map of bean-name -> bean instance for all beans of the given type.

   Example:
     (beans-of-type DataSource)"
  [clazz]
  (into {} (.getBeansOfType (ctx) clazz)))

(defn bean-names
  "Lists all registered bean names in the ApplicationContext."
  []
  (seq (.getBeanDefinitionNames (ctx))))

(defn find-beans-matching
  "Returns all bean names whose name matches the given regex pattern.

   Example:
     (find-beans-matching \".*Repository.*\")"
  [pattern]
  (let [re (re-pattern pattern)]
    (filter #(re-find re %) (bean-names))))

(defn bean->map
  "Converts a Java object to a Clojure map, handling both regular JavaBeans
   and Java records (introduced in Java 16).

   `clojure.core/bean` works by scanning for `getX()` accessor methods.
   Java records generate accessors without the `get` prefix (`rating()`, `comment()`,
   etc.) — `clojure.core/bean` sees none of these and silently returns `{}`.

   `bean->map` detects records via `Class.isRecord()` and uses
   `Class.getRecordComponents()` instead, so the full field map is always returned
   regardless of whether the object is a record or a plain JavaBean.

   Examples:
     ;; Java record (e.g. a DTO returned by a service/controller)
     (lw/bean->map some-stats-dto)
     ;; => {:totalBooks 200, :totalAuthors 30, :totalMembers 50, ...}

     ;; Regular JPA entity — identical to (clojure.core/bean obj)
     (lw/bean->map some-book-entity)
     ;; => {:id 1, :title \"Pride and Prejudice\", :isbn \"...\", :archived false, ...}"
  [obj]
  (let [cls (.getClass obj)]
    (if (.isRecord cls)
      (into {}
            (map (fn [c] [(keyword (.getName c))
                          (.invoke (.getAccessor c) obj (object-array 0))])
                 (.getRecordComponents cls)))
      (clojure.core/bean obj))))

;;; ---------------------------------------------------------------------------
;;; Bean dependency introspection

(defn- bean-factory
  "Returns the ConfigurableListableBeanFactory from the current ApplicationContext."
  []
  (.getBeanFactory (ctx)))

(defn- app-root-package
  "Detects the root package of the application by finding the class annotated
  with @SpringBootApplication. Returns nil if none is found."
  []
  (let [c (ctx)
        names (.getBeanNamesForAnnotation c org.springframework.boot.autoconfigure.SpringBootApplication)]
    (when (seq names)
      (-> c (.getBean (first names)) .getClass .getPackage .getName))))

(defn- clean-dep-name?
  "Returns true for dependency names that are simple bean identifiers.
  Filters out:
  - Fully-qualified class names (contain a dot) — e.g. autoconfiguration placeholders.
  - Object identity strings (contain @) — e.g. raw BeanFactory/ApplicationContext refs."
  [^String s]
  (and (not (clojure.string/includes? s "."))
       (not (clojure.string/includes? s "@"))))

(defn- clean-class-name
  "Strips CGLIB proxy suffixes (e.g. $$SpringCGLIB$$0) from a class name."
  [^String class-name]
  (when class-name
    (clojure.string/replace class-name #"\$\$.*$" "")))

(defn bean-deps
  "Returns a map describing the runtime wiring of a single bean:

     {:bean        \"myService\"
      :class       \"com.example.MyService\"   ; resolved class name (nil for anonymous beans)
      :dependencies [\"repoA\" \"repoB\"]        ; beans this bean injects / depends on
      :dependents   [\"controllerX\"]}           ; beans that inject this bean

   Uses Spring's internal dependency tracking (populated during context refresh)
   which captures constructor injection, @Autowired fields, and @Inject fields.
   Names that look like FQCNs or raw object references are filtered out.

   Example:
     (lw/bean-deps \"bookService\")
     ;; => {:bean         \"bookService\"
     ;;     :class        \"com.example.BookService\"
     ;;     :dependencies [\"bookRepository\"]
     ;;     :dependents   [\"adminController\" \"bookController\"]}"
  [bean-name]
  (let [bf  (bean-factory)
        cls (clean-class-name
              (try (some-> (.getBeanDefinition bf bean-name) .getBeanClassName)
                   (catch Exception _ nil)))]
    {:bean         bean-name
     :class        cls
     :dependencies (filterv clean-dep-name? (.getDependenciesForBean bf bean-name))
     :dependents   (try (filterv clean-dep-name? (.getDependentBeans bf bean-name))
                        (catch Exception _ []))}))

(defn all-bean-deps
  "Returns a seq of dependency maps (see bean-deps) for beans in the application context.

  Options:
    :app-only  (default true) — when true, restricts results to beans whose class
               belongs to the application's own root package (auto-detected from
               @SpringBootApplication). Set to false to include all Spring
               infrastructure beans.

  Each map contains :bean, :class, :dependencies, and :dependents.
  Beans whose names are FQCNs or role >= 2 synthetic beans are always excluded.

  Examples:

    ;; App beans only (default) — typically a handful of your own classes
    (lw/all-bean-deps)

    ;; Full context including Spring Boot infrastructure
    (lw/all-bean-deps :app-only false)

    ;; Top 10 beans by number of dependencies (coupling smell candidates)
    (->> (lw/all-bean-deps)
         (sort-by #(count (:dependencies %)) >)
         (take 10)
         (mapv #(select-keys % [:bean :class :dependencies])))"
  [& {:keys [app-only] :or {app-only true}}]
  (let [c   (ctx)
        bf  (.getBeanFactory c)
        pkg (when app-only (app-root-package))]
    (->> (.getBeanDefinitionNames c)
         (filter (fn [n]
                   (when-let [bd (try (.getBeanDefinition bf n) (catch Exception _ nil))]
                     (and (< (.getRole bd) 2)
                          (not (clojure.string/includes? n "."))
                          (if pkg
                            (some-> (.getBeanClassName bd)
                                    (clojure.string/starts-with? pkg))
                            true)))))
         (mapv bean-deps))))

;;; ---------------------------------------------------------------------------
;;; Transactional boundary introspection

(def ^:private propagation-names
  {TransactionDefinition/PROPAGATION_REQUIRED     :required
   TransactionDefinition/PROPAGATION_SUPPORTS     :supports
   TransactionDefinition/PROPAGATION_MANDATORY    :mandatory
   TransactionDefinition/PROPAGATION_REQUIRES_NEW :requires-new
   TransactionDefinition/PROPAGATION_NOT_SUPPORTED :not-supported
   TransactionDefinition/PROPAGATION_NEVER        :never
   TransactionDefinition/PROPAGATION_NESTED       :nested})

(def ^:private isolation-names
  {TransactionDefinition/ISOLATION_DEFAULT          :default
   TransactionDefinition/ISOLATION_READ_UNCOMMITTED :read-uncommitted
   TransactionDefinition/ISOLATION_READ_COMMITTED   :read-committed
   TransactionDefinition/ISOLATION_REPEATABLE_READ  :repeatable-read
   TransactionDefinition/ISOLATION_SERIALIZABLE     :serializable})

(defn- tx-attr->map
  "Converts a Spring TransactionAttribute to a plain Clojure map."
  [attr]
  {:propagation     (get propagation-names (.getPropagationBehavior attr) (.getPropagationBehavior attr))
   :isolation       (get isolation-names (.getIsolationLevel attr) (.getIsolationLevel attr))
   :read-only       (.isReadOnly attr)
   :timeout         (.getTimeout attr)
   :rollback-for    (vec (keep #(when (and (instance? RollbackRuleAttribute %)
                                           (not (instance? NoRollbackRuleAttribute %)))
                                  (.getExceptionName %))
                               (.getRollbackRules attr)))
   :no-rollback-for (vec (keep #(when (instance? NoRollbackRuleAttribute %)
                                  (.getExceptionName %))
                               (.getRollbackRules attr)))})

(defn bean-tx
  "Returns a map describing the effective @Transactional configuration of a single bean:

     {:bean    \"myService\"
      :class   \"com.example.MyService\"
      :methods [{:method      \"save\"
                 :propagation :required
                 :isolation   :default
                 :read-only   false
                 :timeout     -1
                 :rollback-for    []
                 :no-rollback-for []}
                ...]}

   Only public methods with an effective @Transactional annotation are included
   (both method-level and class-level are resolved correctly). Methods with no
   transaction configuration are omitted.

   Note: JPA repository beans (e.g. BookRepository) expose all methods from
   SimpleJpaRepository including many overloaded variants — the result will be
   verbose. Use all-bean-tx with the default :app-only true to restrict to your
   own service/component classes.

   Example:
     (lw/bean-tx \"bookService\")
     ;; => {:bean \"bookService\"
     ;;     :class \"com.example.BookService\"
     ;;     :methods [{:method \"archiveBook\" :propagation :required :read-only false ...}
     ;;               {:method \"getAllBooks\"  :propagation :required :read-only true  ...}]}"
  [bean-name]
  (let [b          (bean bean-name)
        target-cls (AopUtils/getTargetClass b)
        cls-name   (clean-class-name (.getName target-cls))
        txas       (try (bean "transactionAttributeSource") (catch Exception _ nil))]
    {:bean    bean-name
     :class   cls-name
     :methods (if-not txas
                []
                (->> (.getDeclaredMethods target-cls)
                     (filter #(pos? (bit-and (.getModifiers %) java.lang.reflect.Modifier/PUBLIC)))
                     (keep (fn [m]
                             (when-let [attr (try (.getTransactionAttribute txas m target-cls)
                                                  (catch Exception _ nil))]
                               (assoc (tx-attr->map attr) :method (.getName m)))))
                     (sort-by :method)
                     vec))}))

(defn all-bean-tx
  "Returns a seq of transaction maps (see bean-tx) for beans in the application context,
  restricted to beans that have at least one transactional method.

  Options:
    :app-only  (default true) — when true, restricts to beans in the application's own
               root package (auto-detected from @SpringBootApplication). Recommended:
               this filters out JPA repository beans whose SimpleJpaRepository base
               class produces very verbose output.

  Requires @EnableTransactionManagement to be active. Returns an empty seq if
  transactionAttributeSource is not present in the context.

  Examples:

    ;; All app-level beans with transactional methods (default)
    (lw/all-bean-tx)

    ;; Include Spring infrastructure beans too
    (lw/all-bean-tx :app-only false)

    ;; Methods that look like reads but are not marked read-only — potential smell
    (->> (lw/all-bean-tx)
         (mapcat (fn [b] (map #(assoc % :bean (:bean b)) (:methods b))))
         (filter #(and (not (:read-only %))
                       (re-find #\"(?i)^(get|find|list|count|search|fetch)\" (:method %)))))

    ;; All REQUIRES_NEW methods — potential nested transaction complexity
    (->> (lw/all-bean-tx)
         (mapcat (fn [b] (map #(assoc % :bean (:bean b)) (:methods b))))
         (filter #(= :requires-new (:propagation %))))"
  [& {:keys [app-only] :or {app-only true}}]
  (let [c   (ctx)
        bf  (.getBeanFactory c)
        pkg (when app-only (app-root-package))]
    (->> (.getBeanDefinitionNames c)
         (filter (fn [n]
                   (when-let [bd (try (.getBeanDefinition bf n) (catch Exception _ nil))]
                     (and (< (.getRole bd) 2)
                          (not (clojure.string/includes? n "."))
                          (if pkg
                            (some-> (.getBeanClassName bd)
                                    (clojure.string/starts-with? pkg))
                            true)))))
         (mapv bean-tx)
         (filterv #(seq (:methods %))))))

;;; ---------------------------------------------------------------------------
;;; Environment information

(defn info
  "Returns basic information about the running environment (Spring version,
   Java version, active profiles, etc.)."
  []
  (let [env (.getEnvironment (ctx))]
    {:application-name    (.getProperty env "spring.application.name" "unknown")
     :active-profiles     (vec (.getActiveProfiles env))
     :java-version        (System/getProperty "java.version")
     :os-name             (System/getProperty "os.name")
     :spring-version      (try (org.springframework.core.SpringVersion/getVersion)
                               (catch Throwable _ nil))
     :spring-boot-version (try (org.springframework.boot.SpringBootVersion/getVersion)
                               (catch Throwable _ nil))
     :hibernate-version   (try (org.hibernate.Version/getVersionString)
                               (catch Throwable _ nil))}))

;;; ---------------------------------------------------------------------------
;;; Property inspection

(defn all-properties
  "Returns a map of all resolved environment properties visible to Spring.
   Iterates all EnumerablePropertySources (application.properties,
   system props, env vars, etc.)."
  []
  (let [env (.getEnvironment (ctx))]
    (if (instance? ConfigurableEnvironment env)
      (->> (.getPropertySources ^ConfigurableEnvironment env)
           (mapcat (fn [ps]
                     (when (instance? EnumerablePropertySource ps)
                       (map (fn [k] [k (.getProperty env k)])
                            (.getPropertyNames ^EnumerablePropertySource ps)))))
           (into {}))
      {})))

(defn props-matching
  "Returns a sub-map of all-properties whose keys match the given regex pattern.

   Example:
     (props-matching \"spring\\\\.datasource.*\")"
  [pattern]
  (let [re (re-pattern pattern)]
    (into {} (filter (fn [[k _]] (re-find re k)) (all-properties)))))

;;; ---------------------------------------------------------------------------
;;; Transactional macros

(defmacro in-tx
  "Executes body inside a real Spring transaction that is always rolled back,
   even on success. Safe for mutation exploration — nothing persists.

   Returns the value of the last expression in body.

   Example:
     (in-tx
       (.save userRepository (->User \"test@example.com\"))
       (count (findAll userRepository)))"
  [& body]
  `(let [tt# (doto (TransactionTemplate. (bean PlatformTransactionManager))
               (.setPropagationBehavior TransactionDefinition/PROPAGATION_REQUIRES_NEW))]
     (.execute tt#
       (reify TransactionCallback
         (doInTransaction [_# status#]
           (let [result# (do ~@body)]
             (.setRollbackOnly status#)
             result#))))))

(defmacro in-readonly-tx
  "Executes body inside a read-only Spring transaction.
   Useful for JPQL/SQL queries that require an active session.

   Returns the value of the last expression in body.

   Example:
     (in-readonly-tx
       (.findAll userRepository))"
  [& body]
  `(let [tt# (doto (TransactionTemplate. (bean PlatformTransactionManager))
               (.setPropagationBehavior TransactionDefinition/PROPAGATION_REQUIRES_NEW)
               (.setReadOnly true))]
     (.execute tt#
       (reify TransactionCallback
         (doInTransaction [_# _status#]
           (do ~@body))))))

;;; ---------------------------------------------------------------------------
;;; Security

(defn ->authentication
  "Coerces a string, vector, or existing Authentication object into a
   UsernamePasswordAuthenticationToken for use with `run-as`."
  [user-or-auth]
  (cond
    ;; Already an Authentication object — pass through unchanged
    (instance? org.springframework.security.core.Authentication user-or-auth)
    user-or-auth

    ;; Vector form: [username "ROLE_X" "ROLE_Y"]
    (vector? user-or-auth)
    (let [username (first user-or-auth)
          roles    (mapv #(SimpleGrantedAuthority. (str %)) (rest user-or-auth))]
      (UsernamePasswordAuthenticationToken. username "password" roles))

    ;; Plain string: grant ROLE_USER + ROLE_ADMIN
    (string? user-or-auth)
    (let [roles [(SimpleGrantedAuthority. "ROLE_USER")
                 (SimpleGrantedAuthority. "ROLE_ADMIN")]]
      (UsernamePasswordAuthenticationToken. user-or-auth "password" roles))

    :else
    (throw (IllegalArgumentException.
             "run-as requires a String, Vector [user role1 role2], or Authentication object"))))

(defmacro run-as
  "Runs body with the given user context set in the Spring SecurityContextHolder.
   `user-or-auth` can be:
     - A String username (e.g. \"admin\") -> Automatically gets ROLE_USER and ROLE_ADMIN
     - A vector of [username \"ROLE_1\" \"ROLE_2\"]
     - An actual Spring Authentication object
   Restores the original SecurityContext afterwards. Essential for authorization debugging."
  [user-or-auth & body]
  `(let [auth-obj# (net.brdloush.livewire.core/->authentication ~user-or-auth)
         ctx#      (SecurityContextImpl. auth-obj#)
         old-ctx#  (SecurityContextHolder/getContext)]
     (SecurityContextHolder/setContext ctx#)
     (try
       (do ~@body)
       (finally
         (SecurityContextHolder/setContext old-ctx#)))))
