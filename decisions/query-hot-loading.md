# Decision: How `hot-swap-query!` Patches Spring Data JPA + Hibernate

## Context

Spring Data JPA compiles `@Query` annotations into query objects at application startup and
caches them for the lifetime of the application. Changing a query normally requires a full
restart. `hot-swap-query!` eliminates this by mutating the live objects in place.

This document captures the implementation approach, the internals of the chain we patch, and
the lessons learned when the first version silently failed on Spring Data JPA 4.x / Hibernate 7.

---

## The Object Chain

When Spring Data JPA processes a `@Query`-annotated repository method, it builds the following
chain of objects and stores the root in a private map inside `QueryExecutorMethodInterceptor`:

```
QueryExecutorMethodInterceptor
  └─ Map<Method, RepositoryQuery> queries          ← keyed by java.lang.reflect.Method

       └─ SimpleJpaQuery  (implements RepositoryQuery)
            └─ [field] query: DefaultEntityQuery
                 ├─ [field] queryString: Lazy<String>   ← derived/cached query string
                 └─ [field] query: PreprocessedQuery
                      └─ [field] source: DeclaredQueries$JpqlQuery
                           └─ [field] jpql: String      ← THE string Hibernate actually reads
```

The `QueryExecutorMethodInterceptor` sits on the AOP proxy chain of every Spring Data
repository bean, at position 5 (0-indexed) among the `DefaultPointcutAdvisor` advisors.

### How to reach `QueryExecutorMethodInterceptor`

```clojure
(let [advised     (cast org.springframework.aop.framework.Advised repo)
      interceptor (->> (.getAdvisors advised)
                       (keep #(when (instance? org.springframework.aop.support.DefaultPointcutAdvisor %)
                                (.getAdvice ^org.springframework.aop.support.DefaultPointcutAdvisor %)))
                       (filter #(-> % class .getName (.contains "QueryExecutorMethodInterceptor")))
                       first)
      queries-map (.get (doto (.getDeclaredField (class interceptor) "queries")
                          (.setAccessible true))
                        interceptor)]
  queries-map) ; Map<Method, RepositoryQuery>
```

---

## What Needs to Be Patched (Three Layers)

A working hot-swap requires changes at three distinct layers. Missing any one of them causes
the old query to keep executing silently.

### Layer 1 — `DeclaredQueries$JpqlQuery.jpql` (the runtime string)

This is the `String` field that Hibernate actually reads when building a query. It lives inside
a package-private inner class of `DeclaredQueries`. Patching only the layers above this one
(as the first implementation did) has no effect — Hibernate bypasses them entirely.

```clojure
;; Navigate to DeclaredQueries$JpqlQuery
(let [entity-q     (.get (doto (.getDeclaredField AbstractStringBasedJpaQuery "query")
                            (.setAccessible true)) rq)       ; DefaultEntityQuery
      preprocessed (.get (doto (.getDeclaredField (class entity-q) "query")
                            (.setAccessible true)) entity-q) ; PreprocessedQuery
      jpql-source  (.get (doto (.getDeclaredField (class preprocessed) "source")
                            (.setAccessible true)) preprocessed) ; DeclaredQueries$JpqlQuery
      jpql-field   (doto (.getDeclaredField (class jpql-source) "jpql")
                     (.setAccessible true))]
  (.set jpql-field jpql-source new-jpql))
```

### Layer 2 — `DefaultEntityQuery.queryString` (the Lazy cache)

`DefaultEntityQuery` wraps the derived query string in a `Lazy<String>` for lazy evaluation
and caching. Even after patching `jpql`, this `Lazy` may return the old value if it has already
been resolved. We replace it with an atom-backed `Lazy` so that subsequent re-swaps only need
to `reset!` the atom — no further structural reflection needed.

```clojure
(let [qs-field  (doto (.getDeclaredField (class entity-q) "queryString")
                  (.setAccessible true))
      jpql-atom (atom new-jpql)
      live-lazy (org.springframework.data.util.Lazy/of
                  (reify java.util.function.Supplier (get [_] @jpql-atom)))]
  (.set qs-field entity-q live-lazy))
```

On subsequent swaps for the same method, only `(reset! jpql-atom new-jpql)` is needed
alongside re-setting `DeclaredQueries$JpqlQuery.jpql` — no re-traversal of the chain.

### Layer 3 — Hibernate interpretation caches

Even with both String fields updated, Hibernate's query engine keeps a parsed representation
of the old JPQL in two in-memory caches on `QueryInterpretationCacheStandardImpl`:

