# Notes for AI Agents

This file contains conventions and instructions for AI agents (Claude, etc.)
working on the Livewire project.

---

## Loading new Livewire code into a running app

After editing and installing a new version of Livewire (`bb install`), the
running Spring Boot app still has the **old JAR on its classpath**. Calling
`(require ... :reload)` just re-reads the same old file from that JAR —
it does not pick up the new version.

**Do not waste a turn trying `:reload`. It won't work.**

Instead, push the new definitions directly into the running REPL by evaluating
the relevant `ns` form and function/macro bodies verbatim. Example:

```clojure
;; 1. Switch into (or create) the target namespace
(ns net.brdloush.livewire.query
  (:require [net.brdloush.livewire.core :as core]))

;; 2. Paste the new/fixed definitions
(defn sql [query & params] ...)
```

This hot-patches the live JVM without a restart. The fix is live immediately.
The correct code is already persisted on disk — the eval is just bridging the
gap until the app is next restarted with the new JAR.

---

## REPL results

After evaluating any expression against the live nREPL (port 7888), always
present the result in a human-readable form — don't make the user unroll the
raw MCP tool response.

- **Collections of maps** → markdown table
- **Single map** → markdown table (one row) or inline key/value list
- **Scalar / short value** → inline code in prose
- **Large flat lists** (e.g. bean names) → bullet list or comma-separated prose

Example — query returns a vector of maps:

| id | email | status | active |
|----|-------|--------|--------|
| 1  | test@example.com | PENDING | false |

---
