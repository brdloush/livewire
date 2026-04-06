# Spec 12 — Method Dependency Clustering (`method-dep-clusters`)

## Motivation

`cg/method-dep-map` already tells you which of a bean's injected dependencies each method
actually uses. That is the right raw material for planning a service split. But consuming
it still requires a human or agent to mentally perform a graph partitioning step: which
methods share the same dep footprint? Which deps are exclusive to a group (and can move
cleanly with it)? Which deps are cross-cutting and must stay? Which methods are
orchestrators that should be kept in a thin facade?

On a bean with 8 dependencies and 15 methods this is feasible by eye. On a bean with 21
dependencies and 40+ methods it is a multi-turn manual exercise prone to error and
omission. The real-world trigger was a `bookManagementService`-scale service where working
out the optimal split required reading the source file four or five times, mentally
transposing a method×dep matrix, and discovering one of the clusters only mid-way through
the extraction — after the split plan had already been written.

`method-dep-clusters` automates that partitioning step in a single call.

---

## Relationship to existing features

| Feature | What it answers |
|---|---|
| `lw/all-bean-deps` | How many injected deps does this bean have? |
| `cg/method-dep-map` | Which deps does each individual method use? |
| **`cg/method-dep-clusters`** | **Which methods naturally group together, and which deps move with each group?** |
| `cg/blast-radius` | If I change this method, what is the runtime impact? |

`method-dep-clusters` is a pure composition over `method-dep-map` — no new bytecode
scanning. It post-processes the `:methods` list via set operations.

---

## Goal

Given a bean name, return a structured cluster map that partitions the bean's
non-orchestrator methods into natural extraction groups, classifies each dep as exclusive
or shared, and flags any intra-call violations that would make a proposed split unsafe —
all in a single call, without reading the source file.

---

## Proposed solution

### API

```clojure
(cg/method-dep-clusters bean-name)
(cg/method-dep-clusters bean-name :expand-private? true)  ; fold private helpers into public callers (default false)
(cg/method-dep-clusters bean-name :min-cluster-size 2)    ; suppress single-method islands (default 1)
```

All options accepted by `method-dep-map` are forwarded. Intra-calls are always fetched
internally regardless of options, because they are needed for violation detection.

### Algorithm

1. Call `method-dep-map` with `:intra-calls? true` and any forwarded options.
2. Separate orchestrators. Methods with `:orchestrator? true` are removed from the pool
   before clustering and reported separately in `:orchestrators`. Their wide dep footprint
   exists because they sequence other operations — assigning them to any cluster would
   distort the partition.
3. Build a dep-set per method. Methods with an empty dep-set (no injected dep access) are
   set aside as `:dep-free` — utility or pure methods that can move with any cluster.
4. Group by dep-set equality. Methods with identical dep-sets form a natural cluster.
5. Merge compatible groups. Two groups are compatible if one's dep-set is a subset of the
   other's. Compatible groups merge. The threshold is set-containment, not similarity
   scoring — the result is deterministic and requires no user-tunable parameter.
6. Flag intra-call violations. For each cluster, check whether any member method calls a
   method assigned to a different cluster. These are reported as `:intra-call-violations`
   and signal that the split as drawn would break internal calls.
7. Classify deps per cluster as `:exclusive-deps` (used only by this cluster) or
   `:shared-deps` (also used by at least one other cluster).
8. Build `:shared-deps-summary` — a cross-cluster view of each shared dep and which
   clusters use it, to help decide whether a facade is warranted.

### Return value

```clojure
(cg/method-dep-clusters "bookManagementService")
```

