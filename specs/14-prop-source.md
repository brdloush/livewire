# Property Source Resolution — `lw-prop-source`

## Motivation

Spring Boot resolves configuration through a layered chain of `PropertySource`
objects. When a property value is unexpected — wrong profile override,
environment variable shadowing a YAML value, auto-configuration injecting
something you didn't set — there is no built-in way to see **where a value
came from** at runtime.

`lw-prop-source` answers: *"Which property source provided this value, and
what other sources were checked?"*

Common scenarios:
- `spring.jpa.hibernate.ddl-auto` is `update` but you set `validate` in
  `application.yml` — an env var or profile YAML is overriding it
- A `@ConfigurationProperties` binding picks up a value from the wrong source
- Livewire or another library injects a property programmatically and you
  need to trace it
- Debugging relaxed binding: `spring.datasource.url` in YAML vs
  `SPRING_DATASOURCE_URL` in env — which wins?

## Baseline Expression

The following Clojure expression successfully enumerates all property sources
and extracts application-relevant properties from each `MapPropertySource`:

```clojure
(let [env (lw/bean org.springframework.core.env.Environment)
      ps (.getPropertySources env)
      it (.iterator ps)]
  (loop [result []]
    (if (.hasNext it)
      (let [source (.next it)
            name (.getName source)]
        (if-let [props (when (instance? org.springframework.core.env.MapPropertySource source)
                         (let [m (.getSource source)]
                           (when (instance? java.util.Map m)
                             (->> (.keySet m)
                                  (.toArray)
                                  (mapv #(str % "=" (.get m %)))
                                  (filter #(re-find #"^(spring|app|livewire|local)\." %)))))])
          (recur (conj result {:name name :properties props}))
          (recur result)))
      result)))
```

This returns a vector of `{:name <source-name> :properties [<key=value> ...]}`
entries, ordered by resolution precedence (first source wins).

### Key observations from baseline

1. **`getPropertySources()` returns an `Iterable`** — not a `MutablePropertySources`
   with `getPropertySources()` method (Spring Boot 4 changed the API). Use
   `.iterator` + loop.

2. **Not all sources are `MapPropertySource`** — `systemEnvironment`,
   `systemProperties`, and some internal sources implement `EnumerablePropertySource`
   or custom types. The baseline filters to `MapPropertySource` instances with
   a `java.util.Map` backing store, which covers YAML/config sources.

3. **Resolution order matters** — sources are iterated in precedence order.
   The first source containing a property key wins. `server.ports` is checked
   before `application.yml`, which is checked before `systemEnvironment`.

## Specification

### CLI: `lw-prop-source <property>`

Shows the resolution chain for a single property:

```bash
lw-prop-source spring.jpa.hibernate.ddl-auto
```

Output:
```
spring.jpa.hibernate.ddl-auto = validate
  from: Config resource 'class path resource [application.yml]' via location 'optional:classpath:/'
  checked (not found): server.ports, configurationProperties, commandLineArgs,
                       servletConfigInitParams, servletContextInitParams,
                       systemProperties, systemEnvironment, random,
                       livewire-hibernate-tracing, applicationInfo, Management Server
```

### CLI: `lw-prop-source <property> --all`

Shows all candidates across all sources, including values that lost:

```bash
lw-prop-source spring.jpa.hibernate.ddl-auto --all
```

Output:
```
spring.jpa.hibernate.ddl-auto
  [WINNER] Config resource 'class path resource [application.yml]' = validate
  systemEnvironment = update          (SPRING_JPA_HIBERNATE_DDL_AUTO)
  systemProperties = (not set)
  ... (other sources: not set)
```

### API: `(lw/prop-source "property.name")`

Returns a structured map:

```clojure
(lw/prop-source "spring.jpa.hibernate.ddl-auto")
;; => {:property "spring.jpa.hibernate.ddl-auto"
;;     :value "validate"
;;     :source "Config resource 'class path resource [application.yml]' ..."
;;     :checked [{:name "server.ports" :found? false}
;;               {:name "application.yml" :found? true :value "validate"}
;;               ...]}
```

### CLI: `lw-prop-sources`

Lists all property sources and their contributed properties (filtered to
app-relevant prefixes by default):

```bash
lw-prop-sources
```

Output (markdown table):
```
| Source | Properties |
|--------|-----------|
| server.ports | local.server.port=8080 |
| application.yml | spring.jpa.hibernate.ddl-auto=validate, app.seed.authors=30, ... |
| livewire-hibernate-tracing | spring.jpa.properties.hibernate.session_factory.statement_inspector=... |
| applicationInfo | spring.application.pid=232694 |
```

With `--all` to show every property from every source (verbose).

## Implementation Notes

### Property source types to handle

| Type | How to query |
|------|-------------|
| `MapPropertySource` | `.getSource()` returns `java.util.Map` — use `.keySet()` + `.get()` |
| `EnumerablePropertySource` (e.g. `systemEnvironment`) | `.getPropertyNames()` returns `String[]` — use `.getProperty(name)` |
| `ConfigurationPropertySource` (Spring Boot relaxed binding) | No public API to enumerate keys — report as "(enumeration not available)" |
| Other/custom | Report name only, skip property enumeration |

### Relaxed binding

Spring Boot's relaxed binding means `spring.datasource.url` in YAML can match
`SPRING_DATASOURCE_URL` in env vars. The `Environment` resolves this
automatically, but property sources store keys literally. For `--all` mode,
the implementation should note when a value comes from a relaxed-binding
match (env var with underscores/dashes mapped to dot-notation key).

### Non-Map sources

For `systemEnvironment` and `systemProperties`, use `EnumerablePropertySource`
API:
```clojure
(when (instance? org.springframework.core.env.EnumerablePropertySource source)
  (let [names (.getPropertyNames source)]
    (->> (.toArray names)
         (mapv #(str % "=" (.getProperty source %)))
         (filter #(re-find #"^(spring|app|livewire|local)\." %)))))
```

### Precedence

Sources are ordered in the `Iterable` from highest precedence to lowest.
The first source containing the property key is the winner. Do not reorder
the iteration — Spring maintains the correct precedence internally.
