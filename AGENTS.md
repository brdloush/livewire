# Notes for AI Agents

This file contains conventions and instructions for AI agents (Claude, etc.)
working on the Livewire project.

---

## Load the Livewire skill at session start

At the start of **every** session, immediately load the livewire skill:

```
eca__skill: {"name": "livewire"}
```

After loading, say exactly:

> Livewire skill loaded. Run `lw-start` to connect to a running app, or ask `lw-help` for an overview of what Livewire can do.

Do not run `lw-start` automatically — the app may not be running. Do not add anything else.

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

## Working style — think, don't just do

I'm naturally proactive and eager to fix things. That's not always what you need. Here's how to keep me on a leash:

**Explicit slow-down signals:**

- "Think about..." → reason together, don't code yet
- "Walk me through..." → explain first, don't act
- "Before you do anything..." → propose a plan, then wait
- Questions instead of code → you decide, I don't force solutions

**Scope guardrail:**

> Before starting *any* new task or investigation, ask: *Is this what the user actually asked for, or am I drifting into a side-quest they didn't request?*

If I spot something interesting but unrelated (a bug, refactor, nice-to-have), I mention it as a **note**, not a new todo. I flag it: *"I noticed X — want me to look into it, or stay focused on Y?"* — then wait.

**Tool discipline:**

Before using any tool, ask: *Is this the most efficient path?* Four tool calls debugging a previous attempt is not fun to watch. If I could ask instead of read, I ask. If I could use one tool instead of four, I do.

**When unsure:** pause, ask, don't assume the main track is clear.

---

## Git discipline — always ask before committing or pushing

**At the start of every session, run `git status` before doing anything else.**
Uncommitted changes from a previous session may already be present — code that was
written, validated in the REPL, and then never staged. Discovering this early prevents
that work from being silently overwritten or omitted from a commit. If unexpected
changes are present, show them to the user and agree on how to handle them before
proceeding.

**Investigating a bug and implementing a fix should be done autonomously** — no
need to check in at every step. Use the REPL, read files, write code, validate
against the live app. The gate is at *persisting* the result, not at doing the
work.

Once the fix is validated, present it (diff + REPL evidence where applicable) and
**wait for explicit user approval** before running any `git commit` or `git push`.

This applies even when the change is small or the user said "do it" in a general
sense earlier — confirm the specific commit/push action each time.

**Never combine `git commit` and `git push` in a single command.** Commit first,
then wait for explicit push approval. Bundling them silently bypasses the push gate.

**"Yes" to a task is not "yes" to pushing.** If the user approves a multi-step task
(e.g. "update the docs"), that approval covers commits — not the push. When the commits
are done, stop and ask: *"Ready to push?"* every single time, no exceptions.

---

## No force pushes

Never use `git push --force` or `git push --force-with-lease` on the
`livewire` repository. The repo is public; force-pushing rewrites
history that others may have already fetched.

The only exception is the `gh-pages` branch, which is always
assembled fresh from `web/` by `bb deploy-pages` and has no shared
history worth preserving.

## Git discipline is livewire-only

Git commands (`git add`, `git commit`, `git push`, etc.) apply only to the
`livewire` repository. Never run them in companion apps such as `bloated-shelf`.
If a feature requires changes to a companion app, make them — but leave any
committing or pushing in that repo to Tomas.

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

### Feature delivery checklist — nREPL-facing features

When planning or implementing a feature that users can call from the nREPL, work
through this checklist as part of the plan. Each item is a candidate task, not a
guarantee — rule it in or out explicitly rather than silently skipping it.

