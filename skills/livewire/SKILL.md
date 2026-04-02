---
name: livewire
description: Live nREPL probe for Spring Boot apps. Use when working with a Spring Boot application that has Livewire on its classpath to query the running JVM state, beans, database, and behaviour via the live REPL.
---

# Livewire — Live nREPL probe for Spring Boot apps

Use this skill whenever working with a Spring Boot application that has Livewire
on its classpath. Before answering any question about the running app's state,
beans, database, or behaviour — **try the live REPL first**. A live answer from
the JVM beats static analysis every time.

---

## Writing tests / creating test data

**Before writing any test setup code, always prototype test data in the live REPL using `faker/build-entity`.**

This applies whenever you need to:
- Write a new integration test (service layer, repository, or otherwise)
- Populate test data for manual exploration
- Understand what a valid entity graph looks like

### Test style — follow the project first

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
- After all test data is set up in `@BeforeEach`, call `entityManager.flush(); entityManager.clear()` before the test body runs. Without this, service calls in the same transaction may see stale lazy collections: if a parent entity was saved before its children, its collection is already "initialized" as empty in the L1 cache and Hibernate won't re-query even when children exist in the DB.

**REST/controller tests** — use MockMvc against the HTTP layer when testing status codes, authentication, serialization, or HTTP-level behaviour.

### REPL workflow for test data prototyping

1. **Inspect the entity structure via Livewire — never read Java source files for this.**
   Use `lw-inspect-entity` (or `lw-inspect-all-entities` for the full domain) to get table names, column names, types, nullability, and constraints in one call. The REPL is already running and gives you everything you need faster than reading source.

   ```bash
   lw-inspect-entity Review
   lw-inspect-entity Book    # repeat for every entity you'll need to set up
   ```

   Only fall back to reading source files if you need something the metamodel cannot answer (e.g. custom business logic in a constructor or factory method).

   **Before writing any REPL expression that instantiates entity classes, always call `lw-list-entities` first** to get the exact fully-qualified class names. Never guess the package — `model`, `domain`, `entity`, etc. are all plausible and guessing causes `ClassNotFoundException`. `lw-list-entities` gives you the authoritative FQN in one call:

   ```bash
   lw-list-entities
   # => [{:name "Review", :class "com.example.bloatedshelf.domain.Review", :table-name "review"} ...]
   ```

   To find the repository bean that manages a given entity (or vice versa), use `lw-all-repo-entities` — it gives the authoritative repo → entity mapping with no convention guessing:
   ```bash
   lw-all-repo-entities
   # => [{:bean "reviewRepository" :entity "Review" :entity-fqn "..." :id-type "Long"} ...]
   ```
   Note: when using `lw-build-test-recipe`, the repo bean name is already included in each entity's `:repo` key — no separate call needed.

   **When using `lw-build-test-recipe`, fire it in parallel with `lw-list-entities` and all `lw-inspect-entity` calls** — they are independent and there is no reason to do them sequentially:

   ```bash
   # Fire all of these in a single parallel message
   lw-build-test-recipe Review        # field values + types + repo names
   lw-list-entities                   # FQNs for the REPL prototype
   lw-inspect-entity Review           # constraints, nullability
   lw-inspect-entity Book             # repeat for every entity in the graph
   ```

   **The recipe does not replace `lw-inspect-entity`.** The recipe gives field values and Java types; it does not tell you which fields are `@NotNull`, what `@Size` limits apply, or which associations are truly required. `lw-inspect-entity` gives you the constraints column — you need it before writing the REPL prototype, to know which fields you can safely omit and which will cause a constraint violation if missing.

2. **Check faker is available:**
   ```bash
   clj-nrepl-eval -p 7888 "(faker/available?)"
   ```

3. **Prototype entity creation in the REPL** — use `:auto-deps? true :persist? true :rollback? true` to resolve the full dependency chain without leaving data behind:
   ```bash
   lw-build-entity Review '{:auto-deps? true :persist? true :rollback? true}'
   ```

4. **Inspect the result** — note which fields were generated, what IDs were assigned, and which associations were resolved. This tells you exactly what setup code the test will need.

   The recipe includes the Java type of every field (`:type` key). Use it to apply the correct cast when calling setters — `(short 5)` not `5` for a `short` field, etc.

5. **Adjust with overrides** if specific field values matter for your test assertions:
   ```bash
   lw-build-entity Review '{:auto-deps? true :persist? true :rollback? true :overrides {:rating 5 :comment "Outstanding"}}'
   ```

6. **Prototype the full test logic in Clojure before writing Java — this step is mandatory, not optional.**
   Translate the validated entity graph from the previous steps into a full Clojure REPL expression covering the entire test scenario: setup, the operation under test, and assertions (`clojure.test/is`). This catches real issues — wrong bean names, L1 cache surprises, lazy loading failures, unexpected exceptions — against the live JVM before a single line of Java is written.

   ```clojure
   ;; Example: prototype getReviewsForBook test in Clojure
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
       (is (= 5 (.rating (first result))))
       (is (= "John Doe" (.reviewerName (first result))))))
   ```

7. **If the nREPL is unavailable, ask the user to start the app — never skip prototyping**

   The REPL-first rule is not optional. If `lw-start` finds no server, stop and ask:
   > "The app doesn't seem to be running. Could you start it so I can prototype in the REPL before writing the test?"

   Do not fall back to reasoning theoretically about what the test *should* do. A theoretical prototype defeats the entire purpose — the value is catching real issues (wrong bean names, L1 cache surprises, unexpected exceptions) against a live JVM before a single line of Java is written.

8. **`@BeforeEach` entity setup — store IDs, not entity references**

   When `@BeforeEach` ends with `entityManager.flush() + entityManager.clear()`, any entity references stored as instance fields become **detached**. Using them as `@ManyToOne` targets in test bodies relies on lenient Hibernate behaviour and is fragile.

   **Preferred pattern:** store only the IDs, re-attach in test bodies via `getReferenceById()`:

   ```java
   private Long bookId;
   private Long memberId;

   @BeforeEach
   void setUp() {
       // ... create and save book, member ...
       entityManager.flush();
       entityManager.clear();
       bookId = book.getId();     // store stable IDs
       memberId = member.getId(); // discard the detached refs
   }

   @Test
   void myTest() {
       Book book = bookRepository.getReferenceById(bookId);         // Hibernate proxy, no DB hit
       LibraryMember member = libraryMemberRepository.getReferenceById(memberId);
       // use book and member as @ManyToOne targets — managed proxies, FK resolves cleanly
   }
   ```

   `getReferenceById()` returns a Hibernate proxy with the ID set — no SELECT is fired, and Hibernate resolves the FK correctly on flush. This pattern is explicit, safe across inheritance hierarchies, and stays correct as the test class grows.

9. **Only then write the test** — translate the validated REPL recipe into the setup style already used by the project.

---

## Wrapper scripts

This skill ships named wrapper scripts in a `bin/` subdirectory located in the
same directory as this `SKILL.md` file. If you ever need to find the scripts,
locate `SKILL.md` first (e.g. `find / -name SKILL.md 2>/dev/null | grep livewire`)
and the `bin/` folder is right next to it.

Always prefer these wrapper scripts over raw `clj-nrepl-eval` calls — they
produce cleaner output in the Claude Code UI (the script name is shown instead
of the full Clojure expression) and handle namespace requiring automatically.

Use them via their full path: `~/.claude/skills/livewire/bin/<script-name>`.
The port defaults to **7888** and can be overridden with `LW_PORT`.

| Script | What it does |
|---|---|
| `lw-start` | Discover nREPL + app summary in one shot — **always run this first** |
| `lw-info` | App/env summary |
| `lw-list-entities` | All Hibernate-managed entities |
| `lw-inspect-entity <Name>` | Table, columns, relations for one entity |
| `lw-inspect-all-entities` | Table, columns, relations for **all** entities in one call |
| `lw-list-endpoints` | All HTTP endpoints with auth info |
| `lw-find-beans <regex>` | Filter bean names by regex |
| `lw-bean-deps <beanName>` | Dependencies and dependents for one bean |
| `lw-all-bean-deps` | Wiring maps for all app-level beans (auto-filtered to own package) |
| `lw-bean-tx <beanName>` | `@Transactional` surface for one bean |
| `lw-all-bean-tx` | `@Transactional` surface for all app-level beans (auto-filtered) |
| `lw-repo-entity <beanName>` | Entity class managed by one repository bean |
| `lw-all-repo-entities` | Entity class for every repository bean — the full repo → entity map |
| `lw-props <regex>` | Filter environment properties by regex |
| `lw-sql <query>` | Run a read-only SQL query |
| `lw-jpa-query <jpql> [page] [page-size]` | Run a JPQL query and return serialized entity maps (traced, paged) |
| `lw-trace-sql <clojure-expr>` | Capture SQL fired by an expression |
| `lw-trace-nplus1 <clojure-expr>` | Detect N+1 queries in an expression |
| `lw-call-endpoint [--limit N] <bean> <method> <role> [args...]` | Call a bean method under a single Spring Security role; list results capped at 20 by default |
| `lw-list-queries <repoBeanName>` | List all `@Query` methods on a repo with their current JPQL |
| `lw-build-entity <EntityName> [edn-opts]` | Build a fake entity instance; optional EDN opts map (`:auto-deps?`, `:persist?`, `:rollback?`) |
| `lw-build-test-recipe <EntityName> [edn-opts]` | Build a faker entity graph and extract all scalar field values into a nested map of `{:type … :value …}` entries — use as seed for test setup code and assertions |
| `lw-blast-radius <beanName> <methodName>` | Call-graph impact analysis — which HTTP endpoints, schedulers, and event listeners transitively call this method. Pass `'*'` as method for the full inbound call graph (flat, deduplicated). |
| `lw-blast-radius-all <beanName>` | Per-method inbound call graph for every method on a bean: `{method → {:callers [...]}}`. Methods with empty `:callers` are dead-code candidates. All indexes built once — same speed as a single call. |
| `lw-method-dep-map <beanName>` | For each method on a bean, the subset of its injected dependencies that method actually uses in bytecode. Includes `:dep-frequency` ranking. Options: `:expand-private?` folds private helper deps into their public callers; `:intra-calls?` adds which siblings this method calls; `:callers?` adds which siblings call this method (inverse of `:intra-calls?`). |
| `lw-dead-methods <beanName>` | Analyses public methods on a bean. Splits into `:dead` (no callers anywhere — delete candidates) and `:internal-only` (called only from sibling methods — visibility leaks, refactoring candidates). Warns when messaging beans or db-scheduler tasks are detected. |
| `lw-eval <clojure-expr>` | Generic nREPL eval — **avoid, see pitfall below** |

> ⚠️ **Prefer `clj-nrepl-eval -p <port>` over `lw-eval` for arbitrary expressions.**
> Clojure naming conventions use `!` (mutating fns like `reset-all!`, `hot-swap-query!`),
> `?` (predicates like `running?`), and `->` / `->>` (threading macros). These characters
> can be misinterpreted by the shell when passed through `lw-eval`. Use the dedicated
> wrapper scripts for their named operations; for everything else, call `clj-nrepl-eval`
> directly with a double-quoted expression:
> ```bash
> clj-nrepl-eval -p 7888 "(hq/reset-all!)"
> ```

---

## Prerequisites

This skill relies on `clj-nrepl-eval` — a lightweight CLI from
[clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) that lets
Claude evaluate Clojure expressions against a running nREPL server.

