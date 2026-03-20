# Feature Specification: Inferring Entity Faker Profiles

## 1. Motivation & Philosophy
Livewire currently excels at introspecting live Hibernate entities and running ad-hoc queries ("Prototype in Clojure, ship in Java"). We want to extend this capability to **automated test data generation**.

**The Goal:** Enable Livewire and AI agents to quickly generate valid, persistable entity graphs for speculative execution and testing. 

**The Constraints (Why not annotations or EDN?):**
1. We DO NOT want to pollute production `@Entity` classes with test-specific annotations (e.g., `@Fake("book.title")`).
2. We DO NOT want fragile external configuration files (like `.edn` or `.yaml`) that lose type safety and silently break when a Java field is renamed.

**The Solution: Compile-Time Safe Java Profiles**
We will use AI heuristics combined with Livewire's runtime introspection (`intro/inspect-all-entities`) to automatically infer and generate type-safe Java fixture classes (using Java 8+ method references). These profiles will reside exclusively in `src/test/java`, ensuring zero impact on production code while providing a statically "baked-in" semantic dictionary of data generation rules that benefits both humans and AI agents.

## 2. Technical Design

### Phase 1: The Target Java Contract (Test Scope)
The generated output for the target application should be a strongly-typed Java class. We will need a base interface (e.g., `EntityFakerProfile<T>`) and a builder that maps entity setters to `net.datafaker.Faker` suppliers.

**Example of the generated artifact (e.g., in `src/test/java/.../faker/BookProfile.java`):**
```java
public class BookProfile implements EntityFakerProfile<Book> {
    @Override
    public void configure(FakerBuilder<Book> builder, Faker faker) {
        // Compile-time safe mapping using method references
        builder.bind(Book::setTitle, () -> faker.book().title())
               .bind(Book::setIsbn, () -> faker.code().isbn13(true))
               .bind(Book::setPublishedYear, () -> (short) faker.number().numberBetween(1850, 2024))
               .bind(Book::setAvailableCopies, () -> (short) faker.number().numberBetween(1, 10));
    }
}

(Note: Compare this against the manual seeding logic in bloated-shelf's DataSeeder.java to see the exact Faker methods we want to target).
Phase 2: AI Inference & Generation workflow
The AI agent operating Livewire will perform the following steps to bootstrap these profiles:

    Introspect: Call (intro/inspect-all-entities) to retrieve the live Hibernate metamodel (properties, types, constraints, nullability, relational mappings).
    Infer Heuristics: Map field names and types to their respective Datafaker equivalents (e.g., a String field named firstName -> faker.name().firstName(); a Short field named publishedYear -> faker.number().numberBetween(1850, 2024)).
    Generate & Save: Write the corresponding [EntityName]Profile.java source file into the project's test directory.

Phase 3: Livewire Integration (Dynamic Execution)
Once the Java profile is compiled, Livewire needs a mechanism to utilize it from the Clojure REPL.

    Create a new Livewire namespace (e.g., net.brdloush.livewire.faker pre-aliased as faker).
    Implement a function (faker/build-entity "EntityName" {:optional-overrides}).
    Under the hood, this function should:
        Dynamically locate the EntityNameProfile class in the classpath.
        Instantiate it and pass a net.datafaker.Faker instance.
        Construct the Java entity based on the profile's rules.
        Apply any manual Clojure overrides provided in the REPL call.
        Return the constructed (and potentially persisted, if requested) Java entity ready for REPL experimentation or speculative service calls.

3. Implementation Tasks for the Agent

    Design the base Java API: Draft the EntityFakerProfile and FakerBuilder interfaces/classes to be dropped into the target project's test scope.
    Extend Livewire's Clojure core: Create the faker namespace capable of reflective instantiation of these profiles and dynamic proxying/interop with the Datafaker library.
    Update SKILL.md: Document the new workflow. Show an example of how the AI agent should read the entity model, generate the Java profile, and then use the faker namespace to construct test data live in the REPL.