| # | Deliverable | When to include |
|---|---|---|
| 1 | **Clojure implementation** in the appropriate `net.brdloush.livewire.*` namespace | Always |
| 2 | **Wrapper script** in `skills/livewire/bin/` | When the function takes a small number of fixed arguments and its output is useful standalone. Key criterion: would an agent reach for this script without any extra filtering? (compare `lw-inspect-entity` — yes, one entity name in, structured result out; `lw-all-bean-deps` — yes, now that it defaults to app-only and returns ~10–20 focused beans; `lw-all-bean-deps :app-only false` — no, 250+ raw entries are not standalone-useful) |
| 3 | **SKILL.md** — table row + subsection with examples | Always; the skill is the contract between the library and the agent |
| 4 | **README.md** — extend the relevant "What you can do" section | Always for new public functions; a code snippet + one-liner is enough |
| 5 | **`web/getting-started.html`** — new "try this prompt" card | When there is a natural, compelling plain-English prompt that demonstrates the feature |
| 6 | **`web/index.html`** — update or extend a feature card | When the feature broadens an existing capability category; add a new card only if it represents a genuinely distinct capability |

Raise any of these as explicit tasks in the plan so they are tracked and not forgotten.

---

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

---

## Collaboration improvement loop

`tasks/human-improvements-ideas.md` is a **private, gitignored** capture buffer for
agent observations about how the collaboration itself could be improved — things Tomas
could do differently that would make the agent more effective.

**At session start:** read it alongside `tasks/lessons.md`. Let its observations
inform how you interpret the current session context.

**When you notice something actionable** — a recurring friction, a missing piece of
context, a habit that would save turns — append an entry:
```
- YYYY-MM-DD: <observation> — <why it would help>
```

Keep the bar high: only genuine, recurring patterns worth changing. One-off situational
things (e.g. "app had no data today") do not belong here.

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

### 0. Verify git state is clean before starting

Before touching any files, confirm:

```bash
git status
```

The output must show **"nothing to commit, working tree clean"** and **"Your branch is up to date with 'origin/main'"**. If there are uncommitted changes, a stale rebase in progress (`.git/rebase-merge` or `.git/rebase-apply`), or a diverged branch — **stop and resolve those first**. Starting the release with a dirty or inconsistent git state causes commits to land in the wrong place, branches to diverge, and hard resets to be needed.

### 1. Bump version + update CHANGELOG

Bump the version in **four places**:

- `project.clj` — change `X.Y.Z-SNAPSHOT` → `X.Y.Z`
- `attach/build.clj` — change `(def version "X.Y.Z-SNAPSHOT")` → `"X.Y.Z"`
- `attach/deps.edn` — change `net.brdloush/livewire {:mvn/version "X.Y.Z-SNAPSHOT"}` → `"X.Y.Z"`
- `attach.jsh` — change `String _LW_VERSION = "X.Y.Z-SNAPSHOT"` → `"X.Y.Z"`

Add a `## [X.Y.Z] — YYYY-MM-DD` section to `CHANGELOG.md` covering all changes
since the previous release (new features, fixes, breaking changes).

Commit all files:
```bash
git add project.clj attach/build.clj attach/deps.edn CHANGELOG.md
git commit -m "chore: bump version to X.Y.Z and update changelog"
```

### 2. Build release artifacts

The build order matters here — `livewire-attach` depends on the main `livewire` jar,
which isn't on Maven Central yet. Install it locally first so the attach build can
resolve it, then build both release artifact sets:

```bash
bb install           # installs livewire X.Y.Z into the local Maven repo
bb release-jars      # builds the main livewire release artifacts
bb attach-release-jars  # builds the livewire-attach release artifacts
```

⚠️ **Do not run `bb install` again after this point** — it overwrites `target/provided/`
with a SNAPSHOT build, destroying the signed release artifacts. If anything goes wrong
and you need to rebuild, you must redo `bb release-jars`, `bb attach-release-jars`,
`bb sign-jars`, and `bb bundle` from scratch.

Verify all four artifacts for each module are reported as ✅ before continuing.

### 3. Update `versions.json` with the artifact sha256

After `bb attach-release-jars` the attach bundle exists locally. Compute its checksum
and update `versions.json` before tagging, so the sha256 is part of the tagged commit.

⚠️ **The sha256 must be computed from the exact jar that will be uploaded to the GitHub
Release.** If the jar is ever rebuilt (e.g. because a previous step failed), recompute
the sha256 — rebuilt jars are not byte-for-byte identical due to timestamps.

