# Livewire — Live nREPL probe for Spring Boot apps

Use this skill whenever working with a Spring Boot application that has Livewire
on its classpath. Before answering any question about the running app's state,
beans, database, or behaviour — **try the live REPL first**. A live answer from
the JVM beats static analysis every time.

---

## Workflow

1. **Discover** whether a Livewire nREPL is available:
   ```bash
   clj-nrepl-eval --discover-ports
   ```
   The default port is **7888**. If nothing is found, the app may not be running
   or `livewire.enabled=true` may not be set.

2. **Require** the Livewire namespaces at the start of the session:
   ```clojure
   (require '[net.brdloush.livewire.core :as lw]
            '[net.brdloush.livewire.introspect :as intro]
            '[net.brdloush.livewire.trace :as trace]
            '[net.brdloush.livewire.hot-queries :as hq])
   ```

3. **Evaluate** snippets iteratively — the session persists between calls:
   ```bash
   clj-nrepl-eval -p <port> "<clojure-code>"
   # with an explicit timeout (milliseconds)
   clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"
   ```

4. **Present results readably:**
   - Collections of maps → markdown table
   - Single map → inline key/value list
   - Scalars → inline code in prose

5. **Hot-patching:** Do not use `:reload` to pick up a newly built JAR — it
   re-reads the same old class already on the classpath. Instead, evaluate the
   new `ns` form and function bodies directly into the live REPL.

---

## Core API — `net.brdloush.livewire.core`

| Expression | What it returns |
|---|---|
| `(lw/ctx)` | Live Spring `ApplicationContext` |
| `(lw/info)` | Env summary (Spring version, Java, OS, active profiles) |
| `(lw/bean "name")` | Bean by name |
| `(lw/bean MyService)` | Bean by type |
| `(lw/beans-of-type DataSource)` | All beans of a type → map |
| `(lw/bean-names)` | All registered bean names |
| `(lw/find-beans-matching ".*Repo.*")` | Filter bean names by regex |
| `(lw/all-properties)` | All resolved environment properties → map |
| `(lw/props-matching "spring\\.ds.*")` | Filter properties by regex |
| `(lw/in-tx & body)` | Run body in a transaction — **always rolls back** |
| `(lw/in-readonly-tx & body)` | Run body in a read-only transaction |
| `(lw/run-as user & body)` | Run body with a Spring `SecurityContext` set — required for `@PreAuthorize`-guarded beans |

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
(lw/run-as "admin"
  (.getBookById (lw/bean "bookController") 25))

