# Livewire — Live nREPL probe for Spring Boot apps

Livewire embeds a Clojure nREPL server inside a running Spring Boot application.
When the target app is running with `livewire.enabled=true`, you have a live,
stateful probe into the JVM — beans, queries, transactions and all.

---

## Connecting to the live REPL

`clj-nrepl-eval` (from [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light/))
is available on your PATH. **Always try to connect before answering questions about
the running application** — a live answer beats a static guess every time.

### Discover running nREPL servers

```bash
clj-nrepl-eval --discover-ports
```

The default Livewire port is **7888**. If discovery returns nothing, the app may
not be running or Livewire may not be enabled.

### Evaluate Clojure code

```bash
clj-nrepl-eval -p <port> "<clojure-code>"

# with an explicit timeout (milliseconds)
clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"
```

The session **persists between evaluations** — required namespaces, defs, and any
state you create survive until the process exits.

### Require Livewire namespaces first

```clojure
(require '[net.brdloush.livewire.core :as lw]
         '[net.brdloush.livewire.introspect :as intro]
         '[net.brdloush.livewire.trace :as trace])
```

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
`LazyInitializationException` when the REPL tries to print it (the session is
already closed). **Always eagerly convert to a plain Clojure map inside the
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

## Worked examples

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
```

---

## Workflow guidance

1. **Before answering any question about the running app**, run
   `clj-nrepl-eval --discover-ports` to check if a Livewire REPL is available.
2. **Prefer live answers over static analysis** — the JVM knows things the
   source code doesn't (active profiles, runtime config, actual DB rows, etc.).
3. **Present results readably**: collections of maps → markdown table;
   single map → inline key/value list; scalars → inline code in prose.
4. **Do not use `:reload` to pick up a newly built JAR** — it re-reads the
   same old class from the JAR already on the classpath. Instead, hot-patch
   the live JVM by evaluating the new `ns` form and function bodies directly
   into the REPL.
