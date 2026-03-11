# Livewire — Open Tasks & Ideas

---

## 🚧 In Progress

### `trace` namespace — missing functions
Spec: `specs/01-initial-project-scope.md` → Component 4

- **`(hibernate-stats)`** — Snapshot of Hibernate's `Statistics` object: cache hit/miss ratios,
  entity load counts, collection fetch counts, slow queries.

---

## 🔜 Planned (fully spec'd, not yet started)

### `query` namespace — JPQL execution and entity diffing
Spec: `specs/01-initial-project-scope.md` → Component 3

- **`(jpql query & params)`** — Execute a JPQL query via the live `EntityManager` inside a
  read-only transaction. Return results as Clojure maps.

- **`(diff-entity entity-class id thunk)`** — Capture entity state before and after calling
  `thunk`, return a diff. Used to observe what a service method actually changes.

### ~~`hot-queries` namespace — live `@Query` swap engine~~ → ✅ Done (see below)

### `query-watcher` namespace — file watcher + ASM bytecode reader
Spec: `specs/01-initial-project-scope.md` → Component 6

Makes `hot-swap-query!` invisible to Java/Kotlin developers. They change a `@Query` annotation,
press Recompile (Ctrl+F9) — the query is live within milliseconds, zero REPL interaction needed.

Mechanism:
1. `WatchService` monitors compiled output directories for `.class` file changes.
2. On change, **ASM reads the bytecode** (no classloading, no ClassLoader pollution) and
   extracts all `@Query` annotation values.
3. Diffs against a known-state registry to identify only changed queries.
4. Calls `hot-swap-query!` for each changed method.

Auto-detect output directories at startup:
- `target/classes` (Maven / IntelliJ delegating to Maven)
- `build/classes/java/main`, `build/classes/kotlin/main` (Gradle)
- `out/production/classes` (IntelliJ with own compiler)

Console output on swap:
```
[query-watcher] hot-swapping OrderRepository#findActiveByUserId
```

#### Todo

- [x] **Add ASM dependency** — declare `org.ow2.asm/asm` in `project.clj` (don't rely on the
  transitive copy bundled inside Spring/Hibernate JARs).
- [x] **ASM bytecode reader** — implement a fn that reads a `.class` file path and returns a map
  of `method-name → jpql-string` for every `@Query` annotation found; no classloading, pure
  bytecode inspection.
- [x] **Class→bean-name resolver** — given an ASM-extracted class name (e.g.
  `com/example/BookRepository`), map it to the matching Spring bean name via
  `core/beans-of-type` (or equivalent); needed before calling `hot-swap-query!`.
- [x] **Known-state registry** — define a `defonce` atom; populate it at watcher startup by
  scanning all repository beans via `hot-queries/list-queries` to establish the baseline
  `{[bean method] → jpql}` snapshot.
- [x] **Output directory auto-detection** — at startup, check all four candidate paths
  (`target/classes`, `build/classes/java/main`, `build/classes/kotlin/main`,
  `out/production/classes`) and collect every one that exists (watch all, not just the first).
- [x] **File watcher core** — implement `start-watcher!` using `java.nio.file.WatchService`
  over the detected dirs; on each `.class` change: run ASM reader → diff result against
  registry → call `hot-swap-query!` for each changed method → update registry entry.
- [x] **Fast guard for non-repository classes** — skip ASM parse entirely when the changed
  `.class` file clearly doesn't contain `@Query` annotations (e.g. check constant-pool string
  presence before full parse).
- [x] **Console logging** — emit `[query-watcher] hot-swapping Repo#method` to stdout for
  each successful swap.
- [x] **Lifecycle hookup** — wire `start-watcher!` into `boot/start!`; make it idempotent
  (`defonce` thread/guard); implement `stop-watcher!` that closes the `WatchService` and
  terminates the watch thread cleanly.
- [x] **Validate in Bloated Shelf** — edit a `@Query` annotation in the demo app, hit
  Recompile (Ctrl+F9), confirm the live query updates within milliseconds with zero REPL
  interaction.

---

## ✅ Done

### `hot-queries` namespace — live `@Query` swap engine
Spec: `specs/01-initial-project-scope.md` → Component 5

Implemented in `net.brdloush.livewire.hot-queries`. Instead of replacing the
`RepositoryQuery` entry in the queries map with a reify, the implementation
mutates the `queryString` Lazy field inside `DefaultEntityQuery` (held by
`AbstractStringBasedJpaQuery`) to be atom-backed. The original `SimpleJpaQuery`
— with all of Spring Data's result-type coercion — stays in place.