;; Combine with in-readonly-tx for repository access under a security context
;; Always page or limit — never call .findAll on a large table (see pitfalls)
(lw/run-as "admin"
  (lw/in-readonly-tx
    (->> (.findAll (lw/bean "bookRepository")
                   (org.springframework.data.domain.PageRequest/of 0 20))
         .getContent
         (mapv #(select-keys (clojure.core/bean %) [:id :email])))))

;; Use a specific role set when the method checks for a non-admin role
(lw/run-as ["auditor@example.com" "ROLE_AUDITOR"]
  (.getAuditLog (lw/bean "auditService")))
```

**Prefer `run-as` over bypassing to the service layer** when you want to exercise
the real secured code path — including AOP advice, `@PostAuthorize` filters, etc.

---

## Introspection API — `net.brdloush.livewire.introspect`

| Expression | What it returns |
|---|---|
| `(intro/list-endpoints)` | All registered HTTP endpoints (path, method, controller, handler, params, `:pre-authorize`) |
| `(intro/list-entities)` | All Hibernate-managed entities (simple name + FQN) |
| `(intro/inspect-entity "Name")` | Table name, columns, and relations for one entity |

### Calling controller methods from the REPL

When you discover an endpoint via `list-endpoints`, check its `:pre-authorize` value before calling it.
If one is present, **always wrap the call in `lw/run-as`** — without it the REPL has no
`SecurityContext` and Spring Security throws `AuthenticationCredentialsNotFoundException`.

```clojure
;; Discover what auth an endpoint requires
(->> (intro/list-endpoints)
     (filter #(re-find #"books" (str (:paths %))))
     (mapv #(select-keys % [:paths :handler-method :pre-authorize])))
;; => [{:paths ["/api/books"], :handler-method "getBooks", :pre-authorize "hasRole('MEMBER')"}
;;     ...]

;; Call it with the appropriate role
(lw/run-as "member1"
  (.getBooks (lw/bean "bookController")))
```

`:pre-authorize` reflects both method-level and class-level `@PreAuthorize` annotations —
so it's always populated when security is in play, regardless of where the annotation lives.

---

## Trace API — `net.brdloush.livewire.trace`

| Expression | What it does |
|---|---|
| `(trace/trace-sql & body)` | Captures every SQL fired by Hibernate on the current thread |
| `(trace/trace-sql-global & body)` | Same, but captures across *all* threads (useful for `@Async`) |
| `(trace/detect-n+1 trace-res)` | Analyzes a trace result and flags repeated queries |

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

### ⚠️ Hot-swaps are persistent side effects — always clean up

Unlike `lw/in-tx` (which always rolls back), hot-swapped queries **persist** in the live JVM for
the entire lifetime of the process. If you leave a swap in place, the app continues running the
patched query — which can silently affect other callers, tests, or monitoring.

**Rule: always restore before ending an exploratory session.**

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
      (select-keys [:id :email :status :active])))   ; narrow to what you need
```

`clojure.core/bean` introspects all getter methods and returns a Clojure map.
Wrap it in `select-keys` to avoid triggering lazy associations you don't need.

---

## ⚠️ Important Rules

### Always limit SQL queries when fetching sample data or IDs
Tables in a live app can contain millions of rows. **Always add a `TOP` / `LIMIT` / `FETCH FIRST`
clause** when querying for sample data, example IDs, or exploratory results. The default cap is
**20 rows**.

```clojure
;; ❌ may return millions of rows and hang the REPL
(lw/in-readonly-tx (q/sql "SELECT id FROM books"))

;; ✅ safe — cap at 20
(lw/in-readonly-tx (q/sql "SELECT TOP 20 id, email FROM books"))

;; ✅ also fine with LIMIT (depends on DB dialect)
(lw/in-readonly-tx (q/sql "SELECT id, email FROM books LIMIT 20"))
```

Apply the same discipline to JPQL queries via `EntityManager` and to repository calls —
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
                      (.myEndpoint (lw/bean "myController")
                                   (java.util.UUID/fromString id))))]
          {:id id :total-queries (:count res) :suspicious (count (:suspicious-queries (trace/detect-n+1 res)))}))
      ["uuid-1" "uuid-2" "uuid-3" "uuid-4" "uuid-5"])
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

## ⚠️ Known Pitfalls

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
     (mapv #(select-keys (clojure.core/bean %) [:id :email])))

;; ✅ or use a native/JPQL query with an explicit limit
(lw/in-readonly-tx (q/sql "SELECT TOP 20 id, email FROM books"))
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

### `trace/trace-sql` requires Livewire ≥ 0.1.0-SNAPSHOT (post Hibernate 7 fix)
SQL tracing works with both Hibernate 6 and 7. Older Livewire builds silently registered
no `StatementInspector` on Hibernate 7 apps, resulting in `{:count 0, :queries []}`.
Ensure the app is running with a current Livewire JAR.

---

## Examples

```clojure
;; What DB URL is the app actually using?
(lw/props-matching "spring\\.datasource\\.url")
;; => {"spring.datasource.url" "jdbc:postgresql://localhost:5432/myapp"}

;; Which repository beans exist?
(lw/find-beans-matching ".*Repository.*")
;; => ("bookRepository" "authorRepository" ...)

;; Query a repository safely — always page or limit (see pitfalls)
(lw/in-readonly-tx
  (->> (.findAll (lw/bean "bookRepository")
                 (org.springframework.data.domain.PageRequest/of 0 20))
       .getContent
       (mapv #(select-keys (clojure.core/bean %) [:id :email :status :active]))))
;; => [{:id 1, :email "test@example.com", :status "PENDING", :active false}]

;; Mutate safely — rolls back automatically
;; (count here is intentional — we want the total, not a page of rows)
(lw/in-tx
  (.save (lw/bean "userRepository") (->User "test@example.com"))
  (.count (lw/bean "userRepository")))

;; Capture the SQL a service method fires
(trace/trace-sql
  (lw/in-readonly-tx
    (.count (lw/bean "userRepository"))))
;; => {:result 42, :queries [{:sql "select count(*) ...", :caller "..."}], :count 1, :duration-ms 15}

;; Hunt for N+1 queries
(trace/detect-n+1
  (trace/trace-sql
    (.getAllBooks (lw/bean "bookController")
                        25)))
;; => {:suspicious-queries [{:sql "select ...", :caller "...", :count 18}],
;;     :total-queries 30, :duration-ms 1271}

;; Discover all HTTP endpoints
(first (intro/list-endpoints))
;; => {:methods ["PUT"], :paths ["/api/v1/clients/segments"], :controller "...ApiController", ...}

;; Inspect a Hibernate entity's DB mappings
(intro/inspect-entity "Book")
;; => {:table-name "books", :identifier {:name "id", :columns ["id"], :type "uuid"}, :properties [...]}

;; Call a @PreAuthorize-guarded controller method directly
(lw/run-as "admin"
  (.getBookById (lw/bean "bookController") 25))
;; => #object[QuestionnaireDto ...]

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