| Field | Contents |
|---|---|
| `hqlInterpretationCache` | Parsed HQL/JPQL interpretation keyed by query string |
| `queryPlanCache` | Full query execution plan (SQL, parameter bindings, result mapping) |

Both must be cleared after every swap — including re-swaps — so Hibernate re-parses the
new JPQL on the next execution.

**How to reach the caches:**

```
EntityManagerFactory
  → .unwrap(SessionFactoryImplementor)
  → .getQueryEngine()                     → QueryEngineImpl
  → .getInterpretationCache()             → QueryInterpretationCacheStandardImpl
  → getDeclaredField("hqlInterpretationCache").clear()
  → getDeclaredField("queryPlanCache").clear()
```

> **Hibernate 7 note:** `getQueryPlanCache()` no longer exists on `QueryEngineImpl`.
> The replacement is `getInterpretationCache()`, which returns a
> `QueryInterpretationCacheStandardImpl` that holds both caches.

```clojure
(defn- clear-hibernate-caches! []
  (let [emf  (core/bean "entityManagerFactory")
        sfi  (.unwrap emf org.hibernate.engine.spi.SessionFactoryImplementor)
        ic   (.getInterpretationCache (.getQueryEngine sfi))
        cls  (class ic)]
    (-> (.getDeclaredField cls "hqlInterpretationCache") (doto (.setAccessible true)) (.get ic) .clear)
    (-> (.getDeclaredField cls "queryPlanCache")         (doto (.setAccessible true)) (.get ic) .clear)))
```

---

## Complete Swap Sequence

```
hot-swap-query!(repo, method, newJpql)
  │
  ├─ [first call only]
  │   ├─ walk AOP proxy → find QueryExecutorMethodInterceptor
  │   ├─ reflect into queries map → find Method key + SimpleJpaQuery
  │   ├─ navigate chain → DefaultEntityQuery → PreprocessedQuery → DeclaredQueries$JpqlQuery
  │   ├─ save originals (Lazy + jpql String) for rollback
  │   └─ wire atom-backed Lazy onto DefaultEntityQuery.queryString
  │
  ├─ [every call]
  │   ├─ set DeclaredQueries$JpqlQuery.jpql = newJpql
  │   ├─ reset! jpql-atom → newJpql  (Lazy reads this)
  │   └─ clear hqlInterpretationCache + queryPlanCache
  │
  └─ return {:swapped [bean method] :query newJpql}

reset-query!(repo, method)
  ├─ set DeclaredQueries$JpqlQuery.jpql = originalJpql
  ├─ restore DefaultEntityQuery.queryString = originalLazy
  ├─ clear hqlInterpretationCache + queryPlanCache
  └─ deregister from global registry
```

---

## Why the First Version Silently Failed

The original implementation only replaced `DefaultEntityQuery.queryString` with an atom-backed
`Lazy<String>`. This worked as a proof of concept against earlier Spring Data JPA versions
(where `getQueryString()` was the path used at execution time), but failed silently in
Spring Data JPA 4.x because:

1. `DeclaredQueries$JpqlQuery.jpql` is what Hibernate actually reads — patching the `Lazy`
   cache above it has no effect once Hibernate has the original string in its own cache.
2. Hibernate's `hqlInterpretationCache` and `queryPlanCache` retain the parsed form of the
   original JPQL indefinitely. Even if the Spring Data layer returned the new string,
   Hibernate would find a cache hit for the old string key and execute the old plan.

The symptom was: `list-swapped` showed the new JPQL correctly, but the database received
the original SQL unchanged — exactly what was reported.

---

## Registry

The global registry atom tracks all active swaps:

```clojure
{ ["repoBean" "methodName"]
  {:atom          <jpql-atom>        ; current live JPQL string
   :original-lazy <Lazy>             ; original DefaultEntityQuery.queryString
   :original-jpql <String>           ; original DeclaredQueries$JpqlQuery.jpql
   :qs-field      <Field>            ; DefaultEntityQuery.queryString field (cached)
   :jpql-field    <Field>            ; DeclaredQueries$JpqlQuery.jpql field (cached)
   :entity-q      <DefaultEntityQuery>
   :jpql-source   <DeclaredQueries$JpqlQuery>} }
```

Caching the `Field` objects avoids repeated `getDeclaredField` calls on subsequent swaps.

---

## Tested Against

| Component | Version |
|---|---|
| Spring Boot | 4.0.2 |
| Spring Data JPA | 4.x |
| Hibernate | 7 |
| App | bloated-shelf |
| Method used for verification | `bookRepository#findByIdWithDetails` |
