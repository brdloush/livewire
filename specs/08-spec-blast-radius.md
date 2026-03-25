# Spec — `lw/blast-radius`: method-level call graph and entry-point impact analysis

## What this is

A way to answer: *"If I change this method, what parts of the running application are
affected?"* — answered from the live JVM, not from static grep or IDE indexing.

Given a bean name and a method name, `blast-radius` walks the bean dependency graph
upward (from dependency toward dependents), intersects with a bytecode-level call graph
extracted at runtime, and returns the set of bean methods that transitively invoke the
target — annotated with their distance from the target and, for entry-point beans
(controllers, schedulers, event listeners), their observable surface (HTTP path, cron
expression, event type).

The primary consumer is an AI agent that is about to modify a query, a service method,
or a repository — and needs to know the full blast radius before acting, not after.

---

## Motivating examples

```clojure
;; Which HTTP endpoints call bookRepository/findAllWithAuthorAndGenres, directly or via services?
(lw/blast-radius "bookRepository" "findAllWithAuthorAndGenres")

;; What is affected if I change the signature of BookService/archiveBook?
(lw/blast-radius "bookService" "archiveBook")
```

CLI wrapper:
```bash
lw-blast-radius bookRepository findAllWithAuthorAndGenres
lw-blast-radius bookService archiveBook
```

---

## API

```clojure
(lw/blast-radius bean-name method-name)
(lw/blast-radius bean-name method-name :app-only true)   ; default
(lw/blast-radius bean-name method-name :app-only false)  ; include Spring infra beans
```

**Arguments:**
- `bean-name` — string name of a Spring bean (same as accepted by `lw/bean`)
- `method-name` — simple method name string; if the method is overloaded, all overloads are included (see Overloads below)

**Returns:**

```clojure
{:target   {:bean   "bookRepository"
             :method "findAllWithAuthorAndGenres"}

 :affected [{:bean    "bookService"
              :method  "getAllBooks"
              :depth   1
              :entry-point nil}

             {:bean    "bookController"
              :method  "getBooks"
              :depth   2
              :entry-point {:type         :http-endpoint
                            :path         "/api/books"
                            :http-method  "GET"
                            :pre-authorize "hasRole('MEMBER')"}}

             {:bean    "adminController"
              :method  "getMostBorrowed"
              :depth   2
              :entry-point {:type         :http-endpoint
                            :path         "/api/admin/stats"
                            :http-method  "GET"
                            :pre-authorize "hasRole('ADMIN')"}}

             {:bean    "bookExportScheduler"
              :method  "exportNightly"
              :depth   2
              :entry-point {:type  :scheduler
                            :cron  "0 0 2 * * *"}}]

 :warnings ["bookEventListener/onBookCreated calls bookRepository indirectly via ApplicationEventPublisher — not visible to static bytecode analysis; inspect manually"]}
```

Fields:
- `:depth` — hop count from target bean. Depth 1 = direct caller of the target bean.
- `:entry-point` — present when the node is a publicly reachable surface: HTTP endpoint, `@Scheduled` method, or `@EventListener` method. `nil` for internal service beans.
- `:warnings` — honest notes about analysis gaps (see Limitations section).

---

## Implementation approach

The feature composes three things that Livewire already has or nearly has:

1. **Bean-level reverse dependency graph** — already present in `lw/all-bean-deps`
2. **Method-level call graph** — extracted from bytecode via ASM at analysis time
3. **Entry-point annotation** — from `intro/list-endpoints` (HTTP) plus new reflection over `@Scheduled` and `@EventListener`

These are described in order below.

---

### Phase 1 — Bean-level reverse graph (cheap, already available)

`lw/all-bean-deps` returns `{:bean "bookService" :dependencies [...] :dependents [...]}`.
The `:dependents` side is the reverse edge. BFS over it gives the set of beans that
transitively depend on the target bean.

This is cheap and correct. It is the outer filter: only beans in the transitive dependent
set are candidates for the method-level analysis. This prunes the search space significantly.

