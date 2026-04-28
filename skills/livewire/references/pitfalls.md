# Livewire — Known Pitfalls & Important Rules

Read this file when you encounter an unexpected error, are about to write a query, or want
to verify you are following the correct conventions for data access, security, and argument passing.

---

## Important Rules

### Never guess method names — always read the source

Controller method names, service method names, and repository method names are **never** the same thing. Guessing one from another is the most common cause of `No matching field found` errors and wastes multiple REPL attempts.

```bash
# Controller says: getBooks()
# Service actually has: getAllBooks() ← different name!
# Repository has: findAllWithAuthorGenresReviewsAndMember() ← yet another name!

# ❌ guessing — throws NoSuchMethodError immediately
(.getBooks (lw/bean "bookService"))

# ✅ read the source — one grep, exact answer
grep -n "public.*getBooks\|def getBooks" /path/to/BookService.java
# => returns: getAllBooks(), getBookById(), getReviewsForBook(), getBooksByGenreId()

# Then call with the real method name
(.getAllBooks (lw/bean "bookService"))
```

**The full naming chain is always different:**

| Layer | Example | Notes |
|---|---|---|
| Controller class | `BookController` | FQN from `intro/list-endpoints` |
| Controller bean | `bookController` | lowercase first letter of class name |
| Controller method | `getBooks()` | `@GetMapping` handler method name |
| Service bean | `bookService` | lowercase first letter of service class |
| Service method | `getAllBooks()` | **often different from controller method name** |
| Repository method | `findAllWithAuthorGenresReviewsAndMember()` | **completely different name** |
| Endpoint path | `/api/books` | `@RequestMapping` + `@GetMapping` path |

**Rule: always verify by reading source. One `grep` is faster than three `No matching field` errors.**

---

### Always use JPQL (`lw-jpa-query`) for data queries — raw SQL is the last resort

**Default to `lw-jpa-query` for any query that fetches application data.** Raw `lw-sql` / `q/sql`
is reserved for:
- DB-level metadata: indexes, constraints, `information_schema` columns
- DDL statements (`CREATE INDEX`, `ALTER TABLE`)
- Queries involving tables with no Hibernate entity mapping

```bash
# ❌ raw SQL for entity data — wrong default
lw-sql "SELECT TOP 5 id FROM dbo.loan_record"

# ✅ JPQL — no schema prefix needed, uses entity property names, auto-paginated
lw-jpa-query 'SELECT lr.id FROM LoanRecord lr' 0 5
```

---

### `jpa/jpa-query` uses ID-first pagination — query count is inflated

`jpa/jpa-query` runs the JOIN FETCH query just to collect IDs, then issues a second
`SELECT ... WHERE id IN (...)` to reload each entity with hydrated state. When you wrap
`jpa/jpa-query` in `trace/trace-sql` to prototype a JPQL fix, the count is roughly **doubled**.

This is by design — it allows efficient paged queries on JOIN FETCH results without
returning millions of rows. It is not a bug. But the absolute query count from a
`jpa/jpa-query` prototype is always wrong for the real service method.

```clojure
;; Prototype via jpa/jpa-query — 36 queries (ID-only query + JOIN FETCH + count + reloads)
(jpa/jpa-query jpql-variant :page 0 :page-size 100)

;; Real service method — 2 queries (JOIN FETCH, no reload)
(trace/trace-sql (.getBooksByGenreId (lw/bean "bookService") 1))
```

**How to compare correctly:** the ID-first wrapper adds overhead to **both** baseline and
variant equally. So the **relative difference is still valid** — if baseline is 98 and
variant is 36, the fix works. But never report the variant's raw count as "2 queries".

**To get accurate counts in the REPL:**

```clojure
;; Call the real service method — trace/trace-sql captures it cleanly
(trace/trace-sql (.getBooksByGenreId (lw/bean "bookService") 1))
;; → 98 queries

;; Use EntityManager directly — no ID-first pagination
(let [em (lw/bean jakarta.persistence.EntityManager)]
  (trace/trace-sql
    (doto (clojure.lang.Reflector/invokeInstanceMethod
            em "createQuery" [jpql] [jakarta.persistence.Query])
      (.getResultList))))
```

