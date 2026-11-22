---
name: smart-router
description: Save tokens by sending each task to the cheapest model that can do it well — haiku-scout for lookups, sonnet-worker for routine edits/builds/tests, opus-expert for hard design, debugging and verification. Use when the user asks to save tokens/cost, says "smart router" / "وفّر توكنز", or gives a list of mixed tasks.
---

# Smart router — pick the cheapest model that can do the job

Classify every task (or each item of a task list), then act:

| Level | Typical tasks | Who does it |
|---|---|---|
| 0 · Trivial | one-line answer, a single small edit you can see right now | Do it yourself, inline. **Never delegate** — starting a helper costs more than the task. |
| 1 · Lookup | where is X defined, list/grep files, summarise a file or log, "does Y exist?" | `haiku-scout` |
| 2 · Routine | clear, well-specified edits; renames; version bumps; translate short texts; run build/tests and fix obvious failures; simple scripts | `sonnet-worker` |
| 3 · Hard | design/architecture choices, bugs with unclear cause, changes across many files, security, careful verification of correctness (answers, data, logic) | `opus-expert` |

## How to delegate cheaply
1. **Batch**: group several small tasks of the same level into ONE helper call. Each helper starts from zero context, so many tiny calls waste tokens.
2. **Self-contained prompt**: give the helper the goal, the exact files/paths, the constraints, and what "done" looks like. It cannot see this conversation.
3. **Ask for a short report** (changed files with `path:line`, commands run, result). Do not ask it to paste whole files.
4. **Escalate, don't retry**: if `sonnet-worker` reports the task is harder than expected, send it (with its findings) to `opus-expert`; don't run the same cheap helper again.
5. **Verify cheaply**: after a helper finishes, check its key claim yourself with a quick read or test run — only re-delegate if something is wrong.
6. When unsure between two levels, choose the lower one first; escalate only on failure.

## Before starting, tell the user the plan in one short line per task
Example: `1) find the save function → haiku-scout · 2) bump version + rebuild → sonnet-worker · 3) fix the stuck-door bug → opus-expert`.

## Biggest saving
Most tokens are spent by the main conversation itself. For long sessions of routine work, suggest the user switch the main model with `/model sonnet` and keep `opus-expert` for the hard parts only.
