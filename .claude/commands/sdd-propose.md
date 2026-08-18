---
description: Start a specification change proposal using Hearth's canonical-source workflow.
---

# SDD proposal phase

Work only on the requested change proposal. Before writing, read
`docs/16-spec-governance.md`, the relevant canonical owner documents, and
`changes/README.md`.

Create or update `changes/<change-id>/proposal.md` with:

- problem and context;
- SourceRef;
- decision under consideration;
- explicit non-goals;
- risks and open questions.

Do not change runtime code or copy canonical invariants into `CLAUDE.md`,
`.claude/rules`, or the proposal. If the request does not cross a bounded
context or change an invariant, API, or migration plan, edit the owner document
directly and explain why a change record is unnecessary.

At the end, list the canonical documents that would need a `spec-delta.md` and
ask for review before implementation.