```bash
# Linux
sha256sum attach/target/provided/livewire-attach-X.Y.Z.jar

# macOS
shasum -a 256 attach/target/provided/livewire-attach-X.Y.Z.jar
```

Edit `versions.json`:
- Set `"latest"` to `"X.Y.Z"`
- Add (or update) the version entry under `"versions"` with the correct `"bundle-url"` and the computed `"sha256"`

```json
{
  "latest": "X.Y.Z",
  "versions": {
    "X.Y.Z": {
      "bundle-url": "https://github.com/brdloush/livewire/releases/download/vX.Y.Z/livewire-attach-X.Y.Z.jar",
      "sha256": "<computed-hash>"
    }
  }
}
```

Commit it alongside the version bump:
```bash
git add versions.json
git commit -m "chore: update versions.json sha256 for X.Y.Z"
```

### 4. Tag the release

```bash
git tag -a vX.Y.Z -m "Release X.Y.Z"
```

### 5. Create the GitHub Release

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

### 6. Sign the artifacts — ⚠️ ask first

**Before running `bb sign-jars`, always tell the user to have their GPG password
ready and wait for explicit confirmation.** The command prompts immediately and will
fail silently if the passphrase entry is cancelled.

Once the user confirms:
```bash
bb sign-jars
```

### 7. Build the upload bundle

```bash
bb bundle
```

This produces `target/livewire-X.Y.Z-bundle.zip` ready for upload to
**https://central.sonatype.com/** (Publishing → Upload).

### 8. Push commits and tag

Remind the user to push — do **not** push autonomously (see Git discipline above):
```
git push && git push origin vX.Y.Z
```

Push the specific release tag by name rather than `git push --tags`. The `--tags` flag
pushes **all** local tags including old ones that already exist on the remote, causing
a spurious rejection error even when the new tag is pushed successfully.

### 9. Bump to next SNAPSHOT immediately

⚠️ **Do not run this step until the user confirms the bundle has been uploaded and accepted
by Maven Central.** Running `bb install` overwrites the signed release artifacts in
`target/provided/` with SNAPSHOT builds, destroying them. If you proceed too early the user
will have to re-run `bb release-jars`, `bb sign-jars`, and `bb bundle` from scratch.

After Maven Central confirms, bump the version in **four places**:
```
X.Y.Z → X.(Y+1).0-SNAPSHOT   (or X.Y.(Z+1)-SNAPSHOT for a patch release)
```
- `project.clj`
- `attach/build.clj`
- `attach/deps.edn`
- `attach.jsh` — `String _LW_VERSION`

Commit and install locally so dependent apps pick up the new snapshot immediately:
```bash
git add project.clj attach/build.clj attach/deps.edn attach.jsh
git commit -m "chore: bump version to X.(Y+1).0-SNAPSHOT"
bb install
```

### 10. Update web/ and docs while Maven Central publishes

Maven Central publishing takes **10+ minutes** after the bundle is uploaded. Use this
waiting time productively — update `web/` and any docs that reference the previous version.

**Always do at minimum:**
- Bump the Livewire version string in `web/getting-started.html` (Maven snippet, Step 1)
- Bump the Livewire version string in `web/index.html` (Maven snippet, `#install` section)
- Bump the Livewire version string in `README.md` — both the Maven **and** Gradle snippets

Verify nothing is missed with: `grep -r "X.Y.Z" README.md web/`

**Also consider for significant releases:**
- Add new feature cards to the `#features` section of `index.html`
- Add new example prompts under Step 4 of `getting-started.html`
- Update screenshots or screencasts if the UX changed visibly

Commit these `web/` changes to `main` as usual. **Do not run `bb deploy-pages` yet** —
the artifacts must be live on Maven Central first or the install instructions will point
to a version that cannot be resolved.

### 11. Deploy the website — ⚠️ only after Maven Central confirms

