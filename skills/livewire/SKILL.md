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
**Step 1 — discover:** Run this single chain — it checks known locations then the project tree, and stops at the first match:

```bash
if [ -f ~/.claude/skills/livewire/SKILL.md ]; then echo ~/.claude/skills/livewire
elif [ -f ~/.pi/agent/skills/livewire/SKILL.md ]; then echo ~/.pi/agent/skills/livewire
elif [ -f "/usr/local/lib/node_modules/@mariozechner/pi-coding-agent/skills/livewire/SKILL.md" ]; then echo "/usr/local/lib/node_modules/@mariozechner/pi-coding-agent/skills/livewire"
elif [ -f "$PWD/skills/livewire/SKILL.md" ]; then echo "$PWD/skills/livewire"
elif [ -f "$PWD/../skills/livewire/SKILL.md" ]; then echo "$PWD/../skills/livewire"
else find ~ -path '*/livewire/SKILL.md' 2>/dev/null | head -1
fi
```
**Fallback:** If none of the above match, only then run `find ~ -path '*/livewire/SKILL.md' 2>/dev/null`.

**Step 2 — remember:** That directory is `$SKILL_DIR`. It is your single source of truth for **everything** relative to this skill — wrapper scripts are at `$SKILL_DIR/bin/`, reference files are at `$SKILL_DIR/references/`. Use it everywhere instead of any hardcoded path. Before acting on any query, tracing, or endpoint call, **read the referenced file** — the pitfalls section catches method name guessing, Cartesian products, lazy results, shell escaping, and `trace/trace-sql` traps that cause 10+ failed attempts.

**Step 3 — confirm:** Say exactly this (replacing `$SKILL_DIR` with the actual full path you found):
> Livewire skill loaded from <actual-path>. Run `lw-start` to connect to a running app, or ask `lw-help` for an overview of what Livewire can do.

**Never hardcode `~/.claude/skills/livewire/`. The skill can live anywhere.**

### On `lw-help` (or any "what can Livewire do?" question)
Give a brief prose overview — no headers, no tables, no bullet soup:

> Livewire connects Claude to a running Spring Boot app via nREPL. From there you can inspect beans, entities, and HTTP endpoints; run JPQL queries and raw SQL against the live database; trace SQL to hunt N+1 problems and hot-swap `@Query` JPQL without restarting; observe exactly what fields a service call writes using `diff-entity`; analyse call graphs to find blast radius or plan a service split; and generate realistic fake entity graphs for test data prototyping. Run `lw-start` to connect, or ask about any specific feature.

### On successful `lw-start`
Present the result as a single brief paragraph — no tables, no bullet lists.
Include: app name, Spring Boot version, Hibernate version, Java version, active profiles, database product+version, and port. Example:

> Connected to **bloated-shelf** on port 7888 — Spring Boot 4.0.1 / Hibernate 7.2.0 / Java 25, profiles `dev` + `seed`. Database: PostgreSQL 16.9.

If `lw-start` finds no server, say so briefly and suggest the user start the app.

---

## General interaction rules

This skill's purpose is to be an **interactive assistant**. The user expects help, not
a report.

**Minimum reasonable effort.** When the user asks a question, provide the smallest amount
of investigation that leads to a correct answer. Do not chase every variant, every angle,
or every follow-up step unless the user signals they want it. If `lw-trace-nplus1` gives
you the diagnosis, that's enough — present it and stop. Do not proceed to test fixes
unless the user asks.

**Variants = strong signal.** The user signals they want fix variants by explicitly asking
for them ("fix it", "what are my options", "variants?"). Do not present fix options
without that signal — even when the skill's reference files contain extensive variant
tables. Those tables are there so you can answer the user's question *when they ask it*,
not to preempt their request.

**Stop at the answer.** A diagnosis that explains "what", "where", "how many", and
"why" is complete. The user may agree with the diagnosis and want to fix it themselves.
They may ask for variants. They may not. Don't guess.

**Risk emoji.** Whenever an option, suggestion, or approach carries real risk
(Cartesian product, data loss, silent wrongness, unbounded query counts, etc.),
prefix the risk with ⚠️. When the risk is low, no emoji needed. When in doubt,
flag it.

---

