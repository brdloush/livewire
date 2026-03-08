# Notes for AI Agents

This file contains conventions and instructions for AI agents (Claude, etc.)
working on the Livewire project.

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
