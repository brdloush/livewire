---
name: livewire
description: Live nREPL probe for Spring Boot apps. Use when working with a Spring Boot application that has Livewire on its classpath to query the running JVM state, beans, database, and behaviour via the live REPL.
---

# Livewire — Live nREPL probe for Spring Boot apps

Use this skill whenever working with a Spring Boot application that has Livewire
on its classpath. Before answering any question about the running app's state,
beans, database, or behaviour — **try the live REPL first**. A live answer from
the JVM beats static analysis every time.

---

## Session start behaviour

### On skill load
Say exactly one line — nothing more:
> Livewire skill loaded. Run `lw-start` to connect to a running app, or ask `lw-help` for an overview of what Livewire can do.

### On `lw-help` (or any "what can Livewire do?" question)
Give a brief prose overview — no headers, no tables, no bullet soup:

> Livewire connects Claude to a running Spring Boot app via nREPL. From there you can inspect beans, entities, and HTTP endpoints; run JPQL queries and raw SQL against the live database; trace SQL to hunt N+1 problems and hot-swap `@Query` JPQL without restarting; observe exactly what fields a service call writes using `diff-entity`; analyse call graphs to find blast radius or plan a service split; and generate realistic fake entity graphs for test data prototyping. Run `lw-start` to connect, or ask about any specific feature.

### On successful `lw-start`
Present the result as a single brief paragraph — no tables, no bullet lists.
Include: app name, Spring Boot version, Hibernate version, Java version, active profiles, database product+version, and port. Example:

> Connected to **bloated-shelf** on port 7888 — Spring Boot 4.0.1 / Hibernate 7.2.0 / Java 25, profiles `dev` + `seed`. Database: PostgreSQL 16.9.

If `lw-start` finds no server, say so briefly and suggest the user start the app.

---

## Reference files

Load these on demand — read the relevant file before working in that area:

| File | Read when... |
|---|---|
| `references/api-core.md` | Working with beans, transactions, security contexts, entity/endpoint introspection, SQL tracing, JPQL queries, mutation observation (`diff-entity`), or hot-swapping `@Query` methods |
| `references/callgraph.md` | Analysing blast radius of a change, planning a service split, or detecting dead/internal-only methods |
| `references/writing-tests-and-fake-data.md` | Writing integration tests or prototyping test data with `faker/build-entity` / `faker/build-test-recipe` |
| `references/pitfalls.md` | You hit an unexpected error, are about to write a query, or want to verify correct conventions for data access, security, or argument passing |
| `references/n-plus-one-hunting.md` | Investigating N+1 query problems, measuring query counts, or validating that a JPQL / `JOIN FETCH` fix eliminates excess queries |

---

## Prerequisites

This skill relies on `clj-nrepl-eval` — a lightweight CLI from
[clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) that lets
Claude evaluate Clojure expressions against a running nREPL server.

