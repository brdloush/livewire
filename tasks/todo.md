# Livewire — Open Tasks & Ideas

Multi-session task list. Check and update at session start and end.
Each entry should carry enough context for a fresh agent to resume without asking.

---

_Last updated: 2026-03-21_

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

### ~~faker support — Phase 0: extend `introspect` with `@Column` annotation metadata~~

✅ Done — `:nullable`, `:length`, `:unique`, `:column-definition` added to `inspect-entity` via reflection. Committed in `711c1f5`.

<!--

**Context:** The Hibernate runtime metamodel does not expose `nullable`, `length`, `unique`, or
`columnDefinition`. These are needed by the faker heuristic generator to produce valid entities
without hitting DB constraint violations. This is a prerequisite for Phase 1.

**What to do:**
- Extend `inspect-entity` (and `inspect-all-entities`) to walk entity class fields/methods via
  Java reflection, reading `@Column` and `@ManyToOne`/`@JoinColumn` annotations.
- Add new keys per property: `:nullable`, `:length`, `:unique`, `:column-definition`.
- `find-field` must walk the full class hierarchy (superclasses, `@MappedSuperclass`); try field
  first, fall back to getter method for annotation lookup.

**Acceptance criteria:**
- `(intro/inspect-entity "Book")` → `:nullable false` on `title`, `availableCopies`, `archived`, `author`; `:length 255` on `title`; `:length 20` + `:unique true` on `isbn`.
- `(intro/inspect-entity "LibraryMember")` → `:nullable false :unique true` on `username` and `email`.
- `(intro/inspect-entity "Review")` → `:column-definition "TEXT"` on `comment`.
- No regression on properties with no `@Column` (e.g. `birthYear`, `nationality` on `Author`): new keys return `nil`.

**Spec:** `specs/04-faker-support.md` § Phase 0

-->

---

### ~~faker support — Phase 1: `net.brdloush.livewire.faker` + deliverables~~

✅ Done — `faker/build-entity`, `faker/available?`, `lw-build-entity` wrapper, SKILL.md section,
README.md, web pages all shipped in `711c1f5` + `e0c4c65`. Lookup-table heuristic refined in
the follow-up fix below.

---

## 💡 Ideas (not yet spec'd)

### `faker` namespace — study PHP Faker's field-name guesser for heuristic inspiration

**Context:** While researching whether net.datafaker has a built-in "guess generator by
field name" feature (it doesn't), we discovered that the **PHP Faker** library does:
its `Populator` uses name and column-type guessers — a column named `first_name` gets
`firstName`, a `TIMESTAMP` column gets `dateTime`, etc. The Java ecosystem has no equivalent;
our heuristic table in `faker.clj` fills this gap.

**Why it's worth studying:** PHP Faker's guesser has been battle-tested across many real-world
domains. Studying its guesser rules (field name → provider mappings) could reveal patterns
and field names we haven't covered yet — particularly for financial, healthcare, address,
or e-commerce domains.

**Reference:**
- PHP Faker Populator: https://github.com/fzaninotto/Faker (archived — see `src/Faker/ORM/*/Populator.php`)
- The guesser logic: column names like `first_name`, `email`, `phone_number`, `address`,
  `city`, `country`, `zip_code`, `company`, `website`, `description`, `created_at`, etc.
  each map to a specific Faker formatter. Type-based fallbacks handle `TIMESTAMP`, `INTEGER`,
  `FLOAT`, `BOOLEAN`, `TEXT` etc.

**What to do:** read through the guesser source, extract the name→provider mappings, and
consider which ones are missing from our `heuristic-table` in `faker.clj`. File a spec if
a meaningful expansion is warranted.

---

### ~~`core` namespace — `@Transactional` boundary introspection~~

✅ Done — `lw/bean-tx`, `lw/all-bean-tx` (`:app-only true` default), `lw-bean-tx` and
`lw-all-bean-tx` CLI wrappers added. SKILL.md, README.md, and web pages updated.

<!--

**Context:**
Transactional boundaries are one of the most common sources of subtle Spring bugs —
unexpected rollbacks, silent no-ops on non-public methods, wrong propagation nesting,
read-only violations, missing transactions on self-invocations. Currently neither an agent
nor a human developer can see the effective transaction configuration without reading source.
Livewire can expose this cleanly at runtime with no static analysis required.

**What to implement:**
Two functions in `net.brdloush.livewire.core` (aliased as `lw`):

- **`(lw/bean-tx "beanName")`** — returns `{:bean … :class … :methods […]}` where each
  method entry contains `:method`, `:propagation`, `:isolation`, `:read-only`,
  `:rollback-for`, `:no-rollback-for`, `:timeout`. Only methods with an effective
  `@Transactional` (directly or inherited from class level) are included.
