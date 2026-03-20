# Feature Specification: Faker / Test Data Generation Support

## 1. Motivation & Philosophy

Livewire already excels at introspecting live Hibernate entities and running ad-hoc
queries. This feature extends that capability to **in-REPL test data construction**:
quickly building valid, persistable entity graphs for speculative execution,
service prototyping, and exploratory debugging — all without touching production code
or requiring an application restart.

### Design constraints

1. **No production pollution.** `@Entity` classes must not be touched. No test-only
   annotations, no new imports, nothing that leaks into the production JAR.
2. **No fragile external config.** No `.edn` or `.yaml` files that silently rot
   when a Java field is renamed.
3. **REPL-first.** The primary deliverable is a function you can call from the
   Livewire nREPL immediately. Generated Java profiles are an optional enhancement,
   not a prerequisite.
4. **Zero new runtime deps on Livewire itself.** `net.datafaker` is expected to be
   present on the target application's classpath (it is already a compile-scope dep
   in bloated-shelf). Livewire accesses it reflectively; it is never declared in
   `project.clj`.

### What this is *not*

This feature is not a JUnit fixture framework or a test data DSL. It is a REPL
productivity tool that happens to produce persistable entities. Writing test classes
is a secondary use case addressed in Phase 2.

---

## 2. Phased Plan

### Phase 0 — Extend `introspect` with `@Column` annotation metadata (prerequisite)

**Why this must come first:** the Hibernate runtime metamodel — what
`inspect-all-entities` currently returns — does not expose `nullable`, `length`,
`unique`, or `columnDefinition`. These live in JPA annotations (`@Column`,
`@ManyToOne(optional=false)`, etc.) on the entity class itself. Without them, the
heuristic generator cannot know which fields are required, which strings have a
maximum length, or which values must be unique. Getting a constraint violation from
the DB on the first `persist!` attempt is poor UX.

**What we will add:**

Extend `inspect-entity` (and by extension `inspect-all-entities`) to also walk the
entity class's declared fields and methods via Java reflection, reading `@Column`
and `@ManyToOne`/`@JoinColumn` annotations. Merge the result into the existing
`:properties` entries.

Additional keys to surface per property:

| Key | Source | Example values |
|---|---|---|
| `:nullable` | `@Column(nullable=…)` / `@ManyToOne(optional=…)` | `true`, `false` |
| `:length` | `@Column(length=…)` | `255`, `100`, `60`, `nil` |
| `:unique` | `@Column(unique=…)` | `true`, `false` |
| `:column-definition` | `@Column(columnDefinition=…)` | `"TEXT"`, `nil` |

**Approach:**

```clojure
;; pseudo-sketch inside inspect-entity, per property name pn:
(let [field (find-field entity-class pn)          ; getDeclaredField, walk superclasses
      col   (.getAnnotation field Column)          ; jakarta.persistence.Column
      m2o   (.getAnnotation field ManyToOne)]      ; jakarta.persistence.ManyToOne
  {:nullable          (if m2o
                        (.optional m2o)            ; optional=false ≡ nullable=false
                        (when col (.nullable col)))
   :length            (when col (.length col))
   :unique            (when col (.unique col))
   :column-definition (when (and col (not= "" (.columnDefinition col)))
                        (.columnDefinition col))})
```

`find-field` must walk the class hierarchy (entity superclasses, `@MappedSuperclass`)
because annotations may be on a parent class. Getter-level annotations (`@Column` on
the accessor method rather than the field) are common; we should try field first,
fall back to getter.

**Acceptance criteria for Phase 0:**

- `(intro/inspect-entity "Book")` returns `:nullable false` on `title`,
  `availableCopies`, `archived`, and the `author` association.
- `(intro/inspect-entity "Book")` returns `:length 255` on `title`, `:length 20`
  on `isbn`, `:unique true` on `isbn`.
- `(intro/inspect-entity "LibraryMember")` returns `:nullable false :unique true`
  on `username` and `email`.
- `(intro/inspect-entity "Review")` returns `:column-definition "TEXT"` on
  `comment`.
- No regression on properties that have no `@Column` (e.g. `birthYear`,
  `nationality` on `Author`): they continue to return `nil` for the new keys.

---

### Phase 1 — `faker/build-entity` REPL function (core deliverable)

This is the feature. Everything else is optional.

#### 1.1 New namespace: `net.brdloush.livewire.faker`

Pre-aliased as `faker` in the REPL (add to `boot.clj` auto-alias list alongside
the existing namespaces).

#### 1.2 Public API

```clojure
(faker/build-entity entity-name)
(faker/build-entity entity-name opts)
```

