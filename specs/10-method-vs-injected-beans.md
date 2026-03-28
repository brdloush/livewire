# Spec 10 — Method-level Dependency Fingerprinting & Split Candidates

## Motivation

The existing `lw/all-bean-deps` and `lw-blast-radius` tools operate at the **bean level**: they
tell you how many dependencies a bean has, and which other beans transitively call into a given
method. What they cannot tell you is *which subset of a bean's injected dependencies each
individual method actually uses*.

This gap matters when analysing bloated beans. Knowing that `libraryManagementService` has 6
injected repositories says "this is too large" but gives no guidance on *where* to cut. Knowing
that `libraryManagementService.checkoutBook()` uses `{bookRepository, loanRecordRepository, libraryMemberRepository}`
while `libraryManagementService.addReview()` uses `{reviewRepository, bookRepository, libraryMemberRepository}`
immediately suggests two extractable services that share only `bookRepository` and
`libraryMemberRepository` — clear facade candidates.

This spec describes two new capabilities built on top of the existing ASM bytecode index
introduced by `blast-radius`:

1. **`method-dep-map`** — for each method on a bean, the subset of the bean's injected fields
   it references
2. **`split-candidates`** — clusters those method/dep-subset pairs into extraction proposals,
   scores their cleanliness, and flags shared deps as potential facade targets

---

## Background: what blast-radius already does

`blast-radius` performs a two-phase analysis:

1. **ASM scan** — walks bytecode of every app-level class, recording `GETFIELD` and
   `INVOKEVIRTUAL`/`INVOKEINTERFACE` instructions to build a method-level call graph
2. **BFS traversal** — starting from a target method, walks the graph upward through the bean
   dependency tree to find all transitive callers

Phase 1 already has everything needed: it sees which fields each method accesses. The extension
required is to **correlate those field accesses back to the bean's injected dependencies**, rather
than only using them to follow call edges upward.

---

## Feature 1 — `method-dep-map`

### What it does

For a given bean, returns a map of each public (and package-private) method to the set of
injected dependencies that method directly references in its bytecode.

"Injected dependency" is defined as: any field on the bean class whose type is itself a Spring
bean (i.e. present in `ApplicationContext`). Fields that are not Spring beans (primitive config
values, `String` constants, etc.) are excluded.

### API

```clojure
(cg/method-dep-map bean-name)
(cg/method-dep-map bean-name :include-private? true)  ; default false
(cg/method-dep-map bean-name :depth 2)                ; include transitive field refs, default 1
```

**Returns:**

```clojure
{:bean  "libraryManagementService"
 :class "com.example.bloatedshelf.service.LibraryManagementService"
 :methods
 [{:method        "registerBook"
   :deps          ["bookRepository" "authorRepository" "genreRepository"]
   :orchestrator? false}

  {:method        "checkoutBook"
   :deps          ["bookRepository" "loanRecordRepository" "libraryMemberRepository"]
   :orchestrator? false}

  {:method        "returnBook"
   :deps          ["bookRepository" "loanRecordRepository"]
   :orchestrator? false}

  {:method        "addReview"
   :deps          ["reviewRepository" "bookRepository" "libraryMemberRepository"]
   :orchestrator? false}

  {:method        "generateLibraryReport"
   :deps          ["bookRepository" "authorRepository" "genreRepository"
                   "loanRecordRepository" "libraryMemberRepository" "reviewRepository"]
   :orchestrator? true}

  {:method        "getBookStatus"
   :deps          ["bookRepository"]
   :orchestrator? false}]

 :unaccounted-deps ["auditLog"]   ; injected but not directly referenced in any method
}
```

**`:orchestrator?` flag** — set `true` when a method's dep-set is a superset of (or heavily
overlaps with) two or more other methods' dep-sets. These methods sequence sub-operations rather
than performing a single concern; they are poor candidates for assignment to any one extracted
service and are better kept in a thin coordinating facade.

**`:unaccounted-deps`** — injected fields not referenced in any method. May indicate dead
injections, or deps used only via `@PostConstruct` / field initializers — listed separately
so the user can investigate.

### CLI

```bash
lw-method-dep-map <beanName>
lw-method-dep-map <beanName> --include-private
```

Output is a grouped table: one section per method, listing its dep-set. Orchestrator methods
are marked with `[ORCHESTRATOR]`. Unaccounted deps are listed at the bottom.

---

## Feature 2 — `split-candidates`

### What it does

Takes the method→dep-set map produced by `method-dep-map` and applies a clustering algorithm
to group methods whose dep-sets are similar. Each cluster becomes an extraction candidate. The
result is a concrete split proposal with:

- A suggested name for each extracted service (derived from dep names and method name patterns)
- The dep-set each extracted service would require in its constructor
- A cleanliness score for each split
- Shared deps across clusters flagged as facade candidates

### Algorithm (outline)

