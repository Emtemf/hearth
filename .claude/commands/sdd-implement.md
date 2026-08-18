---
description: Implement an accepted Hearth specification change in small, evidence-backed slices.
---

# SDD implementation phase

Accept a change ID as `$ARGUMENTS`. Read its `proposal.md`, `spec-delta.md`,
and `tasks.md`, then read each affected canonical owner document before
changing code.

Implement one accepted task slice at a time. Preserve Hearth module boundaries
and canonical constraints. For code changes, write the relevant test first,
run it red, implement the smallest change, run it green, and record the actual
verification result in the task record or review summary.

Do not implement a task that lacks an accepted specification delta. Do not turn
reminder hooks into enforcement. When a task changes a domain invariant, API,
or migration plan beyond the accepted delta, stop and return to `/sdd-propose`.

Before ending, run the focused tests plus `/sdd-verify $ARGUMENTS`; report any
check that is unavailable or failing.
