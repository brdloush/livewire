# clj-nrepl-eval — Temp File Rule

**Always write Clojure expressions that contain special characters to a temp file
before passing them to `clj-nrepl-eval`. Inline shell quoting is fragile.**

---

## The Problem

Clojure characters `!`, `?`, `->`, `->>`, `#()`, and nested parentheses fight
with bash quoting. Even a simple `(hq/reset-all!)` can silently change to
`(hq/reset-all)` or trigger glob expansion — producing wrong results or
`Execution error` at the REPL level.

---

## The Rule

**Default to a temp file. Inline is reserved for ultra-trivial evals only** — single
forms with zero arguments, zero nesting, no special characters, no threading, no
multi-step logic. Examples: `(lw/info)`, `(lw/find-beans-matching "Repo")`.

**Checklist — if ANY of these are true, use a temp file:**

| Check | Example | Action |
|---|---|---|
| Contains `!` | `(hq/reset-all!)` | Temp file via wrapper |
| Contains `?` | `(some? value)` | Temp file via wrapper |
| Contains `->` or `->>` | `(-> x .getFoo .bar)` | Temp file via wrapper |
| Contains `#()` | `(map #(.getName %))` | Temp file via wrapper |
| Has `fn` | `(fn [] ...)` | Temp file via wrapper |
| Uses `doall`/`vec`/`mapv` | `(doall (.findAll repo))` | Temp file via wrapper |
| Nested `()` | `(let [x (some-fn)] ...)` | Temp file via wrapper |
| Multi-line | `(do \n  (f) \n  (g))` | Temp file via wrapper |
| Has function arguments | `(lw/bean "bookService")` | Temp file via wrapper |
| Has `let`/`doseq`/`mapv` | `(let [res ...] ...)` | Temp file via wrapper |
| **Ultra-trivial** | `(lw/info)` | Inline OK |

**When in doubt, use a temp file. It's never wrong.**

---

## The Pattern

### Preferred — wrapper scripts with `--file`

The Livewire wrapper scripts accept a `--file` flag to read the expression
from a file. This handles multiline, special characters, and wrapping
(but works for arbitrary Clojure in `lw-eval`).

```bash
# 1. Write the expression to a temp file
cat > /tmp/lw-XXXXX.clj << 'EOF'
(hq/reset-all!)
EOF

# 2. Evaluate it via a wrapper script
lw-eval --file /tmp/lw-XXXXX.clj

# 3. Clean up
rm /tmp/lw-XXXXX.clj
```

Wrappers that support `--file`:
- **`lw-eval --file <path>`** — arbitrary Clojure expression (most flexible)
- **`lw-trace-sql --file <path>`** — wraps in `trace/trace-sql`, strips `:result`
- **`lw-trace-nplus1 --file <path>`** — wraps in `trace/detect-n+1 (trace/trace-sql ...)`
- **`lw-jpa-query --file <path> [page] [page-size]`** — JPQL from file
- **`lw-sql --file <path>`** — raw SQL from file
- **`lw-build-entity <name> --file <path>`** — EDN opts from file
- **`lw-build-test-recipe <name> --file <path>`** — EDN opts from file

### Also supported — `clj-nrepl-eval` via stdin pipe

You can also pipe directly to `clj-nrepl-eval` from a file. This bypasses
all wrapper scripts and sends raw Clojure to the nREPL:

```bash
cat > /tmp/lw-XXXXX.clj << 'EOF'
(->> (.getClass (lw/bean "repo"))
     .getMethods
     (map #(.getName %))
     sort)
EOF
clj-nrepl-eval -p 7888 < /tmp/lw-XXXXX.clj
rm /tmp/lw-XXXXX.clj
```

Note: piping with `< file` (not heredoc) works for multiline expressions.
The `--file` flag on `clj-nrepl-eval` itself does **not** exist.

---

## Examples

### ❌ Bad — inline with `!`