**Rule of thumb:** use `jpa/jpa-query` to **validate JPQL syntax** and result shape.
Use `trace/trace-sql` on the real service method for **accurate query counts**.

---

### Always inspect entities before writing any SQL or JPQL query

**Never guess table names, column names, or join paths.** Use the introspection API every time.

**Workflow — mandatory before every query:**

1. Find the entity name: `lw-list-entities`
2. Inspect the entity and any join targets: `lw-inspect-entity Book`, `lw-inspect-entity Author`
3. Write JPQL using the discovered property paths
4. Only then write the query

```clojure
;; ❌ guessing — likely to fail with wrong table name or column
(lw/in-readonly-tx (q/sql "SELECT id FROM loan_record WHERE returned = 1"))

;; ✅ inspect first → table "loan_record", column is "return_date" not "returned"
(jpa/jpa-query "SELECT lr.id, lr.member.id FROM LoanRecord lr WHERE lr.returnDate IS NULL" :page 0 :page-size 10)
```

---

### Never guess IDs — look them up from the database first

**Before calling any endpoint or service method that takes an ID, foreign key, or lookup parameter, find real values from the live database.** Guessing an ID may work (it happened to match genre 1 = "Science Fiction" and returned 20 results) but it also might not, and it gives the user no way to reproduce the call with a different value. **The lookup is the default, guessing is never the answer.**

**Before calling an endpoint with an ID parameter:**
1. Find the entity name: `lw-list-entities`
2. Inspect the entity: `lw-inspect-entity <Name>` — confirms table and field names
3. **Query the DB for real IDs**: `lw-sql "SELECT id, name FROM genre ORDER BY id LIMIT 5"`
4. Call the endpoint with a real ID

```bash
# ❌ guessing — lucky hit or silent 404
lw-call-endpoint bookController getBooksByGenre ROLE_MEMBER 1

# ✅ look up genres first, then use a real ID
lw-sql "SELECT id, name FROM genre ORDER BY id LIMIT 5"
;; => [{:id 1, :name "Science Fiction"} {:id 2, :name "Fantasy"} ...]
lw-call-endpoint bookController getBooksByGenre ROLE_MEMBER 3   ; Mystery
```

**Apply to all ID parameters, not just foreign keys:**
- `getAuthorBooks(authorId)` → `lw-sql "SELECT id, first_name FROM author ORDER BY id LIMIT 5"`
- `getBookReviews(bookId)` → `lw-sql "SELECT id, title FROM book ORDER BY id LIMIT 5"`
- `getActiveLoans(memberId)` → `lw-sql "SELECT id, first_name FROM member ORDER BY id LIMIT 5"`
- `archiveBook(bookId)` → **never guess for mutating endpoints** — verify the ID exists and its current state first

### Always limit queries when fetching sample data or IDs

Tables in a live app can contain millions of rows. **Always cap results.** The default cap is **10 rows**.

```bash
# ✅ JPQL — page 0, 10 results max by default, no risk of runaway fetch
lw-jpa-query 'SELECT b.id, b.title FROM Book b' 0 10
```

```clojure
;; ❌ may return millions of rows and hang the REPL
(lw/in-readonly-tx (q/sql "SELECT id FROM book"))

;; ✅ cap explicitly
(lw/in-readonly-tx (q/sql "SELECT id, title FROM book LIMIT 10"))
```

---

### Always split large `IN (:ids)` clauses to avoid the "maximum number of ? in a SQL statement" limit

Many databases and JDBC drivers enforce a hard cap on the number of bind parameters per
statement. PostgreSQL's wire protocol supports ~65,535 parameters, but **most frameworks,
connection pools, and ORM layers impose much lower limits** (often 1000–2000). A `WHERE id
IN (…2000+ IDs…)` query can crash at runtime with "too many arguments" or silently be
rejected. This is especially relevant when building `IN` clauses from in-memory collections
(e.g. after fetching 200 book IDs and then batch-loading reviews with `WHERE review.book_id
IN (:bookIds)`).

