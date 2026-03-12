# Contributing to Livewire

Thanks for your interest. Livewire is a focused dev tool — contributions are welcome,
but please keep them in that spirit: sharp, honest, and dev-only.

---

## Prerequisites

- **Java 17+**
- **[Leiningen](https://leiningen.org/)** — `lein` on your PATH
- **[Babashka](https://github.com/babashka/babashka)** — `bb` on your PATH
- **[Bloated Shelf](https://github.com/brdloush/bloated-shelf)** — the canonical dogfood app for integration testing against a live Spring Boot instance

---

## Building

All build tasks are defined in `bb.edn` and run via `bb`:

```bash
bb compile        # Compile Java and Clojure sources
bb jar            # Build the JAR (no install)
bb install        # Build and install to ~/.m2 (use this for local dogfooding)
bb clean          # Remove the target/ directory
bb clean-install  # Full clean rebuild + install to ~/.m2
```

When iterating locally, `bb install` followed by a Bloated Shelf restart is the
standard validation loop.

---

## Project structure

```
src/clojure/net/brdloush/livewire/
  core.clj          — beans, transactions, run-as, properties
  query.clj         — raw SQL / JPQL execution
  trace.clj         — SQL tracing and N+1 detection
  hot_queries.clj   — live @Query hot-swap + restore
  query_watcher.clj — auto-apply @Query changes on recompile
  introspect.clj    — endpoints, entities, Hibernate metamodel
  boot.clj          — nREPL server lifecycle

src/java/net/brdloush/livewire/
  LivewireAutoConfiguration.java      — Spring Boot autoconfiguration
  LivewireBootstrapBean.java          — bean that boots the nREPL
  LivewireEnvironmentPostProcessor.java
  LivewireSqlTracer.java              — Hibernate StatementInspector

resources/META-INF/spring/
  org.springframework.boot.autoconfigure.AutoConfiguration.imports

decisions/         — ADRs explaining non-obvious implementation choices
skills/livewire/
  SKILL.md         — agent instruction manual (keep this up to date!)
```

---

## Testing and validation

There is no automated test suite yet. Validation is done interactively against
the live Bloated Shelf app using the embedded nREPL.

**Workflow:**

1. Make your changes in source
2. Run `bb install` to push them to `~/.m2`
3. Start (or restart) [Bloated Shelf](https://github.com/brdloush/bloated-shelf):
   ```bash
   cd /path/to/bloated-shelf
   mvn spring-boot:run -Dspring-boot.run.profiles=dev,seed
   ```
4. Connect to the nREPL on **port 7888** and run the smoke-test:
   ```clojure
   (require '[net.brdloush.livewire.core :as lw]
            '[net.brdloush.livewire.trace :as trace])

   (let [res (trace/trace-sql
               (lw/run-as "member1"
                 (lw/in-readonly-tx
                   (.getAllBooks (lw/bean "bookService")))))]
     (select-keys res [:count :duration-ms]))
   ;; => {:count 1201, :duration-ms ...}
   ```
5. Exercise the specific feature you changed and confirm behaviour

See [`AGENTS.md`](AGENTS.md) for the full REPL-driven development guide —
it covers the hot-patching workflow, escalation ladder, and cleanup rules.

---

## Submitting changes

- **Open an issue first** for anything non-trivial — alignment before code saves everyone time
- **One concern per PR** — focused changes are easier to review and revert if needed
- **`bb clean-install` must succeed** before submitting
- **Update `SKILL.md`** if you add, remove, or change any public API — agents depend on it
  and a stale skill file causes real problems in agentic sessions
- **Update `CHANGELOG.md`** with a brief entry under `[Unreleased]`

---

## Scope reminder

Livewire is a **dev-only** tool. PRs that blur the line toward production use
(e.g. authentication on the nREPL, remote binding defaults, persistence) will
not be accepted. That boundary is a feature.
