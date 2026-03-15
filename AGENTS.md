# Notes for AI Agents

This file contains conventions and instructions for AI agents (Claude, etc.)
working on the Livewire project.

---

## ECA vs. other agentic tools

These instructions are written for **ECA (Editor Code Assistant)**, which is the
tool used by the primary developer ([@brdloush](https://github.com/brdloush)) for
day-to-day Livewire development. Some rules reference ECA-specific capabilities:

| ECA concept | What it does | If you use a different tool |
|---|---|---|
| `eca__task` | Structured in-session task planner with states, priorities, and blocking dependencies | Use your tool's equivalent, or maintain `tasks/todo.md` manually |
| `explorer` subagent | Spawns a read-only agent for codebase research without polluting the main context | Use a separate read-only context window, or just be mindful of context consumption |
| `general` subagent | Spawns a read-write agent for parallel or isolated workstreams | Use your tool's parallel execution feature, or run sequentially |

The *intent* behind each rule is tool-agnostic. Only the specific mechanism is
ECA-flavoured. If you are working with a different agentic setup, apply the same
principles using the closest equivalent your tool offers.

_(Future sections may add explicit hints for other popular tools — Cursor, Windsurf, Claude Code CLI, etc.)_

---

## Git discipline — always ask before committing or pushing

**Investigating a bug and implementing a fix should be done autonomously** — no
need to check in at every step. Use the REPL, read files, write code, validate
against the live app. The gate is at *persisting* the result, not at doing the
work.

Once the fix is validated, present it (diff + REPL evidence where applicable) and
**wait for explicit user approval** before running any `git commit` or `git push`.

This applies even when the change is small or the user said "do it" in a general
sense earlier — confirm the specific commit/push action each time.

---

## No force pushes

Never use `git push --force` or `git push --force-with-lease` on the
`livewire` repository. The repo is public; force-pushing rewrites
history that others may have already fetched.

The only exception is the `gh-pages` branch, which is always
assembled fresh from `web/` by `bb deploy-pages` and has no shared
history worth preserving.

---

## Planning & task management

For any task with **3 or more steps**, or any task that involves an architectural
decision, enter plan mode before doing anything else:

1. Write the plan as tasks using the `eca__task` tool (preferred for in-session
   work — structured, supports blocking dependencies and status tracking).
2. Present the plan and wait for a go-ahead before starting implementation.
3. **If something goes sideways mid-task: STOP. Re-plan. Do not keep pushing.**
   A wrong direction executed quickly makes things worse; a short pause to
   re-assess saves turns.

### In-session vs. multi-session work

- **`eca__task`** is the primary tracker for work within a single session. It is
  ephemeral — tasks do not survive a context reset.
- **`tasks/todo.md`** is the convention for work expected to span multiple
  sessions or days. Write an initial plan there so a fresh agent (or a future
  session) can resume without losing context. Check and update it at session
  start and end.

---

## Subagent strategy

Two subagent types are available:

| Agent | Type | Use for |
|---|---|---|
| `explorer` | Read-only | Codebase search, file reading, structural analysis — anything that does not modify files |
| `general` | Read-write | Multi-step execution, parallel workstreams, isolated research that would bloat the main context |

**Delegate to a subagent when:**
- The task is exploratory or research-only (use `explorer`)
- Two workstreams can run in parallel and are independent
- A subtask would consume significant context without producing a decision (e.g. scanning many files, summarising a large codebase area)

**Keep in main context when:**
- A REPL evaluation is needed — only the main agent interacts with the live nREPL
- A file edit follows immediately from the result
- The task requires a decision or judgement that affects the overall plan
- The task is small enough that spawning a subagent costs more than it saves

One task per subagent; keep subagent scope focused.

---

## Self-improvement loop

Mistakes that are corrected once and then forgotten will recur. Use
`tasks/lessons.md` as a living capture buffer to prevent that.

**After any user correction:**
Immediately append a brief entry to `tasks/lessons.md` in this format — **this must be the very next action, before resuming any task work**:
```
- YYYY-MM-DD: <one or two sentences describing what went wrong and what to do instead>
```

**At the start of each session:**
If `tasks/lessons.md` exists, read it before doing any work and take its lessons
into account for the session ahead.

**Promoting lessons to canon:**
When a lesson has recurred across sessions or proven broadly applicable, promote it
into a named section of `AGENTS.md` (like "Clojure gotchas") and remove it from
`tasks/lessons.md`. The goal is for `AGENTS.md` to contain stable, curated
knowledge and `tasks/lessons.md` to contain recent, still-accumulating
observations.

---

Praise is also worth capturing. Use `tasks/praises.md` as a record of moments the
user explicitly praised the work done — they signal what "good" looks like here.

**After any explicit praise from the user:**
Append an entry to `tasks/praises.md` in this format:
```
- YYYY-MM-DD: "<exact or paraphrased praise>" — brief description of what was done.

  *Why I think this was praised:* one or two sentences with your own honest
  interpretation of the underlying principle or behaviour that earned it.
```

The "why I think" part is important — it forces reflection on *what* made the work
good, not just that it was praised.

---

## Shell discipline

### Never run long-running processes without `&`

Dev servers, watchers, and any other process that does not exit on its own must always
be backgrounded with `&`. Running them in the foreground blocks the shell and the agent
loses control of the session.

Even with `&`, the shell tool captures stdout — if the process keeps writing output, the
tool still hangs. Always redirect output to a log file so the shell returns immediately:

```bash
# ❌ blocks — agent is stuck until the process exits or times out
bb serve

# ❌ still hangs — & backgrounds the process but stdout capture still blocks the tool
bb serve &

# ✅ correct — backgrounds and redirects output, shell returns immediately
bb serve > /tmp/bb-serve.log 2>&1 &
```

After backgrounding, confirm the port is up before proceeding:

```bash
bb serve > /tmp/bb-serve.log 2>&1 & sleep 1 && grep -m1 "Serving\|http://" /tmp/bb-serve.log
```

---

## Core principles

These are the three meta-rules that all other rules in this document serve:

- **Simplicity first.** Make every change as simple as possible. The REPL
  escalation ladder exists to enforce this — always pick the least invasive option
  that still solves the problem.
- **No laziness.** Find root causes. No temporary fixes. Senior developer
  standards. If a fix feels like a workaround, it probably is.
- **Minimal impact.** Changes should only touch what is necessary. Avoid
  introducing unrelated modifications, reformats, or refactors alongside a fix.

---

## Release process

Follow these steps in order. **Never skip ahead** — each step depends on the previous one.

### 1. Bump version + update CHANGELOG

In `project.clj`, change `X.Y.Z-SNAPSHOT` → `X.Y.Z`.

Add a `## [X.Y.Z] — YYYY-MM-DD` section to `CHANGELOG.md` covering all changes
since the previous release (new features, fixes, breaking changes).

Commit both files:
```bash
git add project.clj CHANGELOG.md
git commit -m "chore: bump version to X.Y.Z and update changelog"
```

### 2. Build release artifacts

```bash
bb release-jars
```

Verify all four artifacts are reported as ✅ before continuing.

### 3. Tag the release

```bash
git tag -a vX.Y.Z -m "Release X.Y.Z"
```

### 4. Create the GitHub Release

Using the changelog notes for the version being released:

```bash
gh release create vX.Y.Z \
  --title "vX.Y.Z" \
  --target <commit-sha-of-tag> \
  --notes "## What's Changed

### Added
...

### Fixed
..."
```

The `--target` must be the **commit SHA** that the tag points to (not the tag name itself):

```bash
git rev-list -n1 vX.Y.Z
```

### 5. Sign the artifacts — ⚠️ ask first

**Before running `bb sign-jars`, always tell the user to have their GPG password
ready and wait for explicit confirmation.** The command prompts immediately and will
fail silently if the passphrase entry is cancelled.

Once the user confirms:
```bash
bb sign-jars
```

### 6. Build the upload bundle

```bash
bb bundle
```

This produces `target/livewire-X.Y.Z-bundle.zip` ready for upload to
**https://central.sonatype.com/** (Publishing → Upload).

### 7. Push commits and tag

Remind the user to push — do **not** push autonomously (see Git discipline above):
```
git push && git push --tags
```

### 8. Bump to next SNAPSHOT immediately

After the release commit, bump `project.clj` to the next development version:
```
X.Y.Z → X.(Y+1).0-SNAPSHOT   (or X.Y.(Z+1)-SNAPSHOT for a patch release)
```
Commit:
```bash
git add project.clj
git commit -m "chore: bump version to X.(Y+1).0-SNAPSHOT"
```

### 9. Update the website (separate step)

Do **not** run `bb deploy-pages` as part of the release — the user controls when
the docs site is updated. Remind them after the release is published:
> The release is live. When you're ready, run `bb deploy-pages` to update the website.

---

## Deploying the website

The files under `web/` are the source for https://brdloush.github.io/livewire/.
They are **not** served directly from `main` — they are published to the `gh-pages`
branch by the maintainer running:

```bash
bb deploy-pages
```

After any commit that touches `web/`, remind the maintainer to run this command
so the live site stays in sync. Do not run it yourself.

---

## Clojure gotchas

### `defonce` does not accept docstrings

Unlike `def`, `defonce` only takes two arguments: the name and the init form.
Passing a docstring as a second argument causes an `ArityException` at load time:

```clojure
;; ❌ Wrong — throws ArityException: Wrong number of args (3) passed to: clojure.core/defonce
(defonce my-atom
  "This docstring is not supported."
  (atom {}))

;; ✅ Correct — use a ;;; comment above the form instead
;;; This atom holds ...
(defonce my-atom (atom {}))
```

---

## Loading new Livewire code into a running app

After editing and installing a new version of Livewire (`bb install`), the
running Spring Boot app still has the **old JAR on its classpath**. Calling
`(require ... :reload)` just re-reads the same old file from that JAR —
it does not pick up the new version.

**Do not waste a turn trying `:reload`. It won't work.**

Instead, push the new definitions directly into the running REPL by evaluating
the relevant `ns` form and function/macro bodies verbatim. Example:

```clojure
;; 1. Switch into (or create) the target namespace
(ns net.brdloush.livewire.query
  (:require [net.brdloush.livewire.core :as core]))

;; 2. Paste the new/fixed definitions
(defn sql [query & params] ...)
```

This hot-patches the live JVM without a restart. The fix is live immediately.
The correct code is already persisted on disk — the eval is just bridging the
gap until the app is next restarted with the new JAR.

---

## REPL results

After evaluating any expression against the live nREPL (port 7888), always
present the result in a human-readable form — don't make the user unroll the
raw MCP tool response.

- **Collections of maps** → markdown table
- **Single map** → markdown table (one row) or inline key/value list
- **Scalar / short value** → inline code in prose
- **Large flat lists** (e.g. bean names) → bullet list or comma-separated prose

Example — query returns a vector of maps:

| id | email | status | active |
|----|-------|--------|--------|
| 1  | test@example.com | PENDING | false |

---

## Demo & dogfood app — Bloated Shelf

[`/home/user/projects/bloated-shelf`](../bloated-shelf) ([GitHub](https://github.com/brdloush/bloated-shelf)) is the canonical target application for
developing and testing Livewire. It is a **self-contained Spring Boot + Hibernate library API**
that intentionally demonstrates the JPA N+1 query problem at scale.

### Purpose

- **Demo**: shows Livewire capabilities against a realistic, reproducible N+1 scenario — hit
  `GET /api/books` and watch Hibernate fire 1,201 queries; hit `GET /api/admin/most-loaned` and
  see a single clean JOIN do the same job.
- **Dogfood**: the nREPL is live at **port 7888** (Livewire default). Any new Livewire feature
  should be validated here before shipping.

### Domain model

```
Author
  └── Book
        ├── Genre       (many-to-many)
        ├── Review      (→ LibraryMember)
        └── LoanRecord  (→ LibraryMember)
```

30 authors · 200 books · 50 members · ~5 reviews/book · ~8 loans/member. All lazily loaded,
all seeded deterministically — results are reproducible across restarts.

### Starting the app

```bash
cd /home/user/projects/bloated-shelf
mvn spring-boot:run -Dspring-boot.run.profiles=dev,seed
```

Testcontainers spins up a PostgreSQL 16 container automatically. No external DB needed.

### Authentication (HTTP Basic)

| Username | Password | Roles |
|---|---|---|
| `admin` | `admin123` | ADMIN, LIBRARIAN, MEMBER, VIEWER |
| `librarian` | `lib123` | LIBRARIAN, MEMBER, VIEWER |
| `member1` | `member123` | MEMBER, VIEWER |
| `readonly` | `read123` | VIEWER |

Use `lw/run-as` with one of these usernames (or an appropriate role vector) when calling
`@PreAuthorize`-guarded controllers from the REPL.

### Key endpoints

| Endpoint | Auth | N+1? |
|---|---|---|
| `GET /api/books` | MEMBER | ✅ ~1,201 queries |
| `GET /api/authors` | VIEWER | ✅ per-author cascade |
| `GET /api/members` | LIBRARIAN | ✅ per-loan cascade |
| `GET /api/admin/stats` | ADMIN | ❌ 5 COUNT queries |
| `GET /api/admin/most-loaned` | ADMIN | ❌ single JOIN |

### Key Spring beans (for REPL use)

| Bean name | Type |
|---|---|
| `bookRepository` | `BookRepository` |
| `authorRepository` | `AuthorRepository` |
| `reviewRepository` | `ReviewRepository` |
| `libraryMemberRepository` | `LibraryMemberRepository` |
| `loanRecordRepository` | `LoanRecordRepository` |
| `bookController` | `BookController` |
| `adminController` | `AdminController` |

### Quick REPL smoke-test

```clojure
(require '[net.brdloush.livewire.core :as lw]
         '[net.brdloush.livewire.trace :as trace])

;; Confirm N+1 on /api/books equivalent
(let [res (trace/trace-sql
            (lw/run-as "member1"
              (lw/in-readonly-tx
                (.getAllBooks (lw/bean "bookService")))))]
  (select-keys res [:count :duration-ms]))
;; => {:count 1201, :duration-ms ...}
```

---

## Specs

The `specs` folder contains various context, architectural designs, and feature specifications about what we want to achieve in this project. When starting a new task or component, always consult the relevant files in the `specs` directory for guidance.

---

## Live REPL-driven development

When developing new features or fixing bugs in Livewire, we strongly prefer an interactive approach. **Always use the live REPL to test things immediately**. Don't write code blindly and hope it compiles/runs; evaluate small snippets, check the output in the target Spring Boot application environment, and iteratively build up the solution. This is the core "agentic feedback loop" we aim to achieve.

### Eval before writing

**Prove every piece of logic in the REPL before writing or editing any `.clj` file.**
This applies to new functions, refactors, and delegations alike — even when the code looks obvious. The order is always:

1. Hot-patch the code into the live REPL (eval `ns` form + function bodies verbatim)
2. Run representative calls and verify the output is correct
3. Only then write the file

The REPL eval is essentially free. A wrong file write followed by a discovered bug costs multiple turns to recover from. When in doubt, eval one more time.

**Unless** constructing the REPL expression itself becomes genuinely impractical — in which case: say so explicitly, explain why, and ask whether to proceed to writing anyway or reconsider the design first. No hurt feelings. But note that difficulty in REPL-testing is itself a signal: good Clojure code should be REPL-friendly by design. If something is hard to run in isolation, the design likely has too many dependencies or baked-in assumptions — worth reconsidering before writing it at all.

### Escalation ladder — pick the least invasive option that works

When a fix can't be validated without running code against the live app, use this order:

1. **`hq/hot-swap-query!`** — if the fix is purely a JPQL change on an existing `@Query` method, swap it live, verify with `trace/trace-sql`, then restore and write to source.
2. **Hot-patch Livewire itself** — if the fix is in a Livewire namespace (e.g. `net.brdloush.livewire.*`), eval the new `ns` form and function bodies directly into the REPL (see "Loading new Livewire code" above).
3. **Prototype the fix in Clojure** — when the fix involves Java/Kotlin service logic that *cannot* be hot-swapped (e.g. a new repository method, a new class, structural changes), re-implement the **core query/logic flow** as a throwaway Clojure expression in the REPL. Wrap it in `trace/trace-sql` to measure query count, confirm the fix works, then write the real Java/Kotlin code. Zero restarts needed for validation.
4. **Restart** — only if none of the above apply.

Option 3 is the least obvious but highly effective. The nREPL runs inside the same JVM as the Spring Boot app, so a Clojure expression can call any Spring bean — repositories, services, anything — making it possible to prototype a Java fix entirely in Clojure before touching source.

**After a successful prototype (step 3), pause before writing the final code.**
Ask: *"Is there a more elegant way?"* The prototype proved the concept; the final
Java/Kotlin implementation must be clean — not a mechanical translation of the
throwaway Clojure expression. If the prototype revealed a simpler model, use it.

**Before presenting any output to the user**, apply this self-check:
*"Would a staff engineer approve this?"* — consider readability, side effects on
other callers, and overall design quality. Tests are not required at this stage
of the project, but write code that is easy to test later: minimal side effects,
logical structure, good naming. If the answer is no, iterate first.

### ⚠️ Side effects — clean up after exploratory sessions

Some REPL operations leave **persistent state** in the live JVM that survives across calls:

- **`hq/hot-swap-query!`** — patched queries stay active until explicitly restored. Other callers, background jobs, or monitoring queries will hit the swapped version.
- **`lw/in-tx`** — always rolls back automatically. Safe, no cleanup needed.
- **`lw/in-readonly-tx`** — read-only, no cleanup needed.

**After any session that used `hq/hot-swap-query!`, always restore before finishing:**

```clojure
;; Restore all swapped queries in one call
(hq/reset-all!)

;; Verify nothing is left
(hq/list-swapped) ; => []
```

If a task involved multiple incremental swaps (e.g. testing several JPQL variants), `reset-all!` handles all of them regardless of how many were accumulated.
