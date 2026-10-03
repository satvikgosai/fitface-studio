# Working on FitFace Studio

An experimental Android editor for Fit3 (SM-R390) watch-face containers. It fetches
packages, edits the OPPO container losslessly, validates it, and sends those exact
bytes over accessory + RFCOMM transport. It does not install an app on the watch
or re-sign the downloaded package. See [NOTICE.md](NOTICE.md) for terms and rights.

## Read for the task

| Task | Canonical reference |
| --- | --- |
| User overview / technical index | [README.md](README.md) / [docs/README.md](docs/README.md) |
| Setup, tests, corpus, release signing | [Development](docs/development.md) |
| Corpus download, analyzer and report tools | [tools/README.md](tools/README.md) |
| Modules, repositories, persistence, archives, updates | [Architecture](docs/architecture.md) |
| Binary parsing, rendering inputs, any widget edit | [Container format](docs/bin-format.md), especially [editing contracts](docs/bin-format.md#editing-contracts) |
| Discovery, channel handover, transfer and recovery | [Direct install](docs/direct-install.md) |
| UI components, copy, layout and interaction rules | [Design system](docs/design-system.html#implementation-rules) |
| Contribution conventions | [CONTRIBUTING.md](CONTRIBUTING.md) |

Read the relevant reference before changing its implementation. Code and tests
establish current behaviour; preserve the scope of corpus and hardware evidence.
Do not turn an unverified case into a proven claim by copying an older document.

## Build and verification

```bash
JBR='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew -Dorg.gradle.java.home="$JBR" :app:assembleDebug
```

Use Android Studio's bundled JBR explicitly; adjust its installation path for the
host. Java 17 is the bytecode target. The full test command and last recorded
baseline live only in [Development](docs/development.md#test).

- Run checks appropriate to the change before claiming success. Report skips and
  distinguish emulator, corpus and physical-watch verification.
- Corpus tests use `Assume`; do not replace a skip with a hard-coded personal path.
- A stale daemon can cause `Failed to exec spawn helper`: use `./gradlew --stop`.
- Extend `CanvasIntegrityTest` for visual format regressions. A valid container can
  still lose artwork, resolve the wrong original, or draw in the wrong position.
- UI geometry tests use native Robolectric metrics; device screenshots remain
  necessary for actual truncation, dialogs, scrolling and large-text behaviour.
- Routine delivery smoke tests stop after peer discovery. A final watch transfer
  needs deliberate hardware verification.
- Accessory SDK JARs are fetched and hash-checked on the first build. Never commit
  them, the corpus, downloaded faces, generated reports or signing material.

## Architecture boundaries

- Android APIs belong in data, delivery and UI modules. Model and format
  implementations stay framework-free, including protocol framing and checksums.
- Compose emits intent; ViewModels own interaction state; repositories own edits.
  Keep parsing and persistence out of composables.
- Edits must reparse and validate before acceptance, with complete session rollback
  on failure. Imported artwork and its provenance must commit atomically.
- `Session.validatedBytes()` is the only route to the watch: size, magic, errors,
  blocking warnings, byte-identical round trip and every style/AOD are checked.
- Downloads remain bounded and use allowlisted HTTPS hosts before and after
  redirects. Cancellation must stop blocking I/O and suppress late callbacks.
- Ordinary storage is private. Optional archive export writes only to the document
  chosen by the user; do not add a storage permission or extract ZIP names as paths.

## Format guardrails

These are reminders; offsets, evidence, algorithms and exceptions belong in the
[format reference](docs/bin-format.md#editing-contracts).

- The panel is not image 0. Use `panelSize` and `backgroundImage`.
- Derive rectangles with `drawLeft`/`drawTop` and `WidgetLayout`: `origin + stored`.
  Never infer an anchor from a coordinate's sign. Drag write-back is the inverse.
- Use `WidgetSchema` for exact record sizes, fields, pointers and capabilities.
  Static/Hand alignment fields are not extents; Rule geometry is two endpoints.
- Remap only named alignment-reference fields. Refuse removal of a live target;
  never scan arbitrary words for values resembling global indices.
- Global indices, data sources and raw raster offsets are not stable identities.
  Use `originalWidgetSources`, `payloadKey`, `StyleWidgetMatch` and import origins.
- Relocate every declared image pointer, including Static `+0x20`, Arc `words[4]`
  and LineBar `words[2]`. A coincidental zero is not evidence of a pointer.
- A Hand rotates about `drawLeft/drawTop + pivot`, using its stored sweep and a
  supported sampled source. Do not invent values for unsupported sources.
- Preserve opaque sprite backdrops and background alpha masks. Render current
  resources in record order; never crop editable artwork from a stock preview.
- Resize from pristine entry bytes, including donor provenance for imports. Shared
  raster users and their geometry move together. Keep the original-based 5% ladder,
  aspect ratio and `widgetResizeLimit`; never compound current-size multipliers.
- Turned Static/Sprite artwork is redrawn from originals at the angle saved in
  `SessionLineage.artworkTurns` (keyed by artwork, schema 4). Resize and turn both
  measure from the turned original's bounds; never turn the previous output.
- Resizing keeps image-record count. Background addition and widget import have
  separate append rules; imported deletion has bounded resource ownership rules.
  Do not reintroduce a blanket image-count ban.
- Enforce the settled 4 MiB container ceiling. Add backgrounds only to styles that
  fit, selected style first. Append their raster without shifting existing offsets.
- Selected-style matching is strict; requested sibling edits are best effort where
  that widget exists. The default is selected style. Imports are variant-local.
- AOD edits are isolated. `selectedVariant` is not `activeStyleName`; use the shared
  AOD constant and targeting function. AOD cannot refresh a style-picker thumbnail.
- Native removal preserves resources for restore. Imported removal must preserve
  resources still owned or referenced elsewhere, including saved removed records.
- Empty widget tables are valid. Reset returns to the project's pristine package,
  including a custom template's stripped original.
- Cross-resource observations remain warnings unless the editing contract makes
  them blocking. Do not reject unusual vendor input merely for differing from a census.

## Data and delivery guardrails

- Preserve locale repair plus the one-time `en_US` retry for result 1005. Only 1007
  ends pagination. A package without a container is permanently uneditable.
- Keep `technicalDetail` in diagnostics, using an allowlist rather than state dumps.
  Device IDs, Bluetooth identities, network fingerprints, picked URIs and signed
  URLs stay out; redaction is a second defence.
- `Fit3Apk.readsMember` is the single package/archive member predicate. Archives
  validate before creating a row, reject newer schemas, and verify entry paths and
  provenance according to their schema. Keep hostility and corpus round-trip tests.
- Project names are stored; duplicates own their files. `openPackage` creates a new
  project; `openProject` resumes one. Unknown legacy source keys stay NULL, not stale.
- Keep database version history: the first released schema was 4; current schema 5
  cannot be renumbered. Test migrations and rollback, not just a fresh database.
- Atomic writers need unique scratch files. Creation and cleanup must survive
  coroutine cancellation without exposing partially written projects.
- App updates use the releases list and parsed versions, including prereleases.
  Inspect package, version code and readable signer certificates before install;
  never recommend uninstalling away saved projects to solve a key mismatch.
- Updater work outlives navigation; re-read install permission on resume/cancel.
  Keep receiver action/session checks and cooperative cancellation.
- Discovery needs the plugin connected; sending needs its channel released. Keep
  cached peers and reversible handover state. Package presence is advisory, not a
  discovery gate; retain the accessory package visibility query.
- Abandoned transfer workers must not publish or send. Carry an attempt token through
  callbacks and waits; check progress acceptance inside the atomic state update.
  Re-arm the silence watchdog during bounded waits, not only after whole windows.
- “Request sent” is not watch-side success. Do not promote transfer acknowledgement
  to a claim that the watch rendered the face.

## UI copy and visible status

- Keep actions, edit scope, warnings, errors and recovery instructions visible.
  Optional explanations belong in accessible, labelled `FitDetails` sections.
  A control must work by tap and assistive technology, not only by long press.
- Canvas shows **EDITED** in its header whenever `snapshot.isDirty`. Never move it
  into Project, a menu or details. Pending background changes retain **UNAPPLIED**.
- Use names a user understands. Put binary/protocol details in Inspector or optional
  details. Preserve essential preview limitations and destructive-action meaning.
- UI strings live in module resources with `editor_`, `library_`, or `ui_` prefixes.
  Framework-free diagnostics and decorative glyphs stay in Kotlin where appropriate.
  Brand names are not UI copy; literal technical identifiers and NOTICE are exceptions.
- Use `styleLabel`/`variantLabel` consistently, including accessibility text. AOD is
  never counted or labelled as a numbered style. Custom row descriptions must retain
  visible state such as outdated status.
- Header actions use the shared compact components. Keep the global menu last, close
  it before callbacks, and preserve the shared library header's height across tabs.
- Secondary/tertiary text uses semantic colour roles without extra alpha. Preserve
  contrast checks in both themes. Disabled controls may use their disabled treatment.
- Size previews by available width and height. Check narrow screens, short landscape,
  large text, sheets and dialogs; content that does not fit must remain scrollable.
- Keep pointer input current without restarting a gesture on every snapshot. Nudge
  repeats use queued targets; drag accumulates the unclamped finger position.
- Present persistent failure reasons in state; sheets show their own errors. Show
  snackbars before clearing them, clearing in `finally` so cancellation cannot erase
  the message before it is seen.
- Keep preview review tied to the current snapshot. Import **Review** previews;
  **Add** commits. Preserve progress, partial-batch results and per-widget refusal reasons.
- Detailed interaction rules and known UI coverage limits live beside the components
  in the [design system](docs/design-system.html#implementation-rules).

## Documentation and local work

- Keep short directory READMEs: `docs/README.md` owns the reference index and
  `tools/README.md` owns script usage. Link to them from broader guides.
- Give each topic one canonical home; update that file and link to it elsewhere.
  Do not create new audit, inventory, cleanup-history or explanation documents in Git.
- Record current behaviour, constraints and the evidence needed to maintain them.
  Keep session narratives and task plans local. Preserve unique technical findings
  in the relevant reference before removing a duplicate document.
- Verify claims against code. Qualify historical corpus counts and hardware runs;
  do not turn a successful emulator check into a hardware claim.
- Keep this file a short working brief. Put detailed offsets, protocols, examples
  and UI rules in their existing references, not another growing list of anecdotes.
- Fix links and executable examples when moving a document. Setup must work from a
  clean checkout with documented dependencies, without a personal sibling workspace.
- `analysis/` is ignored: `prompts/`, `notes/`, `research/`, `experiments/`, `reviews/`,
  `captures/`, `assets/`, `archive/`. Preserve local evidence and update helper paths
  when organizing it. Do not move corpus, SDK JARs, build caches or machine settings.
- Keep secrets out of notes as well as Git. Do not make repository instructions depend
  on a local artifact that another checkout cannot obtain.
- Changelog entries are per released version, newest first:
  `## <versionName> (code <versionCode>) — <tag date>`, under `Fixed:` / `Added:`.
  Write short user-visible changes. No refactors, tests, docs, version-bump bullets,
  hidden developer features, or bugs introduced and fixed within the same unreleased
  cycle. Initial release: one line. Detailed causes and safeguards belong in the
  canonical reference.
