# Livewire Call Graph API Reference

Read this file when analysing call graphs, measuring blast radius of changes, planning
service splits, or detecting dead/internal-only methods.

---

## Call Graph API — `net.brdloush.livewire.callgraph`

### `cg/blast-radius` — method-level call graph and entry-point impact analysis

Given a bean name and method name, `blast-radius` walks the bean dependency graph upward,
intersects with a bytecode-level call graph extracted at runtime via ASM, and returns the
set of bean methods that transitively invoke the target — annotated with their distance and
observable surface (HTTP endpoints, schedulers, event listeners).

**Use this before modifying any repository, service method, or query.**

> ℹ️ `blast-radius` is **static call-graph analysis** — it reads bytecode and Spring
> metadata, fires no SQL, and has nothing to do with N+1 query detection. To detect N+1s,
> read `references/n-plus-one-hunting.md`.

```clojure
(cg/blast-radius bean-name method-name)
(cg/blast-radius bean-name method-name :app-only true)   ; default
(cg/blast-radius bean-name method-name :app-only false)  ; include Spring infra beans
```

**Returns:**
```clojure
{:target   {:bean "bookRepository" :method "findAllWithAuthorAndGenres"}
 :affected [{:bean    "bookService"
             :method  "getAllBooks"
             :depth   1
             :entry-point nil}
            {:bean    "bookController"
             :method  "getBooks"
             :depth   2
             :entry-point {:type          :http-endpoint
                           :paths         ["/api/books"]
                           :http-methods  ["GET"]
                           :pre-authorize "hasRole('MEMBER')"}}
            {:bean    "bookStatsReporter"
             :method  "reportNightlyStats"
             :depth   2
             :entry-point {:type :scheduler
                           :cron "0 0 2 * * *"}}]
 :warnings ["Method name 'findAll' matched multiple signatures — all overloads are included"]}
```

- `:depth` — hop count from the target bean. Depth 1 = direct caller.
- `:entry-point` — present for HTTP endpoints, `@Scheduled` methods, and `@EventListener` methods. `nil` for internal service beans.

**Cache management:** after hot-patching or recompiling during a session, clear the cache:
```clojure
(cg/reset-blast-radius-cache!)
```

**Examples:**
```clojure
;; Which HTTP endpoints call bookRepository/findAll, directly or via services?
(cg/blast-radius "bookRepository" "findAll")

;; What is affected if I change bookService/archiveBook?
(cg/blast-radius "bookService" "archiveBook")
```

**Wildcard `"*"` — full inbound call graph for all methods at once**

Pass `"*"` as the method name to run blast-radius for every method on the bean in one call.
All indexes are built once and reused — same speed as a single method call.

```clojure
(cg/blast-radius "bookService" "*")
;; => {:target {:bean "bookService" :method "*"}
;;     :affected [{:bean "bookController" :method "getBooks" :depth 1
;;                 :entry-point {:type :http-endpoint :paths ["/api/books"] ...}}
;;                ...]
;;     :warnings [...]}
```

### CLI: `lw-blast-radius` / `lw-blast-radius-all`

```bash
# Single method — who calls bookService.archiveBook?
lw-blast-radius bookService archiveBook

# All methods flat — full inbound call graph for the bean
lw-blast-radius bookService '*'

# Per-method map — {method → {:callers [...]}} for every method
# Methods with empty :callers are dead-code candidates
lw-blast-radius-all bookService
```

### ⚠️ Known limitations

- **`ApplicationEventPublisher` calls are invisible.** Event-driven paths cannot be traced further up.
- **`@Async` wrappers.** The direct call site is found in bytecode, but the initiating caller of the async method may be in a different thread.
- **Reflection and lambdas.** `Method.invoke()` and lambda-based dispatch are not traceable.
- **Depth > 1 is best-effort.** At depth 1 the method-level match is precise; at depth 2+ the method name is a best-guess caller.
- **Overloads.** If the target method name matches multiple overloads, all are included and a warning is emitted.
- **Kotlin default-parameter calls are resolved.** Both `method` and `method$default` are checked.
- **`@Scheduled` index absent when `@EnableScheduling` is not active.** A one-time diagnostic is printed explaining why.

---

### `cg/method-dep-map` — method-level dependency fingerprinting

`lw/all-bean-deps` tells you *how many* dependencies a bean has. `method-dep-map` tells you
*which ones each method actually uses* — the missing link for identifying split boundaries
in a bloated bean.

```clojure
(cg/method-dep-map bean-name)
(cg/method-dep-map bean-name :expand-private? true)
(cg/method-dep-map bean-name :intra-calls? true)
(cg/method-dep-map bean-name :callers? true)
```

**Returns:**
```clojure
{:bean             "adminService"
 :class            "com.example.AdminService"
 :methods          [{:method        "getSystemStats"
                     :deps          [{:bean "authorRepository"        :calls ["count"]}
                                     {:bean "bookRepository"          :calls ["count"]}
                                     {:bean "libraryMemberRepository" :calls ["count"]}
                                     {:bean "loanRecordRepository"    :calls ["count"]}
                                     {:bean "reviewRepository"        :calls ["count"]}]
                     :orchestrator? false}
                    {:method        "getTop10MostLoanedBooks"
                     :deps          [{:bean "bookRepository" :calls ["findTop10MostLoaned"]}]
                     :orchestrator? false}]
 :dep-frequency    [{:dep "bookRepository" :used-by-count 2 :methods ["getSystemStats" "getTop10MostLoanedBooks"]}
                    ...]
 :unaccounted-deps []}
```

