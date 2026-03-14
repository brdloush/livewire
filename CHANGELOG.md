# Changelog

All notable changes to Livewire will be documented here.

Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versions follow [Semantic Versioning](https://semver.org/).

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