1. Exclude orchestrator methods from clustering (they'll be described separately)
2. Represent each method as a binary vector over the full dep-set of the bean
3. Compute pairwise Jaccard similarity between method vectors
4. Apply single-linkage clustering with a configurable similarity threshold (default `0.5`)
5. For each cluster, union the dep-sets of its member methods → that's the constructor for the
   extracted service
6. Compute a **split cleanliness score** per cluster: `1 - (shared_deps / cluster_deps)`, where
   `shared_deps` are deps the cluster shares with at least one other cluster
7. Flag any dep appearing in ≥ 2 clusters as a **facade candidate**
8. Describe what to do with orchestrator methods (keep in a coordinating facade that injects the
   extracted services)

### API

```clojure
(cg/split-candidates bean-name)
(cg/split-candidates bean-name :threshold 0.4)   ; similarity threshold, default 0.5
(cg/split-candidates bean-name :min-cluster 2)   ; min methods per cluster to report, default 1
```

**Returns:**

```clojure
{:bean  "libraryManagementService"
 :total-deps 6
 :total-methods 12

 :proposed-splits
 [{:suggested-name  "BookCatalogueService"
   :methods         ["registerBook" "addGenreToBook" "getBookStatus"]
   :deps            ["bookRepository" "authorRepository" "genreRepository"]
   :dep-count       3
   :cleanliness     0.67
   :shared-deps     ["bookRepository"]}          ; shared with LoanManagementService and ReviewService

  {:suggested-name  "LoanManagementService"
   :methods         ["checkoutBook" "returnBook" "getActiveLoans"]
   :deps            ["bookRepository" "loanRecordRepository" "libraryMemberRepository"]
   :dep-count       3
   :cleanliness     0.33
   :shared-deps     ["bookRepository" "libraryMemberRepository"]}

  {:suggested-name  "ReviewService"
   :methods         ["addReview" "getReviewsForBook" "moderateReview"]
   :deps            ["reviewRepository" "bookRepository" "libraryMemberRepository"]
   :dep-count       3
   :cleanliness     0.33
   :shared-deps     ["bookRepository" "libraryMemberRepository"]}]

 :facade-candidates
 [{:dep              "bookRepository"
   :used-by-clusters ["BookCatalogueService" "LoanManagementService" "ReviewService"]
   :suggestion       "Inject bookRepository into all extracted services, or wrap lookup logic in a shared BookLookupFacade"}
  {:dep              "libraryMemberRepository"
   :used-by-clusters ["LoanManagementService" "ReviewService"]
   :suggestion       "Inject libraryMemberRepository into both extracted services, or wrap in a shared MemberLookupFacade"}]

 :orchestrator-methods
 [{:method     "generateLibraryReport"
   :suggestion "Keep in a thin LibraryManagementFacade that delegates to the extracted services"}]

 :unaccounted-deps ["auditLog"]
 :dep-reduction    {:before 6 :after-largest-split 3 :after-all-splits [3 3 3]}}
```

### Cleanliness score interpretation

| Score | Meaning |
|-------|---------|
| `1.0` | Perfect — no deps shared with any other cluster; completely clean extraction |
| `0.5–0.99` | Acceptable — some shared deps; those become constructor args on multiple services or a facade |
| `< 0.5` | Messy — clusters are highly entangled; consider whether the split is worth making, or whether a facade over the shared deps is the right first step |

### CLI

```bash
lw-split-candidates <beanName>
lw-split-candidates <beanName> --threshold 0.4
```

Output renders each proposed split as a named box with its methods, dep-set, and cleanliness
score. Facade candidates and orchestrator methods are called out at the bottom.

---

## Implementation notes

### Reuse from blast-radius

The ASM bytecode scan in `blast-radius` already walks method bodies and records field accesses.
The extension needed:

1. During the scan, when a `GETFIELD` is encountered, check whether the accessed field's type
   is a Spring bean (look it up in `ApplicationContext`). If yes, record
   `{:method method-name :field field-name :bean resolved-bean-name}` in the index.
2. Group by `:method` to produce the raw method→dep-set map.
3. The clustering and scoring logic is new but self-contained — no further bytecode work needed.

The blast-radius cache (`cg/reset-blast-radius-cache!`) covers this index too; calling it
invalidates both features.

### Handling inheritance and delegation

Some beans delegate to private helper methods. With `:include-private? true`, those helper
methods are included in the scan and their field accesses are attributed to the public methods
that call them (one level of inlining). Deeper chains are covered by `:depth 2`.

### Kotlin property accessors

Kotlin compiles `val injectedService: SomeService` to a backing field plus a generated getter.
The `GETFIELD` instruction references the backing field (`injectedService$delegate` or just
`injectedService`). The scanner must normalise these to the logical field name to match against
the bean name resolved from `ApplicationContext`.

---

## Limitations

- **Reflection and lambdas** — field accesses inside `Method.invoke()` or lambda bodies captured
  as separate synthetic classes are not visible to the scanner. Methods using these patterns may
  appear to use fewer deps than they actually do.
- **`@PostConstruct` and field initializers** — accesses outside regular methods go into
  `:unaccounted-deps` rather than being attributed to a specific method.
- **Suggested names are heuristic** — derived from common dep-name prefixes and method-name
  patterns. They are prompts for the developer, not authoritative names.
- **Clustering is sensitive to threshold** — a single threshold doesn't fit all beans. The CLI
  accepts `--threshold` for iteration; a future improvement could auto-select threshold by
  optimising for maximum average cleanliness score.
