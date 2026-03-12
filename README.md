# ⚡ Livewire

> *A live nREPL wire into your running Spring Boot app. Dev only. You've been warned.*

Your AI agent can read your code. What it **can't** do is ask your app a question.

Livewire fixes that. It embeds a Clojure nREPL server inside a running Spring Boot application —
giving an AI agent (or a curious developer) a live, stateful probe into the JVM.
Beans, queries, transactions, security context, and all.

```clojure
;; How many queries does /api/books actually fire?
(trace/detect-n+1
  (trace/trace-sql
    (lw/run-as "member1"
      (.getBooks (lw/bean "bookController")))))
;; => {:total-queries 481, :suspicious-queries [{...} {:count 200} ...]}
```

481 queries. For a list page. Now you know. Now you can fix it — without restarting the app.

> **Not a Clojure developer?** Don't worry about the syntax above — the agent writes and runs it for you.
> The parentheses look foreign at first, but the language is actually small, consistent, and surprisingly
> readable once your eyes adjust. If you ever feel curious enough to type a snippet yourself,
> the basics take an afternoon. You won't regret it. But you don't have to.

---

## The problem

Modern Spring Boot development has a fundamental feedback loop problem.
AI agents make it worse.

```
edit → restart (30–120s) → observe → repeat
```

Agents reason **statically**. They read the code, form a hypothesis, and apply a fix —
but they can't observe the running system. So they guess. And when they're wrong,
you restart again.

Livewire breaks the loop:

```
observe → hypothesise → hot-swap → verify → recompile
         (zero restarts ──────────────────────────────)
```

Recompile and the query-watcher auto-applies your `@Query` changes live.
No REPL call needed — no restart either.

---

## Installation

Build and install to your local Maven repository:

```bash
bb install
```

Then add the dependency to your Spring Boot project:

**Maven**
```xml
<dependency>
  <groupId>net.brdloush</groupId>
  <artifactId>livewire</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**Gradle**
```groovy
implementation 'net.brdloush:livewire:0.1.0-SNAPSHOT'
```

---

## Activation

Livewire auto-configures itself when **two conditions are met**:

1. The JAR is on the classpath
2. The property `livewire.enabled=true` is set

Add it to whichever local properties file your project already uses:

```properties
# application-local.properties  (or -dev, -sandbox, whatever you call it)
livewire.enabled=true