**Rule: never pass more than 500 IDs in a single `IN (:ids)` clause.** Always split large
collections into batches and merge results.

```clojure
;; ❌ may crash — 2000 IDs in one IN clause
(jp/jpa-query "SELECT r FROM Review r WHERE r.book.id IN (:ids)" :ids (mapv #(.getId %) large-book-list))

;; ✅ always split into batches
(defn in-batches [items batch-size]
  (mapv vec (partition-all batch-size items)))

(->> (in-batches (mapv #(.getId %) large-book-list) 500)
     (mapv (fn [batch]
             (jp/jpa-query "SELECT r FROM Review r WHERE r.book.id IN (:ids)" :ids batch)))
     (apply concat))
```

**When this bites you:**
- N+1 fixes that replace per-row queries with a single `IN (:ids)` for a large result set
- `build-entity` / `build-test-recipe` generating large graphs and persisting in one go
- Any code that does `WHERE x.id IN (:ids)` where `ids` comes from a collection query
- `@BatchSize`-based batch fetching — Hibernate itself uses ~10–50 IDs per batch

---

## Known Pitfalls

### Inspect unfamiliar return types before using them — don't guess field names

When calling a controller or service method that returns a type you haven't worked with before,
**always inspect the result's structure first**.

```clojure
;; Step 1 — fetch once and inspect structure in a single call
(let [result (lw/run-as ["user" "ROLE_X"] (.someMethod (lw/bean "someController")))]
  {:result-type   (type result)
   :sample-field  (lw/bean->map result)
   :nested-type   (type (.getSomeCollection result))
   :sample-nested (lw/bean->map (first (.values (.getSomeCollection result))))})
```

Common surprises: collections returned as `LinkedHashMap`, fields named `.display` instead
of `.name`, nested DTOs as Java records (use `lw/bean->map`, not `clojure.core/bean`).

---

### New repository methods require a restart — the query-watcher cannot inject them

The query-watcher can only hot-swap JPQL strings in `@Query` methods that **already existed at
startup**. Adding a brand-new method requires a restart.

```bash
# Confirm the compiled artifact is correct
javap -p target/classes/com/example/yourapp/repository/YourRepository.class

# Confirm the proxy is missing the method — temp file for ->> and #()
cat > /tmp/lw-proxy-methods.clj << 'EOF'
(->> (.getClass (lw/bean "yourRepository"))
     .getMethods
     (map #(.getName %))
     sort)
EOF
lw-eval --file /tmp/lw-proxy-methods.clj
# If the new method is absent here but present in javap output → restart required
```

---

### `lw-eval` mangles Clojure-idiomatic characters — use `clj-nrepl-eval` directly

| Character | Clojure use | Shell hazard |
|---|---|---|
| `!` | Mutating fns: `reset-all!`, `hot-swap-query!` | zsh history expansion |
| `?` | Predicates: `running?`, `empty?` | Glob / conditional |
| `->`, `->>` | Threading macros | Redirect / append |

```bash
# ❌ lw-eval silently drops the ! — "No such var: hq/reset-all"
lw-eval '(hq/reset-all!)'

# ❌ inline clj-nrepl-eval with ! — shell eats it
clj-nrepl-eval -p 7888 "(hq/reset-all!)"

# ✅ correct — temp file
lw-eval --file /tmp/lw-reset.clj
# (in /tmp/lw-reset.clj):
# (hq/reset-all!)
```

---

### Wrapper script string arguments inherit the same shell hazards — avoid `!` in string values

```bash
# ❌ ! in string value triggers zsh history expansion
lw-build-test-recipe Review '{:overrides {:comment "Absolutely wonderful!"}}'

# ✅ avoid ! in string values passed via wrapper scripts
lw-build-test-recipe Review '{:overrides {:comment "Absolutely wonderful"}}'

# ✅ or use clj-nrepl-eval with temp file (has ! and nested quotes)
cat > /tmp/lw-faker.clj << 'EOF'
(faker/build-test-recipe "Review" {:overrides {:comment "Absolutely wonderful!"}})
EOF
lw-eval --file /tmp/lw-faker.clj
```

