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

Then add to your Spring Boot project **in a way that guarantees it never
reaches a production build**:

**Maven** — place the dependency inside a `dev` profile so it is absent from
any build that doesn't explicitly activate that profile:

```xml
<profiles>
  <profile>
    <id>dev</id>
    <dependencies>
      <dependency>
        <groupId>net.brdloush</groupId>
        <artifactId>livewire</artifactId>
        <version>0.1.0-SNAPSHOT</version>
      </dependency>
    </dependencies>
  </profile>
</profiles>
```

Run locally with `./mvnw spring-boot:run -Pdev`. A plain `mvn package` (as
your CI would run it) never sees the dependency at all.

**Gradle** — use the `developmentOnly` configuration provided by the Spring
Boot Gradle plugin. Dependencies here are automatically excluded from
`bootJar` / `bootWar`:

```groovy
developmentOnly 'net.brdloush:livewire:0.1.0-SNAPSHOT'
```

> **Two layers of protection:**
> 1. **Build-time** — the JAR is physically absent from production artifacts
>    (Maven profile / Gradle `developmentOnly`)
> 2. **Runtime** — even if the JAR somehow ends up on the classpath, nothing
>    starts without `livewire.enabled=true` explicitly set

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

Any nREPL client works. With [clojure-mcp](https://github.com/stuarthalloway/clojure-mcp)
(the recommended path for agentic use), point it at port 7888. For a quick
interactive session from the terminal:

```bash
# using the Clojure CLI nREPL client
clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' \
        -M -m nrepl.cmdline --connect --host 127.0.0.1 --port 7888
```

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

## Example session

```clojure
;; What datasource URL is actually in use?
(lw/props-matching "spring\\.datasource\\.url")
;; => {"spring.datasource.url" "jdbc:postgresql://prod-db:5432/myapp"}

;; Find all repository beans
(lw/find-beans-matching ".*Repository.*")
;; => ("userRepository" "orderRepository" ...)

;; Call a service method and inspect the result
(lw/in-readonly-tx
  (.findByEmail (lw/bean "userRepository") "alice@example.com"))

;; Safely mutate — the transaction rolls back automatically
(lw/in-tx
  (.save (lw/bean "userRepository") (->User "test@example.com"))
  (count (.findAll (lw/bean "userRepository"))))
```

---

## What's next

| Component | Status |
|---|---|
| `core` — context, beans, transactions | ✅ Done |
| `boot` — nREPL lifecycle | ✅ Done |
| `introspect` — endpoints, Hibernate metamodel | 🔜 Planned |
| `query` — JPQL/SQL execution, diff-entity | 🔜 Planned |
| `trace` — SQL tracing, N+1 detection | 🔜 Planned |
| `hot-queries` — live @Query swap | 🔜 Planned |
| `query-watcher` — file watcher + ASM | 🔜 Planned |

---

*Don't touch live wires in production. But in dev? Grab on.*
