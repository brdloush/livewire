# Livewire — Known Pitfalls & Important Rules

Read this file when you encounter an unexpected error, are about to write a query, or want
to verify you are following the correct conventions for data access, security, and argument passing.

---

## Important Rules

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
(lw/in-readonly-tx (q/sql "SELECT id, title FROM book LIMIT 20"))
```

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

# Confirm the proxy is missing the method
clj-nrepl-eval -p 7888 '(->> (.getClass (lw/bean "yourRepository")) .getMethods (map #(.getName %)) sort)'
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

# ✅ clj-nrepl-eval passes the expression verbatim
clj-nrepl-eval -p 7888 "(hq/reset-all!)"
```

---

### Wrapper script string arguments inherit the same shell hazards — avoid `!` in string values

```bash
# ❌ ! in string value triggers zsh history expansion
lw-build-test-recipe Review '{:overrides {:comment "Absolutely wonderful!"}}'

# ✅ avoid ! in string values passed via wrapper scripts
lw-build-test-recipe Review '{:overrides {:comment "Absolutely wonderful"}}'

# ✅ or use clj-nrepl-eval directly
clj-nrepl-eval -p 7888 "(faker/build-test-recipe \"Review\" {:overrides {:comment \"Absolutely wonderful!\"}})"
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
# ✅ clj-nrepl-eval with explicit UUID conversion
clj-nrepl-eval -p 7888 "(lw/run-as [\"admin\" \"ROLE_ADMIN\"] (.myMethod (lw/bean \"myController\") (java.util.UUID/fromString \"c97f032f-8e52-4084-9716-1b4ac7295dcc\")))"
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
clj-nrepl-eval -p 7888 "(lw/run-as [\"admin\" \"ROLE_ADMIN\"] (.myMethod (lw/bean \"myController\") (java.util.UUID/fromString \"c97f032f-...\") nil))"
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

### `clojure.core/bean` on a Spring proxy exposes proxy internals, not domain properties

Controllers and services are CGLIB proxies. Calling `(clojure.core/bean proxy)` returns
proxy metadata (`:advisors`, `:callbacks`, etc.), not the bean's own fields. Read source
code to discover what methods to call on a bean.

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