```clojure
(defn- transitive-dependents [bean-name all-deps]
  (let [dep-map (into {} (map (juxt :bean :dependents) all-deps))]
    (loop [frontier #{bean-name} visited #{}]
      (if (empty? frontier)
        visited
        (let [next-layer (->> frontier
                              (mapcat #(get dep-map % []))
                              (remove visited)
                              set)]
          (recur next-layer (into visited frontier)))))))
```

---

### Phase 2 — Method-level call graph via ASM bytecode walking

This is the non-trivial part and the one with the most uncertainty. The approach is:
for each bean class in the transitive dependent set, load its bytecode and extract all
method-call instructions that target the target bean's class (or its interface).

**Why ASM is the right tool:**
ASM is almost certainly already on the classpath — Spring uses it for `@Configuration`
CGLIB subclassing and component scanning. No new dependency needed. It can parse class
bytecode without executing it.

**Bytecode access:**
Each loaded class's bytecode is accessible via its classloader:
```clojure
(let [resource-path (str (.replace (.getName cls) "." "/") ".class")
      stream        (.getResourceAsStream (.getClassLoader cls) resource-path)]
  (ClassReader. stream))
```

This works for app classes. It may not work for classes loaded by non-standard
classloaders (OSGi, custom isolation layers) — not a concern for typical Spring Boot apps.

**ASM visitor pattern:**
Walk each method in each candidate class. For each `INVOKEVIRTUAL`, `INVOKEINTERFACE`,
`INVOKESPECIAL`, `INVOKESTATIC` instruction: record `(caller-class, caller-method-name) →
(callee-owner-class, callee-method-name)`.

```clojure
;; Pseudocode — real implementation will use gen-class or proxy over MethodVisitor
(defn extract-call-sites [^Class cls]
  (let [reader (ClassReader. (class->bytecode-stream cls))
        calls  (atom [])]
    (.accept reader
      (proxy [ClassVisitor] [Opcodes/ASM9]
        (visitMethod [access name descriptor signature exceptions]
          (let [caller-method name]
            (proxy [MethodVisitor] [Opcodes/ASM9]
              (visitMethodInsn [opcode owner method-name desc itf?]
                (swap! calls conj {:caller-method caller-method
                                   :callee-owner  owner       ; JVM internal name, e.g. "com/example/BookRepository"
                                   :callee-method method-name}))))))
      0)
    @calls))
```

**Matching callee to a Spring bean:**
The ASM `owner` field is a JVM internal class name (slash-separated). The target bean's
real class is obtained via `AopUtils/getTargetClass`. For interface-typed beans (JPA
repositories are the common case), match against the bean's interfaces too:
```clojure
(defn- target-class-names [bean-obj]
  (let [real-cls (AopUtils/getTargetClass bean-obj)]
    (conj (set (map #(.getName %) (.getInterfaces real-cls)))
          (.getName real-cls))))
```

Convert JVM internal names to dotted names for comparison.

**Uncertainty note:**
The call graph is necessarily conservative in what it finds and incomplete in what it
misses. It finds static dispatch — the method call instructions baked into bytecode.
It will miss:
- Calls made through `ApplicationEventPublisher` (fire-and-forget events)
- Calls made through `@Async` wrappers (the proxy redirects through a thread pool)
- Reflection-based calls (`Method.invoke(...)`)
- Calls injected through functional interfaces or lambdas in unusual patterns

These are not common patterns for repository or service calls in standard Spring apps,
so the analysis will be practically correct in most cases — but not theoretically
complete. The `:warnings` field in the output is where the implementation should surface
any known gaps it can detect (e.g. if the target bean is also an event listener target,
warn that event-driven callers may be missing).

---

### Phase 3 — Resolving AOP proxies

Spring wraps beans in CGLIB subclass proxies (for `@Transactional`, `@Cacheable`, etc.)
or JDK dynamic proxies (for interface-typed beans). The proxy class bytecode is
generated at runtime and is not interesting to walk — we want the real implementation.

