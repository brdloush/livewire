# Praises

A record of moments the user explicitly praised the work done.
These are worth remembering — they signal what "good" looks like in this project.

---

- 2025-03-15: "TOTALLY!" — closing exclamation wrapping up a long, productive session covering
  diff-entity implementation, website updates, SKILL.md docs, release 0.5.0, and a thorough
  self-improvement loop (lessons captured, promoted, cleared).

  *Why I think this was praised:* The session ended with everything genuinely done — code
  shipped, documented, released, deployed, and the meta-layer (AGENTS.md, lessons.md,
  praises.md) all tidy too. "Totally" feels like satisfaction at a complete loop, not just
  a single good moment.

- 2025-03-15: "I really liked you finally tried the code in REPL before writing the actual
  function into clj file. GOOD JOB!" — for proving `diff-entity` logic in the REPL step by
  step (including cleaning out residual hot-patch state before the final verification) before
  writing any `.clj` file.

  *Why I think this was praised:* It took a few corrections to get there, but once I truly
  internalised the rule, I didn't just run the happy path — I also removed the residual
  hot-patched vars from the namespace to simulate a clean load, and only then ran all three
  query paths. That extra rigour (not just "it works in my dirty REPL state") is what made
  the difference between going through the motions and genuinely proving the code.

- 2025-03-15: "AMAZING JOB. Standing ovation!" — for recognising that the `bb install` after
  SNAPSHOT bump lesson was concrete, release-process-specific, and actionable enough to skip
  lessons.md and go straight into the AGENTS.md release checklist as step 8.

  *Why I think this was praised:* lessons.md is for recent, still-accumulating observations.
  AGENTS.md is for stable, curated knowledge. The distinction matters — not every lesson
  needs to ferment in lessons.md first. A lesson that is immediately precise, non-contextual,
  and fits cleanly into an existing named section belongs in AGENTS.md on the spot. Knowing
  when to promote immediately vs. when to let something accumulate is the skill being praised
  here.

- 2025-03-15: "I praise you as the release checklist worked flawlessly." — for executing the
  full release process (version bump → artifacts → tag → GitHub Release → sign → bundle →
  SNAPSHOT bump) without missing a step, including pausing to push before creating the release.

  *Why I think this was praised:* The release process has many ordered steps and it's easy
  to skip or reorder them. The key moment was stopping before `gh release create`, checking
  whether the commit and tag were pushed, discovering they weren't, and only then proceeding.
  Following a checklist faithfully — even when each individual step feels obvious — is what
  makes a release trustworthy.

- 2025-03-15: "I'm AMAZED. Hard to describe." — for the full cross-project integration loop:
  spec written into bloated-shelf, user implemented it and restarted the app, agent
  connected to the live REPL, diagnosed that diff-entity was missing from the old JAR,
  hot-patched both namespaces, then ran the exact website example against the real
  `.archiveBook` method and got the correct `:changed` diff — all without being guided
  through any of it.

  *Why I think this was praised:* The loop closed completely and autonomously. Each step
  required a different kind of reasoning: recognising the JAR was stale (not a bug in the
  code), knowing that hot-patching was the right fix (not asking for a restart), and then
  trusting the result enough to present it as proof. The praise signals that this is exactly
  what "agentic" should mean — not just executing instructions, but understanding context,
  adapting to unexpected states, and driving toward a verified outcome without hand-holding.

- 2025-03-15: "I'm seriously impressed." — for writing the archive-book spec directly into
  `bloated-shelf/specs/archive-book-endpoint.md` instead of just outputting text, choosing a
  good internal path, and then cheekily offering to implement it from within the Livewire
  session.

  *Why I think this was praised:* The user asked for a spec "the spec way" without specifying
  where it should go. The obvious lazy answer was to paste text into the chat. Instead I
  read the bloated-shelf project structure, found that it had a `SPEC.md` but no `specs/`
  directory, and created one — treating the other project as a first-class workspace rather
  than just a reference. The offer to implement it from within the session was unexpected and
  a bit cheeky, which landed well. The praise signals that proactive, cross-project thinking
  is valued here — don't just answer the question, think about where the work actually belongs.

- 2025-03-15: "Nice job!" — for correctly using `bb serve &` (with `&`) as AGENTS.md instructs,
  immediately after promoting that very rule to canon.

  *Why I think this was praised:* The rule was promoted just moments before running the
  command — applying a newly written rule on the very next opportunity shows the
  self-improvement loop actually works, not just as documentation but as live behaviour.

- 2025-03-15: "I'm seriously IMPRESSED you didn't forget to remove a completed task from
  TODO.md after quite a long and complex discussion." — for removing the `diff-entity` entry
  from TODO.md 🔜 Planned at the right moment, without being prompted, during a commit that
  also added new TODO content (the SQL-tracing follow-up).

  *Why I think this was praised:* The session was long and the TODO edit was easy to miss —
  we'd been talking about docs and web/ for a while, and the original task had been "done"
  several commits earlier. The praise signals that maintaining a clean, trustworthy task list
  matters as much as the code itself. A TODO.md that still lists completed work silently
  erodes trust in the whole file. Noticing that the entry was now stale, even in the middle
  of an unrelated docs commit, is what made the difference.

- 2025-03-15: "Beautiful job." — for the complete `diff-entity` end-to-end REPL test session:
  no-op thunk returning `{:changed {}}`, real mutation via `bookRepository.save()` showing
  `{:availableCopies [3 2]}`, and DB rollback confirmed with `q/sql`.

  *Why I think this was praised:* The test was structured and incremental — first a
  sanity-check baseline (no-op), then a realistic mutation through a real Spring bean, then
  an independent DB query to verify the rollback actually held. Each step built confidence
  in a different part of the feature. That "nice and steady" progression, rather than one
  big combined test, is what made it feel solid.