**Key fields:**
- **`:deps`** — each entry is `{:bean "…" :calls ["method1" "method2"]}`. `:calls` lists distinct method names called on that dep within this method, sorted alphabetically.
- **`:orchestrator?`** — `true` when any of:
  - The method's dep-set is a proper superset of ≥ 2 other methods' dep-sets (wide footprint).
  - The method makes 3+ intra-class calls to sibling methods (deep delegation).
  - The method calls 3+ distinct methods on the same injected bean (multi-op dep use).
- **`:dep-frequency`** — deps ranked by how many distinct methods use them. Count-1 deps are prime extraction candidates.
- **`:unaccounted-deps`** — injected beans not referenced by any public method. Possible dead injections.

**Options:**
- **`:intra-calls? true`** — adds `:intra-calls` listing the sibling methods this method directly calls. Essential for split planning: if method A calls method B, moving A without B will break the service.
- **`:callers? true`** — adds `:intra-callers` showing which siblings call this method. Reveals internal-only methods that are visibility leaks.
- **`:expand-private? true`** — suppresses private helpers from `:methods` and folds their field accesses into the public callers. Surfaces the real dep footprint.

**Example with `:intra-calls?`:**
```clojure
(cg/method-dep-map "bookService" :intra-calls? true)
;; {:method "processOverdueLoans"
;;  :deps   [{:bean "loanRecordRepository"
;;             :calls ["findOverdue" "markNotified" "archiveLoan"]}]
;;  :intra-calls ["sendOverdueNotice" "updateMemberStatus"]
;;  :orchestrator? true}   ; 3+ calls on same dep triggers orchestrator flag
```

**When to use it:**
- Spotted a bean with many injected deps via `lw/all-bean-deps` → use `method-dep-map` to see how they distribute across methods.
- Before a refactor → use `:expand-private? true` to confirm the real dep footprint.
- To surface orchestrator methods that should stay in a thin facade.

**Known limitations:**
- Reflection and lambda captures are invisible.
- `@PostConstruct` and field initializer accesses go into `:unaccounted-deps`.
- `:expand-private?` expands one level of private calls only; chains of private helpers are not recursed further.

### CLI: `lw-method-dep-map`

```bash
lw-method-dep-map adminService
lw-method-dep-map bookService
```

---

### `cg/method-dep-clusters` — method clustering for service split planning

`method-dep-clusters` partitions the methods into natural extraction groups and tells you
exactly where to draw the split boundaries — which methods go together, which deps move
cleanly, and which splits are unsafe.

```clojure
(cg/method-dep-clusters bean-name)
(cg/method-dep-clusters bean-name :expand-private? true)
(cg/method-dep-clusters bean-name :min-cluster-size 2)
```

**Returns:**
```clojure
(cg/method-dep-clusters "memberService")
;; => {:bean    "memberService"
;;     :class   "com.example.MemberService"
;;     :orchestrators []
;;     :dep-free []
;;     :clusters
;;     [{:id 0
;;       :methods        ["getActiveLoansForMember"]
;;       :exclusive-deps ["loanRecordRepository"]
;;       :shared-deps    []
;;       :intra-call-violations []}
;;      {:id 1
;;       :methods        ["getAllMembers" "getMemberById"]
;;       :exclusive-deps ["libraryMemberRepository"]
;;       :shared-deps    []
;;       :intra-call-violations []}]
;;     :shared-deps-summary []
;;     :unaccounted-deps []}
```

**Key fields:**
- **`:orchestrators`** — methods with a wide dep footprint; keep in a thin coordinating facade.
- **`:dep-free`** — methods with no injected dep access; can move anywhere.
- **`:exclusive-deps`** — deps used only by this cluster. Move cleanly to the extracted service.
- **`:shared-deps`** — deps also used by another cluster. Must appear in multiple constructors, or be wrapped in a shared facade.
- **`:intra-call-violations`** — methods in this cluster that call a method assigned to a different cluster. The split is unsafe until resolved.
- **`:shared-deps-summary`** — cross-cluster view: which clusters share each dep, and whether orchestrators use it. High-cardinality entries are facade candidates.

**When to use it:**
- `lw/all-bean-deps` shows a bean with many deps → run this to get a concrete extraction plan without opening the source file.
- Before writing a refactoring plan: confirm no `:intra-call-violations`, then write the plan from the cluster output.

**Limitations:**
- Inherits all limitations of `method-dep-map`.
- Clusters are structural, not semantic. Human review is always the final step.
- Use `:expand-private? true` on beans with heavy private delegation.

### CLI: `lw-method-dep-clusters`

```bash
lw-method-dep-clusters adminService
lw-method-dep-clusters bookService --expand-private
lw-method-dep-clusters adminService --min-cluster-size 2
```

---

### `cg/dead-methods` — unreachable public method detection

Returns public methods on a bean that have no callers in the application.

```clojure
(cg/dead-methods bean-name)
```

**Returns:**
```clojure
{:bean                "bookService"
 :dead                []
 :internal-only       [{:method "archiveBook" :intra-callers ["someOrchestrator"]}]
 :reachable-count     4
 :dead-count          0
 :internal-only-count 1
 :warnings            ["2 @EventListener bean methods detected — ..."]}
```

**Two distinct categories:**
- **`:dead`** — public methods with no external callers AND no intra-class callers. Primary candidates for deletion.
- **`:internal-only`** — public methods with no external callers but called by at least one sibling. Public by accident — refactoring candidates, not deletion candidates.

Only public methods (JVM `ACC_PUBLIC`) are analysed.

⚠️ **False-positive caveat.** Methods invoked only via `ApplicationEventPublisher`, Kafka,
NATS, or other messaging infrastructure will appear in `:dead` or `:internal-only` even
though they are actively used. The `:warnings` key automatically lists detected messaging
beans — cross-reference before acting.

### CLI: `lw-dead-methods`

```bash
lw-dead-methods bookService
lw-dead-methods adminService
```
