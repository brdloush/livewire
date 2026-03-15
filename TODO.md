# Livewire — Open Tasks & Ideas

---

## 🚧 In Progress

### `trace` namespace — `hibernate-stats`

- **`(trace/hibernate-stats)`** — Snapshot of Hibernate's `Statistics` object:
  cache hit/miss ratios, entity load counts, collection fetch counts, slow queries.

---

## 🔜 Planned

### `query` namespace — SQL tracing inside `diff-entity`

Investigate whether wrapping `diff-entity` in `trace/trace-sql` captures the UPDATE/INSERT
statements that Hibernate fires on the explicit `.flush` call inside `in-tx`.

Two constraints to verify:
1. `trace/trace-sql` uses a `StatementInspector` — does it intercept write statements, or
   only SELECTs?
2. Hibernate's write-behind (dirty checking) means the UPDATE only fires on flush. `diff-entity`
   already calls `.flush em` explicitly before the after-snapshot, so the UPDATE *should* fire —
   but whether it is captured by `StatementInspector` before the rollback needs confirming.

If it works, the composition `(trace/trace-sql (q/diff-entity ...))` would give both the
entity diff *and* the SQL shape of the write in one call — a natural complement to the
existing read-side tracing story. Worth documenting in SKILL.md if confirmed.

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