---

### `intro/list-entities` uses `:name` and `:class`, not `:simple-name`

```clojure
;; ❌ NullPointerException
(->> (intro/list-entities) (map :simple-name) (filter #(re-find #"Foo" %)))

;; ✅ correct
(->> (intro/list-entities) (map :name) (filter some?) (filter #(re-find #"Foo" %)))
```

---

### Never call `.findAll` without a `Pageable` — it may return millions of rows

```clojure
;; ❌ danger: fetches every row in the table
(.findAll (lw/bean "bookRepository"))

;; ✅ cap at 20 rows using Pageable
(->> (.findAll (lw/bean "bookRepository")
               (org.springframework.data.domain.PageRequest/of 0 20))
     .getContent
     (mapv #(select-keys (clojure.core/bean %) [:id :title :isbn])))
```

---

### `lw/bean SomeClass` only resolves Spring beans, not JPA entities

```clojure
;; ❌ NoSuchBeanDefinitionException
(lw/bean eu.example.MyEntity)

;; ✅ find the repository instead
(lw/find-beans-matching ".*MyEntity.*[Rr]epo.*")
(lw/bean "myEntityRepository")
```

---

### `EntityManager` is not registered as a named bean — use type-based lookup

```clojure
;; ❌ NoSuchBeanDefinitionException
(lw/bean "entityManager")

;; ✅ type-based lookup resolves the shared proxy
(let [em (lw/bean jakarta.persistence.EntityManager)]
  (lw/in-tx
    (.flush em)
    (.clear em)))
```

---

### `lw-call-endpoint` and `lw/bean` expect the Spring bean name, not the class name

`intro/list-endpoints` reports `:controller` as the **fully-qualified class name**.
The Spring bean name is the camelCase version with a lowercase first letter.

```bash
# ❌ NoSuchBeanDefinitionException
lw-call-endpoint AdminController archiveBook ROLE_ADMIN 1

# ✅ correct — lowercase first letter
lw-call-endpoint adminController archiveBook ROLE_ADMIN 1
```

---

### `lw-call-endpoint` cannot pass UUID arguments — use `clj-nrepl-eval` directly

UUID strings like `c97f032f-8e52-4084-9716-1b4ac7295dcc` are not valid Clojure literals.

```bash
# ✅ correct — temp file (nested quotes + no shell escaping issues)
cat > /tmp/lw-uuid.clj << 'EOF'
(lw/run-as ["admin" "ROLE_ADMIN"] (.myMethod (lw/bean "myController") (java.util.UUID/fromString "c97f032f-8e52-4084-9716-1b4ac7295dcc")))
EOF
lw-eval --file /tmp/lw-uuid.clj
```

---

### `lw-call-endpoint` role argument must include the `ROLE_` prefix

Spring stores granted authorities with the `ROLE_` prefix (e.g. `ROLE_MEMBER`, not `MEMBER`).
Passing a bare role name causes `AuthorizationDeniedException` even though the endpoint's
`@PreAuthorize` expression shows just `hasRole('MEMBER')`.

```bash
# ❌ Access Denied — Spring expects "ROLE_MEMBER", not "MEMBER"
lw-call-endpoint bookController getBooksByGenre MEMBER 1

# ✅ correct
lw-call-endpoint bookController getBooksByGenre ROLE_MEMBER 1
```

If you're unsure which roles or usernames the app has, reflect into the `InMemoryUserDetailsManager`:

```clojure
;; List all in-memory usernames
(let [svc   (lw/bean "userDetailsService")
      field (doto (.getDeclaredField
                    org.springframework.security.provisioning.InMemoryUserDetailsManager
                    "users")
              (.setAccessible true))]
  (keys (.get field svc)))
;; => ("readonly" "admin" "librarian" "member1")

;; Then check their granted authorities
(let [svc (lw/bean "userDetailsService")]
  (map #(hash-map :user % :roles (map str (.getAuthorities (.loadUserByUsername svc %))))
       ["member1" "librarian" "admin"]))
;; => ({:user "member1", :roles ("ROLE_MEMBER" "ROLE_VIEWER")} ...)
```

