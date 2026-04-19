# N+1 Hunting — Tips & Gotchas

Read this file when investigating N+1 query problems, measuring query counts, or validating
that a JPQL / `JOIN FETCH` fix actually eliminates excess queries.

---

## N+1 presence is data-dependent — always test multiple IDs

An N+1 only fires when the problematic association actually has rows. A query that looks
fine on one record may blow up on another. **Always test several representative IDs**
before concluding there is no N+1.

```clojure
;; Test multiple IDs in one shot and compare query counts
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
(let [res (trace/trace-sql (my-service-call))]
  ;; ✅ check both — suspicious may be empty even when count > 1
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
directly in the REPL without restarting. The nREPL runs inside the same JVM, so it can
call any Spring bean — repositories, services, anything. Write a temporary Clojure
expression that reimplements the **core flow** of the service method with your candidate
fix, wrap it in `trace/trace-sql`, and measure query count live against real data.

```clojure
;; Example: service currently does bookRepo.findAll() then lazy-loads genres/reviews/members
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

Rather than edit → restart → retest, hot-swap the candidate fix, verify with `trace-sql`,
then swap back to the original to confirm the N+1 returns. Only then write the fix to
source. This round-trip gives high confidence with zero restarts.

```clojure
;; 1. swap in the fix
(hq/hot-swap-query! "myRepo" "myMethod" "select ... join fetch ...")
;; 2. confirm N+1 is gone
(trace/detect-n+1 (trace/trace-sql (lw/run-as "admin" (.myMethod ...))))
;; 3. swap back to broken — confirm N+1 returns
(hq/hot-swap-query! "myRepo" "myMethod" "select ... -- original without fetch")
(trace/detect-n+1 (trace/trace-sql (lw/run-as "admin" (.myMethod ...))))
;; 4. restore and write the fix to source
(hq/reset-query! "myRepo" "myMethod")
```