## Trigger Rules

MUST read the referenced file BEFORE acting on any question matching the keyword.
This is not optional — static knowledge is unreliable for live-app questions.

| Keywords in user query | File to read | File path |
|---|---|---|
| **`N+1` `n+1` `query count` `N plus one` `slow query` `Cartesian product` `row duplication` `JOIN FETCH` two collections** `id-first` `ID-first` | **N+1 Hunting** | **`$SKILL_DIR/references/n-plus-one-hunting.md`** |
| `blast radius` `call graph` `blast-radius` `inbound` `dead code` `split` `method dependencies` | Call Graph | `$SKILL_DIR/references/callgraph.md` |
| `hot swap` `hot-swap` `@Query` `jpql` `query watcher` `swapped` `jpa/jpa-query` | API Core | `$SKILL_DIR/references/api-core.md` |
| `faker` `fake data` `build-entity` `build-test-recipe` `test data` | Writing Tests & Fake Data | `$SKILL_DIR/references/writing-tests-and-fake-data.md` |
| `pitfall` `error` `exception` `unexpected` `what went wrong` | Pitfalls | `$SKILL_DIR/references/pitfalls.md` |
| `variant discipline` `variant discipline` `don't test other variants` `stop after one` `only test what I chose` | Variant Discipline | `$SKILL_DIR/references/variant-discipline.md` |

---

## ⚠️ Before you act — mandatory file reads

When your message contains any keyword from the table above, **read the referenced file before doing anything else**. This is not a suggestion — the file contains fixes to known traps (lazy results, Cartesian products, shell escaping) that will save you 10+ failed attempts.

If you skip the read and produce a wrong result, the most likely cause is that you missed something in the reference file that you already knew but forgot.

### ⚠️ N+1 HUNTING (keywords: `N+1`, `n+1`, `slow query`, `query count`)

**Read `$SKILL_DIR/references/n-plus-one-hunting.md` before writing any trace expression.** That file contains:
- The exact procedure for N+1 hunting (service method, not controller)
- Why `jpa/jpa-query` returns **lazy** results → wrap in `doall` or trace count is always 0
- The **Cartesian product trap** — `JOIN FETCH` on two collections duplicates rows
- The exact tool for each phase (`lw-trace-nplus1` → `hq/hot-swap-query!` → `hq/reset-all!`)

Do not rely on memory. Read the file. Then act.

---

## Reference files

Load these on demand — read the relevant file before working in that area:

| File | Read when... |
|---|---|
| `$SKILL_DIR/references/api-core.md` | Working with beans, transactions, security contexts, entity/endpoint introspection, SQL tracing, JPQL queries, mutation observation (`diff-entity`), or hot-swapping `@Query` methods |
| `$SKILL_DIR/references/callgraph.md` | Analysing blast radius of a change, planning a service split, or detecting dead/internal-only methods |
| `$SKILL_DIR/references/writing-tests-and-fake-data.md` | Writing integration tests or prototyping test data with `faker/build-entity` / `faker/build-test-recipe` |
| `$SKILL_DIR/references/pitfalls.md` | You hit an unexpected error, are about to write a query, or want to verify correct conventions for data access, security, or argument passing |
| `$SKILL_DIR/references/n-plus-one-hunting.md` | Investigating N+1 query problems, measuring query counts, or validating that a JPQL / `JOIN FETCH` fix eliminates excess queries |

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

## Zero-install attach via jshell

Livewire can also be injected into a running Spring Boot app **without any build changes**
using the `attach.jsh` bootstrap script. This is useful for quick exploration of an app
you didn't write, or for onboarding teammates who haven't added the dependency yet.

```bash
jshell
# then at the jshell prompt:
/open https://raw.githubusercontent.com/brdloush/livewire/refs/heads/main/attach.jsh
```

Or, for a local development build:
```bash
LIVEWIRE_BUNDLE_PATH=/path/to/livewire-attach-X.Y.Z.jar \
  jshell /path/to/attach.jsh
```

**Requirements:** the target JVM must be started with `-XX:+EnableDynamicAgentLoading` (Java 21+).

**Available commands at the jshell prompt** (all require a prior `attach(N)` call):