```clojure
{:bean        "bookManagementService"
 :class       "com.example.bloatedshelf.service.BookManagementService"

 :orchestrators
 [{:method      "registerNewBook"
   :deps        ["bookRepository" "authorRepository" "genreRepository"
                 "loanRecordRepository" "notificationService"]
   :intra-calls ["validateIsbn" "assignGenres" "notifySubscribers"]}
  {:method      "processReturn"
   :deps        ["bookRepository" "loanRecordRepository" "libraryMemberRepository"
                 "notificationService" "auditService"]
   :intra-calls ["markAsReturned" "calculateFine" "notifySubscribers"]}]

 :dep-free
 [{:method "formatIsbn"}
  {:method "buildLoanSummary"}]

 :clusters
 [{:id                    0
   :methods               ["findBooksByGenre" "getBookDetails" "updateBookMetadata" "deactivateBook"]
   :exclusive-deps        ["bookRepository" "genreRepository" "authorRepository"]
   :shared-deps           []
   :intra-call-violations []}

  {:id                    1
   :methods               ["checkoutBook" "extendLoan" "getActiveLoansForMember"]
   :exclusive-deps        ["loanRecordRepository"]
   :shared-deps           ["bookRepository" "libraryMemberRepository"]
   :intra-call-violations []}

  {:id                    2
   :methods               ["addReview" "flagReview" "getReviewsForBook"]
   :exclusive-deps        ["reviewRepository"]
   :shared-deps           ["bookRepository" "libraryMemberRepository"]
   :intra-call-violations []}

  {:id                    3
   :methods               ["sendOverdueNotification"]
   :exclusive-deps        ["notificationService" "emailClient"]
   :shared-deps           ["libraryMemberRepository"]
   :intra-call-violations []}]

 :shared-deps-summary
 [{:dep              "bookRepository"
   :used-by-clusters [1 2]
   :used-by-orchestrators true}
  {:dep              "libraryMemberRepository"
   :used-by-clusters [1 2 3]
   :used-by-orchestrators true}]

 :unaccounted-deps ["auditService"]}
```

### CLI wrapper: `lw-method-dep-clusters`

```bash
lw-method-dep-clusters bookManagementService
lw-method-dep-clusters bookManagementService --expand-private
lw-method-dep-clusters bookManagementService --min-cluster-size 2
```

```
bookManagementService  (14 deps)

ORCHESTRATORS (stay in facade)
  registerNewBook   deps: bookRepository authorRepository genreRepository loanRecordRepository notificationService
  processReturn     deps: bookRepository loanRecordRepository libraryMemberRepository notificationService auditService

DEP-FREE (can move anywhere)
  formatIsbn
  buildLoanSummary

CLUSTER 0
  methods:        findBooksByGenre  getBookDetails  updateBookMetadata  deactivateBook
  exclusive deps: bookRepository  genreRepository  authorRepository
  shared deps:    (none)
  violations:     (none)

CLUSTER 1
  methods:        checkoutBook  extendLoan  getActiveLoansForMember
  exclusive deps: loanRecordRepository
  shared deps:    bookRepository  libraryMemberRepository
  violations:     (none)

CLUSTER 2
  methods:        addReview  flagReview  getReviewsForBook
  exclusive deps: reviewRepository
  shared deps:    bookRepository  libraryMemberRepository
  violations:     (none)

CLUSTER 3  ⚠ single-method cluster
  methods:        sendOverdueNotification
  exclusive deps: notificationService  emailClient
  shared deps:    libraryMemberRepository
  violations:     (none)

SHARED DEPS ACROSS CLUSTERS
  bookRepository          → clusters 1, 2  (also used by orchestrators)
  libraryMemberRepository → clusters 1, 2, 3  (also used by orchestrators)

UNACCOUNTED DEPS
  auditService
```

---

## Usage examples

### Planning a service split

An agent spots `bookManagementService` has 14 injected deps via `lw/all-bean-deps` and
calls:

```clojure
(cg/method-dep-clusters "bookManagementService")
```

Reading the result top-to-bottom, the agent derives an extraction plan without opening
the source file:

- 2 orchestrators (`registerNewBook`, `processReturn`) → stay in a thin `BookManagementFacade`
- Cluster 0 → extract as `BookCatalogueService`: 3 exclusive deps, no shared deps, no violations — a clean cut
- Cluster 1 → extract as `LoanService`: 1 exclusive dep; `bookRepository` and `libraryMemberRepository` are shared with cluster 2
- Cluster 2 → extract as `ReviewService`: 1 exclusive dep; same two shared deps
- Cluster 3 → single method; folds into `LoanService` given the `libraryMemberRepository` overlap
- `bookRepository` and `libraryMemberRepository` appear in 2–3 clusters and in orchestrators — strong facade candidates; worth wrapping in `BookLookupService` / `MemberLookupService` before splitting

The agent writes this plan, gets a go-ahead, and begins extraction with a pre-validated
partition. No re-reads of the source file, no manual dep matrix, no mid-refactor surprises.

### Intra-call violations preventing a clean split

Suppose `getBookDetails` (cluster 0) internally calls `getActiveLoansForMember` (cluster 1).
The cluster 0 entry would show:

```clojure
{:id 0
 :methods               ["findBooksByGenre" "getBookDetails" "updateBookMetadata" "deactivateBook"]
 :exclusive-deps        [...]
 :shared-deps           []
 :intra-call-violations [{:caller "getBookDetails"
                          :callee "getActiveLoansForMember"
                          :callee-cluster 1}]}
```

This means the split as drawn is not clean: extracting cluster 0 into `BookCatalogueService`
and cluster 1 into `LoanService` requires that `getActiveLoansForMember` be promoted to a
visibility that `BookCatalogueService` can call on `LoanService`. The violation is not a
blocker, but it must be resolved before the extraction begins.

---

## Limitations

- Inherits all limitations of `method-dep-map`: reflection, lambdas, and `@PostConstruct`
  accesses are invisible to the scanner. The dep footprint per method may be understated.
- The clusters are structural, not semantic. The algorithm groups by shared dep access, not
  by domain concept. Two methods may share a dep for unrelated reasons and land in the same
  cluster. Human review of the proposed split is always the final step.
- Using the default `:expand-private? false` may undercount deps. A public method that
  delegates entirely to private helpers will appear to have an empty dep-set and land in
  `:dep-free` incorrectly. Use `:expand-private? true` on beans with heavy private delegation.
- Cross-service calls (to other Spring beans) do not appear in `:intra-call-violations` — they
  appear correctly in the method's `:deps` instead. The violations field covers only calls
  within the same class.

---

## Out of scope

- Suggested names for extracted services. The cluster structure gives an agent or developer
  enough information to name services from context; heuristic naming adds noise.
- Automatic generation of the extracted class skeletons or Spring configuration.
- Similarity-threshold-based clustering (see spec 10 for the abandoned approach). This spec
  uses set-containment only, which is deterministic and requires no user-tunable parameter.

---

## Implementation notes

`method-dep-clusters` calls `method-dep-map` with `:intra-calls? true` and performs only
set operations on the `:methods` list — no new ASM scanning. The intra-calls data is always
fetched internally regardless of caller options, because violation detection requires it.

The set-containment grouping approach replaces the Jaccard similarity + single-linkage
clustering proposed in spec 10. That approach introduced a configurable threshold and
non-deterministic results. Set-containment is strictly simpler: identical dep-sets always
cluster together; a method whose dep-set is a strict subset of a group's dep-set merges
into that group; partial overlaps produce separate clusters with shared deps flagged.

Cluster IDs are integers assigned in construction order. They are stable within a single
call but not across calls, and are used only for cross-referencing within `:shared-deps-summary`.

Kotlin property accessors (`getX`, `setX`, `componentN`, `copy`, `equals`, `hashCode`,
`toString`) should be filtered from the clustering input. They rarely access injected
Spring beans and add noise to the output without contributing to the partition.

---

## Deliverables

| # | Item |
|---|---|
| 1 | `cg/method-dep-clusters` in `net.brdloush.livewire.callgraph` |
| 2 | `lw-method-dep-clusters` wrapper script in `skills/livewire/bin/` |
| 3 | SKILL.md — new table row + subsection with input/output examples |
| 4 | README.md — extend the refactoring / architecture section |
