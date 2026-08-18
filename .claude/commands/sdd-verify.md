---
description: Validate an SDD change record, canonical-source drift, and document hygiene.
---

# SDD verification phase

Accept an optional change ID as `$ARGUMENTS`. Review the change record and
canonical owner documents before declaring success. Run:

1. `git diff --check`;
2. local Markdown relative-link validation;
3. validation that every fenced `json` example is one valid JSON document;
4. the exact drift check from `docs/16-spec-governance.md`;
5. a credential-like scan for keys, tokens, and private keys;
6. focused runtime tests and contract checks when implementation exists.

Separate failures from checks that are not applicable because the repository is
still in the design phase. Do not edit canonical documents or change records to
make a failed check appear green. Report file and line references for every
failure, and state the evidence used for each passing check.

A change can move to `changes/archive/` only after its owner documents,
implementation tasks, and required acceptance evidence are complete.