Install it once with [bbin](https://github.com/babashka/bbin):

```bash
brew install babashka/brew/bbin

bbin install https://github.com/bhauman/clojure-mcp-light.git \
  --tag v0.2.1 \
  --as clj-nrepl-eval \
  --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
```

Verify: `clj-nrepl-eval --discover-ports`

---

## Workflow

1. **Start the session** — always run `lw-start` first:
   ```bash
   lw-start
   ```
   Discovers running nREPL servers and prints an app summary in one shot. The default port
   is **7888** and can be overridden with `LW_PORT`.

2. **Verify the connection is live:**
   ```bash
   clj-nrepl-eval -p 7888 "(require '[net.brdloush.livewire.core :as lw]) (lw/info)"
   ```
   **Never use `lw/bean-names` for connection checks** — it emits hundreds of names with no
   useful runtime information.

3. **Namespaces are pre-aliased** in the `user` ns — no manual `require` needed:
   `lw`, `q`, `intro`, `trace`, `qw`, `hq`, `jpa`, `mvc`, `faker`

4. **Evaluate** snippets iteratively:
   ```bash
   clj-nrepl-eval -p <port> "<clojure-code>"
   clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"
   ```

5. **Run independent read queries in parallel** — fire unrelated `clj-nrepl-eval` calls in a
   single message to reduce wall-clock time. Only serialize when one result feeds into the next.

6. **Present results readably:**
   - Collections of maps → markdown table
   - Single map → inline key/value list
   - Scalars → inline code in prose

7. **Hot-patching:** Do not use `:reload` to pick up a newly built JAR — it re-reads the same
   old class on the classpath. Instead, evaluate the new `ns` form and function bodies directly.

8. **After writing a source-code fix:** remind the user that a restart may be required for
   structural changes (new methods, new beans). `@Query` JPQL changes are picked up automatically
   by the query-watcher on recompile.

---

## Wrapper scripts

This skill ships named wrapper scripts in a `bin/` subdirectory next to this `SKILL.md`.

**Always use wrapper scripts for named operations** (SQL, JPQL, trace, N+1, endpoints,
beans, call graphs). They handle namespace requiring automatically and avoid shell escaping
issues. Fall back to raw `clj-nrepl-eval` only for ad-hoc expressions that have no wrapper
equivalent — for example, multi-step `mapv` calls or thunks for `diff-entity`.

The scripts are installed alongside this skill. **Do not check for their existence — invoke
them directly.** If you ever need to locate them, find this `SKILL.md` file first:
`find ~ -path '*/livewire/SKILL.md' 2>/dev/null` — the `bin/` folder is right next to it.

Use them via their full path: `~/.claude/skills/livewire/bin/<script-name>`.
The port defaults to **7888** and can be overridden with `LW_PORT`.

| Script | What it does |
|---|---|
| `lw-start` | Discover nREPL + app summary in one shot — **always run this first** |
| `lw-info` | App/env summary |
| `lw-list-entities` | All Hibernate-managed entities |
| `lw-inspect-entity <Name>` | Table, columns, relations for one entity |
| `lw-inspect-all-entities` | Table, columns, relations for **all** entities in one call |
| `lw-list-endpoints` | All HTTP endpoints with auth info |
| `lw-find-beans <regex>` | Filter bean names by regex |
| `lw-bean-deps <beanName>` | Dependencies and dependents for one bean |
| `lw-all-bean-deps` | Wiring maps for all app-level beans (auto-filtered to own package) |
| `lw-bean-tx <beanName>` | `@Transactional` surface for one bean |
| `lw-all-bean-tx` | `@Transactional` surface for all app-level beans (auto-filtered) |
| `lw-repo-entity <beanName>` | Entity class managed by one repository bean |
| `lw-all-repo-entities` | Entity class for every repository bean — the full repo → entity map |
| `lw-props <regex>` | Filter environment properties by regex |
| `lw-sql <query>` | Run a read-only SQL query |
| `lw-jpa-query <jpql> [page] [page-size]` | Run a JPQL query and return serialized entity maps (traced, paged) |
| `lw-trace-sql <clojure-expr>` | Capture SQL fired by an expression |
| `lw-trace-nplus1 <clojure-expr>` | Detect N+1 queries in an expression |
| `lw-call-endpoint [--limit N] <bean> <method> <role> [args...]` | Call a bean method under a single Spring Security role; list results capped at 10 by default. **Role must include `ROLE_` prefix** (e.g. `ROLE_MEMBER`, not `MEMBER`) — `lw-list-endpoints` shows `required-roles` without this prefix |
| `lw-list-queries <repoBeanName>` | List all `@Query` methods on a repo with their current JPQL |
| `lw-build-entity <EntityName> [edn-opts]` | Build a fake entity instance; optional EDN opts map (`:auto-deps?`, `:persist?`, `:rollback?`) |
| `lw-build-test-recipe <EntityName> [edn-opts]` | Build a faker entity graph and extract all scalar field values into a nested map of `{:type … :value …}` entries — use as seed for test setup code and assertions |
| `lw-blast-radius <beanName> <methodName>` | Call-graph impact analysis — which HTTP endpoints, schedulers, and event listeners transitively call this method. Pass `'*'` as method for the full inbound call graph (flat, deduplicated). |
| `lw-blast-radius-all <beanName>` | Per-method inbound call graph for every method on a bean: `{method → {:callers [...]}}`. Methods with empty `:callers` are dead-code candidates. All indexes built once — same speed as a single call. |
| `lw-method-dep-map <beanName>` | For each method on a bean, the subset of its injected dependencies that method actually uses in bytecode, including which methods it calls on each dep (`:calls`). Includes `:dep-frequency` ranking. Options: `:expand-private?` folds private helper deps into their public callers; `:intra-calls?` adds which siblings this method calls; `:callers?` adds which siblings call this method (inverse of `:intra-calls?`). |
| `lw-method-dep-clusters <beanName>` | Cluster methods by shared dep footprint — split-planning in one call. Groups methods into natural extraction candidates, flags shared deps and intra-call violations. Options: `--expand-private`, `--min-cluster-size N`. |
| `lw-dead-methods <beanName>` | Analyses public methods on a bean. Splits into `:dead` (no callers anywhere — delete candidates) and `:internal-only` (called only from sibling methods — visibility leaks, refactoring candidates). Warns when messaging beans or db-scheduler tasks are detected. |
| `lw-eval <clojure-expr>` | Generic nREPL eval — **avoid, see pitfall below** |

> ⚠️ **Prefer `clj-nrepl-eval -p <port>` over `lw-eval` for arbitrary expressions.**
> Clojure characters like `!`, `?`, `->` / `->>` can be misinterpreted by the shell when
> passed through `lw-eval`. Use dedicated wrapper scripts for named operations; for everything
> else call `clj-nrepl-eval` directly:
> ```bash
> clj-nrepl-eval -p 7888 "(hq/reset-all!)"
> ```

---

## API namespaces — quick reference

Read `references/api-core.md` for full details, patterns, and examples for any of these.

| Namespace | alias | What it covers |
|---|---|---|
| `net.brdloush.livewire.core` | `lw` | Beans, transactions (`in-tx`, `in-readonly-tx`), `run-as`, properties, `bean->map`, `diff-entity` |
| `net.brdloush.livewire.introspect` | `intro` | `list-entities`, `inspect-entity`, `list-endpoints`, endpoint auth metadata |
| `net.brdloush.livewire.trace` | `trace` | `trace-sql`, `trace-sql-global`, `detect-n+1` |
| `net.brdloush.livewire.jpa-query` | `jpa` | `jpa-query` — JPQL → Clojure maps, lazy-safe, paginated |
| `net.brdloush.livewire.query` | `q` | `sql` (raw SQL), `diff-entity` (mutation observer) |
| `net.brdloush.livewire.hot-queries` | `hq` | `hot-swap-query!`, `reset-all!`, `list-swapped` |
| `net.brdloush.livewire.query-watcher` | `qw` | `status`, background `@Query` auto-reloader |
| `net.brdloush.livewire.faker` | `faker` | `build-entity`, `build-test-recipe` — fake data with constraint awareness |
| `net.brdloush.livewire.callgraph` | `cg` | `blast-radius`, `method-dep-map`, `method-dep-clusters`, `dead-methods` |

---

## Essential examples

```clojure
;; Full env + datasource summary — always run this first
(lw/info)
;; => {:application-name "bloated-shelf", :active-profiles ["dev" "seed"],
;;     :java-version "25.0.1", :spring-boot-version "4.0.1",
;;     :hibernate-version "7.2.0.Final",
;;     :datasource {:db-product "PostgreSQL 16.9", :jdbc-url "...", ...}}

;; Which repository beans exist?
(lw/find-beans-matching ".*Repository.*")
;; => ("bookRepository" "authorRepository" ...)

;; List all HTTP endpoints
(first (intro/list-endpoints))
;; => {:methods ["GET"], :paths ["/api/authors/{id}"], :controller "AuthorController", ...}

;; List all Hibernate entities (table names, FQNs)
(intro/list-entities)
;; => [{:name "Book", :class "com.example.Book", :table-name "book"} ...]

;; Inspect an entity's DB mappings before writing any query
(intro/inspect-entity "Book")
;; => {:table-name "book", :identifier {:name "id"}, :properties [...], :relations [...]}
```

```bash
# N+1 detection — always use the wrapper script, not raw trace/detect-n+1 calls
lw-trace-nplus1 '(lw/run-as ["user" "ROLE_MEMBER"] (.getBooksByGenreId (lw/bean "bookService") 1))'
# => {:suspicious-queries [{:sql "select ... from author ...", :count 20} ...], :total-queries 98, ...}
```

---

## ⚠️ Hibernate lazy loading — always convert inside the transaction

Returning a raw Hibernate entity from `in-tx` / `in-readonly-tx` will throw
`LazyInitializationException` when the REPL tries to print it — the session is
already closed. **Always eagerly convert to a plain Clojure map inside the
transaction boundary.**

```clojure
;; ❌ blows up — printed after session closes
(lw/in-readonly-tx
  (.findById (lw/bean "bookRepository") 1))

;; ✅ convert while the session is still open
(lw/in-readonly-tx
  (-> (.findById (lw/bean "bookRepository") 1)
      .get
      clojure.core/bean
      (select-keys [:id :title :isbn])))
```

---

## ⚠️ Critical pitfalls (inline — full list in `references/pitfalls.md`)

- **Never call `.findAll` without a `Pageable`** — may return millions of rows and hang the REPL.
- **`lw/run-as` requires `["username" "ROLE_X"]`, not a bare string** — `"admin"` only grants `ROLE_USER`+`ROLE_ADMIN`; any other role needs the vector form. Same `ROLE_` prefix required for `lw-call-endpoint`.
- **Always use `lw/bean->map` not `clojure.core/bean`** — records return `{}` silently with `clojure.core/bean`.
- **`lw/bean SomeClass` only resolves Spring beans, not JPA entities** — use `lw/find-beans-matching` to find the repository.
- **`EntityManager` is not a named bean** — use `(lw/bean jakarta.persistence.EntityManager)` (type-based lookup).
- **Bean name ≠ class name, and `:controller` from `lw-list-endpoints` is ALWAYS a FQN — never pass it directly to `lw-call-endpoint` or `lw/bean`.** Derive the bean name by taking the simple class name and lowercasing the first letter: `com.example.web.BookController` → `bookController`. When in doubt, verify with `lw-find-beans`.
- **Always use JPQL (`lw-jpa-query`) for data queries** — raw SQL only for metadata, DDL, or unmapped tables. `lw-jpa-query` and `lw-call-endpoint` default to **10 rows**; raw SQL has no limit so add one explicitly.
- **Always inspect entities before writing any query** — never guess table/column names.
- **`lw-eval` mangles `!`, `?`, `->` in zsh** — use `clj-nrepl-eval -p 7888` directly for expressions with these characters.
- **Never use `hq/hot-swap-query!` to test a hypothesis** — it mutates JVM state for all callers until explicitly reset. To prove a candidate JPQL reduces N+1, use `jpa/jpa-query` wrapped in `trace/trace-sql` or `lw-trace-nplus1` — zero side effects, no cleanup needed. Hot-swap is only for final end-to-end confirmation once the fix is already validated.
- **`jpa/jpa-query` takes keyword args, not positional** — correct form: `(jpa/jpa-query jpql :page 0 :page-size 20)`. It does **not** support named query parameters (`:genreId` etc.); for parameterized JPQL use `(lw/bean jakarta.persistence.EntityManager)` directly.

For all other pitfalls (UUID args, optional params, `@PreAuthorize`, `javax` vs `jakarta`, timing warm-up, etc.), read `references/pitfalls.md`.
