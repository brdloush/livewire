# Variant Discipline

## Rule

When you present alternatives (variants, options, approaches) for the user to choose from, **do NOT analyze, benchmark, or explore any variant the user did not choose**. Only investigate the one they pick.

## Examples

- You present 3 N+1 fix variants → the user says "test B" → **only test B**. Do not trace A, C, D, E, or "the others" unless the user explicitly asks.
- You present 2 API design options → the user picks A → **implement A**. Do not benchmark A vs B, prototype B, or suggest "what about C?" unless the user signals they want it.
- The user says "test the rest" or "what about the other two" → now explore those.

## Why

- Each variant you benchmark fires real queries against the live database. That's wall-clock time, connection pool hits, and potential side effects.
- Analysis bloats the conversation context, making it harder to read and reason about.
- The user chose a variant for a reason — likely because the trade-offs made sense to them in context you may not share (team conventions, time pressure, codebase familiarity).

## Signal the user

If a variant you tested has a surprising result that makes another variant suddenly look like a better fit, mention it briefly and let the user decide. Don't preempt — just flag.

## Exception

If the user explicitly asks for comparison ("test all three," "show me the rest," "what about the others?"), then explore the other variants. But that's on them, not on you.
