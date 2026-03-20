# Spec 06 — Lookup-table heuristic refinement

## Problem

`faker/build-entity` with `:auto-deps?` uses a heuristic to detect "lookup tables" —
reference entities like `Genre` that should be fetched from existing rows rather than
created fresh, to avoid unique-constraint violations on seeded data.

**Current heuristic:** an entity is a lookup table if it has:
- at least one `unique=true` string column, AND
- no required `@ManyToOne` associations

This is too broad. `LibraryMember` satisfies both conditions (`username` and `email` are
`unique=true`, and it has no required FKs), yet it is clearly a transactional domain entity
— it owns `@OneToMany` collections (`reviews`, `loanRecords`) and is created by application
logic, not seeded once at schema-creation time.

When the app runs without seed data, the lookup-table fetch for `LibraryMember` returns
no rows → `null` → the entity is set to `null` on the owning side → Hibernate throws:

```
PropertyValueException: not-null property references a null or transient value
  for entity com.example.bloatedshelf.domain.Review.member
```

This error gives no indication that the lookup-table heuristic is the cause. The user
sees a Hibernate constraint violation and has no clear path to diagnosing it.

## Root cause

`Genre` — the prototypical lookup table — has no collections at all:

| Entity | unique string cols | required @ManyToOne | @OneToMany / @ManyToMany collections |
|---|---|---|---|
| `Genre` | `name` (unique) | none | **none** |
| `LibraryMember` | `username`, `email` (both unique) | none | `reviews`, `loanRecords` |

The presence of `@OneToMany` / `@ManyToMany` collections is a reliable signal that an
entity is a full domain object with lifecycle of its own — not a static reference table.

## Proposed fix

### 1. Tighten the lookup-table heuristic

Add a third required condition: the entity must have **no `@OneToMany` or `@ManyToMany`
collections** to qualify as a lookup table.

**New heuristic:** an entity is a lookup table if it has:
- at least one `unique=true` string column, AND
- no required `@ManyToOne` associations, AND
- **no `@OneToMany` / `@ManyToMany` collections**

This correctly classifies `Genre` as a lookup table and `LibraryMember` as a regular
entity that `auto-deps?` should build and persist fresh.

### 2. Fail loudly when a lookup-table fetch returns empty

Even with the refined heuristic, an entity that is correctly classified as a lookup table
may have an empty table (e.g. no seed data loaded). Currently this silently produces `null`,
which surfaces as a cryptic Hibernate constraint violation far from the actual cause.

Instead, throw a clear `ex-info` at the point where the fetch returns no rows:

```
faker/build-entity: dependency 'genre' (→ Genre) was detected as a lookup table,
but the table is empty. Options:
  • seed the Genre table before calling build-entity
  • pass an existing instance via :overrides {:genre <instance>}
  • pass a freshly-built one via :overrides {:genre (faker/build-entity "Genre" {:persist? true})}
```

## Expected outcome

```clojure
;; Before — silent null → cryptic Hibernate PropertyValueException
(faker/build-entity "Review" {:auto-deps? true :persist? true :rollback? true})
;; => PropertyValueException: not-null property references a null or transient value
;;    for entity ...Review.member

;; After — LibraryMember no longer classified as lookup table, built fresh
(faker/build-entity "Review" {:auto-deps? true :persist? true :rollback? true})
;; => #object[Review ...]  with a freshly-persisted LibraryMember wired in

;; After — Genre table empty → clear actionable error instead of null
(faker/build-entity "Book" {:auto-deps? true :persist? true :rollback? true})
;; => ExceptionInfo: dependency 'genres' (→ Genre) was detected as a lookup table,
;;    but the table is empty. ...
```