| Command | What it does |
|---|---|
| `attach(N)` | Inject the agent into JVM #N from the list; connects nREPL client |
| `attach(N, port)` | Same, using a custom nREPL port (default: 7888) |
| `info()` | App name, Spring Boot / Hibernate / Java versions, DataSource details |
| `beans(pattern)` | Sorted list of Spring beans matching the regex pattern |
| `eval(code)` | Evaluate arbitrary Clojure against the live nREPL session |
| `sql(query)` | Run a read-only SQL query through the live DataSource |
| `demo()` | Trace-SQL demo: counts books and reports query count + duration |
| `detach()` | Close the nREPL session; keep jshell running |
| `help()` | Print all available commands |

**Tip:** once attached, `eval()` gives full access to the Livewire API —
`eval("(trace/trace-sql (.getBooks (lw/bean \"bookController\")))")` works exactly as it does
in a full nREPL session.

⚠️ Dev/staging only — never attach to a JVM with real user data.

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

4. **Inspect before writing any query** — before writing SQL or JPQL, always run:
   ```bash
   lw-list-entities          # get table names and entity FQNs
   lw-inspect-entity <Name>  # get columns, FK columns, relations for join paths
   ```
   **Never guess table names, column names, or join conditions.** The `lw-inspect-entity` output is
   the source of truth. Even when the app uses conventional naming (`author_id`, `book_id`),
   non-standard mappings (`referencedColumnName`, `@JoinTable`) break the pattern.

5. **Evaluate** snippets — **default to a temp file for everything except ultra-trivial
   inline evals** (see `references/clj-nrepl-eval-temp-files.md`):

   ```bash
   # Ultra-trivial only (zero args, zero nesting, no special chars):
   clj-nrepl-eval -p <port> "(lw/info)"

   # Everything else — write a .clj file, then use a wrapper script:
   $SKILL_DIR/bin/lw-eval --file /tmp/livewire-XXXXX.clj
   ```

   **Rule of thumb:** inline is fine only for one-liners with no arguments, no nesting,
   and no special characters — things like `(lw/info)` or `(lw/find-beans-matching "Repo")`.
   For anything with function arguments, `do`, `let`, threading, or more than one form,
   use a temp file. When in doubt, use a temp file. It's never wrong.

6. **Run independent read queries in parallel** — fire unrelated `clj-nrepl-eval` calls in a
   single message to reduce wall-clock time. Only serialize when one result feeds into the next.

7. **Present results readably:**
   - Collections of maps → markdown table
   - Single map → inline key/value list
   - Scalars → inline code in prose

8. **Hot-patching:** Do not use `:reload` to pick up a newly built JAR — it re-reads the same
   old class on the classpath. Instead, evaluate the new `ns` form and function bodies directly.

9. **After writing a source-code fix (Java/Kotlin):** always compile first, then restart.
   `mvn compile -DskipTests` before asking the user to restart. Missing a single `import`
   or wrong method reference produces a hard compile failure that stops the entire app
   from starting. The query-watcher cannot pick up constructor changes, new methods, or
   import fixes.

10. **Always verify data correctness after a fix** — `lw-trace-nplus1` returning `{:total-queries 2}`
   only confirms the query count. It does **not** verify that the response data is correct.
   A fix can reduce queries from 98 to 2 and still return empty nested collections
   (e.g. grouping reviews by review ID instead of book ID). **Always call `lw-call-endpoint`
   or `lw/jpa-query` to inspect at least one sample of the response after implementing a fix.**
   The query count trace validates performance; a sample response validates correctness.

---

## Wrapper scripts

This skill ships named wrapper scripts in a `bin/` subdirectory next to this `SKILL.md`.

**Always use wrapper scripts for named operations** (SQL, JPQL, trace, N+1, endpoints,
beans, call graphs). They handle namespace requiring automatically and avoid shell escaping
issues. Fall back to raw `clj-nrepl-eval` only for ad-hoc expressions that have no wrapper
equivalent — for example, multi-step `mapv` calls or thunks for `diff-entity`.

The scripts are installed alongside this skill. **Do not check for their existence — invoke
them directly.** The `bin/` folder is right next to this `SKILL.md`.