- **`(list-queries repo-bean-name)`** — lists all `@Query` methods with current JPQL
- **`(hot-swap-query! repo-bean method-name new-jpql)`** — first call uses reflection
  to wire up an atom-backed `Lazy<String>`; subsequent calls just `reset!` the atom
- **`(list-swapped)`** — global registry of all currently swapped queries
- **`(reset-query! repo-bean method-name)`** — restores the original `Lazy` and deregisters

Verified live on `bookRepository#findByIdWithDetails` in Bloated Shelf.

### `core` namespace — `run-as`
Spec: `specs/01-initial-project-scope.md` → Component 1

- **`(run-as user-details & body)`** — Implemented in `net.brdloush.livewire.core`. Sets
  `SecurityContextHolder`, runs body, restores original context. Accepts a plain username string,
  a `[username "ROLE_X"]` vector, or an existing `Authentication` object.
  Verified live against Bloated Shelf: called `bookController#getBookById` as
  `superadmin@example.com` — full 22-question book details returned with no
  `AuthenticationCredentialsNotFoundException`.

---

## 💡 Ideas (not yet spec'd)

### REPL-driven test runner
Source: `ideas/unit-tests-execition-and-simple-jpda-fixes.md`

When the app runs in debug mode with `-Dexec.classpathScope=test`, the full test classpath
(compiled test classes, JUnit 5, Mockito) is already live in the JVM. This enables running unit
tests directly from the REPL without any Maven invocation, with a very tight loop:

1. Edit source in the IDE
2. Recompile the single file (e.g. via `build_project filesToRebuild`)
3. Hotswap kicks in (debug agent replaces method bodies in the running JVM)
4. Re-run the test via JUnit Platform Launcher API from the REPL

**`(lw/run-test "com.example.MyTest")`** or **`(lw/run-test "com.example.MyTest" "method-name")`**
wrapper in the core namespace. Could also support running all tests in a package via
`DiscoverySelectors/selectPackage`. Summary output formatted as a markdown table.

Known limitations to document:
- Structural changes (adding/removing methods, fields, changing hierarchy) don't hotswap —
  requires a full JVM restart.
- Requires debug mode (JVM must be started with a debug agent).
- Requires `-Dexec.classpathScope=test` for test classes and test-scoped deps to be on the
  runtime classpath.
- Static state in test subjects is shared across runs (same as normal, but worth noting).

Open questions:
- **Reload-friendly classloader for test classes** — test classes are probably never loaded
  automatically before the first test run, which might offer a hook for a disposable,
  reload-friendly classloader specifically for them.
- **Integration test support** — can integration tests be triggered via JUnit Platform Launcher
  from the live REPL? Only unit tests have been tried so far.

---

## 🐛 Known Bugs / Docstring Issues

### `intro/list-entities` key name mismatch
Source: `skills/livewire/SKILL.md`

The docstring says "simple name + FQN" but the actual map keys are `:name` and `:class`, not
`:simple-name`. Using `:simple-name` silently returns `nil` for every entry and causes a
`NullPointerException` in regex filters.

Fix: correct either the docstring or the key names (and update `SKILL.md` and `README.md`).

```clojure
;; ❌ NullPointerException
(->> (intro/list-entities) (map :simple-name) (filter #(re-find #"Foo" %)))

;; ✅ correct
(->> (intro/list-entities) (map :name) (filter some?) (filter #(re-find #"Foo" %)))
```

### `trace/trace-sql` silent no-op on older Livewire builds
Source: `skills/livewire/SKILL.md`

Older builds didn't register any `StatementInspector` on Hibernate 7 apps, resulting in
`{:count 0, :queries []}` with no error. Fixed in current snapshot, but worth adding a startup
warning or a version assertion.

---

## ✅ Pending Verification

### `bookController` N+1 fix
Source: `internal-testing.md`

A severe N+1 issue was traced and identified (30 queries, ~1000ms) — `client_account_yields`
fetched individually for each account in a loop. The fix (e.g. `JOIN FETCH` or `@EntityGraph`
on the repository method) **has not yet been applied or verified**.

Use this snippet to verify once the fix is in place:

```clojure
(require '[net.brdloush.livewire.core :as lw]
         '[net.brdloush.livewire.trace :as trace])

(trace/trace-sql
  (.getAllBooks (lw/bean "bookController")
                      25))
```

---

## ❌ Explicitly Out of Scope (v1)

- `@NamedQuery` / XML-defined queries — not supported in v1, deferred.
- Production use — all components are `dev`-profile only, by design.
- Clojure knowledge for Java/Kotlin developers — the REPL surface is for the agent and the one
  developer who sets it up.
- Replacement of existing test infrastructure — complement only.
