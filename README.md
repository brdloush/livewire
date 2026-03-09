# Livewire

> *Live nREPL wire into your Spring Boot app. Dev only. You've been warned.*

Embeds a Clojure nREPL server inside a running Spring Boot application, giving
an agentic coding assistant (or a curious developer) a live, stateful probe into
the running JVM — beans, queries, transactions and all.

---

## Installation

Build and install to your local Maven repository:

```bash
lein with-profile +provided install
```

Then add to your Spring Boot project:

**Maven**
```xml
<dependency>
  <groupId>net.brdloush</groupId>
  <artifactId>livewire</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**Gradle**
```groovy
implementation 'net.brdloush:livewire:0.1.0-SNAPSHOT'
```

---

## Activation

Livewire auto-configures itself when **two conditions are met**:

1. The JAR is on the classpath
2. The property `livewire.enabled=true` is set

Add it to whichever local properties file your project already uses — no
profile name convention required:

```properties
# application-local.properties  (or -dev, -sandbox, whatever you use)
livewire.enabled=true

# optional: override the default nREPL port (7888)
livewire.nrepl.port=7888
```

You'll see this in the logs on startup:
```
[livewire] nREPL server started on port 7888
```

---

## Connecting

### CIDER (Emacs)

```
M-x cider-connect-clj
  Host: localhost
  Port: 7888
