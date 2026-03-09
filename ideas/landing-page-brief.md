# Landing Page Brief — Livewire

This document is the input brief for an agent (or designer) building the Livewire landing page.
It covers positioning, messaging, features, tone, structure, and visual assets.

---

## What is Livewire?

Livewire embeds a Clojure nREPL server inside a running Spring Boot application.
It gives an AI coding agent (or a curious developer) a **live, stateful, introspectable
probe into the running JVM** — beans, queries, transactions, security context, and all.

The primary consumer is an **agentic coding assistant** (e.g. Claude Code via `clojure-mcp`).
Livewire is the bridge between static code reasoning and real runtime behaviour.

**One-liner:**
> *Stop guessing. Ask the running app.*

**Tagline (already in use):**
> *Live nREPL wire into your Spring Boot app. Dev only. You've been warned.*

---

## The Problem Being Solved

Modern Java/Kotlin Spring Boot development has a slow inner loop:

> **edit → restart → observe** — restarts cost 30 seconds to 2 minutes and destroy all
> in-memory state accumulated during a debugging session.

AI coding agents make this worse: they reason *statically* about Spring apps and guess at
runtime behaviour. They can read the code but cannot ask the running app what it's actually
doing.

Livewire closes this gap. With a live REPL wired into the JVM:
- The agent can probe beans, query the DB, trace SQL, and inspect entity mappings in real time.
- Hypotheses are tested immediately against the live system — not the static code.
- The feedback loop shrinks from minutes to seconds.

---

## Target Audience

**Primary:** Java/Kotlin Spring Boot developers who work alongside AI coding agents
(Claude Code, Cursor, Copilot, etc.) and want the agent to be able to actually *observe*
the running app rather than guess.

**Secondary:** Individual developers who want a Clojure-style REPL experience (live
introspection, safe mutation, SQL tracing) on top of their existing Spring Boot app —
without rewriting anything in Clojure.

**Not the audience:** Production ops teams, frontend developers, pure Clojure shops.

---

## Key Features (with copy)

### 🔍 Live Bean & Property Inspection
Instantly query any Spring bean, list all endpoints, inspect resolved properties, and filter
beans by regex — all against the running app, no source reading required.

### 🗄️ SQL & JPQL Execution
Run raw SQL or JPQL directly through the live `DataSource` / `EntityManager` — with Hibernate
type converters, active `@Filter`s, and proper transaction semantics. The data you see is what
the app sees.

### 🔬 SQL Tracing & N+1 Detection
Wrap any method call with `trace-sql` and see every SQL statement it fires — caller, SQL text,
timing. `detect-n+1` automatically groups repeated queries and flags suspects. Diagnose
performance bugs that would take hours of log trawling in seconds.

### 🔐 `run-as` — Call Secured Beans Directly
Set a Spring `SecurityContext` for the duration of a REPL call. Call `@PreAuthorize`-guarded
controllers and services directly — no more `AuthenticationCredentialsNotFoundException`,
no more bypassing to the service layer.

### 🔥 Live `@Query` Hot-Swap
Replace any Spring Data JPA `@Query` annotation live — without restarting. The agent fixes
a JPQL query, swaps it in, re-runs `detect-n+1`, and confirms the N+1 is gone — all in the
same session, all without touching the running server.

This required patching three internal layers of Spring Data JPA 4.x + Hibernate 7 in concert
(see `decisions/query-hot-loading.md`). Once wired, subsequent swaps are reflection-free.

### 🏗️ Hibernate Entity Introspection
Inspect any entity's live Hibernate metamodel: table name, column mappings, relation definitions,
fetch strategies. No annotation parsing — the live metamodel is the source of truth.

---

## Real-World Story (use as testimonial or "how it works" narrative)

> A performance regression was reported in Sentry — suspiciously high query counts on a
> books endpoint. The AI agent connected to the live app via Livewire, traced the
> service call with `trace-sql`, and found nothing suspicious at first. It tried several
> book IDs. One of them triggered 30+ SQL queries for a single request.
>
> `detect-n+1` immediately flagged the culprit: a `@ManyToOne(LAZY)` relation with
> `referencedColumnName` pointing to a non-PK column — Hibernate can't proxy without the PK,
> so it fires an immediate SELECT per row regardless of `LAZY`.
>
> The agent used `hot-swap-query!` to inject a `JOIN FETCH` fix live, re-ran the trace,
> confirmed the query count dropped from 30 to 1, then wrote the fix back to the source file.
> **Total time: one REPL session. Zero restarts.**

---

## Tone & Style

- **Confident, technical, slightly irreverent.** This is a power tool for people who know
  what they're doing.