Once the user confirms the release is visible on Maven Central
(https://central.sonatype.com/ or by resolving the dependency), remind them:

```bash
bb deploy-pages
```

Do **not** suggest or run this step until Maven Central availability is confirmed.
The live site must always reflect an installable version.

---

## Deploying the website

The files under `web/` are the source for https://brdloush.github.io/livewire/.
They are **not** served directly from `main` — they are published to the `gh-pages`
branch by the maintainer running:

```bash
bb deploy-pages
```

**Do not suggest `bb deploy-pages` after every `web/` commit.** The website should
reflect a released version of the library, not just whatever is on `main`. The correct
order is:

1. Complete the full release process (version bump → artifacts → GitHub Release → push tag)
2. Only then run `bb deploy-pages` to update the live site

After a release is published, remind the maintainer:
> The release is live. When you're ready, run `bb deploy-pages` to update the website.

---

## Clojure gotchas

### Never use the `lw/` alias inside Livewire source files

The `lw` alias (`require '[net.brdloush.livewire.core :as lw]`) only exists in REPL
sessions. Inside `core.clj` itself, always call functions by their bare name:

```clojure
;; ❌ Wrong — compiles fine in a hot-patched REPL but fails on JAR load
(defn bean-tx [bean-name]
  (let [b (lw/bean bean-name) ...]
    ...))

;; ✅ Correct — bare name, works both in REPL and on JAR load
(defn bean-tx [bean-name]
  (let [b (bean bean-name) ...]
    ...))
```

The REPL hot-patch masks this bug because the `ns` form is re-evaluated with the alias
already in scope. The error only surfaces when the JAR is loaded fresh (e.g. app restart),
making it easy to ship broken code that passed all REPL tests.

---

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

The `specs` folder contains context, architectural designs, and feature specifications for this project. When starting a new task or component, always consult the relevant files in the `specs` directory for guidance.

### Reading specs

Specs are numbered sequentially (`01-`, `02-`, …). Read the ones relevant to the area you are working in. They are the authoritative record of *why* a feature was designed the way it was — consult them before making architectural decisions, not after.

### Writing a new spec

Name the file `NN-short-description.md` where `NN` is the next available number. Do **not** read existing spec files to derive the style — use the structure below directly.

Every spec must contain, in order:

1. **Motivation** — what problem or friction prompted this? One or two concrete paragraphs. Ground it in a real scenario, not abstract principles. Name the pain point explicitly.

2. **Relationship to existing features** *(omit if this is the first feature in its area)* — a short table or paragraph showing how this fits alongside what already exists. Prevents duplication and clarifies the scope boundary.

3. **Goal** — one crisp sentence or short bullet list: what does this feature do? Not how — just what.

4. **Proposed solution** — the meat of the spec:
   - API shape (function signatures, option keys, return value structure with a realistic example)
   - Algorithm or implementation outline (enough detail that the implementer does not need to invent the approach, but not so much that it pre-empts REPL-driven discovery)
   - CLI wrapper shape if one is planned (command name, arguments, sample output)

5. **Usage examples** *(omit only if the API is trivially obvious)* — a short worked scenario showing the human question that triggers the feature, the call, and the response interpreted into a concrete decision or action.

6. **Limitations** — be honest. What does this feature *not* see? Where can it give a wrong answer? Under what conditions should the user not trust it? This section exists so the SKILL.md entry can carry the same warnings.

7. **Out of scope** *(omit if nothing is being deliberately excluded)* — things that might seem like natural extensions but are explicitly not part of this spec. Prevents scope creep during implementation.

8. **Implementation notes** *(omit if implementation is straightforward)* — gotchas, reuse opportunities, ordering constraints, known tricky cases. Not a full implementation plan — just the things that are non-obvious and worth flagging before the first line of code is written.

9. **Deliverables** — a small table listing what must exist when this feature is "done": Clojure function(s), wrapper script(s), SKILL.md update, README update, web/ update. Each row is a checkbox for the implementer.

**Style rules:**
- Write in plain prose, not bullet soup. Use bullets only for lists of genuinely parallel items.
- Use the bloated-shelf domain for all examples (books, authors, loans, members, reviews — never accounts, investments, or other app-specific domains).
- Keep the spec self-contained. A reader should understand the full design without reading any other file.
- Avoid hedging language ("might", "could potentially", "possibly"). Either specify it or put it in Out of scope.

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