```

Once the REPL buffer is open, require the Livewire namespaces:

```clojure
(require '[net.brdloush.livewire.core :as lw]
         '[net.brdloush.livewire.query :as q])
```

Then you're ready to go:

```clojure
(q/sql "SELECT count(1) FROM books")
;; => [{:count 0}]
```

### Terminal

```bash
clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' \
        -M -m nrepl.cmdline --connect --host 127.0.0.1 --port 7888
```

### clojure-mcp (agentic use)

Point it at port 7888 — the AI assistant can then use `clojure_eval` to
probe the live app directly.

---

## Core API (`net.brdloush.livewire.core`)

Once connected, require the namespace:

```clojure
(require '[net.brdloush.livewire.core :as lw])
```

| Function / Macro | Description |
|---|---|
| `(lw/ctx)` | Returns the live `ApplicationContext` |
| `(lw/info)` | Basic env info (Spring, Java, OS, active profiles) |
| `(lw/bean "name")` | Get a bean by name |
| `(lw/bean MyService)` | Get a bean by type |
| `(lw/beans-of-type DataSource)` | All beans of a type → map |
| `(lw/bean-names)` | All registered bean names |
| `(lw/find-beans-matching ".*Repo.*")` | Filter bean names by regex |
| `(lw/all-properties)` | All resolved environment properties → map |
| `(lw/props-matching "spring\\.ds.*")` | Filter properties by regex |
| `(lw/in-tx & body)` | Run body in a transaction — **always rolls back** |
| `(lw/in-readonly-tx & body)` | Run body in a read-only transaction |
| `(lw/run-as user & body)` | Run body with a specific Spring `SecurityContext` — essential for calling `@PreAuthorize`-guarded beans from the REPL |

### `run-as` — calling secured beans from the REPL

Without a security context, calling any `@PreAuthorize`-guarded bean from the REPL throws
`AuthenticationCredentialsNotFoundException`. `run-as` temporarily sets the
`SecurityContextHolder` for the duration of the body, then restores it.

`user` accepts three forms:

| Form | Effect |
|---|---|
| `"alice"` | Creates a token for `alice` with `ROLE_USER` + `ROLE_ADMIN` |
| `["alice" "ROLE_READ" "ROLE_WRITE"]` | Creates a token with exactly the specified roles |
| an `Authentication` object | Uses it as-is |

```clojure
;; Call a @PreAuthorize-guarded controller method directly
(lw/run-as "superadmin@example.com"
  (.getBookById (lw/bean "bookController") 25))

;; Verify what principal and roles are active inside the body
(lw/run-as ["alice" "ROLE_READ"]
  (let [auth (-> (org.springframework.security.core.context.SecurityContextHolder/getContext)
                 .getAuthentication)]
    {:principal (.getName auth)
     :roles     (mapv str (.getAuthorities auth))}))
;; => {:principal "alice", :roles ["ROLE_READ"]}
```

---

## Introspection API (`net.brdloush.livewire.introspect`)

Once connected, require the namespace:

```clojure
(require '[net.brdloush.livewire.introspect :as intro])
```

| Function | Description |
|---|---|
| `(intro/list-endpoints)` | Returns all registered HTTP endpoints (path, method, controller, params) |
| `(intro/list-entities)` | Lists all Hibernate-managed entities (simple name and FQN) |
| `(intro/inspect-entity "Name")` | Deeply inspects an entity's mapped table, columns, and relations |

### Introspection examples

```clojure
;; Discover available HTTP endpoints
(first (intro/list-endpoints))
;; => {:methods ["PUT"], :paths ["/api/v1/clients/segments"], :controller "com.example.bloatedshelf.controller.BookController", ...}

;; Find entities managed by Hibernate
(take 2 (intro/list-entities))
;; => ({:name "AccountBalance", :class "eu...AccountBalance"}
;;     {:name "AcknowledgeNotification", :class "eu...AcknowledgeNotification"})

;; Inspect a specific entity to see its exact database mappings and relations
(intro/inspect-entity "Book")
;; => {:entity-name "com.example.bloatedshelf.entity.Book",
;;     :table-name "books",
;;     :identifier {:name "id", :columns ["id"], :type "uuid"},
;;     :properties [{:name "clientAddresses", :is-association true, :collection true, :target-entity "...", :fetch "SELECT", ...}]}
```

---

## Trace API (`net.brdloush.livewire.trace`)

Once connected, require the namespaces:

```clojure
(require '[net.brdloush.livewire.core :as lw]
         '[net.brdloush.livewire.trace :as trace])
```

| Function / Macro | Description |
|---|---|
| `(trace/trace-sql & body)` | Wraps body and captures every SQL statement fired by Hibernate on the current thread. |
| `(trace/trace-sql-global & body)` | Same as above, but captures SQL across *all* threads globally (useful for `@Async`). |
| `(trace/detect-n+1 trace-res)` | Analyzes a trace result and groups repeatedly fired queries to find N+1 problems. |

### Trace examples

```clojure
;; Run a repository method and see the actual SQL that gets executed
(trace/trace-sql
  (lw/in-readonly-tx
    (count (.findAll (lw/bean "userRepository")))))
;; => {:result 42,
;;     :queries [{:sql "select ...", :caller "com.example.MyService:42"}],
;;     :count 1,
;;     :duration-ms 15}

;; Automatically hunt for N+1 queries by passing the trace result to detect-n+1
(trace/detect-n+1
  (trace/trace-sql
    (.getAllBooks (lw/bean "bookController")
                        25)))
;; => {:suspicious-queries [{:sql "select ... from loan_records ...",
;;                           :caller "com.example.bloatedshelf.service.BookService:52",
;;                           :count 18}],
;;     :total-queries 30,
;;     :duration-ms 1271}
```

---

## Example session

```clojure
;; What datasource URL is actually in use?
(lw/props-matching "spring\\.datasource\\.url")
;; => {"spring.datasource.url" "jdbc:postgresql://localhost:5432/myapp"}

;; Find all repository beans
(lw/find-beans-matching ".*Repository.*")
;; => ("bookRepository" "authorRepository" ...)

;; Query via a repository — convert to plain Clojure maps inside the
;; transaction (see note on lazy loading below)
(lw/in-readonly-tx
  (->> (.findAll (lw/bean "bookRepository"))
       (mapv #(select-keys (clojure.core/bean %) [:id :email :status :active]))))
;; => [{:id 1, :email "test@example.com", :status "PENDING", :active false}]

;; Safely mutate — the transaction rolls back automatically
(lw/in-tx
  (.save (lw/bean "userRepository") (->User "test@example.com"))
  (count (.findAll (lw/bean "userRepository"))))
```

### ⚠️ Hibernate lazy loading and transaction boundaries

Returning a raw Hibernate entity from `in-tx` / `in-readonly-tx` will cause
a `LazyInitializationException` when Clojure tries to print it — the session
is already closed by the time the REPL renders the result.

**Always convert to a plain Clojure map inside the transaction boundary:**

```clojure
;; ❌ will blow up — entity printed after session closes
(lw/in-readonly-tx
  (.findById (lw/bean "bookRepository") 1))

;; ✅ convert eagerly while the session is still open
(lw/in-readonly-tx
  (-> (.findById (lw/bean "bookRepository") 1)
      .get
      clojure.core/bean          ; converts all Java bean properties to a map
      (select-keys [:id :email :status :active])))  ; narrow to what you need
```

`clojure.core/bean` introspects all getter methods and returns a Clojure map.
Wrap it in `select-keys` to avoid triggering lazy associations you don't care
about.

---

## Hot Queries API (`net.brdloush.livewire.hot-queries`)

Swap a Spring Data JPA `@Query` annotation live — without restarting the app.
Mutates the `queryString` inside the `SimpleJpaQuery` held by
`QueryExecutorMethodInterceptor`, so all of Spring Data's result-type coercion
stays intact. On subsequent swaps, only an atom is `reset!` — no reflection on
the hot path.

```clojure
(require '[net.brdloush.livewire.hot-queries :as hq])
```

| Function | Description |
|---|---|
| `(hq/list-queries "repoBean")` | Lists all `@Query`-annotated methods on a repository bean with their current JPQL |
| `(hq/hot-swap-query! "repoBean" "methodName" new-jpql)` | Swaps the JPQL live; first call uses reflection, subsequent calls just `reset!` the atom |
| `(hq/list-swapped)` | Shows all currently hot-swapped queries across all repos |
| `(hq/reset-query! "repoBean" "methodName")` | Restores the original JPQL |

### Hot Queries example

```clojure
;; See what @Query methods are on the repository
(hq/list-queries "bookRepository")
;; => ({:method "findByIdWithDetails",
;;      :query-class "SimpleJpaQuery",
;;      :jpql "SELECT DISTINCT b FROM Book b JOIN FETCH b.author LEFT JOIN FETCH b.genres WHERE b.id = :id"}
;;     ...)

;; Swap the query to return nothing (useful for testing / debugging)
(hq/hot-swap-query! "bookRepository" "findByIdWithDetails"
  "select b from Book b where 1=2")
;; [hot-queries] hot-swapped bookRepository#findByIdWithDetails
;; => {:swapped ["bookRepository" "findByIdWithDetails"], :query "select b from Book b where 1=2"}

;; Confirm it's live — call the repo method, get empty result
(lw/run-as "admin"
  (lw/in-readonly-tx
    (.findByIdWithDetails (lw/bean "bookRepository") 25)))
;; => []

;; Subsequent swap is reflection-free (just resets the atom)
(hq/hot-swap-query! "bookRepository" "findByIdWithDetails"
  "select b from Book b where b.id = :id")
;; => {:swapped ["bookRepository" "findByIdWithDetails"], :query "..."}

;; Check what's currently swapped
(hq/list-swapped)
;; => [{:bean "bookRepository", :method "findByIdWithDetails", :jpql "select c from Contract c ..."}]

;; Restore the original
(hq/reset-query! "bookRepository" "findByIdWithDetails")
;; [hot-queries] restored bookRepository#findByIdWithDetails
;; => :restored
```

---

## What's next

See [TODO.md](TODO.md) for open tasks, planned components, and ideas.

---


*Don't touch live wires in production. But in dev? Grab on.*
