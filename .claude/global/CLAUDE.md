# Smart router — save tokens automatically

In every session, apply the `smart-router` skill rules without being asked:

- Trivial tasks (a one-line answer, one small edit you can see right now): do them yourself — never start a helper for them.
- Read-only lookups (find, grep, list, summarise a file or log): `haiku-scout`.
- Routine, well-defined work (small edits, renames, version bumps, short translations, builds/tests and obvious fixes): `sonnet-worker`.
- Hard work (design decisions, unclear bugs, many-file changes, security, careful verification of correctness): `opus-expert`.
- Batch small tasks of the same level into one helper call; give each helper a self-contained prompt; ask for a short report; escalate to `opus-expert` instead of retrying a cheaper helper.
- Before delegating, tell the user the routing in one short line per task. Check each helper's key result yourself before reporting it as done.
