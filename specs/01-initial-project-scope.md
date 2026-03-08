# Livewire

> *Live wire into your Spring app. Dev only. You've been warned.*

---

*Don't touch live wires in production. But in dev? Grab on.*

---

## Motivation

Modern Java/Kotlin Spring Boot development suffers from a fundamental feedback loop problem: the inner loop of **edit → restart → observe** is slow, especially when iterating on JPA queries, debugging service behavior, or exploring a live domain model. Restarts routinely cost 30 seconds to 2 minutes, and they destroy all in-memory state accumulated during a debugging session.

Clojure developers have long solved this with an interactive REPL — a live, stateful, introspectable runtime where hypotheses are tested immediately against the running system. This project brings that leverage to Spring Boot applications, transparently, without requiring the Java/Kotlin team to learn Clojure or change their workflow.

The mechanism is an **nREPL server embedded in the Spring Boot application**, hosting a Clojure namespace that has direct access to the Spring ApplicationContext and all its beans. On top of this, an agentic coding assistant (e.g. Claude Code via `clojure-mcp`) can use `clojure_eval` as a live probe — closing the loop between static code reasoning and real runtime behavior.

---

## Architecture Overview

```
Spring Boot App (running)
  └── nREPL server (embedded, dev profile only)
        └── dev.core namespace
              ├── Spring ApplicationContext reference (injected at boot)
              ├── Utility namespaces (query, introspection, tracing...)
              └── Hot-swap engine (query watcher + live query registry)

Claude Code (agentic)
  └── clojure-mcp  →  clojure_eval  →  nREPL  →  live app
```

The nREPL server is **never active in production**. It is gated behind a Spring profile (`dev`) and optionally a system property. You've been warned.

---

## Component 1: Foundation Namespace (`dev.core`)

Bootstraps access to the running Spring context and exposes core primitives used by all other namespaces.

**Key functions:**
- `(ctx)` — returns the live `ApplicationContext`
- `(bean name-or-class)` — retrieves a Spring bean by name or type
- `(beans-of-type clazz)` — returns all beans matching a type
- `(bean-names)` — lists all registered bean names
- `(all-properties)` — dumps resolved environment properties
- `(props-matching pattern)` — filters properties by regex

**Key macros:**
- `(in-tx & body)` — executes body in a real Spring transaction, **rolls back by default**. Safe mutation exploration.
- `(in-readonly-tx & body)` — same, read-only semantics
- `(run-as user-details & body)` — sets `SecurityContextHolder`, runs body, restores context. Essential for authorization debugging.

---

## Component 2: Introspection Tools (`dev.introspect`)

Allows the agent (or developer) to interrogate the live application structure without reading source files.

**`(list-endpoints)`**
Introspects `RequestMappingHandlerMapping`. Returns a data structure of all HTTP endpoints: method, path, controller class, handler method name, parameters, produces/consumes media types.

**`(inspect-entity entity-class)`**
Reads Hibernate's metamodel for a given entity. Returns: mapped table name, column mappings, relation definitions (type, fetch strategy, cascade), and known indexes. No annotation reading required — the live metamodel is the source of truth.

**`(find-beans-matching pattern)`**
Filters all bean names by regex. Useful for discovering beans related to a feature domain.

---

## Component 3: Query & Data Tools (`dev.query`)

Allows the agent to execute queries through the application layer — with Hibernate type converters, active `@Filter`s, and proper transaction semantics.

**`(jpql query & params)`**
Executes a JPQL query via the live `EntityManager` inside a read-only transaction. Returns results as Clojure maps.

**`(sql query & params)`**
Executes a native SQL query similarly.

**`(diff-entity entity-class id thunk)`**
Captures entity state before and after calling `thunk`, returns a diff. Used to observe what a service method actually changes.

---

## Component 4: Tracing Tools (`dev.trace`)

**`(trace-sql & body)`**
Wraps body and captures every SQL statement fired by Hibernate during execution. Returns `{:result ... :queries [...] :count n :duration-ms n}`. Implemented by instrumenting a `StatementInspector` or P6Spy datasource proxy.

**`(detect-n+1 thunk)`**
Calls `trace-sql` on thunk, groups queries by normalized form, and flags any query template that fires more than a threshold number of times. Returns suspicious patterns with counts.

**`(hibernate-stats)`**
Snapshot of Hibernate's `Statistics` object: cache hit/miss ratios, entity load counts, collection fetch counts, slow queries.

