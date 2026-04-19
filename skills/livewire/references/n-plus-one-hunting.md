# N+1 Hunting — Tips & Gotchas

Read this file when investigating N+1 query problems, measuring query counts, or validating
that a JPQL / `JOIN FETCH` fix actually eliminates excess queries.

---

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

## Use hot-swap to confirm a JPQL fix before touching source code

Rather than edit → restart → retest, hot-swap the candidate fix, then use `lw-trace-nplus1`
to verify the N+1 is gone. Swap back to the original to confirm the N+1 returns. Only then
write the fix to source. This round-trip gives high confidence with zero restarts.

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
