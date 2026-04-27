# Livewire Tool-Call Post-Mortem — What Went Wrong & How to Fix It

A detailed summary of every friction point, mistake, and suggested improvement from a real N+1 hunting session using the Livewire skill.

---

## 1. Wrong Livewire bin path

**What went wrong:** Called `~/.claude/skills/livewire/bin/lw-start` which doesn't exist. The actual skill is at `/home/brdloush/projects/livewire/skills/livewire/bin/`.

**Why at that point:** I relied on the wrapper script section in the skill which says `~/.claude/skills/livewire/bin/<script-name>`, but in this environment the skill lives elsewhere. The skill also says "Find this SKILL.md first" — I skipped that step.

**Suggestion:** The skill reference should emphasize *finding the SKILL.md first as the canonical path source*, not hardcoding `~/.claude/skills/livewire/bin/`. Better yet, wrap it so the skill's own bin/ directory is always injected into PATH, or have the skill emit the correct path on first load.

---

## 2. Syntax error: nested `#()` anonymous functions

**What went wrong:** First REPL attempt produced `Syntax error reading source at (REPL:1:250). Nested #()s are not allowed`.

**Why at that point:** I wrote a `mapv` with a `#()` inside a `#()`, trying to be concise. The pitfall section does say "Nested `#()` anonymous functions — not allowed in Clojure; use `(fn [] ...)` instead" but I didn't read it because my mental model was "the script already handled namespace requiring, so the rest should be straightforward." I assumed I knew Clojure well enough to skip the reference.

**Suggestion:** The pitfall reference should be more prominently surfaced for REPL calls — e.g. in the workflow section or as an inline note when the first tool call involves raw `clj-nrepl-eval`. Even better: the skill's prompt could include a one-liner reminder: "When writing raw Clojure for clj-nrepl-eval, always use `fn` not nested `#()`, always use `doall`/`vec`/`mapv` not `map`."

---

## 3. ClassCastException: PersistentVector cast to Number (repeated 3x)

**What went wrong:** This error occurred three separate times:

```
Execution error (ClassCastException) at user/eval4338 (REPL:1).
class clojure.lang.PersistentVector cannot be cast to class java.lang.Number
```

**Why at that point:** The REPL evaluates the expression and returns the last form. I kept putting `count books` or similar arithmetic as the last form in a `let` block that also contained `prn` side-effects, and the return type was a Java ArrayList that Clojure tried to coerce into a number during evaluation. The actual bug was that `doall` on a `vec` of Java objects returns the vec, but the REPL's type inference got confused by the mixed Java/Clojure boundary.

I resolved it by restructuring the code to avoid arithmetic on mixed types, but the root cause was that I didn't isolate test expressions cleanly.

**Suggestion:** The pitfall reference should include a canonical REPL pattern:
```
Never put arithmetic or `count` directly on Java-coll-returning expressions — always coerce first with `vec` or `doall`, and always put side effects in `doseq` before the return value.
```
Better yet: the skill could note that when the REPL returns unexpected types, it's usually a Java↔Clojure boundary issue and suggest `(into [] ...)` or explicit `vec` coercion as the go-to fix.

---

## 4. ClassCastException: ArrayList cast to Number (the subvec attempt)

**What went wrong:** Tried `(subvec books 0 5)` where `books` was a Java ArrayList, getting `Malformed member expression, expecting (.member target ...)`.

**Why at that point:** I misremembered — `subvec` works on Clojure vectors, not Java ArrayLists. I should have done `(vec .getResultList q)` first to convert to a Clojure persistent vector.

**Suggestion:** Include a "Java return values to Clojure" coercion table in the pitfall reference. Something like:
> Java `List`/`ArrayList` -> wrap in `(vec ...)` before Clojure ops like `subvec`, `nth`, or `take`. Java `Set` -> `(into #{} ...)` if you need a Clojure set.

---

## 5. `createNativeQuery` vs `createQuery` -- JPQL on PostgreSQL

**What went wrong:** Called `.createNativeQuery` with a JPQL string (`select distinct b from Book b left join fetch ...`), which PostgreSQL rejected with `ERROR: syntax error at or near "fetch"`.

**Why at that point:** I conflated "native query" with "any query against the EntityManager." `createNativeQuery` passes the string directly to the database dialect, so JPQL syntax fails. I should have used `.createQuery` which tells Hibernate to parse it as JPQL.

**Suggestion:** The pitfall reference should call this out explicitly: `.createQuery` = JPQL (Hibernate parses it), `.createNativeQuery` = raw SQL (sent directly to DB). Mixing them up wastes 2+ failed attempts. A one-line callout in the pitfall reference for "EntityManagers in the REPL" would prevent this.

---

## 6. Assuming JVM picked up recompile + restart