Use them via their full path: `$SKILL_DIR/bin/<script-name>` (where `$SKILL_DIR` is the
path you discovered on load — **never** use `~/.claude/skills/livewire/`).
The port defaults to **7888** and can be overridden with `LW_PORT`.

| Script | What it does |
|---|---|
| `lw-start` | Discover nREPL + app summary in one shot — **always run this first** |
| `lw-info` | App/env summary |
| `lw-list-entities` | All Hibernate-managed entities |
| `lw-inspect-entity <Name>` | Table, columns, relations for one entity |
| `lw-inspect-all-entities` | Table, columns, relations for **all** entities in one call |
| `lw-list-endpoints` | All HTTP endpoints with auth info |
| `lw-find-beans <regex>` | Filter bean names by regex (case-insensitive) |
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

> ⚠️ **For arbitrary expressions, use `$SKILL_DIR/bin/lw-eval --file <path>`** to avoid shell escaping issues.
> Inline `clj-nrepl-eval` is fine only for ultra-trivial one-liners (no args, no nesting, no special chars).
> Never pass Clojure code with `!`, `?`, `->`, `#()`, `fn`, `doall`, `vec`, `mapv`,
> or nested parens as an inline shell argument — the shell will mangle them.
> Use a temp file with a wrapper script:
> ```bash
> # Write the expression, then pass the file
> $SKILL_DIR/bin/lw-eval --file /tmp/lw-expr.clj
> ```
> See `$SKILL_DIR/references/clj-nrepl-eval-temp-files.md` for the complete rule.

---

## API namespaces — quick reference

Read `$SKILL_DIR/references/api-core.md` for full details, patterns, and examples for any of these.

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
# N+1 detection — always use the wrapper script via $SKILL_DIR, not raw trace calls
$SKILL_DIR/bin/lw-trace-nplus1 '(lw/run-as ["user" "ROLE_MEMBER"] (.getBooksByGenreId (lw/bean "bookService") 1))'
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

## ⚠️ Common Clojure expression gotchas

These are the mistakes that repeatedly cause compiler/runtime errors or wrong return values. They appear in the expression-writing context — apply them **before every `lw-eval`**.

- **Never shadow core functions** — `def`, `let`, `fn`, `loop`, destructuring — don't bind names like `first`, `map`, `update`, `get`, `keys`, `count`, `filter`, `reduce`. Shadowing `first` is the worst offender: every subsequent call to `clojure.core/first` throws `IllegalStateException: Attempting to call unbound fn`. Use `o-first`, `n-first`, `orig`, `new-` as prefixes.
- **EDN doesn't use Java numeric suffixes** — `1L`, `1.0f`, `1d` are not valid EDN. Use `(long 1)`, `(float 1.0)` instead.
- **Java records use field-name accessors** — records generate `.id`, `.title`, `.genreNames` — **not** `.getId()`, `.getTitle()`, `.getGenreNames()`. If you see `No matching field found` or a return value that looks like a `clojure.lang.Var`, it's almost certainly the accessor name. When in doubt, inspect a known instance: `(clojure.core/first (lw/in-readonly-tx (.findAll (lw/bean "bookRepository"))))` then check which methods exist.
- **Always wrap entity access in `in-readonly-tx`** — lazy collections (`reviews`, `member`, etc.) throw `LazyInitializationException` if you touch them outside the transaction boundary. When the trace wrapper alone doesn't keep the session open, explicitly wrap the body in `(lw/in-readonly-tx ...)`.
- **Hibernate `setParameter` — always use named parameters (`:name`)** — the positional path (`?1`, `?2`) is a trap:
  - Bare `?` in JPQL → `ParameterLabelException: Unlabeled ordinal parameter ('?' rather than ?1)`
  - You must construct `?1`, `?2`, ... with `(iterate inc 1)` and `run!`-set each one — easy to get wrong
  - **Named params are the only safe default.** Always write `:paramName` in JPQL and use `.setParameter(q, "paramName", val)`.

