(ns net.brdloush.livewire.faker
  "Test data generation: build valid, optionally-persistable Hibernate entity instances
   using datafaker heuristics. All datafaker access is reflective — no :import of
   net.datafaker classes — so this namespace loads safely on apps that do not have
   datafaker on the classpath."
  (:require [net.brdloush.livewire.core :as core]
            [net.brdloush.livewire.introspect :as intro]
            [clojure.string :as str])
  (:import [java.time LocalDateTime ZoneId]
           [java.util.concurrent TimeUnit]
           [org.springframework.transaction PlatformTransactionManager TransactionDefinition]
           [org.springframework.transaction.support TransactionTemplate TransactionCallback]))

;;; ---------------------------------------------------------------------------
;;; Classpath safety

(defn available?
  "Returns true if net.datafaker.Faker is present on the classpath.
   Call this as a preflight check before any faker workflow:

     (when-not (faker/available?)
       (println \"datafaker not found — add net.datafaker:datafaker to your project\"))"
  []
  (try (Class/forName "net.datafaker.Faker") true
       (catch ClassNotFoundException _ false)))

(defn- make-faker
  "Constructs a net.datafaker.Faker instance reflectively.
   Throws a descriptive ex-info if datafaker is not on the classpath."
  []
  (try
    (clojure.lang.Reflector/invokeConstructor
     (Class/forName "net.datafaker.Faker")
     (object-array []))
    (catch ClassNotFoundException _
      (throw (ex-info
              (str "net.datafaker.Faker not found on the classpath.\n"
                   "Add the following dependency to your project:\n\n"
                   "  Maven:  <dependency>\n"
                   "            <groupId>net.datafaker</groupId>\n"
                   "            <artifactId>datafaker</artifactId>\n"
                   "            <version>2.5.4</version>\n"
                   "          </dependency>\n\n"
                   "  Gradle: implementation 'net.datafaker:datafaker:2.5.4'")
              {:missing-class "net.datafaker.Faker"})))))

;;; ---------------------------------------------------------------------------
;;; Reflection helpers

(defn- find-setter
  "Finds the public setter method for prop-name on cls (setXxx convention)."
  [^Class cls ^String prop-name]
  (let [setter-name (str "set"
                         (str/upper-case (subs prop-name 0 1))
                         (subs prop-name 1))]
    (->> (.getMethods cls)
         (filter #(= setter-name (.getName %)))
         first)))

;;; ---------------------------------------------------------------------------
;;; Value coercion

(defn- coerce-value
  "Coerces a generated value to match the setter's parameter type.
   Handles numeric boxing/unboxing and java.util.Date → LocalDateTime/LocalDate."
  [value ^Class target-type]
  (when (some? value)
    (let [tname (.getName target-type)]
      (cond
        (.isInstance target-type value) value
        (.isAssignableFrom target-type (class value)) value
        (= tname "java.lang.Short") (short value)
        (= tname "short") (short value)
        (= tname "java.lang.Integer") (int value)
        (= tname "int") (int value)
        (= tname "java.lang.Long") (long value)
        (= tname "long") (long value)
        (= tname "java.lang.Boolean") (boolean value)
        (= tname "boolean") (boolean value)
        (= tname "java.math.BigDecimal") (bigdec value)
        (= tname "java.lang.Float") (float value)
        (= tname "float") (float value)
        (= tname "java.lang.Double") (double value)
        (= tname "double") (double value)
        (and (instance? java.util.Date value)
             (= tname "java.time.LocalDateTime"))
        (LocalDateTime/ofInstant (.toInstant ^java.util.Date value)
                                 (ZoneId/systemDefault))
        (and (instance? java.util.Date value)
             (= tname "java.time.LocalDate"))
        (.toLocalDate (LocalDateTime/ofInstant (.toInstant ^java.util.Date value)
                                               (ZoneId/systemDefault)))
        :else value))))

;;; ---------------------------------------------------------------------------
;;; Heuristic value generator

(def ^:private heuristic-table
  "Vector of [name-pattern type-pattern generator-key] used to select a faker provider.
   Evaluated top-to-bottom; first match wins. Name patterns are matched
   case-insensitively against the camelCase property name."
  [;; --- Person ---
   ["^firstName$" "string" :first-name]
   ["^lastName$" "string" :last-name]
   ["^fullName$" "string" :full-name]
   ["^name$" "string" :full-name]
   ["^prefix$" "string" :name-prefix]
   ["^suffix$" "string" :name-suffix]
   ["^gender$" "string" :gender]
   ["^sex$" "string" :gender]
   ["^age$" nil :age]
   ["birth(Day|Date|Ts|At)?$" nil :birthdate]
   ["nationality" "string" :nationality]

   ;; --- Contact ---
   ["email" "string" :email]
   ["username" "string" :username]
   ["^login$" "string" :username]
   ["phone|telephone|mobile|cell|fax" "string" :phone]

   ;; --- Address ---
   ["^streetAddress$" "string" :street-address]
   ["^street$" "string" :street-name]
   ["^address$" "string" :full-address]
   ["^city$" "string" :city]
   ["^town$" "string" :city]
   ["^state$" "string" :state]
   ["^county$" "string" :state]
   ["^country$" "string" :country]
   ["^countryCode$" "string" :country-code]
   ["zipCode|postCode|postalCode" "string" :zip-code]
   ["^zip$" "string" :zip-code]
   ["^postal$" "string" :zip-code]
   ["^latitude$" "string" :latitude]
   ["^longitude$" "string" :longitude]
   ["^timeZone$" "string" :timezone]

   ;; --- Internet / Web ---
   ["^url$" "string" :url]
   ["^website$" "string" :url]
   ["^domainName$" "string" :domain-name]
   ["^slug$" "string" :slug]
   ["^token$" "string" :token]
   ["^image$" "string" :image-url]
   ["^avatar$" "string" :image-url]
   ["^imageUrl$" "string" :image-url]

   ;; --- Identifiers / Codes ---
   ["^isbn$" "string" :isbn]
   ["^isbn10$" "string" :isbn10]
   ["^isbn13$" "string" :isbn]
   ["uuid" nil :uuid]
   ["^reference$" "string" :lorem-word]
   ["^code$" "string" :lorem-word]

   ;; --- Company / Organisation ---
   ["^company$" "string" :company]
   ["^companyName$" "string" :company]
   ["^organization$" "string" :company]
   ["^employer$" "string" :company]
   ["^department$" "string" :department]
   ["^jobTitle$" "string" :job-title]
   ["^position$" "string" :job-position]
   ["^occupation$" "string" :job-title]

   ;; --- Financial ---
   ["^price$" nil :price]
   ["^amount$" nil :decimal]
   ["^salary$" nil :decimal]
   ["^currency$" "string" :currency-code]
   ["^currencyCode$" "string" :currency-code]
   ["^iban$" "string" :iban]
   ["^creditCard$" "string" :credit-card]

   ;; --- Content / Text ---
   ["^title$" "string" :book-title]
   ["^subject$" "string" :lorem-sentence]
   ["^body$" "string" :lorem-para]
   ["^description$" "string" :lorem-para]
   ["^summary$" "string" :lorem-sentence]
   ["^content$" "string" :lorem-para]
   ["^note$" "string" :lorem-sentence]
   ["^bio$" "string" :lorem-sentence]
   ["comment" "string" :lorem-para]

   ;; --- Locale / Language ---
   ["^locale$" "string" :locale]
   ["^language$" "string" :locale]

   ;; --- Appearance ---
   ["^color$" "string" :color-name]
   ["^colour$" "string" :color-name]
   ["^colorHex$" "string" :color-hex]
   ["^colourHex$" "string" :color-hex]

   ;; --- Ratings / counts ---
   ["^rating$" nil :rating]

   ;; --- Suffix / substring patterns ---
   ["[Yy]ear$" nil :year]
   ["[Cc]opies$" nil :copies]
   ["(At|Since|Date|Time)$" nil :past-date]

   ;; --- Type-only fallbacks (nil name pattern = match any name) ---
   [nil "string" :lorem-word]
   [nil "int|long|short|.*[Ii]nteger|.*[Ll]ong|.*[Ss]hort" :number]
   [nil ".*[Dd]ouble|.*[Ff]loat|.*[Bb]ig[Dd]ecimal" :decimal]
   [nil "boolean|.*[Bb]oolean" :bool]
   [nil ".*UUID" :uuid]])

(defn- generate-value
  "Selects a faker generator from heuristic-table and produces a value."
  [faker prop-name prop-type-name]
  (let [number-fn #(.numberBetween (.number faker) (int %1) (int %2))
        past-fn #(.past (.date faker) (int 730) TimeUnit/DAYS)
        match? (fn [[name-pat type-pat _]]
                 (and (or (nil? name-pat)
                          (re-find (re-pattern (str "(?i)" name-pat)) prop-name))
                      (or (nil? type-pat)
                          (re-find (re-pattern (str "(?i)" type-pat)) prop-type-name))))
        [_ _ gen-key] (first (filter match? heuristic-table))]
    (case gen-key
      ;; Person
      :first-name     (.firstName (.name faker))
      :last-name      (.lastName (.name faker))
      :full-name      (str (.firstName (.name faker)) " " (.lastName (.name faker)))
      :name-prefix    (.prefix (.name faker))
      :name-suffix    (.suffix (.name faker))
      :gender         (.types (.gender faker))
      :age            (number-fn 18 90)
      :birthdate      (.birthday (.date faker))
      :nationality    (.nationality (.nation faker))
      ;; Contact
      :email          (.emailAddress (.internet faker))
      :username       (.username (.internet faker))
      :phone          (.phoneNumber (.phoneNumber faker))
      ;; Address
      :street-address (.streetAddress (.address faker))
      :street-name    (.streetName (.address faker))
      :full-address   (.fullAddress (.address faker))
      :city           (.city (.address faker))
      :state          (.state (.address faker))
      :country        (.country (.address faker))
      :country-code   (.countryCode (.address faker))
      :zip-code       (.zipCode (.address faker))
      :latitude       (.latitude (.address faker))
      :longitude      (.longitude (.address faker))
      :timezone       (.timeZone (.address faker))
      ;; Internet / Web
      :url            (.url (.internet faker))
      :domain-name    (.domainName (.internet faker))
      :slug           (.slug (.internet faker))
      :token          (str (java.util.UUID/randomUUID))
      :image-url      (.image (.internet faker))
      ;; Identifiers / Codes
      :isbn           (.isbn13 (.code faker) true)
      :isbn10         (.isbn10 (.code faker) true)
      ;; Company / Organisation
      :company        (.name (.company faker))
      :department     (.department (.commerce faker))
      :job-title      (.title (.job faker))
      :job-position   (.position (.job faker))
      ;; Financial
      :price          (Double/parseDouble (.price (.commerce faker)))
      :currency-code  (.currencyCode (.money faker))
      :iban           (.iban (.finance faker))
      :credit-card    (.creditCard (.finance faker))
      ;; Content / Text
      :book-title     (.title (.book faker))
      :lorem-sentence (.sentence (.lorem faker))
      :lorem-para     (.paragraph (.lorem faker))
      :lorem-word     (.word (.lorem faker))
      ;; Locale / Language
      :locale         (.isoLanguage (.nation faker))
      ;; Appearance
      :color-name     (.name (.color faker))
      :color-hex      (.hex (.color faker))
      ;; Ratings / counts
      :rating         (number-fn 1 6)
      :year           (number-fn 1850 2024)
      :copies         (number-fn 1 10)
      ;; Time
      :past-date      (past-fn)
      ;; Type-only fallbacks
      :number         (number-fn 1 1000)
      :decimal        (.randomDouble (.number faker) (int 2) (long 1) (long 10000))
      :uuid           (java.util.UUID/randomUUID)
      :bool           false
      nil)))

;;; ---------------------------------------------------------------------------
;;; Lookup-table detection

(defn- lookup-table?
  "Returns true if entity-name looks like a reference/lookup table that should be
   fetched from existing rows rather than created fresh.

   All three conditions must hold:
   1. Has at least one unique=true string column (e.g. Genre.name)
   2. Has no required @ManyToOne associations (optional=false)
   3. Has NO @OneToMany / @ManyToMany collections

   The third condition is the key guard: domain entities like LibraryMember have
   unique columns (username, email) but also own collections (reviews, loanRecords)
   — they must be built fresh, not fetched from reference data."
  [entity-name]
  (let [props (:properties (intro/inspect-entity entity-name))]
    (and (some #(and (= "string" (:type %)) (true? (:unique %))) props)
         (not (some #(and (:is-association %)
                          (not (:collection %))
                          (false? (:nullable %)))
                    props))
         (not (some :collection props)))))

(defn- fetch-existing-row
  "Fetches a random existing row from the repository for entity-name.
   Repository bean is located by convention: 'Genre' → 'genreRepository'.
   Throws a descriptive ex-info if the table is empty — a null silently passed
   to a non-nullable FK produces a cryptic Hibernate error far from the cause."
  [entity-name dep-field-name]
  (let [repo-name (str (str/lower-case (subs entity-name 0 1))
                       (subs entity-name 1)
                       "Repository")
        repo (try (core/bean repo-name) (catch Exception _ nil))
        row (when repo
              (core/in-readonly-tx
               (first (shuffle (.findAll repo)))))]
    (when (and repo (nil? row))
      (throw (ex-info
              (str "faker/build-entity: dependency '" dep-field-name "' (→ " entity-name
                   ") was detected as a lookup table, but the table is empty.\n"
                   "Options:\n"
                   "  • seed the " entity-name " table before calling build-entity\n"
                   "  • pass an existing instance via :overrides {:" dep-field-name " <instance>}\n"
                   "  • pass a freshly-built one via :overrides {:" dep-field-name
                   " (faker/build-entity \"" entity-name "\" {:persist? true})}")
              {:entity entity-name :dep-field dep-field-name :cause :lookup-table-empty})))
    row))

;;; ---------------------------------------------------------------------------
;;; Transaction wrapper

(defn- with-transaction
  "Executes thunk inside a Spring transaction.
   When rollback? is true the transaction is always rolled back."
  [rollback? thunk]
  (let [tt (doto (TransactionTemplate. (core/bean PlatformTransactionManager))
             (.setPropagationBehavior TransactionDefinition/PROPAGATION_REQUIRES_NEW))]
    (.execute tt
              (reify TransactionCallback
                (doInTransaction [_ status]
                  (let [result (thunk)]
                    (when rollback? (.setRollbackOnly status))
                    result))))))

;;; ---------------------------------------------------------------------------
;;; Core builder

(declare build-entity)

(defn- apply-constraint-adjustments
  "Adjusts a raw generated value to satisfy constraint metadata.
   Returns the adjusted value. Prints a warning for @Pattern fields (generation
   not supported)."
  [entity-name prop-name raw cm faker]
  (cond
    ;; @Pattern — warn and leave as-is
    (:pattern cm)
    (do (println (str "[faker] WARNING: " entity-name "." prop-name
                      " has @Pattern(regexp=\"" (:pattern cm) "\") — "
                      "regex-constrained generation is not supported. "
                      "Value generated by name heuristic; validate manually."))
        raw)

    ;; @Email fallback — heuristic produced a non-email string
    (and (:email? cm) (string? raw) (not (re-find #"@" (str raw))))
    (.emailAddress (.internet faker))

    ;; @PositiveOrZero on a negative number
    (and (:positive-or-zero? cm) (number? raw) (neg? raw))
    (Math/abs (double raw))

    ;; @Positive on a non-positive number
    (and (:positive? cm) (number? raw) (not (pos? raw)))
    (max 1 (Math/abs (double raw)))

    ;; @Min / @Max numeric clamping
    (and (number? raw) (or (:min cm) (:max cm)))
    (let [lo (or (:min cm) Long/MIN_VALUE)
          hi (or (:max cm) Long/MAX_VALUE)]
      (max lo (min hi raw)))

    :else raw))

(defn- clamp-string
  "Clamps a string to respect both @Size(min/max) and @Column(length),
   taking the most restrictive maximum. Pads to size-min with spaces if needed."
  [s cm col-length]
  (let [size-max (:size-max cm)
        size-min (when (and (:size-min cm) (pos? (:size-min cm))) (:size-min cm))
        effective-max (cond
                        (and size-max col-length) (min size-max col-length)
                        size-max size-max
                        col-length col-length
                        :else nil)
        trimmed (if (and effective-max (> (count s) effective-max))
                  (subs s 0 effective-max)
                  s)]
    (if (and size-min (< (count trimmed) size-min))
      (str trimmed (apply str (repeat (- size-min (count trimmed)) \a)))
      trimmed)))

(defn- build-entity-internal
  "Internal recursive builder.
   seen is a set of entity names currently being resolved — used to detect cycles.
   em is the EntityManager to use for persistence, or nil when not persisting."
  [entity-name opts seen em]
  (let [{:keys [overrides auto-deps?]} opts
        overrides (or overrides {})
        entity (intro/inspect-entity entity-name)]
    (when (:error entity)
      (throw (ex-info (str "Entity not found: " entity-name) {:entity entity-name})))
    (let [fqn (:entity-name entity)
          cls (Class/forName fqn)
          instance (.newInstance cls)
          faker (make-faker)
          id-prop (-> entity :identifier :name)
          all-props (:properties entity)
          ;; Build constraint index once per entity
          cmap (try (into {} (map (fn [p] [(:name p) (intro/constraint-meta cls (:name p))])
                                  all-props))
                    (catch Exception _ {}))
          ;; Fail-fast: validate all overrides against constraints before touching the entity
          _ (doseq [[k v] overrides]
              (let [pname (name k)
                    cm (get cmap pname {})]
                (when (and (:not-null? cm) (nil? v))
                  (throw (ex-info
                          (str "Cannot apply override {" k " nil} — "
                               entity-name "." pname " is @NotNull")
                          {:entity entity-name :field pname :constraint :not-null :value v})))
                (when (and (:not-blank? cm) (or (nil? v) (and (string? v) (str/blank? v))))
                  (throw (ex-info
                          (str "Cannot apply override {" k " " (pr-str v) "} — "
                               entity-name "." pname " is @NotBlank")
                          {:entity entity-name :field pname :constraint :not-blank :value v})))
                (when (and (number? v) (:min cm) (< v (:min cm)))
                  (throw (ex-info
                          (str "Cannot apply override {" k " " v "} — "
                               entity-name "." pname " is @Min(" (:min cm) ")")
                          {:entity entity-name :field pname :constraint :min :value v :min (:min cm)})))
                (when (and (number? v) (:max cm) (> v (:max cm)))
                  (throw (ex-info
                          (str "Cannot apply override {" k " " v "} — "
                               entity-name "." pname " is @Max(" (:max cm) ")")
                          {:entity entity-name :field pname :constraint :max :value v :max (:max cm)})))
                (when (and (number? v) (:positive-or-zero? cm) (neg? v))
                  (throw (ex-info
                          (str "Cannot apply override {" k " " v "} — "
                               entity-name "." pname " is @PositiveOrZero")
                          {:entity entity-name :field pname :constraint :positive-or-zero :value v})))
                (when (and (number? v) (:positive? cm) (not (pos? v)))
                  (throw (ex-info
                          (str "Cannot apply override {" k " " v "} — "
                               entity-name "." pname " is @Positive")
                          {:entity entity-name :field pname :constraint :positive :value v})))))
          ;; Skip: id, collection associations, and properties covered by :overrides
          props (->> all-props
                     (remove #(= (:name %) id-prop))
                     (remove #(:collection %))
                     (remove #(contains? overrides (keyword (:name %))))
                     (remove #(contains? overrides (symbol (:name %)))))]

      ;; Set heuristic values for scalar properties
      (doseq [p (remove :is-association props)]
        (let [pname (:name p)
              ptype (:type p)
              cm (get cmap pname {})
              raw (generate-value faker pname ptype)
              ;; Apply constraint-aware adjustments
                            raw    (when (some? raw)
                                     (apply-constraint-adjustments entity-name pname raw cm faker))
              ;; String clamping: @Size + @Column(length) — take most restrictive max
              raw (if (string? raw)
                    (clamp-string raw cm (:length p))
                    raw)
              setter (find-setter cls pname)]
          (when (and setter (some? raw))
            (let [param-type (first (.getParameterTypes setter))
                  coerced (coerce-value raw param-type)]
              (.invoke setter instance (object-array [coerced]))))))

      ;; Resolve ManyToOne associations
      (doseq [p (filter #(and (:is-association %) (not (:collection %))) props)]
        (let [pname (:name p)
              target-name (when-let [t (:target-entity p)]
                            (last (str/split t #"\.")))
              setter (find-setter cls pname)]
          (when (and setter target-name)
            (cond
              ;; Required association (nullable=false) and auto-deps? -> resolve it
              (and auto-deps? (false? (:nullable p)))
              (let [dep (if (lookup-table? target-name)
                          (fetch-existing-row target-name pname)
                          (do
                            (when (contains? seen target-name)
                              (throw (ex-info
                                      (str "Circular dependency detected while building " entity-name
                                           " — already resolving " target-name)
                                      {:cycle (conj seen target-name)})))
                            (build-entity-internal target-name opts (conj seen entity-name) em)))]
                (.invoke setter instance (object-array [dep])))

              ;; Required association but no auto-deps? -> throw descriptive error
              (and (not auto-deps?) (false? (:nullable p)))
              (throw (ex-info
                      (str "Cannot build " entity-name ": required association '" pname
                           "' (→ " target-name ") is not provided.\n"
                           "Either:\n"
                           "  • pass it via :overrides {:" pname " <instance>}\n"
                           "  • set :auto-deps? true to let Livewire resolve it automatically")
                      {:entity entity-name :missing-association pname :target target-name}))

              ;; Optional association: skip (caller can provide via :overrides)
              :else nil))))

      ;; Apply overrides (always wins). Coerce to the setter's parameter type so
      ;; callers can pass plain Clojure longs/ints (e.g. {:rating 5}) without
      ;; needing to box them manually (e.g. (short 5)).
      ;; Constraint validation already ran above — apply unconditionally here.
      (doseq [[k v] overrides]
        (let [pname (name k)
              setter (find-setter cls pname)]
          (when setter
            (let [param-type (first (.getParameterTypes setter))
                  coerced (coerce-value v param-type)]
              (.invoke setter instance (object-array [coerced]))))))

      ;; Persist this instance if an EntityManager was provided.
      ;; Dependencies are persisted first (above), so the insert order is correct.
      (when em
        (.persist em instance)
        (.flush em))

      instance)))

;;; ---------------------------------------------------------------------------
;;; Public API

(defn build-entity
  "Builds a Hibernate entity instance populated with realistic fake data.

   Arguments:
     entity-name  — simple Hibernate entity name, e.g. \"Book\"
     opts         — optional map:
       :overrides   map of keyword/symbol field-name → value, applied last (always wins)
       :auto-deps?  when true, recursively build and wire required @ManyToOne associations
                    that are not in :overrides (default false)
       :persist?    when true, persist the entity (and auto-built deps) via EntityManager
                    (default false)
       :rollback?   when true, wrap the entire operation in a transaction that always rolls
                    back — meaningful only when :persist? true (default false)

   Examples:

     ;; Simple entity with no required FKs
     (faker/build-entity \"Author\")
     ;; => #object[Author ... {:firstName \"Evelyn\", :lastName \"Hartwell\", ...}]

     ;; Override specific fields
     (faker/build-entity \"Author\" {:overrides {:firstName \"Agatha\" :lastName \"Christie\"}})

     ;; Entity with required FK — provide dependency yourself
     (let [author (faker/build-entity \"Author\" {:persist? true})]
       (faker/build-entity \"Book\" {:overrides {:author author} :persist? true}))

     ;; Let Livewire wire the dependency chain automatically
     (faker/build-entity \"Book\" {:auto-deps? true :persist? true})

     ;; Speculative: build + persist + roll back (useful to get a DB-assigned id without
     ;; leaving data behind)
     (faker/build-entity \"Review\" {:auto-deps? true :persist? true :rollback? true})

   Throws ex-info with :missing-association when a required @ManyToOne is unresolved
   and :auto-deps? is false."
  ([entity-name]
   (build-entity entity-name {}))
  ([entity-name opts]
   (let [{:keys [persist? rollback?]} opts]
     (cond
       (and persist? rollback?)
       (with-transaction true
         #(let [em (core/bean jakarta.persistence.EntityManager)]
            (build-entity-internal entity-name opts #{} em)))

       persist?
       (with-transaction false
         #(let [em (core/bean jakarta.persistence.EntityManager)]
            (build-entity-internal entity-name opts #{} em)))

       :else
       (build-entity-internal entity-name opts #{} nil)))))

;;; ---------------------------------------------------------------------------
;;; Test recipe extraction

(defn- extract-scalars
  "Extracts scalar field values from entity-name/instance into a map of keyword → value.
   Excludes the @Id field and all association properties."
  [entity-name instance]
  (let [entity-meta (intro/inspect-entity entity-name)
        id-prop (-> entity-meta :identifier :name)
        scalar-props (->> (:properties entity-meta)
                          (remove #(= (:name %) id-prop))
                          (remove :is-association))
        b (core/bean->map instance)]
    (into {} (map (fn [p] [(keyword (:name p)) (get b (keyword (:name p)))])
                  scalar-props))))

(defn- collect-recipe
  "Recursively walks the entity instance graph, collecting scalar fields per entity.
   Returns an ordered vector of [keyword-entity-name scalars-map] pairs.
   Root entity is first; @ManyToOne dependencies follow in resolution order.
   Each entity is visited at most once (seen guards cycles)."
  [entity-name instance seen acc]
  (if (contains? seen entity-name)
    acc
    (let [entity-meta (intro/inspect-entity entity-name)
          m2o-props (->> (:properties entity-meta)
                         (filter #(and (:is-association %)
                                       (not (:collection %)))))
          b (core/bean->map instance)
          acc (conj acc [(keyword entity-name) (extract-scalars entity-name instance)])]
      (reduce (fn [acc p]
                (let [dep-val (get b (keyword (:name p)))
                      dep-name (when-let [t (:target-entity p)]
                                 (last (str/split t #"\.")))]
                  (if (and dep-val dep-name)
                    (collect-recipe dep-name dep-val (conj seen entity-name) acc)
                    acc)))
              acc
              m2o-props))))

(defn build-test-recipe
  "Builds a faker entity graph (`:auto-deps? true :persist? true :rollback? true`) and
   extracts all scalar field values into an ordered map keyed by entity class name.

   The root entity appears first; its `@ManyToOne` dependencies follow in resolution
   order (deepest dependency last). Each entity's map contains only scalar fields —
   strings, numbers, booleans, dates — with the `@Id` field excluded. Null values are
   included: a null on a nullable field is itself useful information for assertions.

   Use this to capture faker-generated values *before* writing test setup code, so
   the test uses the same concrete values that were validated in the REPL prototype
   rather than invented replacements.

   Options (merged over defaults):
     :overrides — applied to the root entity (see build-entity)

   Examples:

     ;; Full recipe for a Review with its entire dependency graph
     (faker/build-test-recipe \"Review\")
     ;; => {:Review  {:rating 5, :comment \"A remarkable journey...\", :reviewedAt ...}
     ;;     :Book    {:title \"The Midnight Crisis\", :isbn \"978-...\", ...}
     ;;     :Author  {:firstName \"Kip\", :lastName \"O'Reilly\", ...}
     ;;     :LibraryMember {:username \"kelsey.schaden\", :fullName \"Kelsey Schaden\", ...}}

     ;; With overrides — overridden values appear in the recipe as supplied
     (faker/build-test-recipe \"Review\" {:overrides {:rating 1 :comment \"Terrible\"}})

   CLI: lw-build-test-recipe Review
        lw-build-test-recipe Review '{:overrides {:rating 1}}'"
  ([entity-name]
   (build-test-recipe entity-name {}))
  ([entity-name opts]
   (let [opts (merge {:auto-deps? true :persist? true :rollback? true} opts)
         instance (build-entity entity-name opts)
         pairs (collect-recipe entity-name instance #{} [])]
     (into (array-map) pairs))))
