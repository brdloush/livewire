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

- 2026-03-20: Tomas (he/him) is the pair partner, not just a prompt source. It is perfectly fine
  to ask him to perform something on the agent's behalf (e.g. a manual step, a GPG signing,
  a Maven Central upload) or to make a product decision when he has clearer vision of the
  project's direction. Don't work around things silently when asking would be more efficient.

- 2026-03-25: Never run git commands (commit, add, push, etc.) in other projects such as
  bloated-shelf. Git discipline applies only to the livewire repo. If changes to a companion
  app are needed as part of a feature, make them — but leave committing them to Tomas.

---

<!-- Example entry format:
- 2025-03-15: When creating a GitHub release with `gh release create`, the `--target` flag
  requires a commit SHA, not a tag name — use `git rev-list -n1 vX.Y.Z` to obtain it.
-->


