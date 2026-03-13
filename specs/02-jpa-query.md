  Feature spec: lw-jpa-query — speculative JPQL execution with smart entity serialization

  What the user wanted

  A way to run a candidate JPQL query against the live JVM to validate it before writing it to source — verify query count, result count, and SQL shape. Later extended to also return presentable, readable entity data rather than
  opaque object references.

  Why this approach

  lw-trace-sql wraps the expression in SQL capture. Executing JPQL via EntityManager inside lw/in-readonly-tx runs in a real transaction so Hibernate behaves exactly as it would in production. Paging via .setMaxResults is applied by
   default (matching the existing lw-sql convention of capping at 20 rows) to prevent accidental full-table loads.

  The entity serializer uses intro/inspect-entity metadata to know which properties are scalars vs associations, and threads an immutable set for visited object identities to detect true ancestor-chain cycles without falsely
  collapsing sibling occurrences of shared objects (e.g. the same Genre appearing in two different loans of the same member).

  ---
  The expression that worked (prototype)

  (lw/in-readonly-tx
    (let [meta-map (into {} (map (fn [n] [n (intro/inspect-entity n)])
                                 (map :name (intro/list-entities))))

          entity->map
          (fn entity->map [visited obj]
            (when obj
              (let [idc (System/identityHashCode obj)]
                (if (contains? visited idc)
                  "<circular>"
                  (let [visited' (conj visited idc)
                        b        (clojure.core/bean obj)
                        base-name (first (clojure.string/split
                                           (.getSimpleName (.getClass obj)) #"\$"))
                        em       (get meta-map base-name)]
                    (if em
                      (let [id-key  (keyword (get-in em [:identifier :name]))
                            scalars (->> (:properties em)
                                         (remove :is-association)
                                         (map #(keyword (:name %))))
                            assocs  (->> (:properties em)
                                         (filter :is-association))]
                        (merge
                          {id-key (get b id-key)}
                          (select-keys b scalars)
                          (into {} (for [{:keys [name collection]} assocs]
                                     (let [v (get b (keyword name))]
                                       [(keyword name)
                                        (cond
                                          (nil? v) nil
                                          collection
                                          (if (and (instance? org.hibernate.collection.spi.PersistentCollection v)
                                                   (not (.wasInitialized v)))
                                            "<lazy>"
                                            (mapv #(entity->map visited' %) (take PAGE-SIZE v)))
                                          :else (entity->map visited' v))])))))
                      (str obj)))))))

          em (lw/bean jakarta.persistence.EntityManager)
          q  (doto (.createQuery em "<JPQL>")
                   (.setFirstResult (* PAGE PAGE-SIZE))
                   (.setMaxResults PAGE-SIZE))]
      (mapv #(entity->map #{} %) (.getResultList q))))

  ---
  Implementation decisions

  1. Visited tracking — threaded immutable set (not mutable atom)

  The prototype used a mutable (atom #{}) shared across sibling branches, causing the same Genre object appearing in two different loans to be wrongly rendered as "<circular>".

  Fix: thread an immutable set visited as a value argument, passing (conj visited idc) into each recursive call. This way two sibling branches each get their own copy of the ancestor path — only true ancestor-chain cycles are
  detected.

  2. Uninitialized collections → <lazy>, no surprise queries

  Check (and (instance? org.hibernate.collection.spi.PersistentCollection v) (not (.wasInitialized v))) before iterating a collection. Uninitialized ones render as "<lazy>" rather than silently firing extra SQL. The caller controls
  what gets fetched via the JPQL JOIN FETCH clauses.

  3. Temporal types — keep as-is

  LocalDate, LocalDateTime, etc. are left as #object[java.time.LocalDate ...]. No .toString() coercion for now.

  4. Collection cap — configurable, default 20

  Collections render up to PAGE-SIZE items (same default as lw-sql). Applies both to top-level result list and to inline collections within entities.

  5. Paged fetching — default page 0, size 20

  Query is executed with .setFirstResult(page * pageSize) and .setMaxResults(pageSize). Wrapper signature:

  lw-jpa-query <jpql> [page] [pageSize]
  # defaults: page=0, pageSize=20

  Mirrors lw-sql discipline: never load unbounded result sets by default.

  ---
  Serialization behaviour summary

  ┌─────────────────────────────────────────┬───────────────────────────────────────┐
  │                Scenario                 │                Result                 │
  ├─────────────────────────────────────────┼───────────────────────────────────────┤
  │ Scalar property                         │ Value as-is                           │
  ├─────────────────────────────────────────┼───────────────────────────────────────┤
  │ Temporal type                           │ #object[LocalDate ...]                │
  ├─────────────────────────────────────────┼───────────────────────────────────────┤
  │ Eagerly fetched @ManyToOne              │ Recursed into                         │
  ├─────────────────────────────────────────┼───────────────────────────────────────┤
  │ Eagerly fetched collection (within cap) │ [{...} {...} ...]                     │
  ├─────────────────────────────────────────┼───────────────────────────────────────┤
  │ Collection beyond cap                   │ truncated to cap                      │
  ├─────────────────────────────────────────┼───────────────────────────────────────┤
  │ Uninitialized lazy collection           │ "<lazy>"                              │
  ├─────────────────────────────────────────┼───────────────────────────────────────┤
  │ Ancestor-chain cycle                    │ "<circular>"                          │
  ├─────────────────────────────────────────┼───────────────────────────────────────┤
  │ Sibling reuse of same object            │ fully rendered (not falsely circular) │
  ├─────────────────────────────────────────┼───────────────────────────────────────┤
  │ Non-entity Java object                  │ (.toString obj)                       │
  └─────────────────────────────────────────┴───────────────────────────────────────┘

