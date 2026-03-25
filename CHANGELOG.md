# Changelog

All notable changes to Livewire will be documented here.

Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versions follow [Semantic Versioning](https://semver.org/).

---

## [0.9.0] — 2026-03-25

### Added

- **`cg/blast-radius`** — method-level call graph and entry-point impact analysis. Given a
  bean name and method name, walks the bean dependency graph upward via BFS, intersects with
  a bytecode call graph extracted at runtime via ASM, and returns every bean method that
  transitively calls the target — annotated with `:depth` (hop count) and `:entry-point`
  (HTTP endpoint, `@Scheduled`, or `@EventListener` metadata). The call-graph index is built
  once (~30ms for a typical app) and cached; `(cg/reset-blast-radius-cache!)` invalidates it.
  New namespace `net.brdloush.livewire.callgraph` (`cg` alias), `lw-blast-radius` CLI wrapper.
- **`intro/constraint-meta`** — reads `jakarta.validation.constraints` (and `javax` fallback)
  from a field or getter by reflection. Returns a map of constraint keys (`:not-null?`,
  `:not-blank?`, `:email?`, `:past?`, `:min`, `:max`, `:size-min`, `:size-max`, `:positive?`,
  `:positive-or-zero?`, `:pattern`).
- **`intro/read-constraints`** — returns a `{field-name → constraint-map}` index for all
  properties of a named entity. Suitable for use before writing test data or queries.
- **`:constraints` key in `inspect-entity`** — every property map in `inspect-entity` output
  now carries a `:constraints` vector of human-readable annotation strings, e.g.
  `["@NotNull" "@Size(min=0,max=100)"]`. Empty vector when none.
- **Constraint-aware `faker/build-entity`**:
  - *Fail-fast override validation* — before any setter is called, overrides are checked
    against `@NotNull`, `@NotBlank`, `@Min`, `@Max`, `@Positive`, `@PositiveOrZero`.
    Throws `ex-info` immediately with a clear message naming the entity, field, and
    violated annotation. Previously surfaced as an opaque `ConstraintViolationException`
    from Hibernate at flush time.
  - *Constraint-aware generation* — `@Min`/`@Max` clamp numeric ranges; `@Email` falls back
    to `faker.internet().emailAddress()` when the name heuristic wouldn't produce an email;
    `@Positive`/`@PositiveOrZero` reflect negatives; `@Size(max=…)` takes the most
    restrictive max vs `@Column(length=…)`; `@Pattern` emits a warning (regex generation
    is not supported).
- **`intro/list-endpoints`** now includes `:required-roles` and `:required-authorities`
  (parsed from `@PreAuthorize` SpEL) and enriched `:parameters` maps with `:source`
  (`:path`/`:query`/`:body`/`:header`), `:required`, and `:default-value`.
- **`cg` alias** pre-wired in the `user` namespace at nREPL startup.

### Fixed

- **`all-bean-deps :app-only true`** now correctly includes Spring Data JPA repository beans.
  Previously, beans whose `BeanClassName` is `JpaRepositoryFactoryBean` (a Spring class)
  were silently excluded even though the app-defined repository interface is app code. Fixed
  by falling back to the bean instance's proxy interfaces when `BeanClassName` doesn't match
  the root package. Applies to both `core/all-bean-deps` and the internal predicate used by
  `callgraph/blast-radius`.

## [0.8.0] — 2026-03-21

### Added

- **`faker/build-entity`** — builds valid, optionally-persistable Hibernate entity instances
  using realistic fake data from `net.datafaker`. Supports `:overrides`, `:auto-deps?`
  (recursive `@ManyToOne` resolution), `:persist?`, and `:rollback?`. All datafaker access
  is reflective — the namespace loads safely on apps without datafaker on the classpath.
  Heuristic table covers common field names (`firstName`, `email`, `username`, `isbn`,
  `*Year`, `*At`/`*Since`/`*Date`/`*Time` timestamps) and type fallbacks including
  `String`, `int`/`long`/`short`, `BigDecimal`/`Double`/`Float`, `boolean`, `UUID`,
  `LocalDate`, and `LocalDateTime`.
- **`faker/available?`** — lightweight preflight check for `net.datafaker.Faker` on the classpath.
- **`faker/build-test-recipe`** — builds the full entity graph and extracts all scalar field
  values into an ordered map keyed by entity class name (root entity first, `@ManyToOne`
  dependencies after). Use before writing integration test setup code so test values flow
  directly from the validated REPL prototype rather than being invented.
- **`lw-build-entity`** and **`lw-build-test-recipe`** CLI wrapper scripts.
- **`lw/bean->map`** — converts any Java object to a Clojure map, handling both regular
  JavaBeans and Java records (Java 16+). `clojure.core/bean` silently returns `{}` for
  records because it only scans `getX()` accessors; `bean->map` uses
  `Class.getRecordComponents()` for records and falls back to `clojure.core/bean` otherwise.
- **`intro/inspect-entity`** and **`intro/inspect-all-entities`** now include `@Column` and
  `@ManyToOne` annotation metadata per property: `:nullable`, `:length`, `:unique`, and
  `:column-definition`. Read via reflection; walks the full class hierarchy including
  `@MappedSuperclass`. Field annotations are tried first, getter annotations as fallback.
- **`faker` alias** pre-wired in the `user` namespace at nREPL startup alongside the existing
  `lw`, `q`, `intro`, `trace`, `qw`, `hq`, `jpa`, `mvc` aliases.

### Fixed

- **`faker/build-entity` lookup-table heuristic** — `LibraryMember` and similar domain
  entities were incorrectly classified as reference/lookup tables (like `Genre`) because
  they have unique string columns but no required `@ManyToOne` associations. Added a third
  condition: the entity must have no `@OneToMany` / `@ManyToMany` collections. Domain
  entities own collections; static lookup tables do not.
- **`faker/build-entity` empty lookup-table** — when a dependency is correctly classified as
  a lookup table but the table is empty (no seed data), the previous behaviour silently
  passed `null` to a non-nullable FK → cryptic Hibernate `PropertyValueException` far from
  the root cause. Now throws a descriptive `ex-info` naming the entity, the dep field, and
  three concrete escape hatches.
- **`faker/build-entity` override coercion** — `:overrides` values were passed to `.invoke`
  without type coercion, causing `IllegalArgumentException` when a plain Clojure `int` was
  supplied for a `Short` setter (e.g. `{:rating 5}`). `coerce-value` is now applied to
  override values, matching the behaviour of heuristic-generated values.
- **`entity_serialize/entity->map`** — replaced `clojure.core/bean` with `core/bean->map`
  so DTO records returned by JPQL constructor expressions or service methods serialize as
  proper Clojure maps instead of `{}` or string representations.

### Changed

- Startup log message updated to include `faker` in the alias list.

---

## [0.7.0] — 2026-03-19

### Added

- **`lw/bean-deps`** — returns the runtime wiring map for a single bean:
  `{:bean … :class … :dependencies […] :dependents […]}`. Uses Spring's internal
  dependency tracking (populated during context refresh), covering constructor injection,
  `@Autowired`, and `@Inject` fields. CGLIB proxy suffixes are stripped from `:class`.
- **`lw/all-bean-deps`** — returns the full wiring graph for every bean matching the filter.
  Defaults to `:app-only true`, auto-detecting the application's root package from the
  `@SpringBootApplication`-annotated class so only user-defined beans are returned (not
  250+ Spring Boot internals). Pass `:app-only false` for the unfiltered set.
- **`lw-bean-deps`** and **`lw-all-bean-deps`** CLI wrapper scripts.

### Changed

- **`AGENTS.md`** — added a feature delivery checklist for nREPL-facing features, covering
  Clojure implementation, wrapper scripts, SKILL.md, README.md, and web page updates.

---

## [0.6.0] — 2026-03-16

### Added

- **`boot/start!` StatementInspector warning** — on startup, Livewire now verifies that
  `LivewireSqlTracer` is the active Hibernate `StatementInspector` and prints a clear
  `[livewire] WARNING` if it is not, instead of silently returning `{:count 0, :queries []}`
  from `trace-sql`.
- **`intro/inspect-all-entities`** — returns full Hibernate metamodel detail for every entity
  in a single call. Equivalent to calling `inspect-entity` once per entity but without the
  per-entity round-trip overhead. Useful for agents building ER diagrams or reasoning about
  the full domain model. Also adds the `lw-inspect-all-entities` CLI wrapper.
- **`docs/sb3-vs-sb4-differences.md`** — reference document covering Spring Boot 3.x vs 4.x
  compatibility differences relevant to Livewire (Jackson 2 vs 3, converter class rename,
  `HibernatePropertiesCustomizer` removal, Hibernate 6 vs 7 API compatibility).

### Fixed

- **`mvc/serialize` fails to load on Spring Boot 3.x** — `mvc.clj` previously hard-imported
  `JacksonJsonHttpMessageConverter` (Spring Framework 7 / Jackson 3 only), causing a
  `ClassNotFoundException` at namespace load time on Spring Boot 3.x. The import is now
  replaced with a lazy classpath probe that resolves whichever converter is present
  (`JacksonJsonHttpMessageConverter` on SB4, `MappingJackson2HttpMessageConverter` on SB3).
  This fixes `lw-call-endpoint` being completely broken and all other CLI scripts printing a
  noisy error on Spring Boot 3.x apps. Verified on SB3.5.9/SF6/Hibernate 6 and
  SB4.0.1/SF7/Hibernate 7.

---

## [0.5.0] — 2025-03-15

### Added

- **`q/diff-entity`** — mutation observer for Hibernate-managed entities. Captures entity
  state before and after calling a thunk, runs the thunk inside an auto-rollback transaction
  (database never touched), and returns `{:before … :after … :changed {key [old new]}}`.
  The primary use case is AI agent mutation investigation: systematically calling candidate
  service methods to trace which one caused unexpected entity state.
- **`entity-serialize`** — new internal namespace extracting the entity serialization
  machinery (previously private in `jpa-query`) into a shared helper used by both
  `jpa-query` and `diff-entity`.

### Changed

- `jpa-query` now delegates entity serialization to `entity-serialize`; no behaviour change.

---

## [0.4.0] — 2025-03-14

### Fixed

- **SKILL.md**: added YAML frontmatter (`name` + `description`) required by ECA for
  automatic skill discovery and loading. Without it ECA cannot index or surface the skill.
- **SKILL.md**: expanded the "Wrapper scripts" section with explicit guidance on locating
  the `bin/` directory via `find` — addresses ECA having trouble resolving wrapper script
  paths during sessions.

---

## [0.3.0] — 2025-03-14

### Added

- **`jpa-query`** — new namespace `net.brdloush.livewire.jpa-query` (`jpa` alias).
  `jpa/jpa-query` executes JPQL via the live `EntityManager` inside a read-only
  transaction, serializes results to plain Clojure maps with lazy collections rendered
  as `"<lazy>"`, ancestor-chain cycles as `"<circular>"`, and paging via
  `:page` / `:page-size` (default 0 / 20). Scalar projections (`SELECT expr, …`) are
  unpacked into maps keyed by `AS` aliases or positional `:col0`, `:col1`, … keys.
- **`mvc`** — new namespace `net.brdloush.livewire.mvc` (`mvc` alias).
  `mvc/serialize` serializes any Spring bean method result using the same Jackson
  `ObjectMapper` Spring MVC uses, with optional `:limit` for list truncation.
  Truncated results are Clojure vectors with `{:total :returned :content-size
  :content-size-gzip}` metadata. Binary/non-JSON results return a descriptive string.
- **`lw-jpa-query`** bin script — traced, paged JPQL execution from the shell.
- **`lw-call-endpoint`** bin script — calls a Spring bean method under a single
  Security role; list results capped at 20 by default (`--limit N` to override);
  output is pretty-printed JSON (or EDN with metadata for limited lists).
- **`lw-start`** bin script — combines nREPL discovery and `lw/info` into a single
  startup command.
- `intro/list-entities` now returns `:table-name` alongside `:name` and `:class`
  in a single call — no follow-up `inspect-entity` needed.

### Fixed

- **query-watcher**: new `@Query` methods added after app startup no longer crash
  with a misleading error. They are now logged as "restart required" and their JPQL
  is seeded into `disk-state` to suppress repeated messages on subsequent polls.
- **`jpa-query`**: scalar projections returning `Object[]` are now unpacked into
  readable Clojure maps instead of opaque `toString()` heap addresses.
- **`introspect`**: Hibernate cross-compatibility helpers (`safe-get-entity-persister`
  etc.) moved before their callers to fix a forward-reference load error introduced
  when `list-entities` was updated to resolve table names.
- All bin scripts now require all 8 standard Livewire namespaces (`lw`, `q`, `intro`,
  `trace`, `qw`, `hq`, `jpa`, `mvc`) so aliases are always available regardless of
  whether the boot-time `init-user-ns!` completed successfully.

---

## [0.2.0] — 2025-03-13

First public release. Repository made public on GitHub; landing page and
getting-started guide published.

No API changes from 0.1.0.

---

## [0.1.0] — 2025-03-12

First versioned release.

### Added

- **`core`** — `lw/bean`, `lw/beans-of-type`, `lw/bean-names`, `lw/find-beans-matching`,
  `lw/ctx`, `lw/info`, `lw/all-properties`, `lw/props-matching`,
  `lw/in-tx`, `lw/in-readonly-tx`, `lw/run-as`
- **`query`** — raw SQL and JPQL execution via live `DataSource` / `EntityManager`
- **`trace`** — `trace/trace-sql`, `trace/trace-sql-global`, `trace/detect-n+1`;
  works with both Hibernate 6 and 7
- **`hot-queries`** — `hq/hot-swap-query!`, `hq/list-queries`, `hq/list-swapped`,
  `hq/reset-query!`, `hq/reset-all!`; last-one-wins policy with query-watcher
- **`query-watcher`** — polls compiled output directories every 500 ms, auto-applies
  changed `@Query` JPQL on recompile via ASM bytecode inspection; no classloading
- **`introspect`** — `intro/list-endpoints`, `intro/list-entities`, `intro/inspect-entity`
- Spring Boot autoconfiguration — zero-annotation setup via `livewire.enabled=true`
- nREPL server embedded on port 7888 (configurable via `livewire.nrepl.port`)
