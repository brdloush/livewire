# Changelog

All notable changes to Livewire will be documented here.

Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versions follow [Semantic Versioning](https://semver.org/).

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
