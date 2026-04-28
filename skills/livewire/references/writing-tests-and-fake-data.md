# Writing Tests & Fake Data

Read this file when writing integration tests, prototyping test data in the REPL, or
using `faker/build-entity` / `faker/build-test-recipe` to generate valid entity graphs.

---

## Test style — follow the project first

**Always look at existing tests before writing a new one.** Match the project's conventions for:
- `@SpringBootTest` environment (`MOCK`, `NONE`, `RANDOM_PORT`)
- How test data is set up (repositories, `JdbcTemplate`, `@Sql`, etc.)
- Transaction / rollback strategy (`@Transactional`, `@BeforeEach`/`@AfterEach` cleanup)
- Assertion library (AssertJ, JUnit 5 built-ins, Hamcrest, etc.)

The rules below are **fallback guidance** for when no existing pattern covers the case.

### Fallback rules of thumb

**Service-layer tests** — call the service bean directly; no MockMvc needed:
- Use `WebEnvironment.MOCK` (not `NONE`) when the app has a Spring Security config — `HttpSecurity` is only available in a web context and `NONE` will fail at startup with `NoSuchBeanDefinitionException`.
- `@Transactional` on the test class auto-rolls back each test, keeping the DB clean without explicit teardown.
- After all test data is set up in `@BeforeEach`, call `entityManager.flush(); entityManager.clear()` before the test body runs. Without this, service calls in the same transaction may see stale lazy collections.

**REST/controller tests** — use MockMvc against the HTTP layer when testing status codes, authentication, serialization, or HTTP-level behaviour.

---

## REPL workflow for test data prototyping

**Before writing any test setup code, always prototype test data in the live REPL using `faker/build-entity`.**

1. **Inspect the entity structure via Livewire — never read Java source files for this.**

   ```bash
   # Fire all of these in a single parallel message
   lw-build-test-recipe Review        # field values + types + repo names
   lw-list-entities                   # FQNs for the REPL prototype
   lw-inspect-entity Review           # constraints, nullability
   lw-inspect-entity Book             # repeat for every entity in the graph
   ```

   **The recipe does not replace `lw-inspect-entity`.** The recipe gives field values and Java types; `lw-inspect-entity` gives constraints (`@NotNull`, `@Size`, nullability) — needed to know which fields you can safely omit.

   **Before writing any REPL expression that instantiates entity classes, always call `lw-list-entities` first** to get exact fully-qualified class names. Never guess the package.

2. **Check faker is available:**
   ```bash
   lw-eval --file <(echo '(faker/available?)')
   ```
   **Or use a temp file** (see `$SKILL_DIR/references/clj-nrepl-eval-temp-files.md`):
   ```bash
   cat > /tmp/lw-faker-check.clj << 'EOF'
   (faker/available?)
   EOF
   lw-eval --file /tmp/lw-faker-check.clj
   ```

3. **Prototype entity creation in the REPL:**
   ```bash
   lw-build-entity Review '{:auto-deps? true :persist? true :rollback? true}'
   ```

4. **Inspect the result** — note which fields were generated, what IDs were assigned, which associations were resolved. Use the `:type` key to apply the correct cast in setter calls (`(short 5)` not `5` for a `short` field).

5. **Adjust with overrides** if specific values matter for assertions:
   ```bash
   lw-build-entity Review '{:auto-deps? true :persist? true :rollback? true :overrides {:rating 5 :comment "Outstanding"}}'
   ```

6. **Prototype the full test logic in Clojure before writing Java — this step is mandatory.**

   ```clojure
   (use 'clojure.test)
   (lw/in-tx
     (let [author  (doto (com.example.Author.) (.setFirstName "Jane") (.setLastName "Austen"))
           _       (.save (lw/bean "authorRepository") author)
           book    (doto (com.example.Book.) (.setTitle "Test Book") (.setAuthor author))
           _       (.save (lw/bean "bookRepository") book)
           member  (doto (com.example.LibraryMember.) (.setUsername "jdoe") (.setFullName "John Doe")
                                                       (.setEmail "jdoe@test.com") (.setMemberSince (java.time.LocalDate/now)))
           _       (.save (lw/bean "libraryMemberRepository") member)
           review  (doto (com.example.Review.) (.setBook book) (.setMember member)
                                                (.setRating (short 5)) (.setComment "Great") (.setReviewedAt (java.time.LocalDateTime/now)))
           _       (.save (lw/bean "reviewRepository") review)
           _       (do (.flush (lw/bean "entityManager")) (.clear (lw/bean "entityManager")))
           result  (.getReviewsForBook (lw/bean "bookService") (.getId book))]
       (is (= 1 (count result)))
       (is (= 5 (.rating (first result))))))
   ```

7. **If the nREPL is unavailable, ask the user to start the app — never skip prototyping.**

