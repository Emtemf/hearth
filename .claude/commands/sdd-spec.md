---
description: Convert an accepted proposal into canonical specification deltas and implementation tasks.
---

# SDD specification phase

Read `docs/16-spec-governance.md`, the accepted proposal, and every canonical
owner document named by the proposal before editing.

Create or update `spec-delta.md` with explicit `ADD`, `MODIFY`, and `REMOVE`
entries, naming each owner path. Update the owner documents first when the
change is accepted; then update only routing or concise summaries in
`AGENTS.md`, `CLAUDE.md`, `README.md`, and `.claude/rules/`.

Create or update `tasks.md` with ordered implementation slices, migration and
API compatibility notes, and executable acceptance evidence. Keep document-only
checks separate from runtime acceptance. Do not invent a second `specs/`
source tree, new owner map, or tool-specific hook contract.

Before handing off, run `git diff --check`, the repository drift check, and any
JSON/link checks described in `tasks.md`. Report unresolved conflicts instead
of silently choosing between owner documents.