```clojure
(def em (lw/bean jakarta.persistence.EntityManager))

;; ✅ Named single param — always the first choice
(.setParameter q "genreId" (long 1))
;; JPQL: "... WHERE g.id = :genreId"

;; ✅ Named collection IN (:ids) — safest for variable-size lists
(.setParameter q "bookIds" [1 2 3 4 5])
;; JPQL: "... WHERE r.book.id IN (:bookIds)"

;; ❌ Positional — works but fragile, easily produces ParameterLabelException
;; bare "?" → ParameterLabelException
;; you must construct "?1", "?2" and bind each individually
(let [placeholders (str/join ", " (mapv #(str "?") (take (count ids) (iterate inc 1))))]
  (let [q (.createQuery em (str "... IN (" placeholders ")"))]
    (run! (fn [[val idx]] (.setParameter q idx val))
          (mapv vector ids (iterate inc 1)))))
```

**Rule:** never write positional parameters. Use named params (`:name`) and `.setParameter(q, "name", val)` for every case. Only use positional parameters when you are writing code that runs at runtime (not in the REPL), and even then prefer named params for readability.
- **Never feed an unbounded infinite sequence into a strict consuming function** — `(mapv (iterate inc 1))`, `(reduce + (iterate inc 1))`, `(filter even? (iterate inc 1))` all try to realize the entire infinite lazy seq into a concrete structure (vector, map, set) → `OutOfMemoryError`. Always bound first: `(mapv (iterate inc 1) (take 20 ...))` or use an explicit count. The same trap applies to `partition-all` on an infinite seq. In the REPL, `iterate` is the most common source — if you see `mapv` / `into` / `vec` / `reduce` + `iterate` / `repeatedly` / `cycle`, double-check the consuming function has a finite input.

---

## ⚠️ Critical pitfalls (inline — full list in `$SKILL_DIR/references/pitfalls.md`)