8. **`@BeforeEach` entity setup — store IDs, not entity references.**

   When `@BeforeEach` ends with `entityManager.flush() + entityManager.clear()`, stored entity
   references become **detached**. Store only IDs; re-attach via `getReferenceById()`:

   ```java
   private Long bookId;

   @BeforeEach
   void setUp() {
       // ... create and save book ...
       entityManager.flush();
       entityManager.clear();
       bookId = book.getId();   // store stable ID, discard the detached ref
   }

   @Test
   void myTest() {
       Book book = bookRepository.getReferenceById(bookId);  // Hibernate proxy, no DB hit
   }
   ```

9. **`@NotNull` + `insertable=false` — set the field anyway.**

   Fields annotated both `@NotNull` and `insertable=false` (DB-defaulted columns like `created_at`)
   will fail bean validation in the REPL even though Hibernate would never actually insert the value.
   Always set them explicitly in `lw/in-tx`:

   ```clojure
   (let [i (MyEntity.)
         now (java.time.Instant/now)]
     (.setName i "Test")
     (.setCreatedAt i now)   ; will not be inserted (DB default takes over), but satisfies @NotNull
     (.setUpdatedAt i now))
   ```

10. **Only then write the test** — translate the validated REPL recipe into the setup style used by the project.

---

## Faker API — `net.brdloush.livewire.faker`

Build valid, optionally-persistable Hibernate entity instances using realistic fake data.
All datafaker access is reflective — the namespace loads safely on apps without datafaker.

### Preflight check

```clojure
(faker/available?)
;; => true   (or false if datafaker is not in the project)
```

If it returns `false`, add the dependency:
```xml
<dependency>
  <groupId>net.datafaker</groupId>
  <artifactId>datafaker</artifactId>
  <version>2.5.4</version>
</dependency>
```

### `faker/build-entity` API

```clojure
(faker/build-entity entity-name)
(faker/build-entity entity-name opts)
```

| Option | Type | Default | Description |
|---|---|---|---|
| `:overrides` | map | `{}` | Field-name → value. Applied last; always wins. |
| `:auto-deps?` | boolean | `false` | Recursively build and wire required `@ManyToOne` associations. |
| `:persist?` | boolean | `false` | Persist the entity and auto-built deps. |
| `:rollback?` | boolean | `false` | Wrap in a transaction that always rolls back. Meaningful only when `:persist? true`. |

### Common patterns

```clojure
;; Simple entity with no required FKs
(faker/build-entity "Author")

;; With overrides — they always win
(faker/build-entity "Author" {:overrides {:firstName "Agatha" :lastName "Christie"}})

;; Provide the dependency yourself
(let [author (faker/build-entity "Author" {:persist? true})]
  (faker/build-entity "Book" {:overrides {:author author} :persist? true}))

;; Let Livewire resolve the full dependency chain automatically
(faker/build-entity "Book" {:auto-deps? true :persist? true})

;; Persist + rollback — get a real DB-assigned id without leaving data behind
(let [review (faker/build-entity "Review" {:auto-deps? true :persist? true :rollback? true})]
  (println "Got id:" (.getId review)))

;; Combine with trace/trace-sql to inspect the cascade
(trace/trace-sql
  (faker/build-entity "Review" {:auto-deps? true :persist? true :rollback? true}))
```

### Constraint-aware generation and fail-fast override validation

`faker/build-entity` reads `jakarta.validation.constraints` from each field at build time.

**Fail-fast override validation** — violations throw `ex-info` immediately:
```clojure
(faker/build-entity "Review" {:auto-deps? true :overrides {:comment nil}})
;; throws: "Cannot apply override {:comment nil} — Review.comment is @NotNull"

(faker/build-entity "Review" {:auto-deps? true :overrides {:rating 10}})
;; throws: "Cannot apply override {:rating 10} — Review.rating is @Max(5)"
```

Validated constraints: `@NotNull`, `@NotBlank`, `@Min`, `@Max`, `@Positive`, `@PositiveOrZero`.

**Constraint-aware value generation:**

| Annotation | Generator behaviour |
|---|---|
| `@Min(X)` / `@Max(Y)` | Numeric value clamped to `[X, Y]` |
| `@Positive` / `@PositiveOrZero` | Negative values reflected to positive |
| `@Email` | `faker.internet().emailAddress()` used as fallback |
| `@Past` / `@PastOrPresent` | Date generated in the past |
| `@Size(min=X, max=Y)` | String length clamped |
| `@Pattern(regexp=...)` | Warning printed; value generated by name heuristic |

### Heuristics and skip rules

The generator matches property names case-insensitively (first match wins):

