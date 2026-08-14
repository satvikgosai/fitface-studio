## What this changes, and why

<!-- If it fixes a bug, say what the bug actually did — a reader should not have to
     guess which bug "the bug" was. -->

## Tests

<!-- The command, the baseline and the corpus setup are in CONTRIBUTING.md. -->

Result:

- [ ] Corpus was present for this run

## Checklist

- [ ] Nothing untracked was added: no `libs/*.jar`, no `corpus/`, no `analysis/`, no
      `.bin` or `.apk`.
- [ ] No vendor branding in UI copy.
- [ ] New user-facing copy is in the module's `res/values/strings.xml`.
- [ ] Docs that describe the changed behaviour were updated in the same PR, and any
      counts quoted in them still hold.

## If this touches `:core:format` or the editing rules

- [ ] [`docs/editing.md`](docs/editing.md) and [`docs/bin-format.md`](docs/bin-format.md)
      are still accurate, and any new rule records the evidence behind it.
- [ ] The invariants in [`docs/architecture.md`](docs/architecture.md#invariants) still
      hold, `CanvasIntegrityTest` passes, and no edit path changed the image-record
      count or left a raster pointer stale.

## Hardware

- [ ] Verified on a real SM-R390 — say which faces and which edits
- [ ] Not verified on hardware <!-- fine, and normal; just say so -->
