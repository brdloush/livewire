# Livewire Core API Reference

Read this file when working with beans, transactions, security contexts, entity/endpoint
introspection, SQL tracing, JPQL queries, mutation observation, or hot-swapping `@Query`
methods.

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
;; Always use the vector form so the required role is explicit
(lw/run-as ["user" "ROLE_MEMBER"]
  (.getBookById (lw/bean "bookController") 25))

;; Combine with in-readonly-tx for repository access under a security context
(lw/run-as ["user" "ROLE_MEMBER"]
  (lw/in-readonly-tx
    (->> (.findAll (lw/bean "bookRepository")
                   (org.springframework.data.domain.PageRequest/of 0 3))
         .getContent
         (mapv #(select-keys (clojure.core/bean %) [:id :title :isbn])))))
```

#### ⚠️ Plain string form only grants `ROLE_USER` + `ROLE_ADMIN`

If an endpoint requires a custom role (e.g. `ROLE_MEMBER`, `ROLE_VIEWER`, `ROLE_LIBRARIAN`),
the plain string form will fail with `AuthorizationDeniedException`. **Always use the vector
form for non-admin roles.**

```clojure
;; ❌ fails with AuthorizationDeniedException if endpoint requires ROLE_VIEWER
(lw/run-as "viewer" (.getAuthors (lw/bean "authorController")))

;; ✅ explicitly specify the required role
(lw/run-as ["viewer" "ROLE_VIEWER"] (.getAuthors (lw/bean "authorController")))
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
you need the full domain model. It returns all entities in a single REPL round-trip.

**When presenting entity listings, always include:** `:name`, `:class`, `:table-name`, and
`:constraints` (e.g. `["@NotNull" "@Size(min=0,max=100)"]`) so the user knows validation
in play before writing queries or test data.

**Cap large entity listings at 25 rows** — state both shown and total, never silently truncate.

```clojure
;; List all entities, then inspect one
(->> (intro/list-entities) (map :name) (filter #(re-find #"Book" %)))
;; => ("Book")

(intro/inspect-entity "Book")
;; => {:table-name "book",
;;     :identifier {:name "id", :columns ["id"], :type "long"},
;;     :properties [{:name "title", :columns ["title"], :type "string"} ...],
;;     :relations [{:name "author", :type :many-to-one, :target "Author"} ...]}
```

### Calling controller methods from the REPL

#### Read the source method body before fetching sample data

**Before querying for sample IDs or constructing any call, read the method implementation.**
A parameter named `memberId: UUID` may map to `member.externalRef`, not `LibraryMember.id`.
The method body always reveals the actual lookup.

**Mandatory sequence:**
1. List method signatures (learn parameter types and names)
2. **Read the source** — find what lookup the method performs for each parameter
3. **Inspect the entity** — run `lw-inspect-entity` (or `lw-list-entities` first if the entity
   name is unknown) to confirm the exact entity name, field names, and table structure before
   writing any JPQL. Never guess entity or field names — `Genre` might be `BookGenre`,
   `id` might be `genreCode`. One inspect call prevents multiple failed query attempts.
4. Only then write a JPQL query targeting the confirmed entity and field names
5. Call the method with valid IDs

```bash
# ❌ guessing entity/field names directly
lw-jpa-query 'SELECT g.id, g.name FROM Genre g'

# ✅ inspect first — confirm entity name, id field, and any relevant columns
lw-inspect-entity Genre
# => {:table-name "genre", :identifier {:name "id"}, :properties [{:name "name"} ...]}
# Now write the query with confirmed names:
lw-jpa-query 'SELECT g.id, g.name FROM Genre g'
```

#### ⚠️ Never invoke mutating endpoints without explicit user instruction

Only call **read-only (`GET`) endpoints** on your own initiative. For any mutating endpoint
(`POST`, `PUT`, `PATCH`, `DELETE`), you must not call it without explicit user instruction or
confirmed permission.

When an endpoint has a `:pre-authorize` value, **always wrap the call in `lw/run-as`**.

```clojure
;; Discover what auth an endpoint requires
(->> (intro/list-endpoints)
     (filter #(re-find #"books" (str (:paths %))))
     (mapv #(select-keys % [:paths :handler-method :pre-authorize :required-roles :required-authorities])))

;; Call it using :required-roles directly — always include a username as the first element
(lw/run-as ["user" "ROLE_MEMBER"]
  (.getBooks (lw/bean "bookController")))
```

**When presenting `list-endpoints` results, always include:** HTTP method(s), path(s),
controller class, handler method name, `:pre-authorize` (or "none"), `:required-roles` /
`:required-authorities`, and per-parameter `:name`, `:type`, `:source`, `:required`,
`:default-value`.

#### CLI shortcut: `lw-call-endpoint`

```bash
lw-call-endpoint bookController getBooks ROLE_MEMBER
lw-call-endpoint --limit 5 bookController getBooks ROLE_MEMBER
lw-call-endpoint bookController getBookById ROLE_MEMBER 25
lw-call-endpoint bookController searchBooks ROLE_MEMBER '"spring"'
```

> ⚠️ **The role argument must include the `ROLE_` prefix.** `lw-list-endpoints` reports
> `:required-roles` as bare names (e.g. `"MEMBER"`), but Spring stores authorities with the
> prefix — passing `MEMBER` instead of `ROLE_MEMBER` causes `AuthorizationDeniedException`.

**Always report** `:returned` / `:total`, `:content-size`, and `:content-size-gzip` to the
user when a limited list is returned. Always render the returned items as a markdown table.

If the output file is too large for the Read tool, re-call with `--limit 1`.

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

**Prefer this over raw `q/sql`** when you want to work at the JPA/entity level.

| Expression | What it does |
|---|---|
| `(jpa/jpa-query jpql)` | Run JPQL, return first 10 results as entity maps |
| `(jpa/jpa-query jpql :page 1 :page-size 5)` | Paginate — offset = `page × page-size` |

### Serialization behaviour

| Scenario | Result |
|---|---|
| Scalar property | Value as-is |
| Temporal type (`LocalDate`, etc.) | `#object[LocalDate ...]` (not coerced) |
| Eagerly fetched `@ManyToOne` | Recursed into (full nested map) |
| Uninitialized lazy collection | `"<lazy>"` |
| Ancestor-chain cycle | `"<circular>"` |
| Non-entity Java object | `(.toString obj)` |

### Examples

```clojure
;; Basic — lazy associations render as "<lazy>"
(jpa/jpa-query "SELECT b FROM Book b ORDER BY b.id")

;; JOIN FETCH to eagerly load genres
(jpa/jpa-query "SELECT DISTINCT b FROM Book b LEFT JOIN FETCH b.genres ORDER BY b.id"
               :page 0 :page-size 5)

;; Paginate — page 2 of 10 (rows 20–29)
(jpa/jpa-query "SELECT b FROM Book b ORDER BY b.id" :page 2 :page-size 10)

;; Combine with trace-sql
(trace/trace-sql
  (jpa/jpa-query "SELECT DISTINCT b FROM Book b LEFT JOIN FETCH b.genres"))
;; => {:result [...], :count 1, :duration-ms 12, :queries [{:sql "select ..."}]}
```

### CLI: `lw-jpa-query`

```bash
lw-jpa-query 'SELECT b FROM Book b ORDER BY b.id'
lw-jpa-query 'SELECT DISTINCT b FROM Book b LEFT JOIN FETCH b.genres' 0 5
lw-jpa-query 'SELECT b FROM Book b ORDER BY b.id' 2 10
```

### Scalar projections

**Prefer `AS` aliases** — they produce named keys directly:

```clojure
(jpa/jpa-query
  "SELECT b.title AS title, COUNT(lr) AS loans
   FROM Book b JOIN b.loanRecords lr
   GROUP BY b.id, b.title ORDER BY COUNT(lr) DESC"
  :page 0 :page-size 5)
;; => [{:title "The Green Bay Tree", :loans 7} ...]
```

> ⚠️ **Hibernate 7 lowercases `AS` alias names.** `AS avgRating` → `:avgrating`. Always use
> lowercase alias names.

Without aliases, `Object[]` rows become maps with positional keys `:col0`, `:col1`, etc.

---

## Mutation Observer API — `net.brdloush.livewire.query`

`q/diff-entity` answers *"what did this service call actually write to the database?"* —
the observability gap `trace/trace-sql` leaves open.

| Expression | What it does |
|---|---|
| `(q/diff-entity entity-class id thunk)` | Snapshot entity before and after `thunk`, return `{:before … :after … :changed {key [old new]}}` |

The thunk always runs inside `lw/in-tx` — **the change is always rolled back**.

### When to use it

- **Exploration** — "what fields does `.archiveBook` actually touch?"
- **Debugging** — "why is entity 42 in this unexpected state?"
- **Fix verification** — confirm a patched service method writes the correct fields

### Example

```clojure
(q/diff-entity "Book" 1
  (fn []
    (let [repo (lw/bean "bookRepository")
          book (.get (.findById repo (Long. 1)))]
      (.setAvailableCopies book (short 2))
      (.save repo book))))
;; => {:before  {:id 1, :availableCopies 3, ...}
;;     :after   {:id 1, :availableCopies 2, ...}
;;     :changed {:availableCopies [3 2]}}
```

Use `clj-nrepl-eval` directly for shell access (the thunk is Clojure logic, not a data arg).

---

## Hot Queries API — `net.brdloush.livewire.hot-queries`

Swap a Spring Data JPA `@Query` live without restarting the app.

| Expression | What it does |
|---|---|
| `(hq/list-queries "repoBean")` | Lists all `@Query` methods with their current JPQL |
| `(hq/hot-swap-query! "repoBean" "method" new-jpql)` | Swaps the JPQL live |
| `(hq/list-swapped)` | Shows all currently swapped queries |
| `(hq/reset-query! "repoBean" "method")` | Restores the original JPQL for one method |
| `(hq/reset-all!)` | Restores **every** currently swapped query at once |

### When to use hot-queries

- **Iterating on a known fix** — you have a specific JPQL change in mind and want to
  verify it works through the full Spring Data stack (correct return type coercion,
  pagination, etc.) before writing it to source.
- **N+1 hot-fix confirmation** — swap the fix, verify with `lw-trace-nplus1`, swap back
  to broken to confirm the N+1 returns, then restore and write to source.
- Works best combined with `lw-trace-nplus1` / `trace/trace-sql`.

### ⚠️ Do NOT use hot-swap for speculative REPL verification

When the user asks you to *"try this in the REPL first"* or *"verify before writing"*,
**reach for `jpa/jpa-query` + `lw-trace-nplus1`, not `hq/hot-swap-query!`**.

Hot-swapping is a mutating, session-persistent operation — the swapped query stays live
in the JVM and affects all callers until explicitly reset. It is the wrong tool when the
goal is just to prove that a candidate JPQL reduces query count.

| Goal | Right tool | Why |
|---|---|---|
| Prove a candidate JPQL works and reduces N+1 | `lw-jpa-query` + `lw-trace-nplus1` | Zero side effects, no cleanup needed |
| Verify the fix through the real repo method | `hq/hot-swap-query!` | Exercises the full Spring Data stack; requires `reset-all!` after |

```bash
# ✅ speculative verification — no side effects
lw-trace-nplus1 '(jpa/jpa-query "SELECT DISTINCT b FROM Book b JOIN FETCH b.author LEFT JOIN FETCH b.genres WHERE b.id IN (SELECT bb.id FROM Book bb JOIN bb.genres g WHERE g.id = :genreId)" :page 0 :page-size 5)'

# Only reach for hot-swap when you need the result to go through .findByGenre() itself
clj-nrepl-eval -p 7888 '(hq/hot-swap-query! "bookRepository" "findByGenre" "SELECT DISTINCT b FROM Book b ...")'
```

### Last-one-wins swap policy

A recompile always overrides a REPL pin. `(hq/list-swapped)` shows `:manual? true` for
REPL-initiated swaps.

### ⚠️ Hot-swaps persist — always clean up when done

```clojure
(hq/reset-all!)    ; restore everything in one call
(hq/list-swapped)  ; verify nothing is left — should return []
```

### Example

```clojure
;; See what @Query methods exist on a repo
(hq/list-queries "bookRepository")

;; Swap to a fixed query
(hq/hot-swap-query! "bookRepository" "findByIdWithDetails"
  "select b from Book b join fetch b.author join fetch b.reviews where b.id = :id")

;; Verify the fix
(trace/trace-sql
  (lw/run-as ["admin" "ROLE_ADMIN"]
    (lw/in-readonly-tx
      (.findByIdWithDetails (lw/bean "bookRepository") 25))))

;; Restore original when done
(hq/reset-query! "bookRepository" "findByIdWithDetails")
;; => :restored
```

---

## Query Watcher API — `net.brdloush.livewire.query-watcher`

The query-watcher runs automatically in the background on boot. It polls compiled output
directories every 500 ms, detects `.class` file changes via mtime, and auto-applies
updated `@Query` JPQL strings live — no restart needed.

| Expression | What it does |
|---|---|
| `(qw/status)` | Returns `{:running? true/false, :disk-state-size N, :disk-state {...}}` |
| `(qw/start-watcher!)` | Starts the watcher (idempotent) |
| `(qw/stop-watcher!)` | Stops the watcher |
| `(qw/force-rescan!)` | Clears the mtime cache — next poll re-examines every `.class` file |

`qw/force-rescan!` is rarely needed manually — `hq/reset-all!` calls it automatically.
