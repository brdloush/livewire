# Clojure & REPL-Driven Survival Guide

**Do not write Clojure without understanding the REPL-first workflow and idiomatic patterns.**
This guide contains the syntax, conventions, and anti-patterns needed to write idiomatic Clojure under constraint.

---

## The REPL Loop

**Never write code without REPL validation.** Every coding task follows this loop:

1.  **Gather:** Read the target file, understand the namespace, and identify dependencies.
2.  **Verify:** Ensure `clj-nrepl-eval` can connect (e.g., `(clj-nrepl-eval -p <port> "(+ 1 1)")`).
3.  **Test:** Evaluate the expression or function definition *before* saving it. Verify edge cases.
4.  **Save:** Once the REPL proves the logic works, use `edit` or `write` to persist the code.
5.  **Reload:** Use `(require '[ns.name :as alias] :reload)` to pick up the file changes.

**If REPL evaluation fails:**
1.  Read the error message carefully.
2.  Isolate the failing expression.
3.  Fix the root cause (usually a mismatched delimiter, missing import, or wrong arity).
4.  Re-evaluate before proceeding.

---

## Idioms & Patterns

### Threading Macros (The Core of Pipeline Code)

Clojure favors piping data through a sequence of transformations. Prefer these over deep nesting.

| Macro | Name | Behavior | Example |
| :--- | :--- | :--- | :--- |
| `->` | Thread-first | Inserts value as **first arg** of each form. | `(-> user (assoc :active true) (dissoc :temp))` |
| `->>` | Thread-last | Inserts value as **last arg** of each form. | `(->> [1 2 3] (filter odd?) (map #(* % 2)))` |
| `some->` | Nil-safe first | Short-circuits to `nil` if value is `nil`. | `(some-> user :address :zip (subs 0 5))` |
| `cond->` | Conditional first | Threads value only if the condition is truthy. | `(cond-> req authenticated? (assoc :user u) admin? (assoc :perm :all))` |
| `as->` | Explicit binding | Binds value to a name in every step. | `(as-> [1 2 3] $ (filter odd? $) (map #(* % 2) $))` |

**Rule of thumb:** Keep pipelines to 3–7 steps. Break them up if they exceed that.

### Destructuring

Clojure's `let` and function arguments support deep destructuring.

```clojure
;; Map destructuring (preferred over .getKey() accessors)
(let [{:keys [name age]} user]           ; extracts :name, :age from the map
  (println name))

(let [{:keys [name] :or {name "Unknown"}} user] ; default value
  name)

(let [{:keys [address] :as full-user} user] ; :as captures the whole map
  (str (:zip address) " - " (:email full-user)))

;; Vector destructuring (positional)
(let [[first-name & last-names] parts]
  (str first-name " " (apply str last-names)))

;; Nested destructuring
(let [{:keys [owner]
       :keys [address {:keys [city zip]}]} house]
  (println city zip))
```

### Conditionals

| Form | Use Case | Example |
| :--- | :--- | :--- |
| `when` | Single-branch with side effects (no return value needed) | `(when (> x 0) (log "positive") (process x))` |
| `when-let` | Nil-safe check + binding | `(when-let [v (get map key)] (+ v 1))` |
| `when-some` | Nil check (like `when-let` but checks for logical `nil`) | `(when-some [v (some-fn)] v)` |
| `if-let` | Nil-safe if/else + binding | `(if-let [v (find map key)] (:value v) :missing)` |
| `cond` | Multiple predicates (not constant dispatch) | `(cond (> x 0) :pos (= x 0) :zero :else :neg)` |
| `case` | Constant-value dispatch (faster, but values must be compile-time constants) | `(case operation :add (+ a b) :sub (- a b))` |

### Multi-Arity Functions

Use multiple argument vectors to provide defaults. All arities should dispatch to the full-arity body.

```clojure
(defn greet
  ([name] (greet name "Hello"))       ; dispatches to full arity
  ([name greeting]                     ; full arity, does the actual work
   (str greeting ", " name "!")))
```

### Higher-Order Functions & Composition

```clojure
;; map, filter, reduce are the holy trinity
(map inc [1 2 3])                    ; => (2 3 4)
(filter even? [1 2 3 4])             ; => (2 4)
(reduce + [1 2 3])                   ; => 6

;; comp — function composition (right-to-left evaluation)
(def uppercase-first
  (comp string/upper-case first))
(uppercase-first "hello")            ; => "H"

;; partial — create new functions from existing ones
(def add-five (partial + 5))
(add-five 10)                        ; => 15
```

### Multi-Params and Variadic Functions

```clojure
;; &rest syntax for variadic args
(defn concat-all [& vectors]
  (apply concat vectors))

;; Destructuring with &
(defn first-and-rest [[first-name & rest]]
  (println first-name rest))
```