- **Never call `.findAll` without a `Pageable`** — may return millions of rows and hang the REPL.
- **`lw/run-as` requires `["username" "ROLE_X"]`, not a bare string** — `"admin"` only grants `ROLE_USER`+`ROLE_ADMIN`; any other role needs the vector form. Same `ROLE_` prefix required for `lw-call-endpoint`.
- **Always use `lw/bean->map` not `clojure.core/bean`** — records return `{}` silently with `clojure.core/bean`.
- **`lw/bean SomeClass` only resolves Spring beans, not JPA entities** — use `lw/find-beans-matching` to find the repository.
- **`EntityManager` is not a named bean** — use `(lw/bean jakarta.persistence.EntityManager)` (type-based lookup).
- **Bean name ≠ class name, and `:controller` from `lw-list-endpoints` is ALWAYS a FQN — never pass it directly to `lw-call-endpoint` or `lw/bean`.** Derive the bean name by taking the simple class name and lowercasing the first letter: `com.example.web.BookController` → `bookController`. When in doubt, verify with `lw-find-beans`.
- **Always use JPQL (`lw-jpa-query`) for data queries** — raw SQL only for metadata, DDL, or unmapped tables. `lw-jpa-query` and `lw-call-endpoint` default to **10 rows**; raw SQL has no limit so add one explicitly.
- **Always inspect entities before writing any query** — never guess table/column names.
- **Never guess method names** — controller method name ≠ service method name ≠ repository method name. The controller `getBooks()` delegates to `bookService.getAllBooks()`, not `getBooks()`. **Always read the source code** (`grep -n "getBooks" /path/to/Controller.java`) before calling a method on a bean. Same trap: the bean name is lowercase (`bookService`), the controller class is `BookController`, the endpoint path is `/api/books` — none of these are the same. Full rule: `$SKILL_DIR/references/pitfalls.md`.
- **Never guess IDs for method parameters** — always look up real values from the DB first (`lw-sql "SELECT id, name FROM <table> ORDER BY id LIMIT 5"`). A lucky hit gives the user no way to reproduce with a different value; a miss returns 404. Full rule: `$SKILL_DIR/references/pitfalls.md`.
- **`lw-eval` mangles `!`, `?`, `->` in zsh** — never pass Clojure code with special characters as inline shell args. Write to a temp file instead: `lw-eval --file /tmp/lw.clj` where the file contains the Clojure expression. Full rule: `$SKILL_DIR/references/clj-nrepl-eval-temp-files.md`.
- **`trace/trace-sql` only works on JPA entities** — DTOs, Java records, and `select-keys` results have no JPA metadata. `trace/trace-sql` will fail on them with `find not supported on type`. Trace against repository methods that return entities instead.
- **Don't nest `trace/trace-sql`** — `lw-trace-sql` and `lw-trace-nplus1` already wrap your expression. Putting `trace/trace-sql` or `trace/detect-n+1` inside the expression creates double-wrapping. Use raw `clj-nrepl-eval` when you need both tracing and transformation — but write the expression to a temp file if it contains `!`, `?`, `->`, `#()`, or nested parens. See `$SKILL_DIR/references/clj-nrepl-eval-temp-files.md`.
- **Never use `(dorun (map ...))` in transaction context** — it creates a lazy seq chain that silently blocks and hangs. `dorun` does NOT realize lazy seqs it receives; it only consumes realized ones. Always use `doseq` for side effects or `mapv` for returning data.
- **Never use `hq/hot-swap-query!` to test a hypothesis** — it mutates JVM state for all callers until explicitly reset. To prove a candidate JPQL reduces N+1, use `jpa/jpa-query` wrapped in `trace/trace-sql` or `lw-trace-nplus1` — zero side effects, no cleanup needed. Hot-swap is only for final end-to-end confirmation once the fix is already validated.
- **`jpa/jpa-query` takes keyword args, not positional** — correct form: `(jpa/jpa-query jpql :page 0 :page-size 20)`. It does **not** support named query parameters (`:genreId` etc.); for parameterized JPQL use `(lw/bean jakarta.persistence.EntityManager)` directly.
- **Always warn about Cartesian product when suggesting `JOIN FETCH`** — `JOIN FETCH` on two collections of the same parent duplicates rows at the SQL level (Cartesian product) and produces bloated entities/DTOs even though Hibernate deduplicates. **This is silently wrong — no exception, no warning — the DTO looks "mostly right" but nested collections contain duplicated items.** Even 2 genres × 2 reviews on a single row = 4× duplicated items. Never suggest `JOIN FETCH` on a second collection alongside an already-fetched collection without explicitly calling out the multiplication risk and offering an alternative (batch fetching with `@BatchSize`, two-query approach, or `IN` clause). Full rule: `$SKILL_DIR/references/n-plus-one-hunting.md`.
- **Only present fix variants when the user explicitly asks.** Diagnostic output (N+1 trace, query counts, SQL patterns) is a complete diagnosis — the user knows what's wrong and how many queries it costs. Presenting variants unasked wastes effort and signals you don't distinguish between "what's wrong" and "how to fix it." When the user does ask, read `$SKILL_DIR/references/n-plus-one-hunting.md` for the full variant table and present 2–4 options with pros/cons so they can choose based on their constraints (source edit cost, performance, global vs local impact). The common variants are full `JOIN FETCH` (single query), partial `JOIN FETCH` + `@BatchSize`, multiple queries merged in code, and `@BatchSize` annotation-only fixes. **Never assume one approach is universally best** — context matters (result set size, call frequency, source edit permissions). Never present a single `JOIN FETCH` as THE answer if asked.
- **Always verify data correctness after a fix — a trace showing "2 queries, 0 suspicious" does NOT mean the data is correct.** The most common silent bug: grouping by the wrong key (e.g. grouping reviews by review ID instead of book ID, which compiles, traces, and runs fine but returns empty reviews for every book). **Always call `lw-call-endpoint` or `lw/jpa-query` to inspect the response shape after implementing a fix.** The query count trace validates performance; a sample response validates correctness. They are two separate checks.
- **Always run `mvn compile -DskipTests` after Java source changes** — missing a single `import` or wrong `let` binding produces a hard compile failure that stops the entire restart. The query-watcher cannot pick up constructor changes, new methods, or import fixes. Always compile before restarting.
- **Never trust `trace/trace-sql` or `lw-trace-nplus1` alone as proof of correctness.** They measure query count and suspicious patterns — not data shape, not group keys, not DTO assembly. A fix can reduce queries from 98 to 2 and still return empty nested collections. Always verify with a sample call to `lw-call-endpoint` or `lw/jpa-query`.

For all other pitfalls (UUID args, optional params, `@PreAuthorize`, `javax` vs `jakarta`, timing warm-up, etc.), read `$SKILL_DIR/references/pitfalls.md`.
