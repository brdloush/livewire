# Livewire — Open Tasks & Ideas

---

## 🚧 In Progress

### `trace` namespace — `hibernate-stats`

- **`(trace/hibernate-stats)`** — Snapshot of Hibernate's `Statistics` object:
  cache hit/miss ratios, entity load counts, collection fetch counts, slow queries.

---

## 🔜 Planned

### `query` namespace — JPQL execution and entity diffing

- **`(q/jpql query & params)`** — Execute a JPQL query via the live `EntityManager`
  inside a read-only transaction. Return results as Clojure maps.

- **`(q/diff-entity entity-class id thunk)`** — Capture entity state before and after
  calling `thunk`, return a diff. Useful for observing what a service method actually changes.

---

## 💡 Ideas (not yet spec'd)

### REPL-driven test runner

When the app runs in debug mode with `-Dexec.classpathScope=test`, the full test classpath
(compiled test classes, JUnit 5, Mockito) is already live in the JVM. Enables running unit
tests directly from the REPL without any Maven invocation.

**`(lw/run-test "com.example.MyTest")`** — run a single test class.
**`(lw/run-test "com.example.MyTest" "methodName")`** — run a single test method.

Could support running all tests in a package via `DiscoverySelectors/selectPackage`.
Results formatted as a markdown table.

Known constraints:
- Structural changes (new methods, fields, hierarchy) don't hotswap — requires restart.
- Requires debug mode (JVM started with a debug agent).
- Requires `-Dexec.classpathScope=test` for test classes and test-scoped deps to be on
  the runtime classpath.

---

## 🐛 Known Bugs / Docstring Issues

### `intro/list-entities` — key name mismatch in docstring

The docstring says "simple name + FQN" but the actual map keys are `:name` and `:class`,
not `:simple-name`. Using `:simple-name` silently returns `nil` and causes a
`NullPointerException` in regex filters.

Fix: correct the docstring (or rename the keys) and update `SKILL.md`.

```clojure
;; ❌ NullPointerException
(->> (intro/list-entities) (map :simple-name) (filter #(re-find #"Foo" %)))

;; ✅ correct
(->> (intro/list-entities) (map :name) (filter some?) (filter #(re-find #"Foo" %)))
```

### `trace/trace-sql` — add startup warning for Hibernate version mismatch

Fixed in current build, but worth adding a startup warning or assertion if the
`StatementInspector` fails to register — currently fails silently with `{:count 0, :queries []}`.

---

## ❌ Explicitly Out of Scope (v1)

- `@NamedQuery` / XML-defined queries — deferred.
- Production use — dev-only by design.
- Clojure knowledge requirement — the REPL surface is for agents and the developer who sets it up.
- Replacement of existing test infrastructure — complement only.