---

### `lw-call-endpoint` cannot handle optional (nullable) parameters

If the Java/Kotlin method has optional parameters, passing fewer args than the method arity
causes "No matching method taking N args". Pass `nil` explicitly via `clj-nrepl-eval`:

```bash
cat > /tmp/lw-optional.clj << 'EOF'
(lw/run-as ["admin" "ROLE_ADMIN"] (.myMethod (lw/bean "myController") (java.util.UUID/fromString "c97f032f-...") nil))
EOF
lw-eval --file /tmp/lw-optional.clj
```

---

### `clojure.core/bean` silently returns `{}` for Java records

Java records generate accessor methods without the `get` prefix. `clojure.core/bean` only
sees `getX()` methods, so it returns an empty map with no error.

**Always use `lw/bean->map` instead of `clojure.core/bean`** for any result object:

```clojure
;; ❌ silent empty map for a Java record DTO
(clojure.core/bean some-stats-dto)
;; => {:class com.example.StatsDto}   ← only :class, all fields missing

;; ✅ correct
(lw/bean->map some-stats-dto)
;; => {:totalBooks 200, :totalAuthors 30, :totalMembers 50, ...}
```

---

### Never use Java reflection to discover service method signatures — read the source

Spring beans are CGLIB proxies. Trying to discover method signatures via
`(.getMethods (class (lw/bean "myService")))` or similar reflection gymnastics on the proxy
returns synthetic proxy methods alongside the real ones, and namespace/symbol resolution
errors are common. It is always faster and more reliable to read the source file directly.

```bash
# ❌ reflection on proxy — noisy, error-prone
# ❌ also has ->> and #() — would need temp file even if proxy worked
cat > /tmp/lw-reflect.clj << 'EOF'
(->> (.getMethods (class (lw/bean "bookService")))
     (map #(.getName %))
     sort)
EOF
lw-eval --file /tmp/lw-reflect.clj

# ✅ just grep the source — one call, exact answer
grep -n "public.*getBooks" /path/to/BookService.java
```

### `clojure.core/bean` on a Spring proxy exposes proxy internals, not domain properties

Controllers and services are CGLIB proxies. Calling `(clojure.core/bean proxy)` returns
proxy metadata (`:advisors`, `:callbacks`, etc.), not the bean's own fields. Read source
code to discover what methods to call on a bean.

---

### `@BatchSize` only works on collections — not `@ManyToOne`

`@BatchSize` is supported on `@OneToMany`, `@ManyToMany`, and element collections. It is **not supported on `@ManyToOne`** — Hibernate throws `AnnotationException: Property 'X' may not be annotated '@BatchSize'`. If you need to batch-load an association that is a `@ManyToOne`, use a JPQL `JOIN FETCH` in the owning query or the `spring.jpa.properties.hibernate.default_batch_fetch_size` property instead.

```java
@Entity
public class Book {
    // ✅ works — collection
    @OneToMany(mappedBy = "book")
    @BatchSize(size = 50)
    private List<Review> reviews;

    // ❌ AnnotationException — @ManyToOne cannot use @BatchSize
    @ManyToOne
    @BatchSize(size = 50)
    private LibraryMember member;  // compilation error
}
```

**Alternatives for `@ManyToOne` batch loading:**
- JPQL `JOIN FETCH` in the query that loads the parent
- `spring.jpa.properties.hibernate.default_batch_fetch_size=50` in `application.properties` — Hibernate applies batch loading globally to all `@ManyToOne` and `@OneToMany` associations

---

### `@PreAuthorize` on controllers blocks direct REPL invocation

`lw/run-as` requires a vector of `["username" "ROLE_X"]` — passing a bare string username
causes `AuthorizationDeniedException` because no authorities are set on the authentication.

```clojure
;; ❌ AuthenticationCredentialsNotFoundException
(.myEndpoint (lw/bean "myController") someArg)

;; ❌ AuthorizationDeniedException — bare string sets no authorities
(lw/run-as "admin" (.myEndpoint (lw/bean "myController") someArg))

;; ✅ correct — vector with username + ROLE_-prefixed authority
(lw/run-as ["admin" "ROLE_ADMIN"] (.myEndpoint (lw/bean "myController") someArg))

;; ✅ alternative: call the service directly (skips security entirely)
(.myServiceMethod (lw/bean "myService") someArg)
```

