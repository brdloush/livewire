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
            '[net.brdloush.livewire.trace :as trace])
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

---

## Introspection API — `net.brdloush.livewire.introspect`

| Expression | What it returns |
|---|---|
| `(intro/list-endpoints)` | All registered HTTP endpoints (path, method, controller, params) |
| `(intro/list-entities)` | All Hibernate-managed entities (simple name + FQN) |
| `(intro/inspect-entity "Name")` | Table name, columns, and relations for one entity |

---

## Trace API — `net.brdloush.livewire.trace`

| Expression | What it does |
|---|---|
| `(trace/trace-sql & body)` | Captures every SQL fired by Hibernate on the current thread |
| `(trace/trace-sql-global & body)` | Same, but captures across *all* threads (useful for `@Async`) |
| `(trace/detect-n+1 trace-res)` | Analyzes a trace result and flags repeated queries |

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
Calling a controller method directly from the REPL throws `AuthenticationCredentialsNotFoundException`
because there is no Spring Security context. Bypass by calling the underlying service bean directly.

```clojure
;; ❌ AuthenticationCredentialsNotFoundException
(.myEndpoint (lw/bean "myController") someArg)

;; ✅ call the service the controller delegates to
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

;; Query a repository safely
(lw/in-readonly-tx
  (->> (.findAll (lw/bean "bookRepository"))
       (mapv #(select-keys (clojure.core/bean %) [:id :email :status :active]))))
;; => [{:id 1, :email "test@example.com", :status "PENDING", :active false}]

;; Mutate safely — rolls back automatically
(lw/in-tx
  (.save (lw/bean "userRepository") (->User "test@example.com"))
  (count (.findAll (lw/bean "userRepository"))))

;; Capture the SQL a service method fires
(trace/trace-sql
  (lw/in-readonly-tx
    (count (.findAll (lw/bean "userRepository")))))
;; => {:result 42, :queries [{:sql "select ...", :caller "..."}], :count 1, :duration-ms 15}

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
(intro/inspect-entity "Client")
;; => {:table-name "clients", :identifier {:name "id", :columns ["id"], :type "uuid"}, :properties [...]}
```
