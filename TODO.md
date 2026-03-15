# Livewire — Open Tasks & Ideas

---

## 🚧 In Progress

### `trace` namespace — `hibernate-stats`

- **`(trace/hibernate-stats)`** — Snapshot of Hibernate's `Statistics` object:
  cache hit/miss ratios, entity load counts, collection fetch counts, slow queries.

---

## 🔜 Planned

### `query` namespace — entity diffing

- **`(q/diff-entity entity-class id thunk)`** — Mutation observer for a single entity by PK.
  Answers the question *"what did this service method actually write to the database?"* —
  the gap that `trace/trace-sql` and `jpa/jpa-query` leave open.
  See [`specs/03-diff-entity.md`](specs/03-diff-entity.md) for full spec, use cases, and implementation notes.

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

### `trace/trace-sql` — add startup warning for Hibernate version mismatch

Fixed in current build, but worth adding a startup warning or assertion if the
`StatementInspector` fails to register — currently fails silently with `{:count 0, :queries []}`.

---

## ❌ Explicitly Out of Scope (v1)

- `@NamedQuery` / XML-defined queries — deferred.
- Production use — dev-only by design.
- Clojure knowledge requirement — the REPL surface is for agents and the developer who sets it up.
- Replacement of existing test infrastructure — complement only.