- **`(lw/all-bean-tx)`** — same `:app-only true` default as `all-bean-deps`; returns the
  transactional surface of all application-level beans in one call.

**Implementation notes:**

Spring registers `transactionAttributeSource` as a bean when `@EnableTransactionManagement`
is active. It is an `AnnotationTransactionAttributeSource` and its
`.getTransactionAttribute(method, targetClass)` method returns the full `TransactionAttribute`
for any method+class pair, or `nil` if the method is not transactional. This handles both
method-level and class-level `@Transactional` correctly, including JPA repository defaults
(`SimpleJpaRepository` is `@Transactional(readOnly = true)` at class level).

Suggested algorithm (validate in the REPL first):
1. Get `transactionAttributeSource` bean from the context — if absent, `@EnableTransactionManagement`
   is not active; return an informative error.
2. For each target bean: resolve the target class via `AopUtils/getTargetClass` (unwraps CGLIB proxy).
3. Iterate `.getDeclaredMethods` on the target class — restrict to public methods only
   (non-public `@Transactional` is silently ignored by Spring; surfacing them here as
   non-transactional is correct behaviour and potentially the most useful diagnostic).
4. Call `.getTransactionAttribute txAttrSource method targetClass` for each; collect non-nil results.
5. Map `TransactionAttribute` fields to Clojure keywords:
   - `.getPropagationBehavior` → keyword via `TransactionDefinition` constants
   - `.getIsolationLevel` → keyword
   - `.isReadOnly` → boolean
   - `.getRollbackRules` → seq of class names (distinguish rollback vs no-rollback rules)
   - `.getTimeout` → integer (-1 = default)

**Propagation/isolation constants mapping** (validate exact int values in REPL):
```
PROPAGATION_REQUIRED=0 PROPAGATION_SUPPORTS=1 PROPAGATION_MANDATORY=2
PROPAGATION_REQUIRES_NEW=3 PROPAGATION_NOT_SUPPORTED=4 PROPAGATION_NEVER=5
PROPAGATION_NESTED=6
ISOLATION_DEFAULT=-1 ISOLATION_READ_UNCOMMITTED=1 ISOLATION_READ_COMMITTED=2
ISOLATION_REPEATABLE_READ=4 ISOLATION_SERIALIZABLE=8
```

**Known gaps (by design):**
- Programmatic transactions (`TransactionTemplate`, direct `PlatformTransactionManager`) —
  no annotation trail; undetectable without static analysis. Document in SKILL.md.
- `@Transactional` via XML — effectively extinct; not worth handling.

**Useful derived queries to document in SKILL.md:**
```clojure
;; Methods that look like reads but aren't marked read-only — potential performance smell
(->> (lw/all-bean-tx)
     (mapcat :methods)
     (filter #(and (not (:read-only %))
                   (re-find #"(?i)^(get|find|list|count|search|fetch)" (:method %)))))

;; All REQUIRES_NEW methods — potential nested transaction complexity
(->> (lw/all-bean-tx)
     (mapcat (fn [b] (map #(assoc % :bean (:bean b)) (:methods b))))
     (filter #(= :requires-new (:propagation %))))
```

-->

### ~~`core` namespace — bean dependency introspection~~

✅ Done — `lw/bean-deps`, `lw/all-bean-deps` (`:app-only true` default, `:class` field),
`lw-bean-deps` and `lw-all-bean-deps` CLI wrappers added. SKILL.md, README.md, and web pages updated.

### ~~`introspect` namespace — batch entity inspection (`inspect-all-entities`)~~

✅ Done in `dcf1c3a` — `intro/inspect-all-entities` and `lw-inspect-all-entities` CLI wrapper added.

---

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

### ~~`trace/trace-sql` — add startup warning for Hibernate version mismatch~~

✅ Fixed in `84ad440` — `boot/start!` now checks the active `StatementInspector` after context
startup and prints a `[livewire] WARNING` if it is not `LivewireSqlTracer`.

---

## ❌ Explicitly Out of Scope (v1)

- `@NamedQuery` / XML-defined queries — deferred.
- Production use — dev-only by design.
- Clojure knowledge requirement — the REPL surface is for agents and the developer who sets it up.
- Replacement of existing test infrastructure — complement only.

---

<!--
## Entry format

### [ ] Short imperative title
**Context:** Why this needs to be done, what problem it solves.
**Blocked by:** Anything that must happen first (or omit if unblocked).
**Notes:** Any relevant findings, partial progress, or decisions made so far.

Change [ ] → [x] when done, then remove or archive the entry.
-->
