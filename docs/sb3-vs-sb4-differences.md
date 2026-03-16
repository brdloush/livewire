# Spring Boot 3.x vs Spring Boot 4.x — Livewire compatibility notes

This document captures the meaningful differences between Spring Boot 3.x (Spring Framework 6,
Hibernate 6) and Spring Boot 4.x (Spring Framework 7, Hibernate 7) that affect Livewire's
implementation or behaviour. It is written from a Livewire-internals perspective and is
intended to guide future development when adding support for new Spring Boot versions.

**Tested combinations:**

| Label | Spring Boot | Spring Framework | Hibernate | Jackson |
|---|---|---|---|---|
| **SB3** | 3.5.x | 6.x | 6.x | Jackson 2 (`com.fasterxml.jackson`) |
| **SB4** | 4.0.x | 7.x | 7.x | Jackson 3 (`tools.jackson`) |

---

## 1. JSON serialization — HTTP message converter split

This is the most impactful difference for Livewire.

### What changed

Spring Framework 7 introduced first-class support for **Jackson 3**, which lives in a completely
different Java package (`tools.jackson.*`) compared to Jackson 2 (`com.fasterxml.jackson.*`).
As part of this, the Spring HTTP message converter for JSON was renamed and re-implemented:

|  | SB3 / Spring Framework 6 | SB4 / Spring Framework 7 |
|---|---|---|
| Converter class | `org.springframework.http.converter.json.MappingJackson2HttpMessageConverter` | `org.springframework.http.converter.json.JacksonJsonHttpMessageConverter` |
| Mapper class | `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.json.JsonMapper` |
| Get mapper from converter | `.getObjectMapper()` | `.getMapper()` |
| `MappingJackson2HttpMessageConverter` on SB4 | present but `@Deprecated(forRemoval=true)` since Spring Framework 7.0 | — |

The old `MappingJackson2HttpMessageConverter` is still present in SB4 (deprecated), meaning
a hard import of the **old** class would compile and run on both. However, that class is
scheduled for removal in Spring Framework 7.2, so it is not a viable long-term strategy.

### Livewire's original behaviour

`mvc.clj` originally hard-imported `JacksonJsonHttpMessageConverter` (the SB4 class):

```clojure
(:import [org.springframework.http.converter.json JacksonJsonHttpMessageConverter])
```

On **SB3**, this class does not exist → `ClassNotFoundException` at namespace load time →
`net.brdloush.livewire.mvc` fails to load → `lw-call-endpoint` is completely broken and
all other CLI scripts print a noisy error before each result (because the blanket `require`
preamble in every script attempts to load `mvc`).

### How Livewire handles this (fix)

`mvc.clj` resolves the converter class lazily at first use via a classpath probe, removing
the hard import entirely:

```clojure
(def ^:private jackson-converter-class
  (delay
    (or (try (Class/forName "org.springframework.http.converter.json.JacksonJsonHttpMessageConverter")
             (catch ClassNotFoundException _ nil))
        (try (Class/forName "org.springframework.http.converter.json.MappingJackson2HttpMessageConverter")
             (catch ClassNotFoundException _ nil)))))
```

The mapper getter is resolved the same way at call time — it tries `.getMapper` (SB4) first,
then falls back to `.getObjectMapper` (SB3):

```clojure
(defn- get-mapper [converter]
  (or (try (.getMapper converter)       (catch Exception _ nil))
      (try (.getObjectMapper converter) (catch Exception _ nil))))
```

**The key insight that makes this work cleanly:** although the converter classes and their
mapper getter methods differ between SB3 and SB4, the serialization API surface on the mapper
itself (`writeValueAsString`, `readValue`, `writerWithDefaultPrettyPrinter`) is **identical
by name** on both `ObjectMapper` (Jackson 2) and `JsonMapper` (Jackson 3). Clojure's
untyped reflective method dispatch means these calls require no version-specific branching
— Clojure resolves them against the live runtime class automatically.

### Verified behaviour (REPL-tested on both versions)

| Operation | SB3 result | SB4 result |
|---|---|---|
| Class probe resolves to | `MappingJackson2HttpMessageConverter` | `JacksonJsonHttpMessageConverter` |
| Mapper resolved via | `.getObjectMapper()` | `.getMapper()` |
| Mapper class | `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.json.JsonMapper` |
| `writeValueAsString` | ✅ | ✅ |
| `readValue` → `LinkedHashMap` | ✅ | ✅ |
| `writerWithDefaultPrettyPrinter` | ✅ | ✅ |
| Non-list `serialize` result | ✅ pretty JSON string | ✅ pretty JSON string |
| List `serialize` with `:limit` | ✅ Clojure vector + metadata | ✅ Clojure vector + metadata |