- **Not enterprise-y.** No "enterprise-grade observability platform" language.
- **Honest about scope.** Dev-only. Not for production. The tagline already sets this tone.
- **Show, don't tell.** Code snippets and real outputs beat feature bullets.
- Reference the warning ("You've been warned.") as a badge of honour, not a disclaimer.

---

## Suggested Page Structure

1. **Hero** — tagline, one-liner, "Install" CTA, maybe the logo (see `specs/livewire-logo-chatgpt.png`)
2. **The Problem** — slow inner loop, agents guessing at runtime, the restart tax
3. **How It Works** — short architecture diagram (already in README):
   ```
   Spring Boot App (running)
     └── nREPL server (embedded, dev only)
           └── Livewire namespaces
                 └── Claude Code → clojure_eval → nREPL → live app
   ```
4. **Features** — 6 cards (see "Key Features" section above), each with a code snippet
5. **Story / Demo** — the N+1 debugging story (see "Real-World Story" above)
6. **Milestones / Screenshots** — see `milestones/` directory:
   - `01-repl-accessing-db.png` — first DB access from REPL
   - `02-tracing-sql-of-service.png` — SQL tracing live
   - `03-pinpointing-the-smoking-gun.png` — finding the exact N+1 caller
   - `04-detect-nplus1.png` — `detect-n+1` output
   - `09-he-runs-sql-in-bloated-shelf.png` — SQL running against real app
   - `13-hot-reload-of-Query-in-spring-data.png` — hot-swap in action
7. **Quick Install** — Maven/Gradle snippet + one property to enable
8. **Footer** — "Dev only. Not for production. You've been warned."

---

## Install Snippet (for the page)

```xml
<!-- Maven -->
<dependency>
  <groupId>net.brdloush</groupId>
  <artifactId>livewire</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```properties
# application-local.properties
livewire.enabled=true
```

That's it. Connect on port 7888.

---

## Code Snippets to Feature

Pick 2–3 of these for the features section — they're the most visually striking:

```clojure
;; Find the N+1 culprit in one call
(detect-n+1
  (trace-sql
    (.getAllBooks (lw/bean "bookController") 25)))
;; => {:suspicious-queries [{:sql "select ... from yields ...", :count 30}]
;;     :total-queries 31, :duration-ms 1271}
```

```clojure
;; Swap a broken @Query live — no restart
(hq/hot-swap-query! "bookRepository" "findByIdWithDetails"
  "select b from Book b join fetch b.reviews where b.id = :id")
;; [hot-queries] hot-swapped bookRepository#findByIdWithDetails
```

```clojure
;; Call a @PreAuthorize-guarded controller directly
(lw/run-as "superadmin@example.com"
  (.getBookById (lw/bean "bookController") 25))
```

---

## What to Avoid

- Don't position this as a monitoring / observability tool (it's not APM).
- Don't imply it's safe for production (it isn't, and we're proud of that honesty).
- Don't hide the Clojure angle — it's a feature, not a liability. The REPL is the whole point.
- Don't use the word "seamless".

---

## Color Palette (extracted from the logo)

The logo uses a dark, high-contrast theme. Stick close to these colors throughout the page.

| Role | Hex | Description |
|---|---|---|
| Background | `#151423` | Very dark navy — almost black with a purple undertone |
| Primary accent | `#5785CA` | Electric blue — links, buttons, highlights |
| Secondary accent | `#98C948` | Lime green — the "live wire" energy; use for badges, glows, key callouts |
| Text / light | `#FAFAFA` | Near-white for body text on dark backgrounds |
| Muted / secondary text | `#A5A5AD` | Grey for captions, labels |
| Dark mid-tone | `#3C4A4E` | Subtle borders, card backgrounds |

**Usage guidance:**
- Page background: `#151423`
- Hero headline: `#FAFAFA` with the lime `#98C948` used to highlight a key word (e.g. *"live"*, *"running"*)
- CTA button: `#5785CA` fill, `#FAFAFA` text; hover glow in `#98C948`
- Code blocks: dark card (`#1e1d2e` or similar), with syntax colors that complement the blues and greens
- Section dividers / glows: subtle `#5785CA` or `#98C948` radial gradient on dark background
- Feature card borders: `#3C4A4E` with a `#5785CA` left-border accent

**Overall feel:** A terminal / dev-tool aesthetic — dark, precise, a little electric. Think VS Code Dark+ meets a live oscilloscope readout.

---

## Assets

| File | Use |
|---|---|
| `specs/livewire-logo-chatgpt.png` | Logo / hero image |
| `specs/telia-blog.md` | Reference / inspiration (Telia blog post that inspired the project) |
| `milestones/*.png` | Screenshots for the "in action" section |
| `README.md` | Full technical reference — mine for copy |