```bash
clj-nrepl-eval -p 7888 "(hq/reset-all!)"
# zsh: possible deletion before history word
# bash: bash: !": event not found
```

### ✅ Good — wrapper with `--file`

```bash
cat > /tmp/lw-reset.clj << 'EOF'
(hq/reset-all!)
EOF
lw-eval --file /tmp/lw-reset.clj
rm /tmp/lw-reset.clj
```

### ✅ Good — trace-nplus1 with multiline `--file`

```bash
cat > /tmp/lw-n+1.clj << 'EOF'
(lw/run-as
  ["user" "ROLE_MEMBER"]
  (.getAllBooks (lw/bean "bookService")))
EOF
lw-trace-nplus1 --file /tmp/lw-n+1.clj
rm /tmp/lw-n+1.clj
```

### ❌ Bad — inline with `->` and `#()`

```bash
clj-nrepl-eval -p 7888 "(->> (.getClass (lw/bean \"repo\")) .getMethods (map #(.getName %)) sort)"
# -> is interpreted as shell redirection
# #() may trigger brace expansion
```

### ✅ Good — wrapper with `--file`

```bash
cat > /tmp/lw-methods.clj << 'EOF'
(->> (.getClass (lw/bean "repo"))
     .getMethods
     (map #(.getName %))
     sort)
EOF
lw-eval --file /tmp/lw-methods.clj
rm /tmp/lw-methods.clj
```

### ✅ Inline is fine

```bash
clj-nrepl-eval -p 7888 "(lw/info)"
```

**That's about it.** Even `(q/sql "SELECT 1")` goes to a temp file — string
arguments and `require` are not "ultra-trivial." The bar is intentionally high
because inline quoting traps are subtle and easy to miss.

---

## Surgical fixes

Small, single-step fixes — changing one value or calling one function —
can sometimes be done inline if they meet the ultra-trivial bar above.
For anything that touches multiple forms, even if each form is simple,
use a temp file. The mental overhead of quoting inline is not worth the
few keystrokes saved.

## Linting before running

If you have `clj` (Clojure CLI) or `clj-kondo` installed, run them against
your temp file before piping it into nREPL. This catches syntax errors,
undeclared vars, and typos before they hit the REPL — saving round-trips
and avoiding confusion about whether an error came from the expression or
from shell mangling.

```bash
clj -e "(require '[net.brdloush.livewire.core :as lw])" 2>&1
clj-kondo --lint /tmp/lw-check.clj 2>&1
```

If these tools are not available, the temp file itself is still worth it
— you can copy-paste it into a REPL for testing, and the heredoc with
`'EOF'` quoting guarantees it reaches the nREPL exactly as written.

---

## Why This Matters

1. **Correctness** — shell mangles characters silently; temp file guarantees
   the exact expression is sent to the REPL
2. **Reproducibility** — the temp file content is visible in the conversation
   log, making it clear exactly what was run
3. **Debuggability** — if the expression fails, you can copy-paste the
   temp file content directly into a REPL for testing
4. **Multi-line support** — `cat << 'EOF'` naturally handles multi-line
   expressions without any escaping

---

## Quick Reference

| Expression type | Method |
|---|---|
| `(lw/info)` | Inline: `clj-nrepl-eval -p 7888 "(lw/info)"` |
| `(hq/reset-all!)` | Wrapper: `lw-eval --file /tmp/lw.clj` |
| `(-> x .getFoo .bar)` | Wrapper: `lw-eval --file /tmp/lw.clj` |
| `(map #(.getName %))` | Wrapper: `lw-eval --file /tmp/lw.clj` |
| `(let [res (doall ...)] (:count res))` | Wrapper: `lw-eval --file /tmp/lw.clj` |
| `(fn [] ...)` | Wrapper: `lw-eval --file /tmp/lw.clj` |
| N+1 trace | Wrapper: `lw-trace-nplus1 --file /tmp/lw.clj` |
| SQL trace | Wrapper: `lw-trace-sql --file /tmp/lw.clj` |