Install it once with [bbin](https://github.com/babashka/bbin):

```bash
# Install bbin (if not already installed — e.g. via Homebrew)
brew install babashka/brew/bbin

# Install clj-nrepl-eval
bbin install https://github.com/bhauman/clojure-mcp-light.git \
  --tag v0.2.1 \
  --as clj-nrepl-eval \
  --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
```

Verify it works:
```bash
clj-nrepl-eval --discover-ports
```

---

## Workflow

1. **Start the session** — always run `lw-start` first:
   ```bash
   lw-start
   ```
   This discovers running nREPL servers and prints an app summary (name, profiles,
   Java/Spring/Hibernate versions) in one shot. The default port is **7888** and can
   be overridden with `LW_PORT`. If nothing is found, the app may not be running or
   `livewire.enabled=true` may not be set.

2. **Namespaces are pre-aliased** — the boot sequence wires these into the `user` ns
   automatically, so no manual `require` is needed:
   `lw`, `q`, `intro`, `trace`, `qw`, `hq`, `jpa`, `mvc`, `faker`

3. **Evaluate** snippets iteratively — the session persists between calls:
   ```bash
   clj-nrepl-eval -p <port> "<clojure-code>"
   # with an explicit timeout (milliseconds)
   clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"
   ```

4. **Run independent read queries in parallel** — when multiple `clj-nrepl-eval` calls have
   no dependencies on each other (e.g. inspecting several entities, fetching unrelated
   properties), fire them all in a single message as parallel Bash tool calls. This
   significantly reduces wall-clock time. Only serialize calls when one result feeds
   into the next.

5. **Present results readably:**
   - Collections of maps → markdown table
   - Single map → inline key/value list
   - Scalars → inline code in prose

6. **Hot-patching:** Do not use `:reload` to pick up a newly built JAR — it
   re-reads the same old class already on the classpath. Instead, evaluate the
   new `ns` form and function bodies directly into the live REPL.

7. **After writing a source-code fix:** Once a hypothesis has been validated in
   the REPL and the fix has been written to source, remind the user that a
   restart may be required before the new code takes effect — unless the change
   is limited to `@Query` JPQL strings that the query-watcher can pick up
   automatically on recompile. For structural changes (new methods, service
   logic, new beans), a friendly nudge is appropriate:
   > The fix is in source. If the change involves new methods or service logic,
   > a restart will be needed to activate it. Once you restart, we can continue
   > from the live REPL.

---

## Core API — `net.brdloush.livewire.core`

| Expression | What it returns |
|---|---|
| `(lw/ctx)` | Live Spring `ApplicationContext` |
| `(lw/info)` | Env summary (Spring version, Java, OS, active profiles) + primary DataSource details (db product/version, JDBC URL, driver, pool config) |
| `(lw/bean "name")` | Bean by name |
| `(lw/bean MyService)` | Bean by type |
| `(lw/beans-of-type DataSource)` | All beans of a type → map |
| `(lw/bean-names)` | All registered bean names |
| `(lw/find-beans-matching ".*Repo.*")` | Filter bean names by regex |
| `(lw/bean-deps "name")` | Wiring map for one bean: `:class`, `:dependencies`, `:dependents` |
| `(lw/all-bean-deps)` | Wiring maps for app-level beans (`:app-only true` by default) |
| `(lw/all-bean-deps :app-only false)` | Wiring maps for all beans including Spring infrastructure |
| `(lw/bean-tx "name")` | `@Transactional` surface for one bean: `:methods` with propagation, isolation, `:read-only`, rollback rules |
| `(lw/all-bean-tx)` | `@Transactional` surface for all app-level beans (`:app-only true` by default); only beans with ≥1 transactional method included |
| `(lw/all-bean-tx :app-only false)` | Include Spring infrastructure beans (verbose — JPA repos expose many overloaded variants) |
| `(lw/all-properties)` | All resolved environment properties → map |
| `(lw/props-matching "spring\\.ds.*")` | Filter properties by regex |
| `(lw/bean->map obj)` | Convert any Java object to a Clojure map — handles both regular JavaBeans and Java records (use this instead of `clojure.core/bean`) |
| `(lw/in-tx & body)` | Run body in a transaction — **always rolls back** |
| `(lw/in-readonly-tx & body)` | Run body in a read-only transaction |
| `(lw/run-as user & body)` | Run body with a Spring `SecurityContext` set — required for `@PreAuthorize`-guarded beans |

### `bean-tx` / `all-bean-tx` — `@Transactional` boundary introspection

Use these functions to map the effective transactional configuration of every bean method.
They query Spring's `AnnotationTransactionAttributeSource` at runtime — no static analysis
required. Both class-level and method-level `@Transactional` are resolved correctly,
including JPA repository defaults from `SimpleJpaRepository`.

`bean-tx` returns the full transactional surface of a single bean:

```clojure
(lw/bean-tx "bookService")
;; => {:bean    "bookService"
;;     :class   "com.example.BookService"
;;     :methods [{:method "archiveBook" :propagation :required :isolation :default
;;                :read-only false :timeout -1 :rollback-for [] :no-rollback-for []}
;;               {:method "getAllBooks"  :propagation :required :isolation :default
;;                :read-only true  :timeout -1 :rollback-for [] :no-rollback-for []}]}
```

`all-bean-tx` returns the same for every app-level bean that has at least one transactional
method (`:app-only true` default, same auto-detection as `all-bean-deps`).

> ⚠️ **JPA repository beans are verbose.** `SimpleJpaRepository` exposes many overloaded
> method variants — calling `lw/bean-tx "bookRepository"` will return 50+ entries. The
> `:app-only true` default on `all-bean-tx` excludes repository beans automatically.

> ⚠️ **Programmatic transactions are not visible.** Methods using `TransactionTemplate` or
> `PlatformTransactionManager` directly leave no annotation trail and will not appear here.

**Common patterns:**

```clojure
;; Full transactional surface of the app — clean summary
(lw/all-bean-tx)

;; Methods that look like reads but are not marked read-only — performance smell
(->> (lw/all-bean-tx)
     (mapcat (fn [b] (map #(assoc % :bean (:bean b)) (:methods b))))
     (filter #(and (not (:read-only %))
                   (re-find #"(?i)^(get|find|list|count|search|fetch)" (:method %)))))

;; All REQUIRES_NEW methods — potential nested transaction complexity
(->> (lw/all-bean-tx)
     (mapcat (fn [b] (map #(assoc % :bean (:bean b)) (:methods b))))
     (filter #(= :requires-new (:propagation %))))

;; Non-default rollback rules
(->> (lw/all-bean-tx)
     (mapcat (fn [b] (map #(assoc % :bean (:bean b)) (:methods b))))
     (filter #(or (seq (:rollback-for %)) (seq (:no-rollback-for %)))))
```

---

### `bean-deps` / `all-bean-deps` — wiring graph introspection

Use these functions to map the runtime dependency graph of the Spring context.
They rely on Spring's internal dependency tracking — populated during context refresh —
which captures constructor injection, `@Autowired` fields, and `@Inject` fields.

`bean-deps` returns a map for a single bean:

```clojure
(lw/bean-deps "bookService")
;; => {:bean         "bookService"
;;     :class        "com.example.BookService"
;;     :dependencies ["bookRepository"]          ; beans bookService injects
;;     :dependents   ["adminController"           ; beans that inject bookService
;;                    "bookController"]}
```

`all-bean-deps` returns the same map for every bean matching the filter.
By default (`:app-only true`) it restricts results to beans whose class belongs
to the application's own root package — auto-detected from `@SpringBootApplication`.
This collapses the "which beans are mine?" lookup into the same call.
Pass `:app-only false` to include all Spring infrastructure beans.

```clojure
;; App beans only — the default, typically a handful of your own classes
(lw/all-bean-deps)

;; Full context including Spring Boot infrastructure (~250+ beans)
(lw/all-bean-deps :app-only false)
```

**Common patterns:**

```clojure
;; Find beans with the most dependencies — coupling smell candidates
(->> (lw/all-bean-deps)
     (sort-by #(count (:dependencies %)) >)
     (take 10)
     (mapv #(select-keys % [:bean :class :dependencies])))

;; Find beans with the most dependents — high-impact, highest-risk to change
(->> (lw/all-bean-deps)
     (sort-by #(count (:dependents %)) >)
     (take 10)
     (mapv #(select-keys % [:bean :dependents])))

;; Inspect a specific bean's full wiring context
(lw/bean-deps "adminService")

;; Find all beans that depend on a given bean (inverse lookup)
(->> (lw/all-bean-deps)
     (filter #(some #{"bookRepository"} (:dependencies %)))
     (mapv :bean))
```

---

### `repo-entity` / `all-repo-entities` — repository → entity mapping

Spring Data repository bean names follow a convention (`bookRepository` → `Book`), but
convention breaks down with custom names, multi-module projects, or unfamiliar codebases.
These functions give the authoritative answer at runtime — no guessing required.

```clojure
(lw/repo-entity "bookRepository")
;; => {:bean "bookRepository" :entity "Book"
;;     :entity-fqn "com.example.domain.Book" :id-type "Long"}

(lw/all-repo-entities)
;; => [{:bean "authorRepository"        :entity "Author"        :entity-fqn "..." :id-type "Long"}
;;     {:bean "bookRepository"          :entity "Book"          :entity-fqn "..." :id-type "Long"}
;;     {:bean "libraryMemberRepository" :entity "LibraryMember" :entity-fqn "..." :id-type "Long"}
;;     ...]
```

Cross-referencing with entity inspection:
```clojure
;; Full schema for the entity managed by each repository
(->> (lw/all-repo-entities)
     (map #(assoc % :schema (intro/inspect-entity (:entity %)))))
```

CLI:
```bash
lw-repo-entity bookRepository
lw-all-repo-entities
```

---

### `run-as` — when and how to use it

Use `run-as` whenever calling a bean that is protected by Spring Security (`@PreAuthorize`,
`@Secured`, etc.). Without it the REPL has no `SecurityContext` and throws
`AuthenticationCredentialsNotFoundException`.

`user` accepts three forms:

| Form | Effect |
|---|---|
| `"alice"` | Token for `alice` with `ROLE_USER` + `ROLE_ADMIN` |
| `["alice" "ROLE_X" "ROLE_Y"]` | Token with exactly the specified roles |
| an `Authentication` object | Used as-is |

```clojure
;; Call a @PreAuthorize-guarded controller or service
;; Always use the vector form so the required role is explicit
(lw/run-as ["user" "ROLE_MEMBER"]
  (.getBookById (lw/bean "bookController") 25))

;; Combine with in-readonly-tx for repository access under a security context
;; Always page or limit — never call .findAll on a large table (see pitfalls)
(lw/run-as ["user" "ROLE_MEMBER"]
  (lw/in-readonly-tx
    (->> (.findAll (lw/bean "bookRepository")
                   (org.springframework.data.domain.PageRequest/of 0 3))
         .getContent
         (mapv #(select-keys (clojure.core/bean %) [:id :title :isbn])))))

;; Use a specific role set when the method checks for a non-admin role
(lw/run-as ["auditor@example.com" "ROLE_AUDITOR"]
  (.getAuditLog (lw/bean "auditService")))
```

**Prefer `run-as` over bypassing to the service layer** when you want to exercise
the real secured code path — including AOP advice, `@PostAuthorize` filters, etc.

#### ⚠️ Plain string form only grants `ROLE_USER` + `ROLE_ADMIN`

If an endpoint requires a custom role (e.g. `ROLE_MEMBER`, `ROLE_VIEWER`, `ROLE_LIBRARIAN`),
the plain string form will fail with `AuthorizationDeniedException` — not
`AuthenticationCredentialsNotFoundException`. **Always use the vector form for non-admin roles.**

```clojure
;; ❌ fails with AuthorizationDeniedException if endpoint requires ROLE_VIEWER
(lw/run-as "viewer"
  (.getAuthors (lw/bean "authorController")))

;; ✅ explicitly specify the required role
(lw/run-as ["viewer" "ROLE_VIEWER"]
  (.getAuthors (lw/bean "authorController")))
```

---

## Introspection API — `net.brdloush.livewire.introspect`

| Expression | What it returns |
|---|---|
| `(intro/list-endpoints)` | All registered HTTP endpoints (path, method, controller, handler, enriched params, `:pre-authorize`, `:required-roles`, `:required-authorities`) |
| `(intro/list-entities)` | All Hibernate-managed entities — simple name, FQN, and DB table name |
| `(intro/inspect-entity "Name")` | Table name, columns, and relations for one entity |
| `(intro/inspect-all-entities)` | Table name, columns, and relations for **all** entities in one call |

### Inspecting entity structure

**Prefer `intro/inspect-all-entities` over calling `intro/inspect-entity` in a loop** when
you need the full domain model — e.g. to build an ER diagram, reason about relationships, or
generate a data dictionary. It returns all entities in a single REPL round-trip.

**Prefer `intro/list-entities` + `intro/inspect-entity` over raw SQL schema queries** when
exploring entity mappings, table names, columns, or relations. The introspection API reads
directly from Hibernate's metamodel — it reflects the actual JPA mapping including column
overrides, join tables, and relation types, without needing to know the DB dialect or schema name.

**When presenting entity listings to the user, always include:**
- Entity name (`:name`)
- Fully-qualified class name (`:class`)
- DB table name (`:table-name`) — included directly in `list-entities` output, no extra calls needed
- `:constraints` — each property in `inspect-entity` output now carries a vector of human-readable
  constraint strings (e.g. `["@NotNull" "@Size(min=0,max=100)"]`). Include these when discussing
  entity fields so the user knows what validation is in play before writing queries or test data.

**Cap large entity listings at 25 rows.** If `intro/list-entities` returns more than 25 entities,
render only the first 25 in the markdown table and append a note such as:

> Showing 25 of 42 entities. Ask for more if you need the full list.

Do **not** silently truncate — always state both the number shown and the total. The user can then
ask for the rest, or narrow the listing with a filter (e.g. `(filter #(re-find #"Order" %) ...)`).
This cap applies to the rendered table only; the full result is already in memory and no extra REPL
call is needed to retrieve the remainder.

```clojure
;; ✅ preferred: list all entities, then inspect one
(->> (intro/list-entities) (map :name) (filter #(re-find #"Book" %)))
;; => ("Book")

(intro/inspect-entity "Book")
;; => {:table-name "book",
;;     :identifier {:name "id", :columns ["id"], :type "long"},
;;     :properties [{:name "title", :columns ["title"], :type "string"} ...],
;;     :relations [{:name "author", :type :many-to-one, :target "Author"} ...]}

;; ✅ also fine: raw SQL for schema questions the metamodel can't answer
;;    (e.g. indexes, constraints, DB-level defaults)
(lw/in-readonly-tx (q/sql "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'book'"))
```

Raw SQL schema queries are a valid fallback for DB-level details (indexes, constraints,
sequences) that are outside Hibernate's metamodel.

### Calling controller methods from the REPL

#### Read the source method body before fetching sample data

**Before querying for sample IDs or constructing any call, read the method implementation.**

A parameter named `memberId: UUID` does not necessarily map to `LibraryMember.id`. It might be a
legacy system ID, an external reference, or a UID from a related entity (e.g. `member.externalRef`).
The method body always reveals the actual lookup — e.g. `findByExternalRef(memberId)` immediately
signals that the parameter is *not* the JPA primary key.

**Mandatory sequence:**
1. List method signatures (to learn parameter types and names)
2. **Read the source** — find what lookup the method performs for each parameter
3. Only then write a JPQL query targeting the correct field/entity
4. Call the method with valid IDs

Skipping step 2 leads to calling with wrong IDs, misreading empty results, and unnecessary
round-trips.

#### ⚠️ Never invoke mutating endpoints without explicit user instruction

Only call controller methods that correspond to **read-only (`GET`) endpoints** on your own
initiative. `GET` endpoints are expected to be side-effect free and are safe to invoke for
inspection or debugging.

For any **mutating endpoint** (`POST`, `PUT`, `PATCH`, `DELETE`, or anything else that writes,
deletes, or triggers side effects), you **must not** call it unless one of the following is true:

- The user has **explicitly instructed** you to call it (e.g. "go ahead and create that record").
- You have **asked the user for permission** and received confirmation.

If in doubt, describe what you would call and why, and wait for the user's go-ahead.

When you discover an endpoint via `list-endpoints`, check its `:pre-authorize` value before calling it.
If one is present, **always wrap the call in `lw/run-as`** — without it the REPL has no
`SecurityContext` and Spring Security throws `AuthenticationCredentialsNotFoundException`.

```clojure
;; Discover what auth an endpoint requires and build the run-as vector mechanically
(->> (intro/list-endpoints)
     (filter #(re-find #"books" (str (:paths %))))
     (mapv #(select-keys % [:paths :handler-method :pre-authorize :required-roles :required-authorities])))
;; => [{:paths ["/api/books"], :handler-method "getBooks",
;;      :pre-authorize "hasRole('MEMBER')", :required-roles ["MEMBER"]}
;;     ...]

;; Call it using :required-roles directly — no manual SpEL parsing needed
(lw/run-as ["ROLE_MEMBER"]
  (.getBooks (lw/bean "bookController")))
```

`:pre-authorize` reflects both method-level and class-level `@PreAuthorize` annotations —
so it's always populated when security is in play, regardless of where the annotation lives.

`:required-roles` and `:required-authorities` are parsed from the SpEL expression and let
you construct the `lw/run-as` vector mechanically without reading the raw string.
They handle `hasRole`, `hasAnyRole`, `hasAuthority`, and `hasAnyAuthority`.
If the expression uses none of these patterns (e.g. custom SpEL), the keys are absent
and you must fall back to reading `:pre-authorize` directly.

Each parameter map in `:parameters` now includes:
- `:source` — `:path` | `:query` | `:body` | `:header` | `:unknown`
- `:required` — `true` / `false` (nil for `:unknown`)
- `:default-value` — present only for `:query` / `:header` params that declare a default

```clojure
;; Inspect a single endpoint's parameters — source, required, default-value
(->> (intro/list-endpoints)
     (filter #(= "/api/books/{id}" (first (:paths %))))
     (mapv :parameters))
;; => [[{:name nil, :type "java.lang.Long", :source :path, :required true}]]
```

**When presenting `list-endpoints` results to the user, always include these fields for each endpoint:**
- HTTP method(s) (`:methods`)
- Path(s) (`:paths`)
- Controller class name (`:controller`)
- Handler method name (`:handler-method`)
- Required role / `@PreAuthorize` expression (`:pre-authorize`, or "none" if absent)
- `:required-roles` / `:required-authorities` when present (use these to construct `lw/run-as`)
- For each parameter: `:name`, `:type`, `:source`, `:required`, and `:default-value` when present

#### CLI shortcut: `lw-call-endpoint`

For one-shot calls from the shell, `lw-call-endpoint` handles the require and `run-as`
boilerplate automatically. It accepts a single role (the `ROLE_` prefix is required).

**List results are capped at 20 items by default** — the same convention as `lw-sql` and
`lw-jpa-query`. Use `--limit N` to override. The returned value is a Clojure vector with
metadata attached so you can see how many items were omitted. Single-object results (non-list)
are unaffected and always return in full as pretty-printed JSON.

**Always report the following to the user when a limited list is returned:**
- `:returned` / `:total` — how many rows were shown vs how many exist
- `:content-size` — raw JSON byte size of the **full uncapped** response (all `:total` items, not just the `:returned` ones — do NOT use this to judge whether the returned payload is too large to display)
- `:content-size-gzip` — gzip-compressed byte size of the full uncapped response

**The actual returned payload contains only `:returned` items** — always render it directly as a markdown table without piping through external tools (Python, jq, etc.), even when `:content-size` looks large.

**If the saved output file is too large for the Read tool** (error: token limit exceeded), do NOT fall back to Python/jq/shell tools to parse it. Instead, re-call the same endpoint using `lw-call-endpoint --limit 1`:

```bash
# Re-call with a single item — always fits in the Read tool
lw-call-endpoint --limit 1 bookController getBooks ROLE_MEMBER
```

**Always render the returned items as a markdown table**, using the keys of the first item as
column headers. The number of rows to display in the table follows this rule:
- If the result has `:total` metadata, show up to `:returned` rows — but if the tool result is too large to fully process, show at least 3 rows and note that the rest were omitted due to response size
- Otherwise cap the table at **20 rows** unless the user explicitly asked for more
- Never silently truncate — if rows were omitted, add a note such as
  `_Showing N of M total rows. Use --limit to retrieve more._`

```bash
# List endpoint — capped at 20 by default
lw-call-endpoint bookController getBooks ROLE_MEMBER

# Raise the cap
lw-call-endpoint --limit 5 bookController getBooks ROLE_MEMBER

# Single-object endpoint — limit has no effect, returns full pretty-printed JSON
lw-call-endpoint bookController getBookById ROLE_MEMBER 25

# String argument — quote carefully
lw-call-endpoint bookController searchBooks ROLE_MEMBER '"spring"'
```

When you need **multiple roles** or a custom username, use `clj-nrepl-eval` directly:

```bash
clj-nrepl-eval -p 7888 '(lw/run-as ["alice" "ROLE_LIBRARIAN" "ROLE_VIEWER"] (.getMembers (lw/bean "memberController")))'
```

---

## Trace API — `net.brdloush.livewire.trace`

| Expression | What it does |
|---|---|
| `(trace/trace-sql & body)` | Captures every SQL fired by Hibernate on the current thread |
| `(trace/trace-sql-global & body)` | Same, but captures across *all* threads (useful for `@Async`) |
| `(trace/detect-n+1 trace-res)` | Analyzes a trace result and flags repeated queries |

---

## JPA Query API — `net.brdloush.livewire.jpa-query`

Executes a JPQL query against the live `EntityManager` inside a read-only transaction and
returns a vector of plain Clojure maps. Lazy collections are rendered as `"<lazy>"` rather
than triggering surprise queries; ancestor-chain cycles become `"<circular>"`.

**Prefer this over raw `q/sql`** when you want to work at the JPA/entity level — `JOIN FETCH`
clauses control exactly what gets loaded, and the result is already a readable Clojure structure
with no need to manually call `clojure.core/bean`.

| Expression | What it does |
|---|---|
| `(jpa/jpa-query jpql)` | Run JPQL, return first 20 results as entity maps |
| `(jpa/jpa-query jpql :page 1 :page-size 5)` | Paginate — offset = `page × page-size` |

### Serialization behaviour

| Scenario | Result |
|---|---|
| Scalar property | Value as-is |
| Temporal type (`LocalDate`, etc.) | `#object[LocalDate ...]` (not coerced) |
| Eagerly fetched `@ManyToOne` | Recursed into (full nested map) |
| Eagerly fetched collection (within cap) | `[{…} {…} …]` |
| Collection beyond page-size cap | Truncated to cap |
| Uninitialized lazy collection | `"<lazy>"` |
| Ancestor-chain cycle | `"<circular>"` |
| Sibling reuse of same object | Fully rendered (not falsely circular) |
| Non-entity Java object | `(.toString obj)` |

### Examples

```clojure
;; Basic — lazy associations render as "<lazy>"
(jpa/jpa-query "SELECT b FROM Book b ORDER BY b.id")
;; => [{:id 1, :title "All the King's Men", :isbn "...",
;;      :author {:id 6, :firstName "Kip", :lastName "O'Reilly", :books "<lazy>"},
;;      :genres "<lazy>", :reviews "<lazy>", :loanRecords "<lazy>"} ...]

;; JOIN FETCH to eagerly load genres — they come back as real vectors
(jpa/jpa-query "SELECT DISTINCT b FROM Book b LEFT JOIN FETCH b.genres ORDER BY b.id"
               :page 0 :page-size 5)
;; => [{:id 1, ..., :genres [{:id 3, :name "Fiction"} {:id 7, :name "Drama"}], :reviews "<lazy>"} ...]

;; Paginate — page 2 of 10 (rows 20–29)
(jpa/jpa-query "SELECT b FROM Book b ORDER BY b.id" :page 2 :page-size 10)

;; Combine with trace-sql to inspect query count and SQL shape
(trace/trace-sql
  (jpa/jpa-query "SELECT DISTINCT b FROM Book b LEFT JOIN FETCH b.genres"))
;; => {:result [...], :count 1, :duration-ms 12, :queries [{:sql "select ..."}]}
```

### CLI: `lw-jpa-query`

```bash
# Default: page 0, page-size 20
lw-jpa-query 'SELECT b FROM Book b ORDER BY b.id'

# With JOIN FETCH, first 5 results
lw-jpa-query 'SELECT DISTINCT b FROM Book b LEFT JOIN FETCH b.genres' 0 5

# Page 2 with 10 per page
lw-jpa-query 'SELECT b FROM Book b ORDER BY b.id' 2 10
```

The CLI output is wrapped in `trace/trace-sql`, so you always get `:result`, `:count`,
`:queries`, and `:duration-ms` in one shot.

### Scalar projections

`jpa/jpa-query` and `lw-jpa-query` also work for **scalar projections** — queries that select
expressions or aggregates rather than full entities.

**Prefer `AS` aliases** — they produce named keys directly and are the clearest option:

```clojure
(jpa/jpa-query
  "SELECT b.title AS title, COUNT(lr) AS loans
   FROM Book b JOIN b.loanRecords lr
   GROUP BY b.id, b.title ORDER BY COUNT(lr) DESC"
  :page 0 :page-size 5)
;; => [{:title "The Green Bay Tree",          :loans 7}
;;     {:title "Let Us Now Praise Famous Men", :loans 7}
;;     {:title "Vanity Fair",                  :loans 7} ...]
```

> ⚠️ **Hibernate 7 lowercases `AS` alias names.** `AS avgRating` becomes `:avgrating`,
> `AS loanCount` becomes `:loancount`. Always use lowercase alias names in the query
> to avoid confusion:
> ```clojure
> ;; ✅ lowercase alias — key is :avgrating, no surprise
> "SELECT AVG(r.rating) AS avgrating FROM ..."
> ;; ❌ mixed-case alias — still returns :avgrating, looks wrong
> "SELECT AVG(r.rating) AS avgRating FROM ..."
> ```

If no `AS` aliases are present, `Object[]` rows are unpacked into maps with positional
keys `:col0`, `:col1`, etc. Use `clojure.set/rename-keys` to name them after the fact:

```bash
# No aliases — returns {:col0 <title>, :col1 <count>} maps
lw-jpa-query 'SELECT b.title, COUNT(lr) FROM Book b JOIN b.loanRecords lr GROUP BY b.id, b.title ORDER BY COUNT(lr) DESC' 0 5
```

```clojure
;; => {:duration-ms 26,
;;     :result [{:col0 "Vanity Fair",                  :col1 7}
;;              {:col0 "Let Us Now Praise Famous Men", :col1 7}
;;              {:col0 "The Green Bay Tree",           :col1 7} ...],
;;     :count 1, ...}

;; Rename after the fact if you didn't use AS aliases
(->> (:result (jpa/jpa-query "SELECT b.title, COUNT(lr) FROM Book b JOIN b.loanRecords lr GROUP BY b.id, b.title ORDER BY COUNT(lr) DESC"
                              :page 0 :page-size 5))
     (mapv #(clojure.set/rename-keys % {:col0 :title :col1 :loan-count})))
;; => [{:title "Vanity Fair", :loan-count 7} ...]
```

---

## Mutation Observer API — `net.brdloush.livewire.query`

`q/diff-entity` answers the question *"what did this service call actually write to the database?"* —
the observability gap that `trace/trace-sql` and `jpa/jpa-query` leave open. Those tools cover
the *query* dimension; `diff-entity` covers the *mutation* dimension.

| Expression | What it does |
|---|---|
| `(q/diff-entity entity-class id thunk)` | Snapshot entity before and after calling `thunk`, return `{:before … :after … :changed {key [old new]}}` |

- `entity-class` — Hibernate entity name (string, e.g. `"Book"`) or Java class
- `id` — primary key value
- `thunk` — zero-argument function that performs the mutation

The thunk always runs inside `lw/in-tx` — **the change is always rolled back**. The database is
never affected. The snapshot of the mutated state is captured inside the transaction, before the
rollback fires.

### When to use it

- **Exploration** — "what fields does `.archiveBook` actually touch?" without reading service code
- **Debugging** — "why is entity 42 in this unexpected state?" — call candidate service methods and inspect their diffs
- **Fix verification** — confirm a patched service method now writes the correct fields before committing
- **AI agent mutation investigation** — the primary use case: an agent can systematically call candidate service methods and reason about which one caused an unexpected state change

### Example

```clojure
;; Observe what a repository save would change (rolled back — nothing persists)
(q/diff-entity "Book" 1
  (fn []
    (let [repo (lw/bean "bookRepository")
          book (.get (.findById repo (Long. 1)))]
      (.setAvailableCopies book (short 2))
      (.save repo book))))
;; => {:before  {:id 1, :title "All the King's Men", :availableCopies 3, ...}
;;     :after   {:id 1, :title "All the King's Men", :availableCopies 2, ...}
;;     :changed {:availableCopies [3 2]}}

;; No-op thunk — confirms rollback and baseline
(q/diff-entity "Book" 1 (fn []))
;; => {:before {...}, :after {...}, :changed {}}

;; Non-existent entity — safe, returns nils
(q/diff-entity "Book" 99999 (fn []))
;; => {:before nil, :after nil, :changed {}}
```

### Using it from the shell

There is no dedicated wrapper script — the thunk is Clojure logic, not a data argument.
Use `clj-nrepl-eval` directly:

```bash
clj-nrepl-eval -p 7888 '(q/diff-entity "Book" 1 (fn [] (.save (lw/bean "bookRepository") ...)))'
```

---

## Hot Queries API — `net.brdloush.livewire.hot-queries`

Swap a Spring Data JPA `@Query` live without restarting the app. Works by replacing the
`queryString` Lazy field inside `SimpleJpaQuery` with an atom-backed one — Spring Data's
full result-type coercion stays intact. The first swap uses reflection; subsequent swaps
for the same method are reflection-free (just `reset!` the atom).

| Expression | What it does |
|---|---|
| `(hq/list-queries "repoBean")` | Lists all `@Query` methods on the repo with their current JPQL |
| `(hq/hot-swap-query! "repoBean" "method" new-jpql)` | Swaps the JPQL live; first call uses reflection, subsequent calls just `reset!` the atom |
| `(hq/list-swapped)` | Shows all currently swapped queries across all repos |
| `(hq/reset-query! "repoBean" "method")` | Restores the original JPQL for one method |
| `(hq/reset-all!)` | Restores **every** currently swapped query at once; always call this to clean up after an exploratory session |

### When to use hot-queries

- **Iterating on a JPQL fix** without a restart — swap, call the method, observe, refine.
- **Reproducing a query bug** by temporarily substituting a known-bad query.
- **Testing a `JOIN FETCH` or `@EntityGraph`** addition before writing it to source.
- Works best combined with `trace/trace-sql` to verify the resulting SQL.

### Last-one-wins swap policy

**A recompile always overrides a REPL pin.** When the query-watcher detects a `.class` file
change, it applies the new JPQL regardless of whether you had previously swapped the query
from the REPL. Similarly, a subsequent REPL swap overrides whatever the watcher last applied.
Whoever wrote last wins — no manual cleanup required between iterations.

`(hq/list-swapped)` shows `:manual? true` for REPL-initiated swaps and `:manual? false` for
watcher-initiated ones — informational only, it no longer blocks overwrites.

```clojure
(hq/list-swapped)
;; => [{:bean "bookRepository", :method "findByIdWithDetails",
;;      :manual? true, :jpql "select b from Book b where b.id = :id"}]
```

### ⚠️ Hot-swaps are persistent side effects — clean up when done

Unlike `lw/in-tx` (which always rolls back), hot-swapped queries **persist** in the live JVM.
A recompile will override your swap automatically (last-one-wins), but if you finish an
exploratory session without recompiling, the patched query stays live and can silently affect
other callers or monitoring.

**Rule: call `reset-all!` when you're done experimenting.**

```clojure
;; Restore everything in one call
(hq/reset-all!)
;; => [["bookRepository" "findByIdWithDetails"] ...]   ← keys that were restored

;; Or verify nothing is left swapped
(hq/list-swapped)
;; => []
```

---

```clojure
;; See what @Query methods exist on a repo
(hq/list-queries "bookRepository")
;; => ({:method "findByIdWithDetails", :query-class "SimpleJpaQuery",
;;      :jpql "select b from Book b where b.id = :id ..."} ...)

;; Swap to a fixed query (e.g. add JOIN FETCH to fix N+1)
(hq/hot-swap-query! "bookRepository" "findByIdWithDetails"
  "select b from Book b join fetch b.author join fetch b.reviews where b.id = :id")

;; Verify the fix — combine with trace-sql to confirm query shape
(trace/trace-sql
  (lw/run-as "admin"
    (lw/in-readonly-tx
      (.findByIdWithDetails (lw/bean "bookRepository") 25))))

;; Swap again (reflection-free atom reset)
(hq/hot-swap-query! "bookRepository" "findByIdWithDetails"
  "SELECT DISTINCT b FROM Book b JOIN FETCH b.author LEFT JOIN FETCH b.genres WHERE b.id = :id")

;; Check registry
(hq/list-swapped)
;; => [{:bean "bookRepository", :method "findByIdWithDetails", :jpql "..."}]

;; Restore original when done
(hq/reset-query! "bookRepository" "findByIdWithDetails")
;; => :restored
```

---

## Query Watcher API — `net.brdloush.livewire.query-watcher`

The query-watcher runs automatically in the background (started by Livewire on boot). It polls
compiled output directories (`target/classes`, `build/classes/…`) every 500 ms, detects `.class`
file changes via mtime, and auto-applies updated `@Query` JPQL strings live — no restart needed.

| Expression | What it does |
|---|---|
| `(qw/status)` | Returns `{:running? true/false, :disk-state-size N, :disk-state {...}}` |
| `(qw/start-watcher!)` | Starts the watcher (idempotent — called automatically on boot) |
| `(qw/stop-watcher!)` | Stops the watcher |
| `(qw/force-rescan!)` | Clears the mtime cache — next poll re-examines every `.class` file |

`qw/force-rescan!` is rarely needed manually. `hq/reset-all!` and `hq/reset-query!` call it
automatically after restoring a query so the watcher immediately re-applies whatever is on disk.

---

## ⚠️ Hibernate lazy loading — always convert inside the transaction

Returning a raw Hibernate entity from `in-tx` / `in-readonly-tx` will throw
`LazyInitializationException` when the REPL tries to print it — the session is
already closed. **Always eagerly convert to a plain Clojure map inside the
transaction boundary.**

```clojure
;; ❌ blows up — printed after session closes
(lw/in-readonly-tx
  (.findById (lw/bean "bookRepository") 1))

;; ✅ convert while the session is still open
(lw/in-readonly-tx
  (-> (.findById (lw/bean "bookRepository") 1)
      .get
      clojure.core/bean                              ; all getter properties → map
      (select-keys [:id :title :isbn])))             ; narrow to what you need
```

`clojure.core/bean` introspects all getter methods and returns a Clojure map.
Wrap it in `select-keys` to avoid triggering lazy associations you don't need.

---

## ⚠️ Important Rules

### Always use JPQL (`lw-jpa-query`) for data queries — raw SQL is the last resort

**Default to `lw-jpa-query` for any query that fetches application data** — even simple things
like "get me some sample IDs". Raw `lw-sql` / `q/sql` is reserved for:
- DB-level metadata: indexes, constraints, `information_schema` columns
- DDL statements (`CREATE INDEX`, `ALTER TABLE`)
- Queries involving tables with no Hibernate entity mapping

If you find yourself reaching for `lw-sql` to fetch entity data, stop and write JPQL instead.

```bash
# ❌ raw SQL for entity data — wrong default
lw-sql "SELECT TOP 5 id FROM dbo.loan_record"

# ✅ JPQL — no schema prefix needed, uses entity property names, auto-paginated
lw-jpa-query 'SELECT lr.id FROM LoanRecord lr' 0 5
```

This rule applies even when the query seems trivially simple. JPQL never requires knowing
the schema prefix, never uses DB column names, and integrates naturally with
`lw-inspect-entity` output.

---

### Always inspect entities before writing any SQL or JPQL query

**Never guess table names, column names, or join paths.** The Livewire introspection API gives you exact mappings in one call — use it every time before writing a query, no matter how obvious the schema seems.

**Workflow — mandatory before every query:**

1. **Find the entity name** — if you don't know it yet, list all entities and grep:
   ```bash
   lw-list-entities   # then grep for keywords
   ```

2. **Inspect the entity (and any entity you want to join to)**:
   ```bash
   lw-inspect-entity Book
   lw-inspect-entity Author   # if you need to join
   ```
   Check `:table-name`, `:columns`, and `:relations` (`:type`, `:target-entity`, join columns). This tells you:
   - The exact table and column names for raw SQL
   - The JPQL property path for JPA queries (`b.author.lastName`, not `author_id`)
   - Which side owns the FK and what the join column is

3. **Write JPQL, not raw SQL** — see the "Always use JPQL" rule above. JPQL uses entity property names, handles schema-qualified table names automatically, and composes joins via the object graph. The entity inspection above gives you everything you need to write it correctly.

4. **Only then write the query.**

```clojure
;; ❌ guessing — likely to fail with wrong table name, schema, or column
(lw/in-readonly-tx (q/sql "SELECT id FROM loan_record WHERE returned = 1"))

;; ✅ inspect first
;; lw-inspect-entity LoanRecord  →  table "loan_record", no "returned" column — it's "return_date"
;; lw-inspect-entity LoanRecord  →  relation: member → LibraryMember, join column member_id, property "member"
;; Then write JPQL using the discovered property path:
(jpa/jpa-query "SELECT lr.id, lr.member.id FROM LoanRecord lr WHERE lr.returnDate IS NULL" :page 0 :page-size 10)
```

---

### Always limit queries when fetching sample data or IDs
Tables in a live app can contain millions of rows. **Always cap results** when querying for
sample data, example IDs, or exploratory results. The default cap is **20 rows**.

Prefer `lw-jpa-query` — it paginates automatically via its `page` / `page-size` arguments:

```bash
# ✅ JPQL — page 0, 5 results, no risk of runaway fetch
lw-jpa-query 'SELECT b.id, b.title FROM Book b' 0 5
```

If you must use raw SQL (DB-level metadata, no entity mapping), add an explicit row limit:

```clojure
;; ❌ may return millions of rows and hang the REPL
(lw/in-readonly-tx (q/sql "SELECT id FROM book"))

;; ✅ cap explicitly for raw SQL
(lw/in-readonly-tx (q/sql "SELECT TOP 20 id, title FROM book"))        ; MS SQL Server
(lw/in-readonly-tx (q/sql "SELECT id, title FROM book LIMIT 20"))      ; PostgreSQL / MySQL
(lw/in-readonly-tx (q/sql "SELECT id, title FROM book FETCH FIRST 20 ROWS ONLY"))  ; SQL standard
```

Apply the same discipline to repository calls —
if a method returns a `List`, confirm the table is small before calling it without a
`Pageable` / `limit`.

---

## N+1 Hunting — Tips & Gotchas

### N+1 presence is data-dependent — always test multiple IDs
An N+1 only fires when the problematic association actually has rows. A query that looks
fine on one record may blow up on another. **Always test several representative IDs**
before concluding there is no N+1.

```clojure
;; ✅ test multiple IDs in one shot and compare query counts
(mapv (fn [id]
        (let [res (trace/trace-sql
                    (lw/run-as "admin"
                      (.myEndpoint (lw/bean "myController") id)))]
          {:id id :total-queries (:count res) :suspicious (count (:suspicious-queries (trace/detect-n+1 res)))}))
      [1 2 3 4 5])
;; => look for outliers — the problematic ID will stand out with a much higher :total-queries count
```

### `FetchType.LAZY` on a non-PK `@ManyToOne` is silently ignored by Hibernate
If a `@ManyToOne` uses `referencedColumnName` pointing to a **non-primary-key** column,
Hibernate cannot create a lazy proxy (it needs the PK to do so). The association is
effectively loaded eagerly regardless of the `LAZY` declaration, firing one SELECT per
parent row — a hidden N+1 that is invisible from reading the code alone.

```kotlin
// ⚠️ looks lazy, but Hibernate fires a SELECT per row because Uid is not the @Id
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "book_isbn", referencedColumnName = "isbn")   // ← non-PK join
open var bookIsbn: Book? = null
```

**Fix:** add an explicit `left join fetch` in the JPQL for the owning query so Hibernate
loads the full entity (including its PK) in the main query instead of per-row selects.

### Quick-test a service fix by re-implementing the core flow in Clojure

When a fix involves service-layer logic (not just a JPQL change), you can prototype it
directly in the REPL without restarting. The nREPL runs inside the same JVM, so it can
call any Spring bean — repositories, services, anything. Write a temporary Clojure
expression that reimplements the **core flow** of the service method with your candidate
fix, wrap it in `trace/trace-sql`, and measure query count live against real data.

The expression doesn't need to be a perfect replica — just enough to exercise the query
pattern you're changing. Once `trace-sql` confirms the query count drops, write the real
fix to Java and rebuild.

```clojure
;; Example: service currently does bookRepo.findAll() then lazy-loads genres/reviews/members
;; (481 queries for 200 books). Candidate fix: two eager queries + manual grouping.
;; Prototype in Clojure first:

(import '[com.example.myapp.dto AuthorSummaryDto BookWithReviewsDto ReviewDto])

(defn get-all-books-fixed []
  (let [book-repo   (lw/bean "bookRepository")
        review-repo (lw/bean "reviewRepository")
        books       (.findAllWithAuthorAndGenres book-repo)
        reviews-by-book-id
          (->> (.findAllWithMember review-repo)
               (group-by #(.getId (.getBook %)))
               (into {} (map (fn [[k vs]] [k (mapv ReviewDto/from vs)]))))]
    (mapv (fn [b]
            (BookWithReviewsDto.
              (.getId b) (.getTitle b)
              (AuthorSummaryDto/from (.getAuthor b))
              (->> (.getGenres b) (map #(.getName %)) sort vec)
              (get reviews-by-book-id (.getId b) [])))
          books)))

;; Measure — zero restarts needed
(let [res (trace/trace-sql (lw/in-readonly-tx (count (get-all-books-fixed))))]
  (select-keys res [:count :duration-ms]))
;; => {:count 2, :duration-ms 43}   ← was 481 queries, now 2
```

**Key points:**
- Access `.getId()` on a lazy proxy is safe — Hibernate does not fire a query for PKs
- `ReviewDto/from` works here because `JOIN FETCH r.member` already loaded member into L1 cache
- The expression is throwaway — only the Java fix goes to source

### Use hot-swap to confirm a JPQL fix before touching source code
Rather than edit → restart → retest, hot-swap the candidate fix, verify with `trace-sql`,
then swap back to the original to confirm the N+1 returns. Only then write the fix to
source. This round-trip gives high confidence with zero restarts.

```clojure
;; 1. swap in the fix
(hq/hot-swap-query! "myRepo" "myMethod" "select ... join fetch ...")
;; 2. confirm N+1 is gone
(trace/detect-n+1 (trace/trace-sql (lw/run-as "admin" (.myMethod ...))))
;; 3. swap back to broken — confirm N+1 returns
(hq/hot-swap-query! "myRepo" "myMethod" "select ... -- original without fetch")
(trace/detect-n+1 (trace/trace-sql (lw/run-as "admin" (.myMethod ...))))
;; 4. restore and write the fix to source
(hq/reset-query! "myRepo" "myMethod")
```

---

## Faker API — `net.brdloush.livewire.faker`

Build valid, optionally-persistable Hibernate entity instances using realistic fake data.
All datafaker access is reflective — the namespace loads safely on apps without datafaker.

### Preflight check

Always call `faker/available?` first to confirm `net.datafaker.Faker` is on the classpath:

```clojure
(faker/available?)
;; => true   (or false if datafaker is not in the project)
```

If it returns `false`, add the dependency to the target application:

```xml
<!-- Maven -->
<dependency>
  <groupId>net.datafaker</groupId>
  <artifactId>datafaker</artifactId>
  <version>2.5.4</version>
</dependency>
```

```groovy
// Gradle
implementation 'net.datafaker:datafaker:2.5.4'
```

### `faker/build-entity` API

```clojure
(faker/build-entity entity-name)
(faker/build-entity entity-name opts)
```

| Option | Type | Default | Description |
|---|---|---|---|
| `:overrides` | map | `{}` | Keyword/symbol field-name → value. Applied last; always wins. |
| `:auto-deps?` | boolean | `false` | Recursively build and wire required `@ManyToOne` associations not in `:overrides`. |
| `:persist?` | boolean | `false` | Persist the entity (and auto-built deps) via the live `EntityManager`. |
| `:rollback?` | boolean | `false` | Wrap the whole operation in a transaction that always rolls back. Meaningful only when `:persist? true`. |

### Common patterns

```clojure
;; Simple entity with no required FKs
(faker/build-entity "Author")
;; => #object[Author ... ]

;; With overrides — they always win over heuristics
(faker/build-entity "Author" {:overrides {:firstName "Agatha" :lastName "Christie"}})

;; Entity with a required FK — provide the dependency yourself
(let [author (faker/build-entity "Author" {:persist? true})]
  (faker/build-entity "Book" {:overrides {:author author} :persist? true}))

;; Let Livewire resolve the full dependency chain automatically
(faker/build-entity "Book" {:auto-deps? true :persist? true})

;; Speculative pattern: persist + rollback — get a real DB-assigned id without leaving data behind.
;; Useful for calling a service with a real entity while ensuring nothing persists.
(let [review (faker/build-entity "Review" {:auto-deps? true :persist? true :rollback? true})]
  (println "Got id:" (.getId review))
  ;; Call your service here — entity has a real id from the DB sequence
  ;; Everything is rolled back when the block exits
  )

;; Combine with trace/trace-sql to inspect what the cascade generates
(trace/trace-sql
  (faker/build-entity "Review" {:auto-deps? true :persist? true :rollback? true}))
;; => {:result #object[Review ...], :count 4, :duration-ms 23, :queries [...]}
```

### Constraint-aware generation and fail-fast override validation

`faker/build-entity` reads `jakarta.validation.constraints` (and `javax.validation.constraints`
as a fallback) from each field and getter at build time. This enables two things:

**1. Fail-fast override validation** — before any setter is called, all overrides are checked
against their constraints. If a violation is found, `ex-info` is thrown immediately with a
clear message:

```clojure
(faker/build-entity "Review" {:auto-deps? true :overrides {:comment nil}})
;; throws: "Cannot apply override {:comment nil} — Review.comment is @NotNull"

(faker/build-entity "Review" {:auto-deps? true :overrides {:rating 10}})
;; throws: "Cannot apply override {:rating 10} — Review.rating is @Max(5)"

(faker/build-entity "Author" {:overrides {:firstName ""}})
;; throws: "Cannot apply override {:firstName \"\"} — Author.firstName is @NotBlank"
```

Validated constraints: `@NotNull`, `@NotBlank`, `@Min`, `@Max`, `@Positive`, `@PositiveOrZero`.

**2. Constraint-aware value generation** — the heuristic generator is post-processed to
respect annotation metadata:

| Annotation | Generator behaviour |
|---|---|
| `@Min(X)` / `@Max(Y)` | Numeric value clamped to `[X, Y]` |
| `@Positive` / `@PositiveOrZero` | Negative values reflected to positive |
| `@Email` | `faker.internet().emailAddress()` used as fallback when name heuristic wouldn't produce an email |
| `@Past` / `@PastOrPresent` | Date generated in the past (annotation takes priority over name-suffix heuristic) |
| `@Size(min=X, max=Y)` | String length clamped; takes the most restrictive max of `@Size` and `@Column(length=...)` |
| `@Pattern(regexp=...)` | Warning printed; value generated by name heuristic (regex-constrained generation is not supported) |

### Heuristics and skip rules

The generator matches property names case-insensitively against a priority table (first match wins).
String values are clamped to the most restrictive of `@Column(length=…)` and `@Size(max=…)`.

| Group | Pattern(s) | Generated value |
|---|---|---|
| **Person** | `^firstName$` | `faker.name().firstName()` |
| | `^lastName$` | `faker.name().lastName()` |
| | `^fullName$`, `^name$` | `faker.name().fullName()` |
| | `^prefix$` | `faker.name().prefix()` |
| | `^suffix$` | `faker.name().suffix()` |
| | `^gender$`, `^sex$` | `faker.gender().types()` |
| | `^age$` | random int 18–90 |
| | `birth(Day\|Date\|Ts\|At)?$` | past birthday (`faker.date().birthday()`) |
| | `nationality` | lorem word fallback |
| **Contact** | `email` | `faker.internet().emailAddress()` |
| | `username`, `^login$` | `faker.internet().username()` |
| | `phone\|telephone\|mobile\|cell\|fax` | `faker.phoneNumber().phoneNumber()` |
| **Address** | `^streetAddress$` | `faker.address().streetAddress()` |
| | `^street$` | `faker.address().streetName()` |
| | `^address$` | `faker.address().fullAddress()` |
| | `^city$`, `^town$` | `faker.address().city()` |
| | `^state$`, `^county$` | `faker.address().state()` |
| | `^country$` | `faker.address().country()` |
| | `^countryCode$` | `faker.address().countryCode()` |
| | `zipCode\|postCode\|postalCode`, `^zip$`, `^postal$` | `faker.address().zipCode()` |
| | `^latitude$` | `faker.address().latitude()` |
| | `^longitude$` | `faker.address().longitude()` |
| | `^timeZone$` | `faker.address().timeZone()` |
| **Internet** | `^url$`, `^website$` | `faker.internet().url()` |
| | `^domainName$` | `faker.internet().domainName()` |
| | `^slug$` | `faker.internet().slug()` |
| | `^token$` | random UUID string |
| | `^image$`, `^avatar$`, `^imageUrl$` | `faker.internet().image()` |
| **Identifiers** | `isbn`, `^isbn13$` | `faker.code().isbn13()` |
| | `^isbn10$` | `faker.code().isbn10()` |
| | `uuid` | random `UUID` |
| | `^reference$`, `^code$` | lorem word |
| **Company** | `^company$`, `^companyName$`, `^organization$`, `^employer$` | `faker.company().name()` |
| | `^department$` | `faker.commerce().department()` |
| | `^jobTitle$`, `^occupation$` | `faker.job().title()` |
| | `^position$` | `faker.job().position()` |
| **Financial** | `^price$` | `faker.commerce().price()` |
| | `^amount$`, `^salary$` | random double |
| | `^currency$`, `^currencyCode$` | `faker.money().currencyCode()` |
| | `^iban$` | `faker.finance().iban()` |
| | `^creditCard$` | `faker.finance().creditCard()` |
| **Content** | `^title$` | `faker.book().title()` |
| | `^subject$`, `^summary$`, `^note$`, `^bio$` | lorem sentence |
| | `^body$`, `^description$`, `^content$` | lorem paragraph |
| | `comment` | lorem paragraph |
| **Locale** | `^locale$`, `^language$` | `faker.nation().isoLanguage()` |
| **Appearance** | `^color$`, `^colour$` | `faker.color().name()` |
| | `^colorHex$`, `^colourHex$` | `faker.color().hex()` |
| **Suffix patterns** | `*Year` | random year 1850–2024 |
| | `*(At\|Since\|Date\|Time)` | past `LocalDateTime` |
| | `*Copies` | small positive int |
| **Type fallbacks** | any `String` field | lorem word |
| | any int / long / short | random number |
| | any double / float / BigDecimal | random decimal |
| | any boolean | random bool |
| | any UUID field | random UUID |

**Skipped automatically:**
- `@Id` / identifier property (auto-generated by the DB)
- `@OneToMany` / `@ManyToMany` collections (use `:overrides {:genres [...]}` if needed)
- Any property present in `:overrides`

### Lookup-table detection (Genre problem)

Entities with a `unique=true` string column and no required `@ManyToOne` associations
are detected as reference/lookup tables (e.g. `Genre`). When `:auto-deps? true` encounters
one as a dependency, it fetches a random existing row instead of creating a new one —
preventing unique-constraint violations on seeded data.

### Throws

`faker/build-entity` throws a descriptive `ex-info` with `:missing-association` when a
required `@ManyToOne` is unresolved and `:auto-deps?` is false:

```clojure
(faker/build-entity "Book")
;; throws: "Cannot build Book: required association 'author' (→ Author) is not provided.
;;          Either:
;;           • pass it via :overrides {:author <instance>}
;;           • set :auto-deps? true to let Livewire resolve it automatically"
```

### CLI

```bash
# Build an Author (no required FKs)
lw-build-entity Author

# Build a Book, auto-resolving its Author dependency
lw-build-entity Book '{:auto-deps? true}'

# Build a Review, persist + rollback (speculative — nothing left in DB)
lw-build-entity Review '{:auto-deps? true :persist? true :rollback? true}'
```

---

### `faker/build-test-recipe` — extract assertable values from a faker entity graph

**Problem it solves:** after prototyping with `faker/build-entity`, the generated values
(names, ratings, ISBNs, dates) are transient. Without capturing them, you have to *invent*
equivalent values when writing the Java test — severing the connection between the validated
prototype and the written test. `build-test-recipe` captures them for you.

**Always run `lw-build-test-recipe` before writing any integration test setup code.**
Read the output and use those exact values in `@BeforeEach` and assertions.
The recipe already includes `:repo` (the Spring bean name) for every entity in the graph — **do not call `lw-all-repo-entities` afterwards**, it is redundant.

```clojure
(faker/build-test-recipe entity-name)
(faker/build-test-recipe entity-name opts)   ;; opts: :overrides only (auto-deps/persist/rollback are always true)
```

The entity graph is always built with `:auto-deps? true :persist? true :rollback? true` —
nothing is left in the database.

**Output format:** ordered map keyed by entity class name (root entity first, `@ManyToOne`
deps after in resolution order). Each value is a map of scalar fields only — `@Id` and
collection properties are excluded. Every field entry is `{:type "<java-type>" :value <val>}`.
`null` values are included (`:value nil`). Use `:type` to apply the correct Java cast when
writing setter calls.

```clojure
(faker/build-test-recipe "Review")
;; => {:Review {:repo "reviewRepository"
;;              :fields {:rating     {:type "short",         :value 5}
;;                       :comment    {:type "string",        :value "A remarkable journey..."}
;;                       :reviewedAt {:type "LocalDateTime", :value #object[LocalDateTime "2024-07-14T11:23:05"]}}}
;;     :Book   {:repo "bookRepository"
;;              :fields {:title           {:type "string",  :value "The Midnight Crisis"}
;;                       :isbn            {:type "string",  :value "978-3-16-148410-0"}
;;                       :publishedYear   {:type "short",   :value 1998}
;;                       :availableCopies {:type "short",   :value 3}
;;                       :archived        {:type "boolean", :value false}}}
;;     :Author {:repo "authorRepository"
;;              :fields {:firstName   {:type "string", :value "Kip"}
;;                       :lastName    {:type "string", :value "O'Reilly"}
;;                       :birthYear   {:type "short",  :value 1951}
;;                       :nationality {:type "string", :value "American"}}}
;;     :LibraryMember {:repo "libraryMemberRepository"
;;                     :fields {:username    {:type "string",    :value "kelsey.schaden"}
;;                              :fullName    {:type "string",    :value "Kelsey Schaden"}
;;                              :email       {:type "string",    :value "kelsey.schaden@example.com"}
;;                              :memberSince {:type "LocalDate", :value #object[LocalDate "2019-03-22"]}}}}
```

**Workflow:**

1. Run `lw-build-test-recipe Review` **in parallel with `lw-list-entities` and `lw-inspect-entity` for every entity in the graph** — all calls are independent:

   ```bash
   # Single parallel message — not sequential
   lw-build-test-recipe Review
   lw-list-entities
   lw-inspect-entity Review
   lw-inspect-entity Book    # repeat for each dep entity
   ```

   The recipe gives values, types, and repo names. `lw-list-entities` gives FQNs for the REPL prototype. `lw-inspect-entity` gives constraints (`@NotNull`, `@Size`, nullability) — needed to know which fields can be omitted vs which will blow up. All three are mandatory before writing the REPL prototype.

2. Write `@BeforeEach` using those exact values, applying the cast from `:type` where needed:
   `member.setFullName("Kelsey Schaden")`, `review.setRating((short) 5)`, `author.setBirthYear((short) 1951)`, etc.
   Use the `:repo` name directly in any REPL prototype calls: `(lw/bean "reviewRepository")`.
3. Write assertions using the same values:
   `assertThat(dto.reviewerName()).isEqualTo("Kelsey Schaden")`
4. Run the Clojure service prototype (`lw-build-entity` + REPL call) to validate the happy path.
5. Only then write the final Java test.

**Overrides flow through to the recipe:**

```clojure
;; Specific values for assertions — they appear in the output as supplied
(faker/build-test-recipe "Review" {:overrides {:rating 1 :comment "Terrible."}})
;; => {:Review {:repo "reviewRepository"
;;              :fields {:rating  {:type "short",  :value 1}
;;                       :comment {:type "string", :value "Terrible."}
;;                       ...}} ...}
```

**CLI:**

```bash
lw-build-test-recipe Review
lw-build-test-recipe Review '{:overrides {:rating 1 :comment "Terrible."}}'
```

---

## ⚠️ Known Pitfalls

### Inspect unfamiliar return types before using them — don't guess field names or collection types

When calling a controller or service method that returns a type you haven't worked with before,
**always inspect the result's structure first** before writing code that traverses it. Guessing
field names or assuming collection types leads to multiple failed attempts and wasted round-trips.

**Mandatory pattern for unfamiliar return types:**

```clojure
;; Step 1 — fetch once and inspect structure in a single call
(let [result (lw/run-as ["user" "ROLE_X"] (.someMethod (lw/bean "someController")))]
  {:result-type   (type result)
   :sample-field  (lw/bean->map result)              ; reveals actual field names
   :nested-type   (type (.getSomeCollection result))  ; check if List, Map, Set, etc.
   :sample-nested (lw/bean->map (first (.values (.getSomeCollection result))))})
```

This single call tells you:
- The actual Java type of the result (may be a DTO, record, or proxy)
- Exact field names (`.getDisplay` not `.getTitle`, etc.)
- Whether a collection is a `List`, `LinkedHashMap`, `Set`, etc. — which determines how to iterate it

**Step 2 — only then write the real query** using the confirmed field names and collection access pattern.

Common surprises:
- Collections returned as `LinkedHashMap` (keyed by ID) → iterate with `.values()`, not directly
- Field named `.display` or `.label` instead of the expected `.name` or `.title`
- Nested DTOs that are Java records → use `lw/bean->map`, not `clojure.core/bean`

Skipping the inspection step and guessing field names is the single most common cause of
unnecessary REPL round-trips when working with controller return values.

### New repository methods require a restart — the query-watcher cannot inject them

The query-watcher can only hot-swap JPQL strings in `@Query` methods that **already existed at startup**.
If you add a brand-new method to a Spring Data repository interface, compile it, and update the service
to call it, the running JVM will not pick up the change — for two reasons:

1. **The JVM classloader** won't reload an already-loaded interface. The new `.class` on disk is ignored;
   the old interface definition (without the new method) stays live in memory.
2. **The Spring Data proxy** was created at startup from the old interface. It has no knowledge of methods
   added after the fact, so calling the new method throws `NoSuchMethodError` or simply isn't reachable.

This is distinct from editing an existing `@Query` JPQL string, which the watcher handles transparently.

**Rule of thumb:** if `javap` shows the new method on disk but the REPL proxy doesn't have it, this is
the cause. A restart is the correct fix — not cache flushing or `force-rescan!`.

```bash
# Confirm the compiled artifact is correct
javap -p target/classes/com/example/yourapp/repository/YourRepository.class

# Confirm the proxy is missing the method
clj-nrepl-eval -p 7888 '(->> (.getClass (lw/bean "yourRepository")) .getMethods (map #(.getName %)) sort)'
# If the new method is absent here but present in javap output → restart required
```

### `lw-eval` mangles Clojure-idiomatic characters — use `clj-nrepl-eval` directly

`lw-eval` passes its argument through the shell, where characters common in Clojure
symbol names are misinterpreted:

| Character | Clojure use | Shell hazard |
|---|---|---|
| `!` | Mutating fns: `reset-all!`, `hot-swap-query!`, `swap!` | zsh history expansion |
| `?` | Predicates: `running?`, `empty?` | Glob / conditional |
| `->`, `->>` | Threading macros | Redirect / `>>` append |

**Rule: never use `lw-eval` for expressions containing these characters.** Use
`clj-nrepl-eval -p <port>` directly with a double-quoted string instead — the
expression reaches the JVM unmodified:

```bash
# ❌ lw-eval silently drops the ! — "No such var: hq/reset-all"
lw-eval '(hq/reset-all!)'

# ✅ clj-nrepl-eval passes the expression verbatim
clj-nrepl-eval -p 7888 "(hq/reset-all!)"
```

Use the dedicated wrapper scripts (`lw-sql`, `lw-jpa-query`, `lw-call-endpoint`, etc.)
for their named operations. For everything else, go straight to `clj-nrepl-eval`.

### Wrapper script string arguments inherit the same shell hazards — avoid `!` in string values

All wrapper scripts (`lw-build-entity`, `lw-build-test-recipe`, `lw-call-endpoint`, etc.) pass
their arguments through the shell. **Any `!` inside a string literal in an EDN opts map will be
treated as a zsh history expansion**, causing it to be mangled before Clojure ever sees it.

This applies to `:overrides` comment strings or any other string value — not just Clojure symbol
names:

```bash
# ❌ ! in string value triggers zsh history expansion
lw-build-test-recipe Review '{:overrides {:comment "Absolutely wonderful!"}}'
# Error: Unsupported escape character: \!

# ✅ simply avoid ! in string values passed via wrapper scripts
lw-build-test-recipe Review '{:overrides {:comment "Absolutely wonderful"}}'

# ✅ or use clj-nrepl-eval directly with a double-quoted outer string
clj-nrepl-eval -p 7888 "(faker/build-test-recipe \"Review\" {:overrides {:comment \"Absolutely wonderful!\"}})"
```

**Rule: when an override string needs `!`, switch to `clj-nrepl-eval` directly.**

### `intro/list-entities` uses `:name` and `:class`, not `:simple-name`
The docstring mentions "simple name + FQN" but the actual map keys are `:name` and `:class`.
Using `:simple-name` returns nil for every entry and causes a NullPointerException in regex filters.

```clojure
;; ❌ NullPointerException
(->> (intro/list-entities) (map :simple-name) (filter #(re-find #"Foo" %)))

;; ✅ correct
(->> (intro/list-entities) (map :name) (filter some?) (filter #(re-find #"Foo" %)))
```

### Never call `.findAll` without a `Pageable` — it may return millions of rows
`.findAll()` on a `JpaRepository` has no built-in limit and will eagerly load every row in
the table. On large production-like datasets this will hang the REPL and potentially OOM the JVM.
Always pass a `PageRequest` to cap results, or use a more specific query method.

```clojure
;; ❌ danger: fetches every row in the table
(.findAll (lw/bean "bookRepository"))

;; ✅ cap at 20 rows using Pageable
(->> (.findAll (lw/bean "bookRepository")
               (org.springframework.data.domain.PageRequest/of 0 20))
     .getContent
     (mapv #(select-keys (clojure.core/bean %) [:id :title :isbn])))

;; ✅ or use a native/JPQL query with an explicit limit
(lw/in-readonly-tx (q/sql "SELECT id, title FROM book LIMIT 20"))
```

### `lw/bean SomeClass` only resolves Spring beans, not JPA entities
JPA entity classes are not Spring beans. Passing an entity class throws `NoSuchBeanDefinitionException`.
Use `lw/bean "repositoryBeanName"` to access data — find the right name with `lw/find-beans-matching`.

```clojure
;; ❌ NoSuchBeanDefinitionException
(lw/bean eu.example.MyEntity)

;; ✅ find the repository instead
(lw/find-beans-matching ".*MyEntity.*[Rr]epo.*")
(lw/bean "myEntityRepository")
```

### `EntityManager` is not registered as a named bean — use type-based lookup

`EntityManager` is a scoped proxy tied to the current persistence context. Spring does **not** register it under the name `"entityManager"`, so `(lw/bean "entityManager")` throws `NoSuchBeanDefinitionException`. Use the type-based form instead, which resolves the shared proxy bean (`jpaSharedEM_entityManagerFactory`) correctly:

```clojure
;; ❌ NoSuchBeanDefinitionException — "entityManager" is not a registered bean name
(lw/bean "entityManager")

;; ✅ type-based lookup resolves the shared proxy
(let [em (lw/bean jakarta.persistence.EntityManager)]
  (lw/in-tx
    ;; ... persist entities ...
    (.flush em)
    (.clear em)))
```

This pattern is needed in REPL test prototyping when you need to flush and clear the L1 cache between setup and the service call under test.

### `lw-call-endpoint` and `lw/bean` expect the Spring bean name, not the class name

`intro/list-endpoints` reports `:controller` as the **fully-qualified class name** (e.g.
`"com.example.myapp.web.AdminController"`). The Spring bean name is **not** the simple class
name — it is the camelCase version with a lowercase first letter (e.g. `adminController`).
Passing the class simple name directly to `lw-call-endpoint` or `lw/bean` throws
`NoSuchBeanDefinitionException`.

**Rule:** when calling an endpoint discovered via `list-endpoints`, always derive the bean
name by lowercasing the first letter of the simple class name, or verify with
`lw/find-beans-matching` before calling.

```bash
# ❌ NoSuchBeanDefinitionException — AdminController is the class name, not the bean name
lw-call-endpoint AdminController archiveBook ROLE_ADMIN 1

# ✅ correct — lowercase first letter
lw-call-endpoint adminController archiveBook ROLE_ADMIN 1

# ✅ or verify the exact bean name first
clj-nrepl-eval -p 7888 "(lw/find-beans-matching \".*[Aa]dmin.*\")"
# => ("adminController" ...)
lw-call-endpoint adminController archiveBook ROLE_ADMIN 1
```

### `lw-call-endpoint` cannot pass UUID arguments — use `clj-nrepl-eval` directly

`lw-call-endpoint` passes positional args through the shell as Clojure expressions. UUID strings
like `c97f032f-8e52-4084-9716-1b4ac7295dcc` are **not valid Clojure literals** — they fail with
"Unable to resolve symbol". Quoting them as strings doesn't help either, because the Java method
expects `java.util.UUID`, not `String` — resulting in "No matching method taking N args".

**Rule:** whenever a controller method takes a `UUID` parameter, drop to `clj-nrepl-eval` directly
and wrap the ID string with `java.util.UUID/fromString`:

```bash
# ❌ bare UUID — treated as unresolvable Clojure symbol
lw-call-endpoint myController myMethod ROLE_ADMIN c97f032f-8e52-4084-9716-1b4ac7295dcc

# ❌ quoted string — Java method expects UUID, not String → "No matching method"
lw-call-endpoint myController myMethod ROLE_ADMIN '"c97f032f-8e52-4084-9716-1b4ac7295dcc"'

# ✅ clj-nrepl-eval with explicit UUID conversion
clj-nrepl-eval -p 7888 "(lw/run-as [\"admin\" \"ROLE_ADMIN\"] (.myMethod (lw/bean \"myController\") (java.util.UUID/fromString \"c97f032f-8e52-4084-9716-1b4ac7295dcc\")))"
```

### `lw-call-endpoint` cannot handle optional (nullable) parameters

`lw-call-endpoint` only accepts positional trailing args and has no syntax for passing `nil`.
If the Java/Kotlin method has optional parameters (e.g. `consultantId: UUID?`), passing fewer
args than the method arity causes "No matching method taking N args" — even when the remaining
params are nullable.

**Rule:** whenever a method has optional/nullable parameters, use `clj-nrepl-eval` and pass
`nil` explicitly for each omitted optional arg:

```bash
# ❌ only 1 arg, but method has 2 params (second is nullable UUID) → "No matching method taking 1 args"
lw-call-endpoint myController myMethod ROLE_ADMIN '"some-id"'

# ✅ explicit nil for the optional second param
clj-nrepl-eval -p 7888 "(lw/run-as [\"admin\" \"ROLE_ADMIN\"] (.myMethod (lw/bean \"myController\") (java.util.UUID/fromString \"c97f032f-8e52-4084-9716-1b4ac7295dcc\") nil))"
```

**Tip:** always read the controller method signature before calling — check parameter count and
types. If any param is a `UUID` or nullable (`?` in Kotlin), reach for `clj-nrepl-eval` instead
of `lw-call-endpoint`.

### `clojure.core/bean` silently returns `{}` for Java records

Java records (DTOs, projections) generate accessor methods without the `get` prefix —
`rating()`, `comment()`, etc. `clojure.core/bean` only sees `getX()` methods, so it returns
an empty map `{}` with no error.

**Always use `lw/bean->map` instead of `clojure.core/bean`** when converting any result object
to a map — it dispatches to `getRecordComponents()` for records and falls back to
`clojure.core/bean` for everything else:

```clojure
;; ❌ silent empty map for a Java record DTO
(clojure.core/bean some-stats-dto)
;; => {:class com.example.StatsDto}   ← only :class, all fields missing

;; ✅ correct — full field map regardless of record vs plain class
(lw/bean->map some-stats-dto)
;; => {:totalBooks 200, :totalAuthors 30, :totalMembers 50, ...}
```

### `clojure.core/bean` on a Spring proxy exposes proxy internals, not domain properties
Controllers and services are CGLIB proxies. Calling `(clojure.core/bean proxy)` returns proxy metadata
(`:advisors`, `:callbacks`, `:frozen`, etc.), not the bean's own fields or properties.
To discover what methods to call on a bean, read the source code instead.

### `@PreAuthorize` on controllers blocks direct REPL invocation
Calling a controller or service method directly from the REPL throws
`AuthenticationCredentialsNotFoundException` because there is no Spring Security context.
Use `lw/run-as` to set one, or bypass to the underlying service if security is not relevant.

```clojure
;; ❌ AuthenticationCredentialsNotFoundException
(.myEndpoint (lw/bean "myController") someArg)

;; ✅ preferred: use run-as to exercise the real secured code path
(lw/run-as "admin"
  (.myEndpoint (lw/bean "myController") someArg))

;; ✅ alternative: call the service the controller delegates to (skips security entirely)
(.myServiceMethod (lw/bean "myService") someArg)
```

### Use `jakarta.persistence` not `javax.persistence` on Spring Boot 3+

Spring Boot 3 migrated to Jakarta EE. When importing `EntityManager` or other JPA types
directly in REPL expressions, use the `jakarta.persistence` package.

```clojure
;; ❌ ClassNotFoundException on Spring Boot 3+
(import 'javax.persistence.EntityManager)

;; ✅ correct
(import 'jakarta.persistence.EntityManager)
(lw/bean EntityManager)
```

### `q/sql` is query-only — use raw JDBC for DDL

`q/sql` executes via `executeQuery` and expects a result set. DDL statements (`CREATE INDEX`,
`ALTER TABLE`, etc.) return no rows and throw `PSQLException: No results were returned by the query`.
Use a raw JDBC connection instead.

```clojure
;; ❌ PSQLException: No results were returned by the query
(lw/in-tx (q/sql "CREATE INDEX idx_foo ON bar(baz)"))

;; ✅ raw JDBC for DDL
(let [ds (lw/bean javax.sql.DataSource)]
  (with-open [conn (.getConnection ds)]
    (-> conn .createStatement (.execute "CREATE INDEX idx_foo ON bar(baz)"))))
```

### First call after restart inflates timing — always warm up before measuring

The JVM JIT hasn't compiled anything on a fresh restart. The first call can be 5–10× slower
than steady-state. Always run several iterations before drawing conclusions about performance.

```clojure
;; ❌ misleading — first call may show 98ms due to cold JIT
(:duration-ms (trace/trace-sql (lw/run-as "admin" (.myEndpoint (lw/bean "myController")))))

;; ✅ warm up first, then measure
(mapv (fn [_] (:duration-ms (trace/trace-sql (lw/run-as "admin" (.myEndpoint (lw/bean "myController"))))))
      (range 5))
;; => [98 25 15 12 12]  ← ignore the first result
```

### `trace/trace-sql` requires Livewire ≥ 0.1.0-SNAPSHOT (post Hibernate 7 fix)
SQL tracing works with both Hibernate 6 and 7. Older Livewire builds silently registered
no `StatementInspector` on Hibernate 7 apps, resulting in `{:count 0, :queries []}`.
Ensure the app is running with a current Livewire JAR.

---

## Examples

```clojure
;; Full env + datasource summary — always run this first
(lw/info)
;; => {:application-name "bloated-shelf", :active-profiles ["dev" "seed"],
;;     :java-version "25.0.1", :spring-boot-version "4.0.1",
;;     :hibernate-version "7.2.0.Final",
;;     :datasource {:db-product "PostgreSQL 16.9",
;;                  :jdbc-url "jdbc:postgresql://localhost:32769/test?loggerLevel=OFF",
;;                  :db-user "test", :driver "PostgreSQL JDBC Driver 42.7.8",
;;                  :pool-name "HikariPool-1", :pool-size-max 10}}

;; JPA configuration — always present, includes open-in-view, show-sql, etc.
(lw/props-matching "spring\\.jpa.*")
;; => {"spring.jpa.open-in-view" "false", "spring.jpa.show-sql" "false", ...}

;; Note: spring.datasource.url is dynamically bound on Testcontainers-based apps
;; and will not appear in props-matching — use spring.jpa.* for reliable inspection

;; Which repository beans exist?
(lw/find-beans-matching ".*Repository.*")
;; => ("bookRepository" "authorRepository" ...)

;; Query a repository safely — always page or limit (see pitfalls)
(lw/in-readonly-tx
  (->> (.findAll (lw/bean "bookRepository")
                 (org.springframework.data.domain.PageRequest/of 0 3))
       .getContent
       (mapv #(select-keys (clojure.core/bean %) [:id :title :isbn]))))
;; => [{:id 1, :title "All the King's Men", :isbn "..."}
;;     {:id 2, :title "A Handful of Dust", :isbn "..."}
;;     {:id 3, :title "Butter In a Lordly Dish", :isbn "..."}]

;; Mutate safely — rolls back automatically
;; (count here is intentional — we want the total, not a page of rows)
(lw/in-tx
  (.save (lw/bean "bookRepository") (->Book "New Title"))
  (.count (lw/bean "bookRepository")))

;; Capture the SQL a service method fires
(trace/trace-sql
  (lw/in-readonly-tx
    (.count (lw/bean "bookRepository"))))
;; => {:result 200, :queries [{:sql "select count(*) ...", :caller "..."}], :count 1, :duration-ms 15}

;; Hunt for N+1 queries
(trace/detect-n+1
  (trace/trace-sql
    (lw/run-as "member1"
      (.getBooks (lw/bean "bookController")))))
;; => {:suspicious-queries [{:sql "select ...", :caller "...", :count 200} ...],
;;     :total-queries 481, :duration-ms 1271}

;; Discover all HTTP endpoints
(first (intro/list-endpoints))
;; => {:methods ["GET"], :paths ["/api/authors/{id}"], :controller "AuthorController", ...}

;; Inspect a Hibernate entity's DB mappings
(intro/inspect-entity "Book")
;; => {:table-name "book", :identifier {:name "id", :columns ["id"], :type "long"},
;;     :properties [{:name "title", :columns ["title"], :type "string"} ...],
;;     :relations [{:name "author", :type :many-to-one, :target "Author"} ...]}

;; Call a @PreAuthorize-guarded controller method directly
(lw/run-as "admin"
  (.getBookById (lw/bean "bookController") 25))
;; => #object[BookDto ...]

;; Live-swap a JPQL query, verify with trace-sql, then restore
(hq/hot-swap-query! "bookRepository" "findByIdWithDetails"
  "select b from Book b join fetch b.author join fetch b.reviews where b.id = :id")
(trace/trace-sql
  (lw/run-as "admin"
    (lw/in-readonly-tx
      (.findByIdWithDetails (lw/bean "bookRepository") 25))))
;; => {:result [...], :queries [{:sql "select ... join addresses ..."}], :count 1, :duration-ms 8}
(hq/reset-query! "bookRepository" "findByIdWithDetails")
;; => :restored
```


---

## Call Graph API — `net.brdloush.livewire.callgraph`

### `cg/blast-radius` — method-level call graph and entry-point impact analysis

Given a bean name and a method name, `blast-radius` walks the bean dependency graph
upward (from dependency toward dependents), intersects with a bytecode-level call
graph extracted at runtime via ASM, and returns the set of bean methods that
transitively invoke the target — annotated with their distance from the target and,
for entry-point beans, their observable surface.

**Use this before modifying any repository, service method, or query** — to know the
full impact before acting, not after.

```clojure
(cg/blast-radius bean-name method-name)
(cg/blast-radius bean-name method-name :app-only true)   ; default
(cg/blast-radius bean-name method-name :app-only false)  ; include Spring infra beans
```

**Arguments:**
- `bean-name` — Spring bean name string (same as accepted by `lw/bean`)
- `method-name` — simple method name string; if overloaded, all overloads are included and a warning is emitted

**Returns:**
```clojure
{:target   {:bean   "bookRepository"
            :method "findAllWithAuthorAndGenres"}

 :affected [{:bean    "bookService"
             :method  "getAllBooks"
             :depth   1
             :entry-point nil}

            {:bean    "bookController"
             :method  "getBooks"
             :depth   2
             :entry-point {:type          :http-endpoint
                           :paths         ["/api/books"]
                           :http-methods  ["GET"]
                           :pre-authorize "hasRole('MEMBER')"}}

            {:bean    "bookStatsReporter"
             :method  "reportNightlyStats"
             :depth   2
             :entry-point {:type :scheduler
                           :cron "0 0 2 * * *"}}]

 :warnings ["Method name 'findAll' matched multiple signatures — all overloads are included"]}
```

- `:depth` — hop count from the target bean. Depth 1 = direct caller.
- `:entry-point` — present for HTTP endpoints (`@RequestMapping`), `@Scheduled` methods, and `@EventListener` methods. `nil` for internal service beans.
- `:warnings` — notes about analysis gaps (overloads, event-listener limitations, etc.)

**Cache management:**

The bytecode call-graph index is built once and cached. After hot-patching a class
or recompiling sources during a session, clear it:

```clojure
(cg/reset-blast-radius-cache!)
```

**Examples:**

```clojure
;; Which HTTP endpoints call bookRepository/findAll, directly or via services?
(cg/blast-radius "bookRepository" "findAll")

;; What is affected if I change bookService/archiveBook?
(cg/blast-radius "bookService" "archiveBook")

;; Include Spring infrastructure beans in the analysis
(cg/blast-radius "bookRepository" "findAll" :app-only false)
```

**Wildcard `"*"` — full inbound call graph for all methods at once**

Pass `"*"` as the method name to run blast-radius for every method on the bean and get a
unified, deduplicated result. All four indexes (HTTP endpoints, scheduler, event listeners,
call graph) are built once and reused across all methods — so the total time is roughly
the same as a single call, not N × single call.

```clojure
;; Unified inbound call graph for the entire bean — deduplicates by [bean method]
(cg/blast-radius "bookService" "*")
;; => {:target {:bean "bookService" :method "*"}
;;     :affected [{:bean "bookController" :method "getBooks" :depth 1
;;                 :entry-point {:type :http-endpoint :paths ["/api/books"] ...}}
;;                {:bean "bookController" :method "getBookById" :depth 1
;;                 :entry-point {:type :http-endpoint :paths ["/api/books/{id}"] ...}}
;;                ...]
;;     :warnings [...]}
```

Use `"*"` when you need to understand the full surface of a bean — e.g. before a major
refactor, or to confirm that every public method is reachable via HTTP and none are orphaned.

### CLI: `lw-blast-radius` / `lw-blast-radius-all`

```bash
# Single method — who calls bookService.archiveBook?
lw-blast-radius bookService archiveBook

# All methods flat — full inbound call graph for the bean
lw-blast-radius bookService '*'

# Per-method map — {method → {:callers [...]}} for every method
# Methods with empty :callers are dead-code candidates
lw-blast-radius-all bookService
```

### ⚠️ Known limitations

- **`ApplicationEventPublisher` calls are invisible.** If bean A calls the target method
  in response to a Spring event published by bean B, the path B → A will appear but the
  event publication from B will not be traced further up.
- **`@Async` wrappers.** The direct call site is found in bytecode, but the initiating
  caller of the async method may be in a different thread and not visible.
- **Reflection and lambdas.** `Method.invoke()` and lambda-based dispatch are not traceable.
- **Depth > 1 is best-effort.** At depth 1 the method-level match is precise (bytecode
  confirms the call). At depth 2+, the BFS knows bean B depends on bean A which depends
  on the target — but does not re-verify that B.methodX specifically calls A.methodY.
  Entry-point annotation at depth 2+ (via `intro/list-endpoints`) is accurate for HTTP
  handlers because the handler method is matched directly; for intermediate service hops
  the method name is the best-guess caller.
- **Overloads.** If the target method name matches multiple overloads, all are included
  and a warning is emitted. Disambiguation requires a full JVM method descriptor.
- **Kotlin default-parameter calls are resolved.** When a Kotlin function has default
  parameters, call sites use a synthetic `method$default` wrapper. blast-radius checks
  both `method` and `method$default` in the call-graph index, so callers using default
  argument values are not missed.
- **`@Scheduled` index absent when `@EnableScheduling` is not active.** When the scheduler
  is not wired up (e.g. disabled in dev profiles), blast-radius prints a one-time diagnostic
  explaining why — including the active profile list — and omits scheduler entry points from
  results. The message is printed at most once per JVM session.

---

### `cg/method-dep-map` — method-level dependency fingerprinting

`lw/all-bean-deps` tells you *how many* dependencies a bean has. `method-dep-map` tells you
*which ones each method actually uses* — the missing link for identifying split boundaries
in a bloated bean.

```clojure
(cg/method-dep-map bean-name)
(cg/method-dep-map bean-name :expand-private? true)
(cg/method-dep-map bean-name :intra-calls? true)
```

**Returns:**
```clojure
{:bean             "adminService"
 :class            "com.example.AdminService"
 :methods          [{:method        "getSystemStats"
                     :deps          ["authorRepository" "bookRepository"
                                     "libraryMemberRepository" "reviewRepository"
                                     "loanRecordRepository"]
                     :orchestrator? false}
                    {:method        "getTop10MostLoanedBooks"
                     :deps          ["bookRepository"]
                     :orchestrator? false}]
 :dep-frequency    [{:dep "bookRepository" :used-by-count 2 :methods ["getSystemStats" "getTop10MostLoanedBooks"]}
                    {:dep "authorRepository" :used-by-count 1 :methods ["getSystemStats"]}
                    ...]
 :unaccounted-deps []}
```

- **`:deps`** — the bean's injected dependencies that this method directly touches in bytecode,
  resolved by field type → `ApplicationContext` lookup.
- **`:orchestrator?`** — `true` when the method's dep-set is a superset of ≥ 2 other methods'
  dep-sets. These methods sequence sub-operations rather than performing a single concern; they
  are poor candidates for assignment to any one extracted service.
- **`:dep-frequency`** — all injected dependencies ranked by how many distinct methods use them,
  descending. High-count deps are load-bearing (every extracted service will need them, or they
  belong in a shared facade). Count-1 deps are prime extraction candidates — they can move with
  their single method without splitting anything.
- **`:unaccounted-deps`** — injected beans not referenced in any public method. Possible dead
  injections, or deps used only in `@PostConstruct` / field initializers.

**`:callers?` option (default `false`)**

When `true`, adds `:intra-callers` to each method entry — the siblings that call IT (the
inverse of `:intra-calls?`). Reveals which methods are only called internally, making them
visibility-leak candidates rather than true dead code. `dead-methods` uses this data
automatically to populate `:internal-only`.

```clojure
(cg/method-dep-map "bookService" :callers? true)
;; each method entry gains :intra-callers showing who calls it within the same bean
```

**`:intra-calls?` option (default `false`)**

When `true`, adds an `:intra-calls` key to each method entry listing the sibling
methods on the same bean it directly calls. Self-calls, `$default` synthetics,
and constructors are excluded. Essential for refactoring splits: if method A calls
method B internally, moving A without B will break the service.

```clojure
(cg/method-dep-map "bookService" :intra-calls? true)
;; :methods contains entries like:
;; {:method "getAllBooks"
;;  :deps   ["bookRepository"]
;;  :intra-calls []
;;  :orchestrator? false}
;;
;; A larger service would show e.g.:
;; {:method "createBook"
;;  :intra-calls ["validateIsbn" "notifySubscribers" "updateIndex"]
;;  :orchestrator? true}
```

Use `:intra-calls?` alongside `:dep-frequency` when planning a split: methods with
no intra-calls and a count-1 dep are the cleanest candidates to extract first.

**`:expand-private?` option (default `false`)**

When `true`, private and package-private helper methods are suppressed from `:methods` and their
field accesses are folded into the public methods that call them (one level of inlining). This
surfaces the *real* dep footprint of each public method rather than showing helpers as independent
entries.

```clojure
;; Without expand (default) — each method shows only its directly accessed fields
(cg/method-dep-map "bookService")
;; :methods contains e.g. getAllBooks with deps [bookRepository]
;; AND any private helpers listed as independent entries

;; With expand — private helper deps are folded into the public callers that call them
(cg/method-dep-map "bookService" :expand-private? true)
;; private helper methods are suppressed from :methods;
;; their dep accesses are attributed to the public method that calls them
```

**When to use it:**

- An agent has spotted a bean with many injected dependencies via `lw/all-bean-deps`. Use
  `method-dep-map` to see how those deps distribute across methods — natural clusters suggest
  extractable services.
- Use `:dep-frequency` to distinguish load-bearing deps (used by many methods) from extraction
  candidates (used by only 1 method). Deps with `used-by-count 1` can move with their method
  cleanly.
- Before a refactor: use `:expand-private? true` to confirm the real dep footprint of each
  public method, including what its private helpers need.
- To surface orchestrator methods that should stay in a thin facade while the rest is split out.

**Known limitations:**
- Reflection and lambda captures are invisible to the scanner.
- `@PostConstruct` and field initializer accesses go into `:unaccounted-deps`.
- Without `:expand-private?`, private helper methods appear as independent entries; their field
  accesses are not attributed to their public callers.
- `:expand-private?` expands one level of private calls only. Chains of private helpers calling
  other private helpers are not recursed further.

### CLI: `lw-method-dep-map`

```bash
lw-method-dep-map adminService
lw-method-dep-map bookService
```

---

### `cg/dead-methods` — unreachable public method detection

Returns the public methods on a bean that have no callers in the application — no HTTP endpoint,
scheduler, or event listener transitively invokes them. These are candidates for removal,
dead-code investigation, or evidence of missing wiring.

```clojure
(cg/dead-methods bean-name)
```

**Returns:**
```clojure
{:bean                "bookService"
 :dead                []                   ; true dead code — no callers anywhere
 :internal-only       [{:method "archiveBook" :intra-callers ["someOrchestrator"]} ...]
 :reachable-count     4
 :dead-count          0
 :internal-only-count 1
 :warnings            ["2 @EventListener bean methods detected — ..."]}
```

**Two distinct result categories:**

- **`:dead`** — public methods with no external callers AND no intra-class callers. Nothing calls these, inside or outside the bean. Primary candidates for deletion.
- **`:internal-only`** — public methods with no external callers but called by at least one sibling method. Public by accident (testability, Kotlin default visibility). These are **refactoring candidates**, not deletion candidates — they reveal that a method's public visibility exceeds its actual call surface.

**Only public methods (JVM ACC_PUBLIC) are analysed.** Private and package-private methods are excluded regardless of whether they are called.

⚠️ **False-positive caveat.** Methods invoked only via `ApplicationEventPublisher`, NATS,
Kafka, or other messaging infrastructure will appear in `:dead` or `:internal-only` even
though they are actively used. The `:warnings` key automatically lists any detected messaging
beans and db-scheduler tasks — cross-reference before acting.

The scheduler index is built by static `@Scheduled` annotation scanning (no `@EnableScheduling`
required), so scheduled entry points are correctly detected even in local dev profiles where
the scheduler is typically disabled.

### CLI: `lw-dead-methods`

```bash
lw-dead-methods bookService
lw-dead-methods adminService
```
