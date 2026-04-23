# Lessons Learned

This file is a living capture buffer for session observations and corrections.
After any user correction, append a brief entry here immediately.
At the start of each session, read this file and take its lessons into account.

Stable, recurring lessons should be promoted into named sections of `AGENTS.md`
and removed from here.

---

<!-- All lessons from this session promoted to AGENTS.md on 2026-03-19:
  - lw/ alias in source files → Clojure gotchas section
  - README.md version strings → Release process step 9
  - bb install after bb bundle → Release process step 8 warning
  - SKILL.md on commit → already covered by Feature Delivery Checklist
-->

---

<!-- All lessons from 2026-03-20 to 2026-03-29 promoted to AGENTS.md:
  - Tomas as pair partner / ask don't work around → already in collaborative tone of AGENTS.md
  - Git discipline is livewire-only (bloated-shelf) → new "Git discipline is livewire-only" section
  - "Yes" to task ≠ "yes" to push → strengthened Git discipline section
  - git status before release → Release process Step 0
-->

---

<!-- Example entry format:
- 2025-03-15: When creating a GitHub release with `gh release create`, the `--target` flag
  requires a commit SHA, not a tag name — use `git rev-list -n1 vX.Y.Z` to obtain it.
-->

- 2026-04-23: jshell interactive mode requires `;` at the end of every statement. When
  giving the user jshell snippets to paste at a live `jshell>` prompt, always include the
  trailing semicolon. Script files (loaded via `/open`) do not require them, but interactive
  input does.