---

## Naming Conventions

| Type | Pattern | Example | Notes |
| :--- | :--- | :--- | :--- |
| Functions / Vars | kebab-case | `calculate-total` | |
| Predicates | suffix `?` | `valid?`, `active?` | Should return boolean |
| Conversions | `source->target` | `map->vector`, `string->int` | |
| Dynamic Vars | `earmuffs` | `*connection*`, `*max-retries*` | Use only when necessary (e.g., threading context) |
| Private Helpers | prefix `-` | `-parse-date` | Never expose from the namespace |
| Unused Bindings | underscore | `_request`, `_` | |

**⚠️ CRITICAL: NEVER use `!` suffix on pure functions.**
In Clojure, `!` signifies **side effects** (usually mutation or blocking I/O), not "do it now".
Bad: `save-user!`, `update-config!`
Good: `save-user`, `update-config`

---

## Error Handling

Clojure prefers structured exceptions using `ex-info`.

```clojure
(try
  (slurp "missing.txt")
  (catch java.io.FileNotFoundException e
    (log/error "File not found" {:path "missing.txt"})
    nil)
  (catch Exception e
    (log/error "Unexpected error" {:error e})
    (throw e)))
```

**Creating custom errors with data:**
```clojure
(throw (ex-info "Validation failed"
                {:field :email
                 :value input
                 :reason "Invalid format"}))
```
Catch `ex-info` data using `ex-data`:
```clojure
(try
  (some-operation)
  (catch Exception e
    (when-let [data (ex-data e)]
      (log "Field" (-> e ex-data :field)))))
```

---

## Java Interop

You *will* interact with Java (Spring, Hibernate, JDK). Clojure has two main syntaxes:

### Static Methods
```clojure
;; Class/method(args)
(java.time.LocalDate/now)
(java.util.Collections/singletonList 1)
```

### Instance Methods
```clojure
;; (.method obj args...)
(.getName user)
(.toUpperCase "hello")

;; .-field on instance
(.getLength string-obj)
```

### Creating Java Objects
```clojure
;; New Class/args...
(Object.)
(String. "hello")
(java.util.HashMap.)
```

### Using the Threading Macro with Interop
When chaining Java method calls, use `->` or `->>`:
```clojure
(-> user
    (.getName)
    (.toUpperCase))
```

---

## Anti-Patterns to Avoid

### 1. Using Atoms for Accumulation
Don't mutate an atom inside `doseq`. Use `reduce`.
```clojure
;; BAD
(defn bad-sum [nums]
  (let [sum (atom 0)]
    (doseq [n nums]
      (swap! sum + n))
    @sum))

;; GOOD
(defn good-sum [nums]
  (reduce + nums))
```

### 2. Nested Null Checks
Use `some->` or destructuring.
```clojure
;; BAD
(if user
  (if (:address user)
    (if (:zip (:address user))
      (:zip (:address user)))))

;; GOOD
(some-> user :address :zip)
```

### 3. Infinite Recursion
Always use `recur` for tail-call optimization.
```clojure
;; BAD — will cause StackOverflowError
(defn bad-count [n]
  (if (zero? n) 0 (inc (bad-count (dec n)))))

;; GOOD — compiles to a loop, no stack growth
(defn good-count [n]
  (loop [x n]
    (if (zero? x)
      0
      (recur (dec x)))))
```

### 4. Forgetting `recur` in Tail Position
If the recursive call is in tail position, you *must* use `recur` or the JVM will blow the stack on large inputs.

### 5. Realizing Infinite Lazy Sequences
Clojure's `range`, `iterate`, `cycle`, `repeat`, and `repeatedly` return **lazy infinite sequences**. Realizing them (forcing all elements into memory) hangs the JVM or causes an `OutOfMemoryError`.

Direct realization is obvious but accidental realization through a transitive operation is easy to miss:

```clojure
;; BAD — direct realization, hangs forever
(count (range))
(vec (iterate inc 0))

;; BAD — indirect realization via json/str/print
(def naturals (iterate inc 0))
(cheshire.core/encode naturals)   ; json serializer walks the whole seq — hangs
(str naturals)                    ; calls .toString() on a LazySeq — hangs
(println naturals)                ; same — hangs

;; BAD — clojure.core functions that realize the whole seq
(last (range))                    ; tries to reach the end — hangs
(sort (range))                    ; must see all elements to sort — hangs
(reverse (range))                 ; same — hangs
```

Always bound infinite sequences with `take`, `take-while`, or use eager alternatives:

```clojure
;; GOOD — bound with take before any realization
(take 10 (range))                 ; => (0 1 2 3 4 5 6 7 8 9)
(take 5 (iterate #(* % 2) 1))    ; => (1 2 4 8 16)

;; GOOD — use a finite range when you know the bound
(range 100)                       ; => (0 1 2 ... 99)

;; GOOD — when passing to code that may serialize/print,
;;        always realize a bounded slice first
(let [first-100 (vec (take 100 (iterate inc 0)))]
  (cheshire.core/encode first-100))
```

**Danger zones** — functions and contexts that silently realize the full sequence:
- JSON serializers (`cheshire`, `jsonista`, `clojure.data.json`)
- `str`, `println`, `prn`, `print` on a lazy seq value
- `count`, `last`, `sort`, `reverse`, `reduce`, `into`
- Any Java method that accepts `Iterable` or `Collection` and iterates it to the end
- REPL pretty-printers — the REPL itself will try to print the result, hanging the session

**Rule of thumb:** if a value *might* be an infinite lazy seq, call `take` on it before doing anything else with it.

---

## Syntactic Gotchas

### Anonymous Functions Cannot Be Nested

The `#(...)` reader macro cannot be nested inside another `#(...)`. The reader cannot disambiguate which `%` arguments belong to which function. Use `fn` for the outer (or inner) function instead.

```clojure
;; BAD — reader error: "Nested #() are not allowed"
(map #(map #(* % 2) %) data)

;; GOOD — use fn for the outer function
(map (fn [row] (map #(* % 2) row)) data)

;; GOOD — use fn for the inner function
(map #(map (fn [x] (* x 2)) %) data)

;; GOOD — use fn for both when nesting is deep
(map (fn [row] (map (fn [x] (* x 2)) row)) data)
```

**Rule of thumb:** as soon as you feel the urge to nest `#()`, reach for `fn` for at least the outer level.

---

## Tooling Survival

### clj-nrepl-eval
The agent's gateway to the live JVM.

**Basic Usage:**
```bash
# Evaluate inline expression (must be quoted)
clj-nrepl-eval -p 7888 "(+ 1 2 3)"

# Use --file for complex expressions (avoids shell escaping hell)
clj-nrepl-eval -p 7888 --file /tmp/eval.clj
```

**Discovery Commands (evaluate these to learn the API):**
```clojure
;; List public vars in a namespace
(clojure.repl/dir clojure.string)

;; Get documentation for a function
(clojure.repl/doc map)

;; Search for functions by name
(clojure.repl/apropos "split")

;; Get function signatures (arglists)
(:arglists (meta #'reduce))

;; Read source code (if available)
(clojure.repl/source filter)
```

**Shell Escaping Warning:**
Clojure contains characters that shells eat: `!`, `#`, `@`, `(`, `)`.
**NEVER** pass complex Clojure code as an inline argument.
```bash
# BAD — shell mangles ! and #
clj-nrepl-eval -p 7888 "(map #(+ % 1) [1 2 3])"

# GOOD — write to file first
echo "(map #(+ % 1) [1 2 3])" > /tmp/lw.clj
clj-nrepl-eval -p 7888 --file /tmp/lw.clj
```

### clj-paren-repair
Fixes mismatched delimiters in Clojure source files. Use it whenever the compiler complains about EOF, unexpected token, or delimiter errors.

```bash
# Fix a single file
clj-paren-repair src/core.clj

# Fix multiple files
clj-paren-repair src/core.clj src/util.clj

# Check stdin
echo '(defn hello [x] (+ x 1)' | clj-paren-repair
```

**When to use:**
- REPL evaluation fails with "unexpected token" or "EOF"
- Editing multiple files and want to ensure well-formedness
- You get a cryptic compiler error

---

## Namespace Template

This is the standard boilerplate for a Clojure namespace.

```clojure
(ns project.module
  ;; Standard library requires (sorted alphabetically)
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [project.core :as core]
   [project.db :as db])

  ;; Java imports
  (:import
   (java.time LocalDate)
   (java.util List Map)))

;; Enable reflection warnings (catches missing type hints)
(set! *warn-on-reflection* true)

;;; Public API
(defn do-something
  "Short description of what this does.

   Args:
     input - description of input
     opts  - map of options

   Returns:
     description of return value

   Example:
     (do-something [1 2 3])
     ;; => 6"
  [input {:keys [verbose] :or {verbose false}}]
  (when verbose (println "Processing input"))
  (reduce + input))
```

---

## Research Note

The REPL-first approach is grounded in research showing LLMs with compiler access outperform model-only baselines by 5.3–79.4 percentage points (Kjellberg et al., 2026). The Clojure REPL serves as a runtime oracle that grounds the AI in executable truth.

*Reference: arXiv:2601.12146v1*
