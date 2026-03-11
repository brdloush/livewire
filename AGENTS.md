# Notes for AI Agents

This file contains conventions and instructions for AI agents (Claude, etc.)
working on the Livewire project.

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

### Escalation ladder — pick the least invasive option that works

When a fix can't be validated without running code against the live app, use this order:

1. **`hq/hot-swap-query!`** — if the fix is purely a JPQL change on an existing `@Query` method, swap it live, verify with `trace/trace-sql`, then restore and write to source.
2. **Hot-patch Livewire itself** — if the fix is in a Livewire namespace (e.g. `net.brdloush.livewire.*`), eval the new `ns` form and function bodies directly into the REPL (see "Loading new Livewire code" above).
3. **Prototype the fix in Clojure** — when the fix involves Java/Kotlin service logic that *cannot* be hot-swapped (e.g. a new repository method, a new class, structural changes), re-implement the **core query/logic flow** as a throwaway Clojure expression in the REPL. Wrap it in `trace/trace-sql` to measure query count, confirm the fix works, then write the real Java/Kotlin code. Zero restarts needed for validation.
4. **Restart** — only if none of the above apply.

Option 3 is the least obvious but highly effective. The nREPL runs inside the same JVM as the Spring Boot app, so a Clojure expression can call any Spring bean — repositories, services, anything — making it possible to prototype a Java fix entirely in Clojure before touching source.

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