**Arguments:**

| Argument | Type | Description |
|---|---|---|
| `entity-name` | `String` | Simple Hibernate entity name, e.g. `"Book"` |
| `:overrides` | `map` | Keyword field name → value. Applied last; always wins. |
| `:auto-deps?` | `boolean` | If `true`, recursively build and wire required `@ManyToOne` associations that are not covered by `:overrides`. Default `false`. |
| `:persist?` | `boolean` | If `true`, persist the entity (and any auto-built deps) via the live `EntityManager`. Default `false`. |
| `:rollback?` | `boolean` | If `true`, wrap the whole operation in a transaction that always rolls back. Meaningful only when `:persist? true`. Default `false`. |

**Examples:**

```clojure
;; Simplest: self-contained entity, no required FKs
(faker/build-entity "Author")
;; => #object[Author ... {:firstName "Evelyn", :lastName "Hartwell",
;;            :birthYear 1923, :nationality "French"}]

;; With overrides
(faker/build-entity "Author" {:overrides {:firstName "Agatha" :lastName "Christie"}})

;; Entity with a required FK — provide the dependency yourself
(let [author (faker/build-entity "Author" {:persist? true})]
  (faker/build-entity "Book" {:overrides {:author author} :persist? true}))

;; Let Livewire resolve the dependency chain automatically
(faker/build-entity "Book" {:auto-deps? true :persist? true})

;; Speculative: build + persist + roll back everything
;; Useful to get a real DB-assigned id and call a service without leaving data behind
(faker/build-entity "Review"
  {:auto-deps?  true
   :persist?    true
   :rollback?   true})
;; => the Review object; DB changes already rolled back
```

#### 1.3 Internal execution flow

```
faker/build-entity "Review" {:auto-deps? true, :persist? true, :rollback? true}
        │
        ├─ 1. introspect: intro/inspect-entity "Review"
        │       → properties: book (@ManyToOne, nullable=false),
        │                     member (@ManyToOne, nullable=false),
        │                     rating (short, nullable=false),
        │                     comment (string, nullable=true),
        │                     reviewedAt (date, nullable=false)
        │
        ├─ 2. resolve required associations (auto-deps)
        │       ├─ build-entity "Book"    (recursively)
        │       │       └─ build-entity "Author"  → apply heuristics → Author instance
        │       │          apply heuristics        → Book instance (Author wired in)
        │       └─ build-entity "LibraryMember"
        │               └─ apply heuristics → LibraryMember instance
        │
        ├─ 3. apply heuristics to scalar properties
        │       rating      → faker.number().numberBetween(1, 6) cast to short
        │       comment     → faker.lorem().paragraph()       (nullable; generate anyway)
        │       reviewedAt  → faker.date().past(365*2, DAYS)
        │
        ├─ 4. apply :overrides (always wins)
        │
        ├─ 5. set values via reflection
        │       entity-class → Class/forName from list-entities :class
        │       for each [field value]: find setter, .invoke instance value
        │
        └─ 6. if :persist?
                in-tx (rollback if :rollback?):
                  em.persist(author) → em.persist(book) → em.persist(member)
                  → em.persist(review) → em.flush()
                  → return review (id is now set)
```

#### 1.4 Heuristic table

A `def`-ed vector of `[name-pattern type-pattern generator-fn]` triples, evaluated
top-to-bottom, first match wins. Name patterns are matched case-insensitively against
the camelCase property name. The fallback row (nil name pattern) matches on type only.

Priority order:

1. Exact name match (`:firstName`, `:isbn`, `:rating`, …)
2. Suffix/substring name match (`:*Year`, `:*At`, `:*Since`, …)
3. Type-only fallback (`"string"` → lorem word, `"boolean"` → false, etc.)

Representative entries (not exhaustive — the full table lives in the namespace):

| Name pattern | Type pattern | Generator |
|---|---|---|
| `firstName` | `string` | `faker.name().firstName()` |
| `lastName` | `string` | `faker.name().lastName()` |
| `fullName` | `string` | `firstName + " " + lastName` |
| `email` | `string` | `faker.internet().emailAddress()` |
| `username` | `string` | `faker.internet().username()` |
| `title` | `string` | `faker.book().title()` |
| `isbn` | `string` | `faker.code().isbn13(true)` |
| `nationality` | `nationality` | `faker.nation().nationality()` |
| `comment` | `string` | `faker.lorem().paragraph()` |
| `rating` | `short\|int` | `numberBetween(1, 6)` cast to short |
| `*Year` (suffix) | `short\|int\|long` | `numberBetween(1850, 2024)` |
| `*Copies` (suffix) | `short\|int\|long` | `numberBetween(1, 10)` |
| `*At` / `*Since` (suffix) | `*[Dd]ate*\|*[Tt]ime*` | `faker.date().past(365*2, DAYS)` |
| *(fallback)* | `string` | `faker.lorem().word()` |
| *(fallback)* | `int\|long` | `numberBetween(1, 1000)` |
| *(fallback)* | `boolean` | `false` |

