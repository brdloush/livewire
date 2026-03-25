# Spec — `faker/build-entity` constraint awareness

## Problem

`faker/build-entity` generates values using heuristic name matching only
(field name patterns → faker providers). It has zero awareness of
`jakarta.validation.constraints` annotations. Two failure modes:

1. **Override blindly applied** — when you pass `{:overrides {:comment nil}}`,
   the `nil` is set directly on the entity with no check against `@NotNull`
   before the persist attempt.

2. **Failure is late and opaque** — the error surfaces as a
   `ConstraintViolationException` from Hibernate at flush time, far from where
   the override was declared. The stack trace points into Livewire internals
   rather than telling you "you passed nil for a `@NotNull` field".

A secondary gap: even without overrides, if faker's heuristics happen to
generate a value that violates `@Size`, `@Min`, `@Max`, `@Positive` etc.,
the same late-failure pattern applies.

---

## Solution

Add a constraint-scanning step that reads `jakarta.validation.constraints`
(and `javax.validation.constraints` for older apps) from fields and getters at
build time. This enables:

### 1. Fail-fast override validation

Before applying any override, check it against the collected constraint map and
throw immediately with a clear message:

```
Cannot apply override {:comment nil} — Review.comment is @NotNull
```

### 2. Constraint-aware value generation

Use constraint metadata to guide generation alongside column metadata:

| Annotation | Generator behaviour |
|---|---|
| `@NotNull` | Never generate nil (skip the `(when (some? raw) ...)` guard for this field) |
| `@NotBlank` / `@NotEmpty` | Ensure generated string is non-blank |
| `@Size(min=X, max=Y)` | Clamp to `[max-size-min, min(size-max, column-length)]` |
| `@Min(X)` / `@Max(Y)` | Clamp numeric range |
| `@Positive` / `@PositiveOrZero` | Force positive (or ≥ 0) number |
| `@Email` | Use `faker.internet().emailAddress()` as fallback when name heuristic doesn't already produce an email |
| `@Past` / `@Future` | Direct date direction (currently done by name suffix heuristic; annotation takes priority) |
| `@Pattern(regexp=...)` | Surface as a `println` warning — regex-constrained generation is hard; deferred |

**Conflict resolution:** when both `@Column(length=N)` and `@Size(max=M)` are
present and `M ≠ N`, take the minimum — the most restrictive constraint
guaranteed to satisfy both the DB and the validator.

### 3. Surface constraints in `lw-inspect-entity` (separate task)

Expose validation annotations alongside column metadata in `inspect-entity`
output so constraints are visible before attempting a build:

```clojure
{:name "comment", :columns ["comment"], :type "string",
 :constraints ["@NotNull" "@Size(max=2000)"]}
```

This is a backwards-compatible additive change to `introspect.clj`. Scoped
separately because it has independent value (useful to agents even without
the faker changes) and different risk profile.

---

## Implementation

### Key design note: `@NotNull` on association fields

`@NotNull` on a `@ManyToOne` field is common — it is the Bean Validation
equivalent of `optional=false`. The faker already handles required associations
via `(:nullable p)` from `column-annotation-meta`. The constraint scanner should
emit `:not-null? true` for association fields too (so the override check fires
correctly when `{:author nil}` is passed), but the *generation path* for
association fields remains unchanged — let the existing `auto-deps?` logic run,
do not try to generate a value for it.

### `constraint-meta` helper

Mirror of `column-annotation-meta` in `introspect.clj`. Can live in either
`faker.clj` (keeping introspect pure to Hibernate metamodel) or `introspect.clj`
(keeping all annotation-reading in one place). Prefer `introspect.clj` since:
- It already has `find-field` / `find-getter` helpers
- Part 3 (surface in `inspect-entity`) will reuse the same data

The helper reflects over both `jakarta.validation.constraints.*` and
`javax.validation.constraints.*` (try `jakarta` first, fall back to `javax`
without failing — apps may have either or neither):

```clojure
(defn constraint-meta
  "Returns a map of constraint metadata for a single property, read from
   jakarta.validation.constraints (or javax.validation.constraints as fallback).

   Returns:
     {:not-null?          true/false
      :not-blank?         true/false
      :not-empty?         true/false
      :email?             true/false
      :past?              true/false
      :future?            true/false
      :positive?          true/false
      :positive-or-zero?  true/false
      :size-min           N or nil
      :size-max           N or nil
      :min                N or nil
      :max                N or nil
      :pattern            \"regexp\" or nil}"
  [entity-cls prop-name])
```

Reads annotations from the field first, getter second (same priority as
`column-annotation-meta`). Returns all-nil map when no validation constraints
are present or when the validation API is not on the classpath.

### Threading into `build-entity-internal`

1. After resolving `cls`, call `(constraint-meta cls pname)` per property and
   merge it into the property map alongside the existing `ann-meta`.
2. In the override-check pass (new, runs before `(doseq [[k v] overrides]…)`):
   for each override entry, look up the constraint map and throw on violations.
3. In the scalar generation loop: use constraint data to clamp/guide values.

### Fail-fast override check — exact error shape

```clojure
(throw (ex-info
  (str "Cannot apply override {" k " " (pr-str v) "} — "
       entity-name "." pname " is @NotNull")
  {:entity entity-name :field pname :constraint :not-null :value v}))
```

Analogous messages for `@NotBlank`, `@Size`, etc.

### `@Pattern` warning

```clojure
(println (str "[faker] WARNING: " entity-name "." pname
              " has @Pattern(regexp=\"" pattern "\") — "
              "constraint-aware generation is not supported for regex patterns. "
              "Value was generated by name heuristic; validate manually."))
```

---

## Non-goals

- Actually generating strings that satisfy `@Pattern` — out of scope
- Honouring `@Valid` / cascaded validation on nested associations
- Supporting custom constraint annotations (only standard `jakarta.validation` set)
- Part 3 (`inspect-entity` `:constraints` key) — tracked as a follow-on task

---

## Acceptance criteria

- `(faker/build-entity "Review" {:overrides {:comment nil}})` throws immediately
  with a message naming the field and the violated constraint, before any
  transaction is opened
- `(faker/build-entity "Book")` never generates a string longer than
  `min(@Size(max=...), @Column(length=...))` when both are present
- `@Positive` / `@Min` / `@Max` numeric fields always receive values in the
  valid range
- `@Email` fallback fires when the name heuristic would not produce an email
  (e.g. a field named `contactInfo` with `@Email`)
- `@Pattern` fields emit a warning but do not crash
- All existing `build-entity` behaviour is unchanged when no validation
  annotations are present
