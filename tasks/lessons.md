# Lessons Learned

This file is a living capture buffer for session observations and corrections.
After any user correction, append a brief entry here immediately.
At the start of each session, read this file and take its lessons into account.

Stable, recurring lessons should be promoted into named sections of `AGENTS.md`
and removed from here.

---

- 2026-03-19: README.md contains version strings in both the Maven and Gradle dependency
  snippets — these must be bumped alongside web/index.html and web/getting-started.html during
  the release web-update step (AGENTS.md step 9). Check with `grep -r "X.Y.Z" README.md web/`.

- 2026-03-19: Running `bb install` for the next SNAPSHOT immediately after `bb bundle` overwrites
  the signed release artifacts in `target/provided/`. After `bb bundle`, do NOT run `bb install`
  or any build step until the user confirms the bundle has been uploaded and accepted by Maven
  Central. Explicitly tell the user "do not continue to the next step until Maven Central confirms"
  before bumping the SNAPSHOT version.

- 2026-03-16: After implementing `inspect-all-entities`, committed the code without updating
  SKILL.md — the user had to point it out. Any new user-facing function or CLI script must
  include SKILL.md documentation in the same commit, not as a follow-up. Before committing a
  feature, explicitly check: CLI table, API reference table, and any relevant guidance section.

---

<!-- Example entry format:
- 2025-03-15: When creating a GitHub release with `gh release create`, the `--target` flag
  requires a commit SHA, not a tag name — use `git rev-list -n1 vX.Y.Z` to obtain it.
-->