**Length clamping:** if Phase 0 surfaced a `:length` for a `string` property, truncate
the generated value to that length before setting it. Prevents `DataException` on
columns like `isbn VARCHAR(20)` when the `faker.lorem().word()` fallback is used.

**Skip rules:**
- Skip `@Id` / identifier property (auto-generated by the DB).
- Skip `@OneToMany` / `@ManyToMany` collections (let cascade or the caller manage them).
- Skip any property present in `:overrides`.

#### 1.5 Lookup / pre-seeded entities ("Genre problem")

Some entities in a real application are **reference data** seeded by Flyway or a
data-migration script, not created on demand. Creating duplicates would violate unique
constraints (`genre.name` is unique). `faker/build-entity` must not attempt to
auto-create these.

Detection heuristic: a target entity with a `unique=true` string column and no
nullable=false FK associations is likely a lookup table. When `:auto-deps?` is true
and we encounter such an entity, **fetch a random existing row** instead of building
a new one:

```clojure
;; pseudo — fetch one Genre at random from the DB
(first (shuffle (.findAll (core/bean "genreRepository"))))
```

This is the `Genre` case in bloated-shelf. The heuristic may need manual override in
edge cases; `:overrides` is always the escape hatch.

#### 1.6 Reflection helpers (internal)

```clojure
;; entity class: available from (list-entities) :class key
(defn- entity-class [entity-name]
  (-> (filter #(= entity-name (:name %)) (list-entities))
      first :class Class/forName))

;; setter discovery: :firstName → "setFirstName", look up in .getMethods
(defn- find-setter [^Class clazz prop-name]
  (let [setter-name (str "set" (str/capitalize (name prop-name)))]
    (->> (.getMethods clazz)
         (filter #(= setter-name (.getName %)))
         first)))

;; Faker instance: constructed once per build-entity call, not shared.
;; Throws a descriptive ex-info if net.datafaker is not on the classpath.
(defn- make-faker []
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
        {:missing-class "net.datafaker.Faker"}))))))
```

#### 1.7 Classpath safety & `faker/available?`

**No `import` in the `ns` form.** All datafaker access is reflective (`Class/forName`,
`Reflector/invokeConstructor`, etc.). A top-level `:import` of any `net.datafaker`
class would cause the namespace to fail loading on apps that don't have the library,
which would in turn break the entire REPL startup (since `boot.clj` auto-aliases every
Livewire namespace). This constraint must be maintained for the lifetime of the
namespace.

**`faker/available?` predicate.** A lightweight public function for preflight checks:

```clojure
(defn available?
  "Returns true if net.datafaker.Faker is present on the classpath."
  []
  (try (Class/forName "net.datafaker.Faker") true
       (catch ClassNotFoundException _ false)))
```

Agents should call `(faker/available?)` as the first step of any faker workflow and
surface a clear message if it returns `false` rather than letting `build-entity` throw
mid-execution. The SKILL.md entry for `faker/` should reflect this as a recommended
preflight step.

The `lw-build-entity` wrapper script does not need special handling — the `ex-info`
message from `make-faker` is already human-readable prose (including the Maven/Gradle
snippet) and will print cleanly to the terminal.

#### 1.8 Acceptance criteria for Phase 1

- `(faker/build-entity "Author")` returns an `Author` instance with all string fields
  non-nil and non-blank.
- `(faker/build-entity "Book" {:auto-deps? true :persist? true :rollback? true})`
  returns a `Book` with a non-nil `id` and rolls back cleanly (no rows left in DB).
- `(faker/build-entity "Review" {:auto-deps? true :persist? true :rollback? true})`
  works end-to-end, wiring `Book` → `Author` and `LibraryMember` transitively.
- `:overrides` values always take precedence over heuristics.
- Calling `(faker/build-entity "Book")` without `:auto-deps?` and without providing
  `:author` in `:overrides` throws a descriptive error naming the missing association.
- `Genre` is not created new; a random existing genre is picked when building a `Book`
  that needs genres (if genres are wired at all — `@ManyToMany` is skipped by default;
  caller can add via `:overrides {:genres [...]}`).

#### 1.9 Deliverables checklist (per AGENTS.md feature delivery)