```clojure
(org.springframework.aop.support.AopUtils/getTargetClass (lw/bean "bookService"))
;; => class com.example.BookService  (not $$EnhancerByCGLIB$$...)
```

`AopUtils/getTargetClass` handles both CGLIB and JDK proxy cases. Use it consistently
before extracting bytecode for any bean class.

**Uncertainty note:**
There may be edge cases with nested proxies (a bean that is both `@Transactional` and
`@Cacheable` gets multiple proxy layers). `AopUtils/getTargetClass` is documented to
unwrap one level. It should be sufficient for typical Spring Boot setups, but this should
be tested and confirmed during implementation.

---

### Phase 4 — Entry-point annotation

Once the affected `(bean, method)` pairs are known, classify each affected bean as an
entry point or an internal component.

**HTTP endpoints — already available:**
`intro/list-endpoints` returns all `RequestMappingInfo`-registered handlers. Cross-reference
the affected `(bean, method)` pairs against this list by matching `:handler-method` and
bean name.

**`@Scheduled` methods — reflection:**
```clojure
;; ScheduledAnnotationBeanPostProcessor keeps a registry of scheduled tasks
;; It is registered as a bean — accessible via lw/bean
(let [processor (lw/bean org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor)]
  (.getScheduledTasks processor))
;; Returns Set<ScheduledTask> — each has .getTask with the Runnable and metadata
```

**Uncertainty note:**
The exact API of `ScheduledAnnotationBeanPostProcessor.getScheduledTasks()` and the shape
of `ScheduledTask` objects should be verified at implementation time against the Spring
version in use. This is internal Spring API that has been stable but is not guaranteed.
The method `getScheduledTasks()` was public as of Spring 5 — verify it remains so in
Spring Boot 3.x / 4.x.

The Runnable inside a `ScheduledTask` is typically a `ScheduledMethodRunnable` which
carries a reference to the target bean and method. This should allow matching back to
`(bean, method)`. Verify the exact field/method names on `ScheduledMethodRunnable` during
implementation.

**`@EventListener` methods — reflection:**
```clojure
;; ApplicationContext keeps an ApplicationEventMulticaster
;; Spring's SimpleApplicationEventMulticaster holds a set of ApplicationListener instances
;; @EventListener-annotated methods are wrapped in ApplicationListenerMethodAdapter
(let [multicaster (lw/bean "applicationEventMulticaster")]
  ;; retrieve listeners — exact API depends on Spring version
  ...)
```

**Uncertainty note:**
This is the murkiest part. `SimpleApplicationEventMulticaster` has a `getApplicationListeners()`
method, but it may not be public in all Spring versions. `ApplicationListenerMethodAdapter`
does carry the target method and bean as fields (needed for the `@EventListener` invocation
mechanism) but these are private. Plan B: scan all beans for `@EventListener`-annotated
methods using reflection directly, without going through the multicaster. This is simpler
and more reliable:

```clojure
(defn find-event-listener-methods [bean-name bean-obj]
  (let [cls (AopUtils/getTargetClass bean-obj)]
    (->> (seq (.getMethods cls))
         (filter #(.isAnnotationPresent % org.springframework.context.event.EventListener))
         (map (fn [m] {:method (.getName m)
                       :event-types (mapv #(.getSimpleName %) (.getParameterTypes m))})))))
```

Walk all app-level beans for `@EventListener` annotations upfront and build an index.

---

### Phase 5 — Building and presenting the result

Once the affected `(bean, method)` pairs are collected with their depth and entry-point
metadata, sort by depth ascending (direct callers first), then by bean name for stability.

The `:warnings` list should be populated with:
- A note if `@EventListener`-driven callers could not be statically resolved
- A note if any candidate beans use non-standard classloaders (bytecode not accessible)
- A note if the method name matched multiple overloads (list them)

---

### Phase 6 — Caching the call graph index

Walking bytecode for all app-level bean classes on every `blast-radius` call is wasteful.
Build the full `callee → [callers]` index once (lazily on first call or eagerly at
startup) and cache it. Invalidate on demand via `(lw/reset-blast-radius-cache!)` for
cases where a class was hot-patched or recompiled during the session.

