# N+1 Hunting — Tips & Gotchas

Read this file when investigating N+1 query problems, measuring query counts, or validating
that a JPQL / `JOIN FETCH` fix actually eliminates excess queries.

---

> ⚠️ **`cg/blast-radius` is NOT an N+1 detection tool.** It performs static call-graph
> analysis and fires zero SQL. Passing it to `lw-trace-nplus1` will always return
> `{:total-queries 0}` — a meaningless result. For N+1 detection, the expression must
> directly invoke a service or repository method against the live database.

## Which tool to use

| Goal | Use |
|---|---|
| Detect N+1 in a service or endpoint call | **`lw-trace-nplus1`** — runs `detect-n+1(trace-sql(...))` in one shot |
| Measure raw query count or inspect SQL shape | `lw-trace-sql` — returns `:count`, `:duration-ms`, `:queries` |
| Prototype a service fix and measure improvement | `lw-trace-sql` inline in Clojure — you need the raw count, not pattern detection |

**Default to `lw-trace-nplus1` when hunting N+1.** It is purpose-built for this: it captures
SQL, runs `detect-n+1` on the result, and surfaces `:suspicious-queries` in one call.
Only drop to `lw-trace-sql` when you need the raw SQL list or want to compare exact query counts.

---

## Tracing an endpoint you have already called

When you have just called an endpoint (e.g. via `lw-call-endpoint`) and are then asked
to check it for N+1, **reuse what you already know** — do not re-discover method signatures
or guess at repository calls. The pattern is always:

```bash
lw-trace-nplus1 '(lw/run-as ["user" "ROLE_X"] (.serviceMethod (lw/bean "serviceBean") arg))'
```

Where `serviceMethod` and `serviceBean` come from reading the controller source — a step
you should already have done. One `grep` is enough:

```bash
grep -n "getBooksByGenre" /path/to/BookController.java
# => calls bookService.getBooksByGenreId(genreId)
```

Then trace it directly:

```bash
lw-trace-nplus1 '(lw/run-as ["user" "ROLE_MEMBER"] (.getBooksByGenreId (lw/bean "bookService") 1))'
```

**Common mistakes to avoid:**

- ❌ Passing a call-graph expression like `(cg/blast-radius ...)` — this fires no SQL; `:total-queries 0` always means the wrong expression was traced, not that there is no N+1
- ❌ Using HTTP client or `lw-call-endpoint` inside the trace expression — these don't run on the REPL thread Livewire intercepts
- ❌ Using Java literal `1L` — not valid EDN; pass `1` (auto-coerced to Long) or `(long 1)` explicitly
- ❌ Nested `#()` anonymous functions — not allowed in Clojure; use `(fn [] ...)` instead

---

## N+1 presence is data-dependent — always test multiple IDs

An N+1 only fires when the problematic association actually has rows. A query that looks
fine on one record may blow up on another. **Always test several representative IDs**
before concluding there is no N+1.

```bash
# ✅ preferred — lw-trace-nplus1 detects the pattern in one shot
lw-trace-nplus1 '(lw/run-as "admin" (.myEndpoint (lw/bean "myController") 1))'
lw-trace-nplus1 '(lw/run-as "admin" (.myEndpoint (lw/bean "myController") 2))'
```