---

### Use `jakarta.persistence` not `javax.persistence` on Spring Boot 3+

```clojure
;; ❌ ClassNotFoundException on Spring Boot 3+
(import 'javax.persistence.EntityManager)

;; ✅ correct
(import 'jakarta.persistence.EntityManager)
(lw/bean EntityManager)
```

---

### `q/sql` is query-only — use raw JDBC for DDL

```clojure
;; ❌ PSQLException: No results were returned by the query
(lw/in-tx (q/sql "CREATE INDEX idx_foo ON bar(baz)"))

;; ✅ raw JDBC for DDL
(let [ds (lw/bean javax.sql.DataSource)]
  (with-open [conn (.getConnection ds)]
    (-> conn .createStatement (.execute "CREATE INDEX idx_foo ON bar(baz)"))))
```

---

### Never use `(dorun (map ...))` in Hibernate transaction context

In the nREPL + Hibernate `in-readonly-tx` context, `(dorun (map ...))` creates a lazy
seq chain that silently blocks and hangs — it never completes and eventually times out.
The `dorun` call returns the lazy seq instead of consuming it, and the transaction/nREPL
thread pool deadlocks on the lazy chain.

**Why this is tricky:** `dorun` exists to consume side effects from realized seqs, but
`map` returns a lazy seq. `(dorun (map ...))` passes that lazy seq to `dorun`, which
doesn't realize it — `dorun` only consumes what it receives, it doesn't realize lazy
inputs. The lazy seq sits un-consumed and the nREPL thread blocks.

```clojure
;; ❌ hangs silently — dorun receives lazy seq but never realizes it
(dorun (map #(process x) collection))

;; ✅ eager, always works — no lazy seq created
doseq [x collection]
  (process x))

;; ✅ eager, returns data — no laziness
doseq [x collection]
  (mapv process collection))
```

**Rule of thumb:** `dorun` is effectively dead code when paired with `map`. Use `doseq`
for side effects (eager) or `mapv` for returning data (eager). If you ever find
yourself wrapping `map` in `dorun`, stop — that's a code smell even in plain Clojure.

### `lw-call-endpoint` defaults to 10 rows — never override unless necessary

`lw-call-endpoint` lists results capped at **10 by default**. Do not pass `--limit` with an arbitrary higher number (15, 20, 50) just to "see more." 10 is enough to show the response structure and a representative sample. Higher limits flood the output and make it harder to read. Only increase the limit if the user specifically needs more rows for a particular reason.

```bash
# ✅ default — 10 rows, structured output
lw-call-endpoint bookController getBooksByGenre ROLE_MEMBER 1

# ❌ arbitrary override — no reason to fetch 20 when 10 suffices
lw-call-endpoint --limit 20 bookController getBooksByGenre ROLE_MEMBER 1
```

### `JOIN FETCH` on multiple collections causes a Cartesian product — always warn about it

When fixing N+1 by adding `JOIN FETCH`, **never suggest `JOIN FETCH` on a second collection alongside an already-fetched collection without explicitly warning about the Cartesian product.** A `JOIN FETCH` on two collections (e.g. `JOIN FETCH b.author` + `JOIN FETCH b.reviews`) multiplies rows: if a book has 3 authors and 20 reviews, the query returns 3 × 20 = 60 rows for that single book. Hibernate deduplicates at the entity level, but the DTO still contains duplicated data, and the response is bloated identically to what N+1 would produce.

**⚠️ This is silently wrong — no exception, no warning, only bloated responses.**
Even a single row with 2 genres × 2 reviews produces 4 duplicated genres and 4 duplicated reviews. The DTO looks "mostly right" (all entities present, all data correct) but the nested collections contain duplicates. This is **always wrong**, even for tiny result sets. There is no scenario where joining two collections on the same parent produces a correct DTO.

