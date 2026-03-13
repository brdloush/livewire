# Changelog

All notable changes to Livewire will be documented here.

Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versions follow [Semantic Versioning](https://semver.org/).

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