When you need to compare counts across many IDs at once, use `clj-nrepl-eval` with `trace-sql`
(not `lw-trace-nplus1`, which is a shell script and doesn't compose in a `mapv`):

```clojure
;; Compare query counts across multiple IDs — use trace-sql for the mapv
(mapv (fn [id]
        (let [res (trace/trace-sql
                    (lw/run-as "admin"
                      (.myEndpoint (lw/bean "myController") id)))]
          {:id id :total-queries (:count res) :suspicious (count (:suspicious-queries (trace/detect-n+1 res)))}))
      [1 2 3 4 5])
;; => look for outliers — the problematic ID will stand out with a much higher :total-queries count
```

---

## Shared associations in synthetic REPL test data mask N+1

When creating multiple rows inside `lw/in-tx` to reproduce an N+1, if all rows point to
the **same** associated entity (e.g. the same `createdBy` employee for every row), the L1
cache serves hits 2..N from memory — only 1 extra query fires instead of N, and
`detect-n+1` reports nothing suspicious.

**Rule:** every row must have a **distinct** instance of each suspect association. Reusing
the same entity across rows is the most common reason a synthetic REPL reproduction fails
to trigger the N+1 you already know exists in production.

```clojure
;; ❌ all 3 interventions share the same createdBy — L1 cache hides the N+1
(dotimes [n 3]
  (.setCreatedBy intervention same-employee))   ; only 1 SELECT fires, not 3

;; ✅ distinct entity per row — L1 cache can't help, N+1 fires as expected
(dotimes [n 3]
  (.setCreatedBy intervention (nth distinct-employees n)))  ; 3 SELECTs fire
```

---

## `detect-n+1` has a count threshold — low-count N+1s may not be flagged

`detect-n+1` only marks a query pattern as `:suspicious-queries` when it fires above an
internal minimum count. In practice a pattern that fires 3× may not be flagged even though
it is a genuine N+1 — while a pattern that fires 4× is. **Always inspect `:query-count`
and the raw `:queries` list directly**, not just `:suspicious-queries`, especially when
working with small synthetic datasets.

```clojure
;; ✅ check both — suspicious may be empty even when count > 1
(let [res (trace/trace-sql (my-service-call))]
  {:total    (:count res)
   :queries  (frequencies (map :sql (:queries res)))   ; spot repeats manually
   :flagged  (:suspicious-queries (trace/detect-n+1 res))})
```

---

## `FetchType.LAZY` on a non-PK `@ManyToOne` is silently ignored by Hibernate

If a `@ManyToOne` uses `referencedColumnName` pointing to a **non-primary-key** column,
Hibernate cannot create a lazy proxy (it needs the PK to do so). The association is
effectively loaded eagerly regardless of the `LAZY` declaration, firing one SELECT per
parent row — a hidden N+1 that is invisible from reading the code alone.

```kotlin
// ⚠️ looks lazy, but Hibernate fires a SELECT per row because isbn is not the @Id
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "book_isbn", referencedColumnName = "isbn")   // ← non-PK join
open var bookIsbn: Book? = null
```

**Fix:** add an explicit `left join fetch` in the JPQL for the owning query so Hibernate
loads the full entity (including its PK) in the main query instead of per-row selects.

---

## Quick-test a service fix by re-implementing the core flow in Clojure

When a fix involves service-layer logic (not just a JPQL change), you can prototype it
directly in the REPL without restarting. Write a temporary Clojure expression that
reimplements the **core flow** of the service method with your candidate fix, wrap it in
`trace/trace-sql`, and measure raw query count against real data.

```clojure
;; Example: service currently does bookRepo.findAll() then lazy-loads everything
;; (481 queries for 200 books). Candidate fix: two eager queries + manual grouping.

(defn get-all-books-fixed []
  (let [book-repo   (lw/bean "bookRepository")
        review-repo (lw/bean "reviewRepository")
        books       (.findAllWithAuthorAndGenres book-repo)
        reviews-by-book-id
          (->> (.findAllWithMember review-repo)
               (group-by #(.getId (.getBook %))))]
    (mapv (fn [b] {:id (.getId b) :title (.getTitle b)}) books)))

;; Measure — zero restarts needed
(let [res (trace/trace-sql (lw/in-readonly-tx (count (get-all-books-fixed))))]
  (select-keys res [:count :duration-ms]))
;; => {:count 2, :duration-ms 43}   ← was 481 queries, now 2
```

---

## Use hot-swap only as a final end-to-end check — never for hypothesis testing

**Phase 1 — prove the JPQL fix (no side effects):**
Run the candidate query directly via `jpa/jpa-query` wrapped in `trace/trace-sql` or
`lw-trace-nplus1`. This mutates nothing and needs no cleanup. Only move to phase 2 once
the fix is validated here.

**Phase 2 — end-to-end confirmation (optional, mutating):**
Once the JPQL is proven, hot-swap it into the real repository method to exercise the full
Spring Data stack (caching, pagination, projections). Swap back and verify the N+1 returns.
Always call `(hq/reset-all!)` when done.

> ⚠️ **Never reach for hot-swap first.** It persists across the session and affects all
> callers until reset. Using it to explore or test a hypothesis is the wrong tool — use
> `jpa/jpa-query` + `trace/trace-sql` for that.

```bash
# 1. swap in the fix
clj-nrepl-eval -p 7888 '(hq/hot-swap-query! "myRepo" "myMethod" "select ... join fetch ...")'

# 2. confirm N+1 is gone
lw-trace-nplus1 '(lw/run-as "admin" (.myMethod (lw/bean "myRepo")))'

# 3. swap back to broken — confirm N+1 returns
clj-nrepl-eval -p 7888 '(hq/hot-swap-query! "myRepo" "myMethod" "select ... -- original")'
lw-trace-nplus1 '(lw/run-as "admin" (.myMethod (lw/bean "myRepo")))'

# 4. restore and write the fix to source
clj-nrepl-eval -p 7888 '(hq/reset-query! "myRepo" "myMethod")'
```
