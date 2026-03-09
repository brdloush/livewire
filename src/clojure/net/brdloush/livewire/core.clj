(ns net.brdloush.livewire.core
  "Core namespace: Spring ApplicationContext access, bean lookup,
   property inspection, and transactional exploration macros.

   This namespace is populated by the Spring bootstrap bean at startup.
   All functions require the context to be set first (happens automatically
   when the nREPL server starts)."
  (:import [org.springframework.core.env ConfigurableEnvironment EnumerablePropertySource]
           [org.springframework.transaction PlatformTransactionManager TransactionDefinition]
           [org.springframework.transaction.support TransactionTemplate TransactionCallback]))

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
   UsernamePasswordAuthenticationToken for use with `run-as`.
   Returns nil if Spring Security is not on the classpath."
  [user-or-auth]
  (let [auth-class (try (Class/forName "org.springframework.security.core.Authentication") (catch Exception _ nil))]
    (cond
      (not auth-class)
      (throw (IllegalStateException. "Spring Security is not on the classpath."))

      ;; If it's already an Authentication object
      (instance? auth-class user-or-auth)
      user-or-auth

      ;; If it's a vector [username "ROLE_ADMIN" "ROLE_USER"]
      (vector? user-or-auth)
      (let [username (first user-or-auth)
            roles (mapv #(org.springframework.security.core.authority.SimpleGrantedAuthority. (str %)) (rest user-or-auth))]
        (org.springframework.security.authentication.UsernamePasswordAuthenticationToken. username "password" roles))

      ;; If it's a simple string, give them a generic token with USER and ADMIN
      (string? user-or-auth)
      (let [roles [(org.springframework.security.core.authority.SimpleGrantedAuthority. "ROLE_USER")
                   (org.springframework.security.core.authority.SimpleGrantedAuthority. "ROLE_ADMIN")]]
        (org.springframework.security.authentication.UsernamePasswordAuthenticationToken. user-or-auth "password" roles))

      :else
      (throw (IllegalArgumentException. "run-as requires a String, Vector [user role1], or Authentication object")))))

(defmacro run-as
  "Runs body with the given user context set in the Spring SecurityContextHolder.
   `user-or-auth` can be:
     - A String username (e.g. \"admin\") -> Automatically gets ROLE_USER and ROLE_ADMIN
     - A vector of [username \"ROLE_1\" \"ROLE_2\"]
     - An actual Spring Authentication object
   Restores the original SecurityContext afterwards. Essential for authorization debugging."
  [user-or-auth & body]
  `(let [auth-obj# (net.brdloush.livewire.core/->authentication ~user-or-auth)
         ctx-class# (Class/forName "org.springframework.security.core.context.SecurityContextImpl")
         ctx#       (clojure.lang.Reflector/invokeConstructor ctx-class# (object-array [auth-obj#]))
         holder#    (Class/forName "org.springframework.security.core.context.SecurityContextHolder")
         old-ctx#   (clojure.lang.Reflector/invokeStaticMethod holder# "getContext" (object-array []))]
     (clojure.lang.Reflector/invokeStaticMethod holder# "setContext" (object-array [ctx#]))
     (try
       (do ~@body)
       (finally
         (clojure.lang.Reflector/invokeStaticMethod holder# "setContext" (object-array [old-ctx#]))))))
