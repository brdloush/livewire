# Spec 11 — Test Recipe Redesign: First-class Relations

## Problem

`faker/build-test-recipe` currently omits association fields from `:fields`. The dependency
entities appear as separate top-level keys in the recipe map, but the relation fields
themselves (e.g. `review.book`, `review.member`, `book.author`) are absent. An agent or
developer consuming the recipe must infer the wiring from a secondary `lw-inspect-entity`
call, reasoning about which entity maps to which field and in what order saves must happen.

This "fill the gap" step is error-prone when:
- An entity has multiple associations of the same target type
- A `@ManyToOne` joins on a non-PK column
- The entity graph is deep or branching

## Goal

Make the recipe **self-contained**: everything needed to write `@BeforeEach` setup code
should be present in the recipe output with no secondary calls required.

## Proposed Output Shape

```clojure
{:save-order    [:Author :LibraryMember :Book :Review]

 :Author        {:tempid :author/1
                 :repo   "authorRepository"
                 :fields {:firstName   {:type "string" :value "Frederic"}
                          :lastName    {:type "string" :value "Terry"}
                          :birthYear   {:type "short"  :value 1942}
                          :nationality {:type "string" :value "Maltese"}}}

 :LibraryMember {:tempid :member/1
                 :repo   "libraryMemberRepository"
                 :fields {:username    {:type "string"    :value "stevie.schumm"}
                          :fullName    {:type "string"    :value "Kelly Ondricka"}
                          :email       {:type "string"    :value "evangelina.fisher@hotmail.com"}
                          :memberSince {:type "LocalDate" :value #object[LocalDate "2025-09-14"]}}}

 :Book          {:tempid :book/1
                 :repo   "bookRepository"
                 :fields {:title           {:type "string"   :value "The Daffodil Sky"}
                          :isbn            {:type "string"   :value "979-0-9978081-1-6"}
                          :publishedYear   {:type "short"    :value 1944}
                          :availableCopies {:type "short"    :value 2}
                          :archived        {:type "boolean"  :value false}
                          :archivedAt      {:type "LocalDateTime" :value nil}
                          :author          {:type "relation" :ref :author/1}}}

 :Review        {:tempid :review/1
                 :repo   "reviewRepository"
                 :fields {:rating     {:type "short"         :value 3}
                          :comment    {:type "string"        :value "Harum dignissimos..."}
                          :reviewedAt {:type "LocalDateTime" :value #object[LocalDateTime "2025-11-10T00:15:35.048"]}
                          :book       {:type "relation"      :ref :book/1}
                          :member     {:type "relation"      :ref :member/1}}}}
```

## Changes from Current Shape

| Aspect | Current | Proposed |
|---|---|---|
| Relation fields | absent from `:fields` | present with `{:type "relation" :ref <tempid>}` |
| Entity identity | implicit (map key) | explicit `:tempid` keyword on each entity |
| Save order | implicit (key order) | explicit top-level `:save-order` vector |
| Secondary calls needed | `lw-inspect-entity` for wiring | none |

## Tempid Scheme

- Format: `:<EntityName>/<index>`, e.g. `:author/1`, `:book/1`, `:review/1`
- Index starts at `1` and increments when multiple instances of the same entity type appear in one graph
- Tempids are stable within a single recipe call; they carry no meaning across calls

## Field Type Sentinel

Relation fields use `{:type "relation" :ref <tempid>}`. The `:ref` value is always a tempid
keyword present as a top-level key in the same recipe map.

Collection associations (`@OneToMany`, `@ManyToMany`) remain excluded — they are never
required for basic entity construction.

## Agent Translation Rules

A recipe consumer can translate to Java `@BeforeEach` code mechanically:

1. Iterate `:save-order` to determine instantiation and save sequence
2. For each entity, iterate `:fields`:
   - `{:type "relation" :ref k}` → call `entity.setFoo(resolvedInstances.get(k))`
   - anything else → call `entity.setFoo((CastFromType) value)`
3. After all saves: `entityManager.flush(); entityManager.clear()`
4. Store only IDs, re-attach via `getReferenceById()` in test bodies

## Out of Scope

- Non-PK join columns: `:ref` still points to the tempid of the target entity; the
  consuming code must use the correct field (e.g. `.getIsbn()`) when setting the FK.
  This edge case should be documented in the skill but does not change the output shape.
- Overrides: `:overrides` continue to work as today; overriding a relation field should
  accept either a live entity instance or a tempid reference (TBD).