**`(call-service bean-name method-sym & args)`**
Invokes a service method via reflection, inside a read-only transaction, with full Spring AOP active (security interceptors, transaction advice, etc.). This is the key tool for "exercise the real code path and observe."

---

## Component 5: Hot Query Swap Engine (`dev.hot-queries`)

### The Problem
`@Query` annotations on Spring Data repository methods are compiled into `SimpleJpaQuery` / `NativeJpaQuery` objects at application startup and stored in a private `Map<Method, RepositoryQuery>` inside `QueryExecutorMethodInterceptor`. Changing a query today requires a full application restart.

### The Approach
**Reach into the queries map via reflection and replace entries** with atom-backed live wrappers. The atom holds the current JPQL string. Swapping the query becomes `reset!` on the atom — no reflection on subsequent swaps, no ClassLoader manipulation.

**`(hot-swap-query! repo-bean method-name new-jpql)`**
- First call: reflects into the queries map, wraps the existing `RepositoryQuery` in a `LiveQuery` reify backed by an atom, registers it in a global registry
- Subsequent calls for the same method: `reset!` on the existing atom only
- Returns `{:swapped key :query jpql}`

The live wrapper delegates `getQueryMethod` to the original (preserving parameter metadata), and re-executes the JPQL from the atom on every `execute` call, handling both named and positional parameters.

---

## Component 6: File Watcher (`dev.query-watcher`)

### Purpose
Makes `hot-swap-query!` invisible to Java/Kotlin developers. They change a `@Query` annotation and press **Recompile** (Ctrl+F9 / Cmd+F9 in IntelliJ). The new query is live within milliseconds. No REPL interaction required.

### Mechanism
1. A `WatchService` monitors the compiled output directories for `.class` file changes
2. On change, **ASM reads the bytecode** of the modified class (no classloading, no ClassLoader pollution) and extracts all `@Query` annotation values
3. Diffs against a known-state registry to identify only changed queries
4. Calls `hot-swap-query!` for each changed method

**Resolves output directories automatically** at startup by checking which of the following exist:
- `target/classes` (Maven / IntelliJ delegating to Maven)
- `build/classes/java/main`, `build/classes/kotlin/main` (Gradle)
- `out/production/classes` (IntelliJ with own compiler)

**Console output on swap:**
```
[query-watcher] hot-swapping OrderRepository#findActiveByUserId
```

That is the entirety of what the Java/Kotlin developer sees.

---

## The Agentic Loop (Primary Motivation)

The compound value of all components together is an agentic feedback loop that eliminates guesswork:

```
Agent reads bug report / failing test
  → (inspect-entity) to understand mappings
  → (jpql ...) to probe data directly
  → (hot-swap-query!) to try a fix
  → (call-service ...) to verify through business logic
  → (trace-sql ...) to confirm query shape
  → (detect-n+1 ...) to validate no regressions
  → repeat until correct
  → write fix back to .java / .kt source
```

Today, agents reason about Spring applications *statically* and guess at runtime behavior. This toolkit gives the agent the ability to be wrong and find out immediately — the same leverage a Clojure developer has at a REPL, applied to a Java/Kotlin Spring codebase.

---

## Non-Goals

- No production use. All components are `dev` profile only.
- No replacement of existing test infrastructure. This is a development-time complement.
- No Clojure knowledge required from Java/Kotlin developers. The REPL surface is for the agent and for the one developer who sets this up.
- No support for `@NamedQuery` (XML-defined queries) in v1.

---

## Suggested Module Layout

```
src/
  main/
    clojure/
      dev/
        core.clj          ; context access, in-tx, run-as
        introspect.clj    ; list-endpoints, inspect-entity
        query.clj         ; jpql, sql, diff-entity
        trace.clj         ; trace-sql, detect-n+1, hibernate-stats, call-service
        hot_queries.clj   ; hot-swap-query!, live query registry
        query_watcher.clj ; file watcher, ASM bytecode reader, auto-dir detection
        boot.clj          ; nREPL server start, namespace init, context injection
```

`boot.clj` is the only entry point Spring needs to know about — a `@Component` / `@Bean` conditioned on `@Profile("dev")` that starts the nREPL server and injects the `ApplicationContext` reference into `dev.core`.


## References

- https://engineering.telia.no/blog/java-troubleshooting-on-steroids-with-clojure-repl
 