| # | Deliverable | Notes |
|---|---|---|
| 1 | Clojure implementation in `net.brdloush.livewire.faker` | Core of Phase 1 |
| 2 | Wrapper script `skills/livewire/bin/lw-build-entity` | One entity name in, printed map out; useful standalone |
| 3 | SKILL.md — new `faker/…` section | Show `build-entity` with and without `:auto-deps?`; show speculative pattern |
| 4 | README.md — extend "What you can do" | One paragraph + code snippet |
| 5 | `web/getting-started.html` — "try this prompt" card | "Build me a test Review and call the rating service with it" |
| 6 | `web/index.html` — new feature card | "Test data generation" category |

---

### Phase 2 — AI-generated `[Entity]Faker.java` classes (optional, later)

Once Phase 1 is working and the heuristic table has stabilised from real use, the AI
agent can generate **plain Java source files** that encode the same heuristics at
compile time. These live in `src/test/java` of the target application.

**Why plain Java, not a Livewire interface:**

A generated `BookFaker.java` needs no Livewire type to implement. It is a simple
standalone class with a `build(Faker faker)` method:

```java
// src/test/java/.../faker/BookFaker.java
public class BookFaker {
    public static Book build(Faker faker) {
        Book b = new Book();
        b.setTitle(faker.book().title());
        b.setIsbn(faker.code().isbn13(true));
        b.setPublishedYear((short) faker.number().numberBetween(1850, 2024));
        b.setAvailableCopies((short) faker.number().numberBetween(1, 10));
        return b;  // caller is responsible for wiring author and genres
    }
}
```

Livewire discovers these classes at runtime by convention: scan for types whose simple
name ends in `Faker` or `Profile` and whose `build` method takes a
`net.datafaker.Faker` argument. If a compiled profile is found, it takes precedence
over the in-namespace heuristic table.

**Generation workflow for the AI agent:**

1. `(intro/inspect-all-entities)` — get the full metamodel (with Phase 0 annotation
   data included).
2. Apply the same heuristic logic as Phase 1, but emit Java source rather than calling
   setters at runtime.
3. Write the `.java` file into the target project's `src/test/java` tree.
4. The target project's build compiles it; Livewire picks it up on the next REPL call.

**Scope note:** the `EntityFakerProfile<T>` / `FakerBuilder<T>` generic interface
described in the original spec is explicitly **deferred**. It adds a compile-time
contract between Livewire and the target app's test tree without a clear payoff at
this stage. If the test-fixture use case grows to demand it, revisit then.

---

### Phase 3 — Deferred: typed `EntityFakerProfile<T>` contract

Deferred until there is concrete demand from a test-fixture use case. The plain-Java
`build(Faker)` pattern of Phase 2 is sufficient for agent-generated code and covers
all known REPL use cases. Re-evaluate if multiple target apps request a shared
Livewire interface for their fixture classes.

---

## 3. Known Constraints & Edge Cases

| Constraint | Mitigation |
|---|---|
| Hibernate metamodel does not expose `@Column` nullability | Phase 0 adds reflection-based annotation reading |
| Some entities are reference data (unique constraint, no FKs) — cannot be auto-created | Detection heuristic + random fetch from DB; `:overrides` as escape hatch |
| `datafaker` may not be on the classpath of every target app | `make-faker` should throw a clear `ClassNotFoundException` message pointing to the missing dep |
| Collection associations (`@OneToMany`, `@ManyToMany`) skipped by default | Caller uses `:overrides {:genres [...]}` when needed |
| Primitive type coercion (e.g. `short` for `rating`) | Setter discovery returns `Class` of parameter; coerce generated value before `.invoke` |
| Circular deps in entity graph (unlikely but possible) | Guard in recursive `build-entity` via a `seen` set; throw if cycle detected |
| `unique=true` fields (e.g. `isbn`, `username`, `email`) may collide on repeated calls | Faker's built-in uniqueness providers or caller-side `:overrides` |

---

## 4. Relationship to Existing Livewire Features

| Existing feature | Interaction |
|---|---|
| `intro/inspect-entity` | Phase 0 extends it; Phase 1 depends on the enriched output |
| `core/in-tx` | Used by `:persist? true :rollback? true` to wrap the persist + flush |
| `core/bean` | Used to fetch `entityManagerFactory` and lookup repositories for genre fetch |
| `q/diff-entity` | Complementary: `diff-entity` observes mutations on an existing entity; `faker/build-entity` constructs a new one |
| `trace/trace-sql` | Can be wrapped around `faker/build-entity` calls to inspect what the cascade generates |
| `jpa/query` | Can be used post-build to assert the entity landed in the DB correctly before rollback |
