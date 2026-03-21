# Spec 07 — Test Recipe: Extract Assertable Values from a Faker-Built Entity Graph

## Motivation

When an AI agent (or a developer) uses `lw-build-entity` to prototype test data, the faker-generated
values are transient. The entity graph is built, the prototype is validated, the transaction rolls
back — and all the concrete values (names, ratings, comments, ISBNs, dates) are gone. The agent then
has to *invent* equivalent values when writing the actual Java/Kotlin integration test.

This invention step is the problem. It severs the connection between the validated prototype and the
written test, and it introduces unnecessary manual work and a subtle risk: the invented values may not
reflect realistic constraints the domain actually imposes (field lengths, uniqueness, nullable rules).
The prototype proved the entity graph is valid; the test setup code should flow directly from that
proof — not be reimagined from scratch.

## Real Example

During a session working on `bloated-shelf`, the agent was asked to write an integration test for
`BookService#getReviewsForBook`. The agent ran:

```
lw-build-entity Review '{:auto-deps? true :persist? true :rollback? true}'
```

Faker resolved the full dependency chain (Author → Book, LibraryMember → Review) and persisted it
within a rolled-back transaction. The agent then ran a Clojure prototype that called the live service
and confirmed:

```clojure
{:count 1, :first-rating 5, :first-reviewer-name "Kelsey Schaden", :original-rating 5}
```

At this point the agent knew:
- The member's full name was `"Kelsey Schaden"`
- The review rating was `5`
- The review comment was some faker-generated string (not extracted)
- The book title, author name, etc. were also generated but not captured

When writing the Java test, the agent invented completely new values:

```java
member.setFullName("John Doe");
member.setEmail("jdoe@example.com");
expectedRating = 5;
expectedComment = "An absolute classic.";
```

The test is correct, but its values have no connection to the validated prototype. The agent had the
real values in hand and discarded them.

## Proposed Solution

Add a `lw-build-test-recipe` wrapper script (and a backing `faker/build-test-recipe` Clojure
function) that builds a faker entity graph — with `:auto-deps? true :persist? true :rollback? true`
— and then walks the resulting object graph, extracting all scalar field values into a nested
Clojure map keyed by entity name.

The output is a plain data structure. No Java or Kotlin code is emitted. The agent reads the map
and uses the values directly when writing test setup code.

## Output Format

The output is a nested map: top-level keys are the simple entity class names (as keywords), values
are maps of field name (keyword) to value. Associations are included as nested maps, not as IDs.
Collections (`@OneToMany`, `@ManyToMany`) are omitted — they are the inverse side and not useful
for test setup. The `@Id` field is omitted (it is a DB-assigned value irrelevant to assertions).

Example output for `Review`:

```clojure
{:Review  {:rating    5
           :comment   "A remarkable journey through the highs and lows of modern life."
           :reviewedAt #object[LocalDateTime "2024-07-14T11:23:05"]}
 :Book    {:title          "The Midnight Crisis"
           :isbn           "978-3-16-148410-0"
           :publishedYear  1998
           :availableCopies 3
           :archived       false}
 :Author  {:firstName  "Kip"
           :lastName   "O'Reilly"
           :birthYear  1951
           :nationality "American"}
 :LibraryMember {:username    "kelsey.schaden"
                 :fullName    "Kelsey Schaden"
                 :email       "kelsey.schaden@example.com"
                 :memberSince #object[LocalDate "2019-03-22"]}}
```

Rules:
- Only scalar properties are included (strings, numbers, booleans, dates). No lazy collections,
  no associations represented as nested entity objects (to avoid infinite recursion).
- `@Id` / identifier fields are excluded.
- `null` values are included — they communicate that a nullable field was left empty, which is
  itself useful information for test assertions.
- The entity being built is listed first; its `@ManyToOne` dependencies follow in dependency
  order (deepest dependency last).

## CLI

```bash
# Basic usage — builds the entity and prints the recipe map
lw-build-test-recipe Review

# With overrides — the overridden values appear in the output as supplied
lw-build-test-recipe Review '{:overrides {:rating 1 :comment "Terrible"}}'
```

## Clojure API

```clojure
(faker/build-test-recipe "Review")
(faker/build-test-recipe "Review" {:overrides {:rating 1}})
```

Both forms return the nested map described above. The underlying entity graph is always built with
`:auto-deps? true :persist? true :rollback? true` — the recipe is a side-effect-free snapshot.

## How the Agent Should Use This

The updated workflow replaces the ad-hoc value extraction at the end of the Clojure prototype:

1. Run `lw-build-test-recipe Review` **before** writing any test code.
2. Read the output map. Every value in it is a candidate for use in `@BeforeEach` setup and in
   test assertions.
3. Write the test setup using those exact values. For example, if the recipe shows
   `:fullName "Kelsey Schaden"`, the `@BeforeEach` sets `member.setFullName("Kelsey Schaden")`
   and the assertion checks `assertThat(dto.reviewerName()).isEqualTo("Kelsey Schaden")`.
4. Run the Clojure service-call prototype as before (using `faker/build-entity` directly, or
   `lw-build-entity`) to validate the happy path works end-to-end.

The recipe does not replace the prototype — it feeds into it. The prototype validates that the
service returns the right shape; the recipe supplies the concrete values to assert on.

## What This Does NOT Do

- Emit Java or Kotlin code of any kind.
- Replace `lw-build-entity` or the Clojure prototype step — those remain the validation mechanism.
- Handle `@OneToMany` / `@ManyToMany` collections — the root entity's owned scalar fields and its
  direct `@ManyToOne` associations are sufficient for typical test setup.
- Guarantee uniqueness across test runs — if a test uses `"kelsey.schaden@example.com"` and the
  DB already has that row (e.g. from a non-rolled-back previous run), the test will fail on insert.
  Tests should either use `@Transactional` for rollback or prefix values with a random suffix.

## Implementation Notes

- The scalar extraction logic is similar to `lw/bean->map` but filtered to non-association,
  non-collection properties. Hibernate's `EntityType` metamodel can be used to identify which
  properties are scalar vs. association.
- `@ManyToOne` associations are resolved by calling the getter and recursing one level — no deeper,
  to avoid chains.
- The ordering (root entity first, dependencies after) can be derived from the same dependency
  resolution order already computed by `faker/build-entity` with `:auto-deps? true`.
- Temporal types (`LocalDate`, `LocalDateTime`, `Instant`) should be printed as-is (`.toString()`
  is readable enough for the agent to copy).