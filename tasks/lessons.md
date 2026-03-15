# Lessons Learned

This file is a living capture buffer for session observations and corrections.
After any user correction, append a brief entry here immediately.
At the start of each session, read this file and take its lessons into account.

Stable, recurring lessons should be promoted into named sections of `AGENTS.md`
and removed from here.

---

- 2025-03-15: Before creating a GitHub Release, always verify the version bump commit and
  tag are pushed to origin. `git log origin/main..HEAD` will show any unpushed commits.

<!-- Example entry format:
- 2025-03-15: When creating a GitHub release with `gh release create`, the `--target` flag
  requires a commit SHA, not a tag name — use `git rev-list -n1 vX.Y.Z` to obtain it.
-->