| Group | Pattern(s) | Generated value |
|---|---|---|
| **Person** | `^firstName$` | `faker.name().firstName()` |
| | `^lastName$` | `faker.name().lastName()` |
| | `^fullName$`, `^name$` | `faker.name().fullName()` |
| | `^age$` | random int 18–90 |
| | `birth(Day\|Date\|Ts\|At)?$` | past birthday |
| **Contact** | `email` | `faker.internet().emailAddress()` |
| | `username`, `^login$` | `faker.internet().username()` |
| | `phone\|telephone\|mobile\|cell\|fax` | `faker.phoneNumber().phoneNumber()` |
| **Address** | `^streetAddress$` | `faker.address().streetAddress()` |
| | `^city$`, `^town$` | `faker.address().city()` |
| | `^country$` | `faker.address().country()` |
| | `zipCode\|postCode\|postalCode` | `faker.address().zipCode()` |
| **Internet** | `^url$`, `^website$` | `faker.internet().url()` |
| | `^token$` | random UUID string |
| **Identifiers** | `isbn`, `^isbn13$` | `faker.code().isbn13()` |
| | `uuid` | random `UUID` |
| **Company** | `^company$`, `^companyName$` | `faker.company().name()` |
| | `^jobTitle$`, `^occupation$` | `faker.job().title()` |
| **Financial** | `^amount$`, `^salary$` | random double |
| | `^iban$` | `faker.finance().iban()` |
| **Content** | `^title$` | `faker.book().title()` |
| | `^description$`, `^content$` | lorem paragraph |
| | `comment` | lorem paragraph |
| **Suffix patterns** | `*Year` | random year 1850–2024 |
| | `*(At\|Since\|Date\|Time)` | past `LocalDateTime` |
| | `*Copies` | small positive int |
| **Type fallbacks** | any `String` | lorem word |
| | any int / long / short | random number |
| | any boolean | random bool |
| | any UUID | random UUID |

**Skipped automatically:** `@Id`, `@OneToMany` / `@ManyToMany` collections, any property in `:overrides`.

### Lookup-table detection

Entities with a `unique=true` string column and no required `@ManyToOne` associations are
detected as reference/lookup tables (e.g. `Genre`). When `:auto-deps? true` encounters one
as a dependency, it fetches a random existing row instead of creating a new one — preventing
unique-constraint violations on seeded data.

### Throws

`faker/build-entity` throws with `:missing-association` when a required `@ManyToOne` is
unresolved and `:auto-deps?` is false:

```clojure
(faker/build-entity "Book")
;; throws: "Cannot build Book: required association 'author' (→ Author) is not provided.
;;          Either:
;;           • pass it via :overrides {:author <instance>}
;;           • set :auto-deps? true to let Livewire resolve it automatically"
```

### CLI

```bash
lw-build-entity Author
lw-build-entity Book '{:auto-deps? true}'
lw-build-entity Review '{:auto-deps? true :persist? true :rollback? true}'
```

---

## `faker/build-test-recipe` — extract assertable values from a faker entity graph

**Always run `lw-build-test-recipe` before writing any integration test setup code.**
The recipe captures generated values so you don't have to invent equivalent values when
writing the Java test — severing the connection between the validated prototype and the
written test.

```clojure
(faker/build-test-recipe entity-name)
(faker/build-test-recipe entity-name opts)   ;; opts: :overrides only
```

The entity graph is always built with `:auto-deps? true :persist? true :rollback? true`.

**Output format:** ordered map keyed by entity class name (root first, `@ManyToOne` deps
after). Each value is a map of scalar fields only — `@Id` and collections excluded.
Every field entry is `{:type "<java-type>" :value <val>}`.

```clojure
(faker/build-test-recipe "Review")
;; => {:Review {:repo "reviewRepository"
;;              :fields {:rating     {:type "short",         :value 5}
;;                       :comment    {:type "string",        :value "A remarkable journey..."}
;;                       :reviewedAt {:type "LocalDateTime", :value #object[LocalDateTime ...]}}}
;;     :Book   {:repo "bookRepository"
;;              :fields {:title {:type "string", :value "The Midnight Crisis"} ...}}
;;     :Author {:repo "authorRepository"
;;              :fields {:firstName {:type "string", :value "Kip"} ...}}
;;     :LibraryMember {:repo "libraryMemberRepository"
;;                     :fields {:username {:type "string", :value "kelsey.schaden"} ...}}}
```

The recipe includes the `:repo` bean name for every entity — **do not call `lw-all-repo-entities`
afterwards**, it is redundant.

**Workflow:**

1. Run all in parallel:
   ```bash
   lw-build-test-recipe Review
   lw-list-entities
   lw-inspect-entity Review
   lw-inspect-entity Book
   ```

2. Write `@BeforeEach` using those exact values with the correct cast from `:type`:
   `review.setRating((short) 5)`, `author.setBirthYear((short) 1951)`, etc.

3. Write assertions using the same values:
   `assertThat(dto.reviewerName()).isEqualTo("Kelsey Schaden")`

4. Run the Clojure service prototype to validate the happy path.

5. Only then write the final Java test.

**Overrides flow through:**
```clojure
(faker/build-test-recipe "Review" {:overrides {:rating 1 :comment "Terrible."}})
;; => {:Review {:repo "reviewRepository"
;;              :fields {:rating  {:type "short",  :value 1}
;;                       :comment {:type "string", :value "Terrible."} ...}} ...}
```

**CLI:**
```bash
lw-build-test-recipe Review
lw-build-test-recipe Review '{:overrides {:rating 1 :comment "Terrible."}}'
```
