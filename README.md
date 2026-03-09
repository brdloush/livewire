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
| `(lw/bean "name")` | Get a bean by name |
| `(lw/bean MyService)` | Get a bean by type |
| `(lw/beans-of-type DataSource)` | All beans of a type → map |
| `(lw/bean-names)` | All registered bean names |
| `(lw/find-beans-matching ".*Repo.*")` | Filter bean names by regex |
| `(lw/all-properties)` | All resolved environment properties → map |
| `(lw/props-matching "spring\\.ds.*")` | Filter properties by regex |
| `(lw/in-tx & body)` | Run body in a transaction — **always rolls back** |
| `(lw/in-readonly-tx & body)` | Run body in a read-only transaction |

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

## What's next

| Component | Status |
|---|---|
| `core` — context, beans, transactions | ✅ Done |
| `boot` — nREPL lifecycle | ✅ Done |
| `introspect` — endpoints, Hibernate metamodel | ✅ Done |
| `query` — JPQL/SQL execution, diff-entity | 🔜 Planned |
| `trace` — SQL tracing, N+1 detection | 🔜 Planned |
| `hot-queries` — live @Query swap | 🔜 Planned |
| `query-watcher` — file watcher + ASM | 🔜 Planned |

---

*Don't touch live wires in production. But in dev? Grab on.*
