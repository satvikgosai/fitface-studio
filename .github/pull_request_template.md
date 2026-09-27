## What this changes

<!-- If it fixes a bug, say what the bug actually did — a reader should not have to
     guess which bug "the bug" was. -->

## Tests

<!-- The command, the baseline and the corpus setup are in docs/development.md. -->

Result:

- [ ] Corpus was present for this run

## Checklist

- [ ] No local artifacts were added: no `libs/*.jar`, no `corpus/`, no `analysis/`, no
      `.bin` or `.apk`.
- [ ] No vendor branding in UI copy.
- [ ] New user-facing copy is in the module's `res/values/strings.xml`.
- [ ] Docs that describe the changed behaviour were updated in the same PR, and any
      counts quoted in them still hold.

## If this touches `:core:format` or the editing rules

- [ ] [Format and editing contracts](../docs/bin-format.md#editing-contracts) remain
      accurate; new rules name their corpus or hardware evidence.
- [ ] [Architecture invariants](../docs/architecture.md#invariants) hold, relevant
      `CanvasIntegrityTest` checks pass, and image-count/pointer changes follow the
      specific operation's contract.

## Hardware

- [ ] Verified on a real SM-R390 — say which faces and which edits
- [ ] Not verified on hardware <!-- fine, and normal; just say so -->