**Rule: when suggesting `JOIN FETCH`, you must simultaneously warn:**
1. The multiplication factor (how many items per collection)
2. The risk that DTOs or responses will be inflated even after Hibernate deduplication — **this is always wrong, never acceptable for DTOs**
3. Alternatives — `@BatchSize(size = N)` on the collection, two-query approach, or `IN` clause for the second collection

```bash
# ❌ dangerous — two collections on the same parent
"select distinct b from Book b join fetch b.author join fetch b.reviews"
;; Book has 2 authors × 20 reviews = 40 rows per book
;; Hibernate deduplicates entities but the DTO still sees 40× inflated data

# ✅ safe — first collection in JOIN FETCH, second via @BatchSize
"select distinct b from Book b join fetch b.genres"
;; @BatchSize(size=50) on the reviews collection → 1 extra batched IN query

# ✅ safe — two queries, no multiplication
;; Query 1: parent + first collection
;; Query 2: second collection via IN clause
```

### First call after restart inflates timing — always warm up before measuring

```clojure
;; ✅ warm up first, then measure
(mapv (fn [_] (:duration-ms (trace/trace-sql (lw/run-as "admin" (.myEndpoint (lw/bean "myController"))))))
      (range 5))
;; => [98 25 15 12 12]  ← ignore the first result
```

---

### `trace/trace-sql` requires Livewire ≥ 0.1.0-SNAPSHOT (post Hibernate 7 fix)

Older Livewire builds silently registered no `StatementInspector` on Hibernate 7 apps,
resulting in `{:count 0, :queries []}`. Ensure the app is running with a current Livewire JAR.

### `trace/trace-sql` only works on JPA entities — not DTOs, records, or `select-keys`

`trace/trace-sql` inspects each result to capture which SQL statements loaded it. It does this
by calling JPA `EntityManager.find()` metadata on the result type. DTOs, Java records, and
`select-keys` results are not JPA-managed — they have no entity metadata, so `find` fails:

```
find not supported on type: com.example.dto.BookWithReviewsDto
```

**Always trace against repository methods that return managed JPA entities**, then map
to DTOs/records *after* tracing:

```clojure
;; ❌ fails — trace tries to inspect BookWithReviewsDto (a Java record)
(trace/trace-sql (mapv #(select-keys % [:id :title]) (.getAllBooks (lw/bean "bookService"))))

;; ✅ works — trace inspects Book entities, then you map afterward
(let [res (trace/trace-sql (doall (.findAllWithAuthorGenresReviewsAndMember (lw/bean "bookRepository"))))]
  ;; now safely convert to DTOs
  (count res))
```

### Never nest `trace/trace-sql` inside a wrapper call

`lw-trace-sql` and `lw-trace-nplus1` already wrap your expression in `trace/trace-sql`
internally. Putting `trace/trace-sql` or `trace/detect-n+1` **inside** the expression
creates a double-wrap that fails:

```
bash
# ❌ lw-trace-sql wraps your expression — no need to add trace/trace-sql inside it
lw-trace-sql '(do (let [res (trace/trace-sql ...)] ...))
;; => Syntax error (ArityException) — let binds res but the body tries to use it wrong

# ✅ correct — just pass the raw expression, the wrapper handles tracing
lw-trace-sql '(doall (.findAllWithAuthorGenresReviewsAndMember (lw/bean "bookRepository")))

# ✅ if you need both tracing + transformation, use temp file
# (has let, doall, nested parens — inline quoting will break)
cat > /tmp/lw-trace-count.clj << 'EOF'
(let [res (trace/trace-sql (doall (.findAllWithAuthorGenresReviewsAndMember (lw/bean "bookRepository"))))]
  (:count res))
EOF
lw-eval --file /tmp/lw-trace-count.clj
```

### After implementing a performance fix, always verify data correctness

**A trace showing `{:total-queries 2, :suspicious-queries []}` only proves the query count went down.**
It does **not** prove the response data is correct. The most common silent bug after an N+1 fix:

- **Wrong grouping key** — grouping reviews by review ID instead of book ID (compiles fine, traces fine, returns empty reviews)
- **Missing join condition** — fetching all reviews but grouping by the wrong field (compiles, traces, wrong data)
- **DTO field mismatch** — populating the wrong DTO fields (compiles, traces, garbled output)

