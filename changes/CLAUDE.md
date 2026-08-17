# Change Records

This file applies only while working under `changes/`.

`changes/` records a proposed or completed specification change. It is not a
source of current architecture facts. For any conflict, read
`docs/16-spec-governance.md` and the relevant canonical owner document before
editing a summary or implementation plan.

## Required shape

A change that crosses a bounded context or changes a domain invariant, API, or
migration plan contains:

```text
changes/<change-id>/
  proposal.md
  spec-delta.md
  tasks.md
```

Use the phases exposed by `/sdd-propose`, `/sdd-spec`, `/sdd-implement`, and
`/sdd-verify`. Do not create phase-specific `CLAUDE.md` files inside individual
change directories: command invocation selects the phase without causing
unrelated phase instructions to load automatically.

## Completion rule

A change is not complete merely because its change record is complete. Update
the canonical owner documents first, implement only the accepted tasks, collect
Evidence where runtime behavior exists, then pass `/sdd-verify` before moving
the record to `changes/archive/`.
