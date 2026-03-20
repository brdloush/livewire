# Spec 05 — `smart-bean`: unified Java object → Clojure map conversion

## Problem

`clojure.core/bean` is used throughout Livewire to convert Java objects to Clojure maps for
inspection and serialization. It works by scanning for JavaBeans getter methods (`getX()`).

Java records (introduced in Java 16) generate accessor methods without the `get` prefix —
`rating()`, `comment()`, `reviewerName()` etc. `clojure.core/bean` sees none of these and
returns an empty map `{}`.

This silently breaks REPL exploration whenever a Livewire API returns or processes a Java
record — most commonly DTOs returned by service/controller methods, e.g.:

```clojure
;; ReviewDto is a Java record — returns {} instead of the field values
(clojure.core/bean some-review-dto)
;; => {}

;; The user is forced to know the field names and call accessors manually:
{:rating (.rating some-review-dto) :comment (.comment some-review-dto) ...}
```

This is a silent failure — no error, just an empty map — which is hard to diagnose.

## Proposed solution

Introduce an internal `smart-bean` utility that detects whether an object is a Java record
and dispatches accordingly:

```clojure
(defn smart-bean [obj]
  (let [cls (.getClass obj)]
    (if (.isRecord cls)
      ;; Java record: use getRecordComponents() — each component carries its accessor Method
      (into {}
            (map (fn [c] [(keyword (.getName c))
                          (.invoke (.getAccessor c) obj (object-array 0))])
                 (.getRecordComponents cls)))
      ;; Everything else: standard JavaBeans introspection
      (clojure.core/bean obj))))
```

`Class.isRecord()` and `Class.getRecordComponents()` are standard JVM APIs since Java 16.
No additional dependencies are required.

## Where to apply it

Replace all internal uses of `clojure.core/bean` on user-supplied or result objects with
`smart-bean`. The key sites are:

- **`jpa/jpa-query` serialization** — when serializing entity graph nodes to Clojure maps,
  any node that is a record (e.g. a DTO projected by a JPQL constructor expression) currently
  serializes as `{}`.
- **`faker/build-entity` result** — the returned entity instance is a plain JPA entity (not a
  record), so this is safe today, but future callers passing the result through `clojure.core/bean`
  in their own REPL expressions will hit the issue.
- **Any Livewire helper that accepts an arbitrary object and converts it to a map for display.**

Exposing `smart-bean` as a public var in the `lw` namespace (e.g. `lw/bean->map`) would also
let users call it directly in REPL expressions, replacing the current idiom of
`(clojure.core/bean obj)`.

## Expected outcome

```clojure
;; Before — silent empty map for records
(clojure.core/bean some-review-dto)
;; => {}

;; After — full field map regardless of whether it's a record or a regular class
(lw/bean->map some-review-dto)
;; => {:id 42, :rating 5, :comment "Loved it", :reviewerName "Alice Smith", :reviewedAt ...}

;; Works unchanged for regular JPA entities
(lw/bean->map some-book-entity)
;; => {:id 1, :title "Pride and Prejudice", :isbn "...", :archived false, ...}
```

REPL exploration of service/controller results that return DTOs becomes seamless — no need
to know in advance whether the object is a record or a bean.