**These bugs never show up in `lw-trace-nplus1` or `lw-trace-sql`.** The trace only measures SQL execution.

**Always verify with a sample call after a fix:**

```bash
# Step 1: trace confirms query count
lw-trace-nplus1 '(lw/run-as ["user" "ROLE_MEMBER"] (.getBooksByGenreId (lw/bean "bookService") 1))'
;; => {:total-queries 2, :suspicious-queries []}  ← performance is fixed

# Step 2: verify data shape and content
lw-call-endpoint bookController getBooksByGenre ROLE_MEMBER 1
;; => look for: author names populated, genres present, reviews NOT empty
```

**The two-step verification is mandatory.** Performance trace + sample response.

---

### Missing Java imports cause hard compile failures — compile before restarting

After writing a Java source fix, **always run `mvn compile -DskipTests`** before asking the
user to restart. A single missing import (e.g. forgetting `import com.example.Review;`)
produces a hard compile failure that prevents the entire app from starting. The query-watcher
cannot pick up constructor changes, new repository methods, or import fixes — these require
a full restart.

```bash
# ✅ compile first — catches the error immediately
mvn compile -DskipTests
# => if it fails, fix the error before restarting

# ✅ then restart the app
```

**Common compile errors to check for:**
- Missing `import` for a class you reference (Review, Genre, Map, Collectors)
- Constructor signature change — new injected field requires updating the constructor
- New repository method name doesn't match the call site
- Wrong method reference (e.g. `ReviewDto::id` instead of the book ID)

---

### Self-referential `let` bindings fail silently

In Clojure, you **cannot** reference a `let` binding from its own initializer:

```clojure
;; ❌ res is not bound yet when the body tries to use it
(let [res (trace/trace-sql (doall {:data (.getAllBooks ...) :queries (:queries res)}))] res)
;; => Unable to resolve symbol: res

;; ✅ correct — separate the computation from the inspection
(let [data (.getAllBooks (lw/bean "bookService"))]
  (let [res (trace/trace-sql (doall data))]
    {:count (:count res)}))
```

This happens when trying to compute data and inspect the trace in a single `let` form.
Always split into two forms: compute first, trace second.

---

### Never use positional `?1`, `?2` parameters in JPQL queries via `EntityManager` — always use named `:paramName`

When calling `EntityManager.createQuery()` directly (not via `@Query` or `jpa/jpa-query`),
Hibernate requires **named parameters** (`:genreId`, `:bookIds`). Positional parameters
(`?1`, `?2`) are fragile and a frequent source of errors:

- Bare `?` in a string → `ParameterLabelException: Unlabeled ordinal parameter ('?' rather than ?1)`
- You must manually build `?1, ?2, ...` placeholders and bind each one with `run!` + `iterate inc 1`
- This pattern is error-prone, hard to read, and easy to forget the `?` vs `?1` distinction

**Named parameters are the only safe default.** Always write JPQL with `:name` and bind with
`.setParameter(q, "name", val)`:

```clojure
(def em (lw/bean jakarta.persistence.EntityManager))

;; ✅ Named single param — always the first choice
(.setParameter q "genreId" (long 1))

;; ✅ Named collection IN (:ids) — simplest for variable-size lists
(.setParameter q "bookIds" [1 2 3 4 5])

;; ❌ Positional — works but fragile
;; bare "?" → ParameterLabelException
;; you must build "?1", "?2" and bind each individually — easy to forget
(let [placeholders (str/join ", " (mapv #(str "?") (take (count ids) (iterate inc 1))))]
  (let [q (.createQuery em (str "... IN (" placeholders ")"))]
    (run! (fn [[val idx]] (.setParameter q idx val))
          (mapv vector ids (iterate inc 1)))))
```

**Rule: never write positional parameters.** Use named params (`:name`) and `.setParameter(q, "name", val)` for every case. Only use positional parameters when you are writing code that runs at runtime (not in the REPL), and even then prefer named params for readability.
