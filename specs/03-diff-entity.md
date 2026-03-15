  Feature spec: q/diff-entity — mutation observer for Hibernate-managed entities

  What the user wanted

  A way to answer: "what did this service method actually write to the database?" — the
  observability gap that trace/trace-sql and jpa/jpa-query leave open. Those tools cover
  the query dimension (how many SQL statements, what do they look like, what do they
  return). diff-entity covers the mutation dimension: given an entity before and after a
  service call, what changed?

  The primary motivation is agentic: an AI agent investigating unexpected entity state
  currently has no structured way to observe writes. It can load an entity, call a service
  method, load the entity again, and compare at the REPL by eye — but that is brittle and
  produces no machine-readable output. diff-entity makes mutation causality a first-class
  observable, on par with what trace/trace-sql does for queries.

  ---
  API

  (q/diff-entity entity-class id thunk)

  Arguments:
    entity-class  — Hibernate entity name (string, e.g. "Book") or Java class
    id            — primary key value
    thunk         — zero-argument function that performs the mutation

  Returns a map:
    {:before  { ... entity map before thunk ... }
     :after   { ... entity map after thunk  ... }
     :changed { key [old-value new-value] ... }}   ; only keys that differ

  The thunk is called inside lw/in-tx, which rolls back by default. The caller
  gets the diff without any persistent side effects.

  ---
  Use cases

  1. Understanding an unfamiliar service method (exploration)

  Instead of reading through layers of JPA mappings and service code, just run it:

    (q/diff-entity "Book" 42 #(.processBookReturn (lw/bean "loanService") 42))
    ;; => {:changed {:status     ["LOANED" "AVAILABLE"]
    ;;               :returnedAt [nil "2025-03-15"]}}

  Immediate answer. No source diving, no annotation archaeology.

  2. Debugging unexpected entity state

  A user reports book 42 ended up in a wrong state after a return. Call the suspect
  method wrapped in diff-entity, see exactly what it wrote, and compare to what it
  should have written. Because the thunk is rolled back, this is safe to run against
  the live dev database without corrupting data.

  3. Verifying a fix before committing

  After patching a service method (hot-patched via the REPL or recompiled), confirm the
  diff now shows the expected field changes before restarting or running tests. Tight
  feedback loop, no full rebuild needed.

  4. AI agent mutation investigation (the primary use case)

  An agent investigating "why is entity X in state Y?" can systematically call
  candidate service methods and inspect their diffs — tracing mutation causality
  rather than just query shape.

  Example agent workflow:
    - trace/trace-sql told us 47 queries fire on GET /api/books/{id}
    - jpa/jpa-query showed us the current state of Book 42
    - But the agent needs to know: which service call put it in status ARCHIVED?

    (q/diff-entity "Book" 42 #(.archiveBook (lw/bean "bookService") 42))
    ;; => {:changed {:status     ["ACTIVE" "ARCHIVED"]
    ;;               :archivedAt [nil "2025-03-14T10:00:00"]
    ;;               :archivedBy [nil "admin"]}}

    (q/diff-entity "Book" 42 #(.softDeleteBook (lw/bean "bookService") 42))
    ;; => {:changed {:status  ["ACTIVE" "DELETED"]
    ;;               :deleted [false true]}}

  The agent can now reason: "archiveBook sets :archivedAt and :archivedBy; softDeleteBook
  does not — the bug must be in the archival path." This is the same leverage a developer
  has when comparing DB snapshots by hand, but structured and scriptable.

  5. Auditing what fields a method touches

  "Does updateBookMetadata modify archivedAt or only title and description?" Useful for
  both correctness and security reviews — you see exactly which fields are in scope
  without reading every line of the service implementation.

  ---
  Implementation sketch

  The implementation can reuse the entity serialization machinery already in
  jpa/jpa-query (entity->map, visited-set cycle detection, <lazy> sentinel for
  uninitialized collections):

  (defn diff-entity [entity-class id thunk]
    (let [load  (fn [] (jpa/load-entity entity-class id))   ; to be extracted from jpa-query
          before (load)
          _      (lw/in-tx (thunk))                         ; rolls back automatically
          after  (load)
          changed (into {}
                    (for [k (clojure.set/union (set (keys before)) (set (keys after)))
                          :let [v-before (get before k)
                                v-after  (get after  k)]
                          :when (not= v-before v-after)]
                      [k [v-before v-after]]))]
      {:before before :after after :changed changed}))

  Key decisions:
  - Rollback by default: the thunk runs inside lw/in-tx, which rolls back unless the
    caller explicitly commits. Safe for exploration against a live dev database.
  - Two separate loads: load-before happens outside the thunk transaction so it sees
    the committed state; load-after happens in a fresh read-only tx after the rollback.
    This means :after should be identical to :before for a correctly rolled-back thunk —
    which is useful as a sanity check. If the caller wants to observe a committed write,
    they wrap the thunk in lw/in-tx themselves and pass a no-rollback variant.
  - Serialization: delegate to the same entity->map used by jpa/jpa-query.
    Uninitialized lazy collections render as "<lazy>" rather than silently firing extra SQL.
  - Diff granularity: flat key comparison on the serialized maps. Nested entity
    associations that differ will appear as changed values (the whole nested map changes),
    not as recursive diffs. Deep diffing can be added later if needed.

  ---
  Dependency on jpa/jpa-query serialization

  The entity serialization machinery (entity->map, cycle detection, <lazy> handling)
  currently lives inline in jpa_query.clj. For diff-entity to reuse it cleanly, the
  serializer should be extracted into a private (or internal) helper — either a separate
  namespace (e.g. livewire.entity-serialize) or a set of private defns shared between
  jpa_query.clj and the future query.clj. This extraction is the main prerequisite for
  implementing diff-entity.