# Optional: override the default nREPL port
livewire.nrepl.port=7888
```

You'll see this in the logs on startup:
```
[livewire] nREPL server started on port 7888
```

That's it. No annotations, no Spring profiles to configure, no code changes.

---

## Connecting

### CIDER (Emacs)

```
M-x cider-connect-clj  →  localhost  →  7888
```

Then require the namespaces you need:

```clojure
(require '[net.brdloush.livewire.core :as lw]
         '[net.brdloush.livewire.trace :as trace]
         '[net.brdloush.livewire.hot-queries :as hq]
         '[net.brdloush.livewire.introspect :as intro])
```

### Terminal

```bash
clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' \
        -M -m nrepl.cmdline --connect --host 127.0.0.1 --port 7888
```

### AI agents (clojure-mcp)

Point the MCP server at port 7888. The agent can then use `clojure_eval` to
probe the live app directly — no HTTP, no mocking, no guessing.

---

## What you can do

### 🔍 Inspect beans and properties

```clojure
;; What repos are registered?
(lw/find-beans-matching ".*Repository.*")
;; => ("bookRepository" "authorRepository" "reviewRepository" ...)

;; What DB URL is the app actually talking to?
(lw/props-matching "spring\\.datasource\\.url")
;; => {"spring.datasource.url" "jdbc:postgresql://localhost:32808/test"}

;; Runtime environment summary
(lw/info)
;; => {:spring-boot "4.0.1", :spring "7.0.2", :hibernate "7.2.0.Final", :java "25", ...}
```

### 🗄️ Run queries safely

```clojure
;; Raw SQL through the live DataSource — always cap results
(lw/in-readonly-tx
  (q/sql "SELECT id, title FROM book LIMIT 5"))
;; => [{:id 1, :title "All the King's Men"} ...]

;; Repository calls — always page, never call .findAll without a Pageable
(lw/in-readonly-tx
  (->> (.findAll (lw/bean "bookRepository")
                 (org.springframework.data.domain.PageRequest/of 0 3))
       .getContent
       (mapv #(select-keys (clojure.core/bean %) [:id :title :isbn]))))
;; => [{:id 1, :title "All the King's Men", :isbn "979-0-925405-37-0"} ...]

;; Mutations roll back automatically — safe to experiment
(lw/in-tx
  (.save (lw/bean "bookRepository") ...)
  (.count (lw/bean "bookRepository")))
;; => 201  (and then silently rolled back)
```

### 🔒 Call security-guarded methods

Spring Security doesn't know about your REPL. Without a `SecurityContext` it'll throw
`AuthenticationCredentialsNotFoundException` the moment you call anything `@PreAuthorize`-guarded.
`run-as` sets one for the duration of the call:

```clojure
;; Pass a username → gets ROLE_USER + ROLE_ADMIN by default
(lw/run-as "admin"
  (.getBookById (lw/bean "bookController") 25))

;; Or specify exact roles
(lw/run-as ["alice" "ROLE_VIEWER"]
  (.getAuthors (lw/bean "authorController")))
```

### 🔬 Trace SQL and detect N+1

```clojure
;; See every SQL a call fires — wrap it and look
(trace/trace-sql
  (lw/in-readonly-tx
    (.count (lw/bean "bookRepository"))))
;; => {:result 200, :count 1, :duration-ms 8,
;;     :queries [{:sql "select count(*) from book b1_0", :caller "..."}]}

;; Detect N+1 automatically
(trace/detect-n+1
  (trace/trace-sql
    (lw/run-as "member1"
      (.getBooks (lw/bean "bookController")))))
;; => {:total-queries 481,
;;     :suspicious-queries [{:sql "select ... from book_genre ...", :count 200}
;;                          {:sql "select ... from review ...",     :count 200}
;;                          {:sql "select ... from library_member", :count 50}
;;                          {:sql "select ... from author ...",     :count 30}]}
```

481 queries for one endpoint. Four N+1 suspects flagged automatically.
Now let's fix it.

### 🔥 Hot-swap a `@Query` live

No restart needed. Swap the JPQL, verify with `trace-sql`, iterate, commit the fix:

```clojure
;; See what @Query methods exist on a repo
(hq/list-queries "bookRepository")
;; => ({:method "findAllWithAuthorAndGenres",
;;      :jpql "SELECT DISTINCT b FROM Book b JOIN FETCH b.author LEFT JOIN FETCH b.genres"}
;;     {:method "findByGenreId", ...} ...)

;; Swap to a candidate fix
(hq/hot-swap-query! "bookRepository" "findAllWithAuthorAndGenres"
  "SELECT DISTINCT b FROM Book b JOIN FETCH b.author
   LEFT JOIN FETCH b.genres LEFT JOIN FETCH b.reviews")

;; Verify — does the query count drop?
(trace/trace-sql
  (lw/run-as "member1"
    (.getBooks (lw/bean "bookController"))))

;; Restore when done — don't leave swapped queries hanging
(hq/reset-all!)
;; => [["bookRepository" "findAllWithAuthorAndGenres"]]
```

Alternatively: just edit the `@Query` in your IDE, recompile, and the query-watcher
picks it up automatically — same result, no REPL call.

### 🧭 Introspect the app's structure

```clojure
;; All HTTP endpoints with their auth requirements
(->> (intro/list-endpoints)
     (filter #(re-find #"books" (str (:paths %))))
     (mapv #(select-keys % [:paths :methods :handler-method :pre-authorize])))
;; => [{:paths ["/api/books"], :methods ["GET"],
;;      :handler-method "getBooks", :pre-authorize "hasRole('MEMBER')"}]

;; All Hibernate-managed entities
(map :name (intro/list-entities))
;; => ("Author" "Book" "Genre" "LoanRecord" "LibraryMember" "Review")

;; Entity schema straight from Hibernate's metamodel
(intro/inspect-entity "Book")
;; => {:table-name "book",
;;     :identifier {:name "id", :type "long"},
;;     :properties [{:name "title", :columns ["title"], :type "string"} ...],
;;     :relations  [{:name "author",  :type :many-to-one,  :target "Author"}
;;                  {:name "genres",  :type :many-to-many, :target "Genre"}
;;                  {:name "reviews", :type :one-to-many,  :target "Review"}]}
```

---

## ⚠️ Security and data — read this

Livewire is a **dev-only** tool and is intentionally not subtle about it.

### Your data is reachable

The nREPL can query any table, call any service, and access anything the JVM can touch.
This is the point — and the risk. **Never enable Livewire against a production database
or any environment with real user data.**

Use it with:
- A local development database seeded with anonymized or synthetic data
- A sandbox / staging environment that is completely isolated from production
- Testcontainers-spun databases (like the Bloated Shelf playground below)
- A self-hosted LLM — your ground, your rules, no data leaving the building

### The nREPL can execute arbitrary code

There is no sandbox. Connecting to port 7888 means executing arbitrary JVM code.
Exposing this port outside localhost is equivalent to handing over the JVM process.

```properties
# ✅ default — localhost only
livewire.nrepl.bind=127.0.0.1

# ❌ please don't
livewire.nrepl.bind=0.0.0.0
```

Livewire defaults to `127.0.0.1` and will not bind to a broader interface unless
you explicitly tell it to. That's a guardrail, not a permission slip.

---

## Try it — the Bloated Shelf playground

[**Bloated Shelf**](https://github.com/brdloush/bloated-shelf) is a real Spring Boot app —
Spring Security, JPA, PostgreSQL, multiple roles, a handful of controllers and services,
a domain model with real relationships. It happens to have an N+1 problem baked in,
but that's just one reason to visit.

The real reason: it's a safe, self-contained Spring app you can hand to an AI agent
along with a live Livewire nREPL and just... see what happens.

**You will be surprised.** Give an agent live, responsive tools with a fast feedback loop
and it stops guessing. It starts *exploring*. It asks questions. It forms hypotheses,
tests them in seconds, and builds on what it learns. The creativity that comes out of
a well-equipped agent with shiny new toys is something you have to see to believe.

**30 authors · 200 books · 50 members · ~5 reviews/book · all lazily loaded**

### Start it

```bash
git clone https://github.com/brdloush/bloated-shelf
cd bloated-shelf
mvn spring-boot:run -Dspring-boot.run.profiles=dev,seed
```

Testcontainers spins up a PostgreSQL 16 container automatically. No external database needed.
The Livewire nREPL comes up on **port 7888**.

### Things to try

- 🔎 **Hunt the N+1**: call the `bookController` and watch `trace-sql` report 481 queries — then fix it without restarting
- 🧭 **Discover the app cold**: ask an agent to map out the domain model, endpoints, and auth rules using only the live REPL — no source reading
- 🔥 **Hot-swap queries**: iterate on JPQL live, measure each variant with `trace-sql`, find the winner, commit
- 🔒 **Test auth boundaries**: call the same endpoint under different roles with `lw/run-as`, see what changes
- 📊 **Profile and compare**: trace the naive N+1 endpoints against the clean aggregation queries in `adminController`
- 🧪 **Prototype in Clojure, ship in Java**: re-implement a service method as a REPL expression, validate query count, *then* write the real fix
- 💬 **Ask nontrivial questions about your data**: *"Which genre has the most overdue loans?"*, *"Who are the top reviewers and what do they have in common?"* — the agent will introspect the entity model, figure out the schema, iterate on queries, and come back with an actual answer. Powerful BI in an agentic chat, no dashboard required
- 🤖 **Let the agent loose**: point a capable agent at the nREPL, give it `SKILL.md` as context, and watch what it does with the freedom

The app ships with an [`AGENTS.md`](https://github.com/brdloush/bloated-shelf/blob/main/AGENTS.md)
covering worked REPL examples, bean names, credentials, and a quick smoke-test —
a solid starting point for an agentic session.

---

## 📖 SKILL.md — the agent's instruction manual

[`skills/livewire/SKILL.md`](skills/livewire/SKILL.md) is the most important file
in this repository if you're working with an AI agent.

It covers the full API across all six namespaces, worked examples, known pitfalls,
and escalation strategies for debugging without restarts. It's written for agents —
but it's perfectly readable by humans too.

**Without `SKILL.md` in the agent's context, cooperation will be poor.**
The agent will hallucinate method signatures, call things that don't exist,
and make sloppy guesses about behaviour it could just... ask the live app about.
With it, the agent knows exactly what tools it has, how to use them, and what to watch out for.

### Make it discoverable

Most agent toolchains pick up context files from well-known locations automatically.
Drop `SKILL.md` somewhere the agent will find it:

```bash
# Globally — available in every project, every session
cp skills/livewire/SKILL.md ~/.claude/skills/livewire.md
# or wherever your agent looks for global context
# (e.g. ~/.config/aider/skills/, a global AGENTS.md, etc.)

# Per-project — checked in alongside the app that uses Livewire
cp skills/livewire/SKILL.md /your/spring-app/SKILL-livewire.md
```

### Load it explicitly

Even if the file is in place, explicitly telling the agent to load the skill
at the start of a session gets much better results than hoping it gets picked up passively:

> *"Load the Livewire skill."*

That one sentence changes the entire session. The agent switches from guessing to probing.
From static analysis to live questions. From "I think the query might be..." to
"I just measured it — 481 queries. Here's why, and here's the fix."

Quick namespace cheatsheet:

| Namespace | Require as | What it does |
|---|---|---|
| `net.brdloush.livewire.core` | `lw` | Beans, transactions, run-as, properties |
| `net.brdloush.livewire.query` | `q` | Raw SQL / JPQL execution |
| `net.brdloush.livewire.trace` | `trace` | SQL tracing, N+1 detection |
| `net.brdloush.livewire.hot-queries` | `hq` | Live `@Query` swap + restore |
| `net.brdloush.livewire.query-watcher` | `qw` | Auto-apply `@Query` on recompile |
| `net.brdloush.livewire.introspect` | `intro` | Endpoints, entities, schema |

---

## What's next

See [TODO.md](TODO.md) for open tasks, planned components, and ideas.

---

*Don't touch live wires in production. But in dev? Grab on.*