The index is just a map: `{[callee-class callee-method] → [{:bean ... :method ...}]}`.
Building it for a typical Spring Boot app (a few hundred bean classes) should take well
under a second.

---

## Limitations — be explicit in SKILL.md

The following gaps should be documented in `SKILL.md` under the `blast-radius` entry so
that an agent using the feature understands what it may be missing:

- **`ApplicationEventPublisher` calls are invisible.** If bean A calls the target method
  in response to a Spring event published by bean B, the path B → A will appear but the
  event publication from B will not be traced further up.
- **`@Async` wrappers.** Methods annotated `@Async` are invoked through a thread-pool proxy.
  The static call site is present in bytecode (so the direct caller is found), but the
  initiating caller of the async method may be in a different thread and not visible.
- **Reflection and lambdas.** If a bean calls the target method via `Method.invoke()` or
  passes it as a `Function<>` / lambda, the bytecode will show a call to `Method.invoke`
  or a lambda bootstrap — not the target method name. These are rare in standard Spring
  service layers but possible.
- **Bean-level accuracy only for depth > 1.** At depth 1, the method-level match is
  precise (bytecode confirms the call). At depth 2+, the method-level match is heuristic:
  we know bean B depends on bean A and that B.methodX calls A.something — but the BFS
  does not re-verify that B.methodX is the specific method that calls A.something. A
  future improvement would do a full recursive method-level BFS; the current spec does
  not require this.
- **Overloads.** If the target method name matches multiple overloads, all are included
  and a warning is emitted. There is no way to disambiguate by method name alone without
  the caller specifying a full descriptor.

---

## Non-goals for this spec

- Argument-level dataflow analysis (which argument flows from caller to callee)
- Cross-service (HTTP → HTTP) call tracing
- Runtime call recording / dynamic tracing (that's `trace/trace-sql` territory)
- Production use

---

## New namespace vs. extension to `core`

`blast-radius` and its cache can live in `net.brdloush.livewire.core` (aliased as `lw`)
since it is conceptually a bean-graph operation. Alternatively it can be its own
namespace (`net.brdloush.livewire.callgraph`, aliased as `cg`) if the implementation
grows large. Start in `core`; extract if warranted.

The CLI wrapper `lw-blast-radius` follows the existing pattern in `skills/livewire/bin/`.

---

## New dependencies

ASM is almost certainly already transitively available via Spring. Verify with:
```bash
mvn dependency:tree | grep asm
# or
clj-nrepl-eval -p 7888 "(Class/forName \"org.objectweb.asm.ClassReader\")"
```

If it is not available (unlikely), add `org.ow2.asm/asm` at the appropriate version. Do
not add it as a compile-time dependency of the Livewire JAR without first confirming it
is not already present — double-classloading ASM causes hard-to-diagnose version conflicts.

---

## Suggested implementation order

1. Implement `transitive-dependents` — trivial BFS over existing `lw/all-bean-deps` data.
   Smoke-test in the REPL against Bloated Shelf.
2. Implement `extract-call-sites` for a single class using ASM. Verify ASM is available.
   Smoke-test by extracting call sites from `BookService.class` and confirming
   `bookRepository` calls appear.
3. Build the full call graph index over all app-level bean classes. Measure build time.
4. Implement `blast-radius` combining phases 1–3. Return `:affected` without entry-point
   metadata first — confirm correctness.
5. Add entry-point annotation for HTTP (cross-reference `intro/list-endpoints`).
6. Add `@Scheduled` entry-point detection. Verify against Bloated Shelf if it has a
   scheduled bean; add one to Bloated Shelf if not.
7. Add `@EventListener` detection via direct method reflection scan.
8. Add `:warnings` population.
9. Add caching + `lw/reset-blast-radius-cache!`.
10. Write `lw-blast-radius` CLI wrapper.
11. Update `SKILL.md` with the new function, wrapper script, and limitations section.