**What went wrong:** I recompiled and asked for a restart, then immediately re-ran the N+1 trace. It still returned 481 queries because the running JVM hadn't actually picked up the new classes. I confirmed by checking that the service still called `findAll()` (the old method) instead of `findAllWithAuthorGenresReviewsAndMember()`.

**Why at that point:** I assumed the user had restarted after I wrote the code, but they restarted *before* I wrote the code. The timeline was: I prototyped in REPL (no code changes) -> user "restarted" -> I checked (still broken) -> then I wrote code. The user's second restart happened after the code was written, but I should have verified the running bean was using the new method.

**Suggestion:** The workflow should include a verification step: after restart, before tracing, check which method the service is actually calling. Something like:
```bash
clj-nrepl-eval -p 7888 "(require '[net.brdloush.livewire.core :as lw]) (def svc (lw/bean \"bookService\")) (class svc)"
```
Or better yet, the skill could suggest using the query-watcher status to confirm the new method is registered:
```clojure
(qw/status) ; shows all @Query methods the JVM knows about
```

---

## 7. `hq/hot-swap-query!` failed on `findAll` because it's not a `@Query` method

**What went wrong:** Called `(hq/hot-swap-query! "bookRepository" "findAll" "select distinct b from Book b ...")` which threw `No @Query method 'findAll' found on bean 'bookRepository'`. I then had to hot-swap `findAllWithAuthorAndGenres` instead, which still didn't help because the service calls `findAll()` not the renamed method.

**Why at that point:** I forgot that `findAll()` is a standard Spring Data method, not a `@Query` annotation. `hq/` only swaps `@Query` methods. The pitfall reference mentions `hq/` is for `@Query` methods, but it's easy to assume "all repository methods" are fair game. I should have checked first which method the service actually calls, then verified whether that method is `@Query`-based.

**Suggestion:** The pitfall reference should explicitly list: "Standard Spring Data methods (`findAll`, `findById`, `save`) are **not** swappable with `hq/` -- only `@Query`-annotated methods are. If the broken call uses a standard method, you need a structural code change (or a repository interface change + restart)." Better yet, add a `hq/swap-all-methods` or similar that also intercepts standard methods, or document the limitation prominently.

---

## 8. Query watcher had the old `findAllWithAuthorAndGenres` JPQL, needed manual swap

**What went wrong:** The query watcher (`qw/status`) showed the old query `SELECT DISTINCT b FROM Book b JOIN FETCH b.author LEFT JOIN FETCH b.genres` for `findAllWithAuthorAndGenres`, even though the source file had the expanded version in the new method `findAllWithAuthorGenresReviewsAndMember()`. I had to manually swap `findAllWithAuthorAndGenres` to the expanded query via `hq/hot-swap-query!`.

**Why at that point:** I assumed the query watcher would auto-discover the new query, but it only watches existing `@Query` methods for file changes. The new method was compiled fresh and added to the watcher, but the hot-swap was still needed to replace the old query. I didn't read the pitfall section about the query watcher's behavior.

**Suggestion:** Document this clearly in the pitfall or reference: "The query watcher registers methods it finds on startup from the running JVM. New methods appear on next watcher scan (or after recompilation). To test a new JPQL immediately, use `hq/hot-swap-query!` manually." This saved me from wondering why the new method wasn't picked up.

---

## 9. Not tracing all service methods systematically

**What went wrong:** I only traced `getAllBooks()` for N+1. The other methods `getBooksByGenreId()` (98 queries), `getReviewsForBook()` (6 queries), and `getBookById()` were obvious candidates that went unchecked.

**Why at that point:** Time/attention drifted after the big `getAllBooks` win. I should have been more systematic and traced every service method the same way.

**Suggestion:** After fixing one N+1, always immediately trace the next service method you see with the same pattern. Don't stop at one. A "trace everything" pattern should be the default workflow.

---

## Summary: What the skill/reference should do better

1. **Path discovery:** Make the bin path derivation automatic rather than hardcoded — find SKILL.md -> read `bin/` from there.
2. **REPL patterns:** Front-load Clojure REPL gotchas (no nested `#()`, Java-to-Clojure coercion, `vec`/`doall` before arithmetic) in the main workflow section, not buried in pitfalls.
3. **`hq/` limitations:** Explicitly call out that only `@Query`-annotated methods are swappable — standard Spring Data methods require source changes + restart.
4. **Post-startup verification:** After a restart, verify the running bean's method is the new one before tracing. Don't assume.
5. **`createQuery` vs `createNativeQuery`:** Call this out explicitly in the pitfall — JPQL on PostgreSQL dies silently with a SQL syntax error.
6. **Systematic approach:** Recommend a "trace everything" pattern after a fix — the N+1 on `getAllBooks` was the biggest win, but `getBooksByGenreId` had 98 queries and `getReviewsForBook` had 6. They were obvious candidates that went unchecked.
