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

## ⚠️ The Cartesian product trap — never JOIN FETCH two collections on the same parent

A `JOIN FETCH` on two collections in one query produces a **Cartesian product**: every row
of collection A × every row of collection B. This is not an N+1 (it's one query) but it
duplicates data identically to what N+1 would do — bloated responses, inflated collections,
and 500 KB where 10 KB would do.

```
Book has: 3 genres, 9 reviews
JOIN FETCH genres + JOIN FETCH reviews → 3 × 9 = 27 rows for ONE book
Result: reviews appear duplicated 3×, genres appear duplicated 9×
```

**⚠️ Silent inflation — no warning, no exception, always wrong.**
Hibernate deduplicates entity instances in the persistence context, but the **nested
collections inside each entity are silently multiplied**. The DTO will contain repeated
items with the same `id`. This happens **even for a single result row**:

```
1 book with 2 genres × 2 reviews → 4 rows returned by SQL
Hibernate deduplicates to 1 entity
→ genres list: [genre1, genre1, genre2, genre2]   ← duplicated!
→ reviews list: [review1, review1, review2, review2]  ← duplicated!
Response is 4× bloated for one book. No warning. No exception.
```

This is **always wrong**, even for tiny result sets. A single row with 3 genres × 3 reviews
gives 9 duplicated genres and 9 duplicated reviews. There is no scenario where a Cartesian
product `JOIN FETCH` produces a correct DTO. **Always warn explicitly when suggesting
`JOIN FETCH` and never suggest `JOIN FETCH` on two collections on the same parent**.

**Symptoms:**
- `lw-trace-nplus1` reports `{:total-queries 1}` but the response is enormous
- The SQL contains multiple `left join fetch` on collection associations
- Collection fields in the response contain repeated entries with the same `id`
- Total rows returned by the query vastly exceeds the parent entity count
- Even a single result row shows duplicated items in nested collections

**Diagnosis — check the row multiplication factor:**

```clojure
;; Run the query and check how many rows Hibernate got vs how many unique parents
(def rows (.findAllWithAuthorGenresReviewsAndMember (lw/bean "bookRepository")))
(count rows)                    ; ← e.g. 200 (Hibernate deduplicates)
(reduce + (map #(count (.getReviews %)) rows))  ; ← inflated review count, e.g. 1776
;; If review count / unique reviews >> 1, you have Cartesian product
```

**Fix — two queries instead of one JOIN FETCH chain:**

```sql
-- Query 1: parent + first collection (no join fetch on second)
select distinct b from Book b left join fetch b.author left join fetch b.genres

-- Query 2: load the second collection separately (Hibernate batches this with IN)
select distinct r from Review r left join fetch r.member where r.book.id in (:ids)
```

Or use `@BatchSize(size = 50)` on the collection association and skip `JOIN FETCH` entirely —
Hibernate loads collections in batches with `IN` clauses.

**Never use `hq/hot-swap-query!` to fix a Cartesian product** — swap a single JPQL string
cannot remove a `JOIN FETCH` on the second collection while keeping the first. This requires
rewriting the query strategy, which is a source-level change, not a hot-swap.

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
(not `lw-trace-nplus1`, which is a shell script and doesn't compose in a `mapv`).
**Write to a temp file** — the expression uses `fn`, `let`, `mapv`, and nested parens:

```bash
cat > /tmp/lw-multi-id.clj << 'EOF'
(mapv (fn [id]
        (let [res (trace/trace-sql
                    (lw/run-as "admin"
                      (.myEndpoint (lw/bean "myController") id)))]
          {:id id :total-queries (:count res) :suspicious (count (:suspicious-queries (trace/detect-n+1 res)))}))
      [1 2 3 4 5])
EOF
lw-eval --file /tmp/lw-multi-id.clj
rm /tmp/lw-multi-id.clj
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

## ⚠️ `jpa/jpa-query` applies ID-first pagination — query count is inflated

`jpa/jpa-query` (the `lw-jpa-query` wrapper) uses an **ID-first pagination strategy**:
it runs the JOIN FETCH query just to collect IDs, then issues a second `SELECT ... WHERE
id IN (...)` to reload each entity with the hydrated state. When you wrap `jpa/jpa-query`
in `trace/trace-sql` to prototype a JPQL fix, you will see the count roughly **doubled**.

This is by design — it allows efficient paged queries on JOIN FETCH results without
returning millions of rows. It is not a bug. **But it means the absolute query count
from a `jpa/jpa-query` prototype is always wrong.**

```clojure
;; Prototype: JOIN FETCH fix via jpa/jpa-query
(jpa/jpa-query jpql-variant :page 0 :page-size 100)
;; → 36 queries (20 entities × 2 reloads + 1 JOIN FETCH + 1 count query)
;; → the variant fires the JOIN FETCH ONCE but then reloads each entity

;; But the real service method fires:
;; → 2 queries (1 JOIN FETCH, no reload)
```

**How to compare correctly:** the ID-first wrapper adds the same overhead to **both**
baseline and variant (it runs the same ID-first logic for any JPQL). So the **relative
difference is still valid** — if baseline is 98 queries and variant is 36, the fix is
real. But never report the variant's raw count as "2 queries" — that only holds
for the actual service method, not for `jpa/jpa-query` in the REPL.

**To get accurate counts in the REPL without the wrapper:**

```clojure
;; Use EntityManager directly — no ID-first pagination, no COUNT query
(let [em (lw/bean jakarta.persistence.EntityManager)]
  (trace/trace-sql
    (doto (clojure.lang.Reflector/invokeInstanceMethod
            em "createQuery" [jpql] [jakarta.persistence.Query])
      (.getResultList))))

;; Or call the real service method — trace/trace-sql captures it cleanly
(trace/trace-sql
  (.getBooksByGenreId (lw/bean "bookService") 1))
;; → 98 queries (baseline)
```

**Rule of thumb:** use `jpa/jpa-query` to **validate JPQL syntax** and to see that the
result shape is correct (no duplicates, right associations). Use `trace/trace-sql` on
the real service method or `EntityManager` directly for **accurate query count**.

---

## When offering fix — always present multiple known variants

> ⚠️ **Diagnostic phase is complete when `lw-trace-nplus1` returns.** The N+1 detection
> gives you: total query count, suspicious queries with frequencies, and the SQL patterns
> firing per-entity. That is a **complete diagnosis** — the user knows what's wrong,
> which associations are lazy, and how many queries it costs.
>
> **Do not present fix variants until the user explicitly asks for one.** The skill should
> not assume the user wants solutions — it's an interactive assistant. If the user says
> "fix it" or asks about variants, then present options. Otherwise, stop at the diagnosis.
> Presenting variants unasked wastes effort the user may never read and signals that the
> agent doesn't understand the difference between "what's wrong" and "how to fix it."

Never propose a single `JOIN FETCH` query as THE solution. Always evaluate the N+1
associations and present 2–4 viable variants with pros/cons. The common choices are:

### Variant A — Full `JOIN FETCH` (single query)
`JOIN FETCH` on every association. One query, no N+1. But **always risks Cartesian product**
when fetching two or more collections on the same parent — this is **never correct** for DTOs
because Hibernate deduplicates entities but silently duplicates nested collection items with
zero warning. **Only acceptable when exactly one collection association is fetched**.

```sql
-- One query, but JOIN FETCH on books+reviews duplicates each 3× if avg 3 genres/book
SELECT DISTINCT b FROM Book b
LEFT JOIN FETCH b.author
LEFT JOIN FETCH b.reviews r
LEFT JOIN FETCH r.member
LEFT JOIN FETCH b.genres
WHERE b.id IN (SELECT DISTINCT b2.id FROM Book b2 JOIN b2.genres g WHERE g.id = :genreId)
```

### Variant B — Partial `JOIN FETCH` (scalar + first collection only)
Fetch the `@ManyToOne` associations and maybe one collection with `JOIN FETCH`, then
let Hibernate batch-load the rest via `@BatchSize` (or fire per-row lazy loads if no
`@BatchSize` is configured). Trade: more queries (e.g. 3–5) but no Cartesian product.

```sql
-- Fetch author+genres; reviews+member load via subsequent IN or per-row selects
SELECT DISTINCT b FROM Book b
LEFT JOIN FETCH b.author
LEFT JOIN FETCH b.genres
WHERE b.id IN (SELECT DISTINCT b2.id FROM Book b2 JOIN b2.genres g WHERE g.id = :genreId)
```

### Variant C — Multiple fetches, merge in code
Two or more repository queries, one per parent/collection pair, then join in Clojure
with `group-by`. Gives full control over what gets batched but requires code changes.

```clojure
;; Query 1: books + author + genres
(def books (-> (.findByGenreId bookRepo genreId)
               (mapv #(bean->map %))))

;; Query 2: reviews for all fetched books (batched IN)
(def book-ids (mapv :id books))
(def reviews (-> (jp/jpa-query (str "SELECT DISTINCT r FROM Review r LEFT JOIN FETCH r.member WHERE r.book.id IN (:ids)"
                                    :ids book-ids))
                              ))
(def reviews-by-book (group-by #(.getId (.getBook %)) reviews))
```

⚠️ **SQL `IN` clause size limit:** Many databases (PostgreSQL, MySQL) reject `IN (:ids)`
when the placeholder count exceeds ~1000–2000. For large sets, **split into batches**:

```clojure
;; Batch-split IN clause — never pass 2000+ IDs in one query
(defn in-batches [items batch-size]
  (mapv #(vec %) (partition-all batch-size items)))

(into []
  (mapcat (fn [batch]
            (-> (jp/jpa-query (str "SELECT r FROM Review r WHERE r.book.id IN (:ids)"
                                   :ids batch)))
  (in-batches book-ids 500)))
```

### Variant D — `@BatchSize` annotation (source-level, zero query changes)
Add `@BatchSize(size = 50)` on every `@OneToMany` / `@ManyToOne` association in the
dependency graph. Hibernate then batches all lazy loads via `IN` clauses with configurable
batch size. Trade: easy to add. **Downside:** `@BatchSize` is **global** on the entity —
any code path that lazy-loads that collection benefits (or pays) for the batch size you
pick. If your entity has a large collection that is rarely loaded, `@BatchSize(50)` may
cause heavy IN clauses or unnecessary fetches on that code path. **But this can also be
a good thing:** if multiple endpoints access the same association, one annotation fixes
all of them — no scattered JPQL changes needed.

```java
@BatchSize(size = 50)
private List<Review> reviews;

@BatchSize(size = 50)
private LibraryMember member;
```

```java
@BatchSize(size = 50)
private List<Review> reviews;

@BatchSize(size = 50)
private LibraryMember member;
```

### When to choose which variant
- **One collection only:** Variant A (full JOIN FETCH) — safe, no Cartesian product risk
- **Multiple collections:** Variant B (partial FETCH + BatchSize) — always prefer this
- **Large result set (100+):** Variant C (multiple queries, split IN batches) or Variant D (BatchSize)
- **Can't modify source:** Variant B (partial JPQL swap) — never use full JOIN FETCH if >1 collection
- **Can modify source + need best performance:** Variant D + selective Variant B

**Never use Variant A with two or more collection associations** — there is no scenario
where it produces a correct response. The multiplication is silent, the inflated DTO looks
"mostly right" (entities are there, data is correct), and it only shows up in memory/CPU
or response-size budgets. That makes it one of the hardest bugs to catch in testing.

---

## Use hot-swap only as a final end-to-end check — never for hypothesis testing

**Phase 0 — establish the baseline (required):**
Before testing any fix, record the current query count of the **exact same service
method call**. This baseline is your control: you compare every variant against it.

```clojure
;; Record baseline — this is the broken version, no hot-swap needed
(def baseline (trace/trace-sql (lw/in-readonly-tx (.getBooksByGenreId (lw/bean "bookService") 1))))
;; => {:count 98, :duration-ms 18, ...}
```

**Phase 2 — prove the JPQL fix (no side effects):**
Run the candidate query directly via `jpa/jpa-query` wrapped in `trace/trace-sql` or
`lw-trace-nplus1`. This mutates nothing and needs no cleanup. Only move to phase 3 once
the fix is validated here.

**Phase 3 — end-to-end confirmation (optional, mutating):**
Once the JPQL is proven, hot-swap it into the real repository method to exercise the full
Spring Data stack (caching, pagination, projections). Swap back and verify the N+1 returns.
Always call `(hq/reset-all!)` when done.

> ⚠️ **Never reach for hot-swap first.** It persists across the session and affects all
> callers until reset. Using it to explore or test a hypothesis is the wrong tool — use
> `jpa/jpa-query` + `trace/trace-sql` for that.

```bash
# BASELINE: record the broken version query count first — use temp file for let/trace-sql
cat > /tmp/lw-baseline.clj << 'EOF'
(let [r (trace/trace-sql (.myMethod (lw/bean "myRepo")))]
  (println "baseline:" (:count r)))
EOF
lw-eval --file /tmp/lw-baseline.clj

# 2. swap in the fix — temp file for !
cat > /tmp/lw-swap.clj << 'EOF'
(hq/hot-swap-query! "myRepo" "myMethod" "select ... join fetch ...")
EOF
lw-eval --file /tmp/lw-swap.clj

# 3. confirm N+1 is gone
lw-trace-nplus1 '(lw/run-as "admin" (.myMethod (lw/bean "myRepo")))'

# 4. swap back to broken — temp file for ! and nested quotes
cat > /tmp/lw-swap-back.clj << 'EOF'
(hq/hot-swap-query! "myRepo" "myMethod" "select ... -- original")
EOF
lw-eval --file /tmp/lw-swap-back.clj
lw-trace-nplus1 '(lw/run-as "admin" (.myMethod (lw/bean "myRepo")))'

# 5. restore and write the fix to source — temp file for reset-all!
cat > /tmp/lw-reset.clj << 'EOF'
(hq/reset-all!)
EOF
lw-eval --file /tmp/lw-reset.clj
```

**Hot-swap expressions with `!` or nested quotes must use a temp file.**
See `$SKILL_DIR/references/clj-nrepl-eval-temp-files.md` for the complete rule.