---

## 2. Hibernate StatementInspector registration

### What changed

Spring Boot's `HibernatePropertiesCustomizer` — a convenient hook for injecting Hibernate
properties at context build time — was **removed in Spring Boot 4**. It is not available
on the SB4 classpath at all.

Livewire originally used this hook to register `LivewireSqlTracer` as Hibernate's
`StatementInspector`. That approach broke silently on SB4: the customizer bean was never
called, the inspector was never registered, and `trace-sql` returned `{:count 0, :queries []}`
with no error or warning.

### How Livewire handles this

The registration was moved to a `LivewireEnvironmentPostProcessor` (an
`org.springframework.boot.env.EnvironmentPostProcessor` registered in `META-INF/spring.factories`)
which injects the property:

```
spring.jpa.properties.hibernate.session_factory.statement_inspector
  = net.brdloush.livewire.LivewireSqlTracer
```

This runs before the Spring context is built and works on both SB3 and SB4. The property
is added at **lowest priority** (`addLast`) so any user-defined value still wins.

### Startup warning

Because registration failures are silent by nature (a missing or overridden inspector just
means `trace-sql` returns zero results), `boot/start!` now verifies registration explicitly
after the context is up:

```clojure
;; in boot/start! after core/set-context!
(check-statement-inspector!)
```

If `LivewireSqlTracer` is not the active `StatementInspector`, a `[livewire] WARNING` is
printed to stdout immediately at startup. This catches Hibernate version mismatches,
accidental property overrides, and any future registration mechanism changes.

### Note on `decisions/tracing.md`

`decisions/tracing.md` still describes the old `HibernatePropertiesCustomizer` approach.
It is **outdated** and should be updated to reflect the `LivewireEnvironmentPostProcessor`
mechanism.

---

## 3. Hibernate version differences (6 vs 7)

SB3.5 ships with Hibernate 6, SB4 ships with Hibernate 7. The following aspects were
checked and found **compatible across both versions** from Livewire's perspective:

| API | Hibernate 6 | Hibernate 7 | Notes |
|---|---|---|---|
| `org.hibernate.resource.jdbc.spi.StatementInspector` | ✅ | ✅ | Used by `LivewireSqlTracer` |
| `org.hibernate.engine.spi.SessionFactoryImplementor` | ✅ | ✅ | Used in `check-statement-inspector!` |
| `.getSessionFactoryOptions().getStatementInspector()` | ✅ | ✅ | Used in `check-statement-inspector!` |
| `org.hibernate.collection.spi.PersistentCollection` | ✅ | ✅ | Used in `entity-serialize.clj` |
| `jakarta.persistence.EntityManager` | ✅ | ✅ | Used throughout `core.clj` |

---

## 4. What works transparently on both versions

The following Livewire features have no dependency on the Spring/Jackson version and work
identically on SB3 and SB4 without any conditional logic:

- `trace/trace-sql` and `trace/trace-sql-global` — Hibernate `StatementInspector` only
- `trace/detect-n+1` — pure Clojure data transformation
- `jpa/jpa-query` — `jakarta.persistence.EntityManager` + `entity-serialize`
- `query/sql` — raw JDBC via Spring's `JdbcTemplate`
- `introspect/list-entities`, `introspect/inspect-entity`, `introspect/list-endpoints`
- `core/bean`, `core/in-tx`, `core/in-readonly-tx`, `core/run-as`
- `hot-queries` / `query-watcher`

---

## 5. Compatibility matrix

| Feature | SB3.5 + H6 | SB4 + H7 |
|---|---|---|
| nREPL startup | ✅ | ✅ |
| `trace-sql` / `detect-n+1` | ✅ | ✅ |
| `jpa-query` | ✅ | ✅ |
| `sql` | ✅ | ✅ |
| `list-entities` / `inspect-entity` | ✅ | ✅ |
| `list-endpoints` | ✅ | ✅ |
| `mvc/serialize` / `lw-call-endpoint` | ✅ (after fix) | ✅ |
| StatementInspector startup warning | ✅ | ✅ |
