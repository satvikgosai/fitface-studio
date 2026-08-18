# AGENTS.md — working on FitFace Studio

Read this before changing anything. It is the short brief: what the app is, how
to build it, and which mistakes have already been made and fixed here so you do
not re-make them. The reference material lives in [`docs/`](docs/) — start with
[`docs/README.md`](docs/README.md).

## What this app is

An Android app that downloads a Fit3 (SM-R390) watch-face package, losslessly
edits the OPPO-format container inside it, validates the result, and sends those
exact bytes to a paired watch over the accessory + RFCOMM transport.

It is **not** an APK re-signer, does **not** install anything on the watch as an
app, and does **not** rebuild the downloaded package. Personal, experimental,
never shipping to a store. Non-affiliation and the terms of use are in
[`NOTICE.md`](NOTICE.md); the naming rule that follows from it — brand strings
only as literal technical identifiers, never in UI copy — is in
[`CONTRIBUTING.md`](CONTRIBUTING.md#conventions).

| Property | Value |
| --- | --- |
| Application ID | `dev.fitface.studio` |
| minSdk / target / compile | 28 / 36 / 36 |
| JDK | Android Studio's bundled JBR (Java 17) |

## Build and test

```bash
JBR='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew -Dorg.gradle.java.home="$JBR" :app:assembleDebug
```

Newer system JDKs break Robolectric (`Unsupported class file major version`), so
always pass the JBR. The full test command, the current baseline and the corpus
setup are in [`CONTRIBUTING.md`](CONTRIBUTING.md#running-the-tests) — run it before
claiming anything passes, and do not restate the counts here or they will drift.

A stale Gradle daemon left over from an earlier Android Studio version fails every
test task with `Failed to exec spawn helper` before a single test runs. `./gradlew
--stop` fixes it; nothing in this repository is wrong when that happens.

Corpus tests are guarded by `Assume`. Do not "fix" a skip by hard-coding a path.

`CanvasIntegrityTest` is the one to extend when a bug is visual rather than structural.
It replays every structural edit over all 99 corpus faces and asserts the canvas still
agrees with itself — the outline matches the artwork filling it, every widget resolves
to an original of the same identity, nothing still on the canvas stops drawing. That
class of bug produces a valid container the watch would accept, so nothing else catches
it. Two of the bugs listed under "Before you touch the format layer" below were found
by these assertions rather than by a crash: the one where a global index was treated
as an identity across a structural edit, and the one where a Static's `+0x20` raster
pointer was left stale when the image section moved.

`libs/*.jar` is gitignored and the two accessory SDK JARs are **not committed** — do
not add them back. The first build on a clean clone needs network to fetch them. See
[`libs/README.md`](libs/README.md).

No doc or build file may depend on a path outside this repository. `analysis/` is a
local working area and is gitignored — findings that matter get written up in `docs/`.

## Module map

Module boundaries and what each one owns are in
[`docs/architecture.md#modules`](docs/architecture.md#modules). The rule that matters
while editing: **Android APIs stop at `:core:data`, `:core:delivery` and the UI
modules.** Binary parsing, protocol framing, CRC calculation, descriptor generation
and install packet encoding stay pure Kotlin and JVM-tested.

## Before you touch the format layer

Everything proven about the container is in
[`docs/bin-format.md`](docs/bin-format.md), and everything the editor is allowed
to change — with the corpus evidence for each rule — is in
[`docs/editing.md`](docs/editing.md). Read the second one at minimum. The rules it
records are not stylistic; each one is there because breaking it made real faces
unopenable or uneditable.

The four that catch people fastest:

* **The panel is not raster 0.** Use `FaceRecordParser.panelSize` and
  `backgroundImage`, never `scanImages(entry).first()`.
* **`drawLeft`/`drawTop` in `:core:model` are the only correct way to derive a
  widget rectangle.** Never call `displayCoordinate` on a widget directly — Badge
  endpoint ordering is handled once, there.
* **No field holds another widget's global index.** An earlier guard based on that
  guess blocked 68% of removals. It is replaced by
  `StructuralEditor.requireSurvivorsUnchanged`. Do not reinstate it.
* **Compare image pointers as record indices, never as raw offsets.** A widget's
  pointers are byte offsets into the image section, so relocating that section rewrites
  them without changing what the widget refers to. `originalWidgetSources` resolves them
  through `payloadKey` before comparing; comparing raw values made every Static on a
  resized face look like a different widget, and faces carrying a row of identical ones
  (00003 has nine) then resolved to nothing and stopped drawing.
* **A global index is not an identity across a structural edit.** `removeWidget`
  renumbers every record after the one it cuts and `appendWidget` puts it back at the
  end, so after a remove-and-restore face `00022`'s seq-10 hour sprite sits at index 10
  — which in the original container is the seq-37 battery. Resolve the original through
  `FaceRecordParser.originalWidgetSources`, **never** `originalRecords[globalIndex]`.
  Reading it by index handed the composer the battery's rectangle to clear and the
  frame lookup an 11-frame table to index with 6 words, so the restored sprite dropped
  off the canvas leaving a bare outline — with no exception and no validation error.
* **A Static's raster pointer is `+0x20`, and it has to be relocated with the
  section.** `words[0]` is `0x0` in every corpus Static and only looks like a pointer
  because `0x0` is the first image's own relative offset, so relocating the word while
  leaving `+0x20` stale silently dangles it. Faces `00010` and `00061` each lost a
  Static to this the moment an in-place sprite resize shifted the records under it.
* **Alpha is not cosmetic.** Do not mask an `0x0082` sprite's backdrop; the watch
  paints its whole rectangle and the preview must say so.

## Traps that already bit this codebase

* **Styles do not carry the same widgets.** `style0` of face `00001` has Value
  widgets for data sources 17 and 18 and `style1` has neither. Requiring a match
  in every variant made 183 selectable widgets across 20 faces refuse to move —
  every one on face `00001` — so the canvas showed the drag and snapped back.
  Cross-style edits are strict on the selected variant and best effort on the
  rest; see `StyleWidgetMatch`.
* **The store validates `locale` against a whitelist, and Android emits values that are not
  on it.** `resultCode=1005 "locale not supported"` on page 0 surfaces as an empty
  catalogue, so an affected phone never gets past the first screen — and the panel blamed
  the connection, which is how this arrived as a screenshot of a five-bar phone being told
  to check its network. Three device-derived shapes are refused: UN M.49 numeric regions
  (`es_419` — the *default* Spanish across Latin America — plus `en_001`, `en_150`), Java's
  obsolete codes (Android's libcore still converts `id`→`in`, `he`→`iw`, `yi`→`ji`, while
  the desktop JDK stopped in 17, so a JVM test cannot reproduce it through `Locale`), and
  languages with no two-letter code (`fil`, `tl`, `qu`, `gn`). The pair is case-sensitive
  and a bare language is refused, so `en` alone is not a fallback. `CatalogLocale` repairs
  what it can recognise — every specific country is accepted, so only the region is
  replaced, which keeps the reader's own language — but the whitelist is not enumerable and
  `qu_PE` is well-formed and still refused, so `CatalogRetry` retries the page once in
  `en_US`. Do not reduce that to normalisation alone, and do not make the fallback an empty
  `locale`: it is accepted, and serves **Korean** names, because `cc` is hardcoded `KOR`.
* **`WatchFaceException.technicalDetail` is the half that explains a failure.** It was
  filled in at every throw site and then dropped by both UI funnels, so the store's result
  code existed in the process and reached nobody. Both funnels record it into
  `DiagnosticsLog` now, and that buffer is what `DiagnosticsReporter` renders for the
  "Report a problem" dialog. The report is built from an **allowlist** — never serialise a
  state object into it. `Settings.Secure.ANDROID_ID` (sent as `extuk`, which is why no full
  URL is ever recorded), Bluetooth addresses and bonded-watch names, the `csc`/`mcc`/`mnc`
  fingerprints, and any picked image's URI stay out; `DiagnosticsRedaction` is the second
  line, not the first.
* **A snackbar is not a place to keep a reason.** Both routes clear it as soon as it has
  been shown, so an empty state that outlives it needs its own field —
  `LibraryUiState.catalogFailure` — or it falls back to asserting something it cannot know.
* **Not every catalogue entry has a container.** Face `00254` ("Photos") is a
  601-file customisation app with no `.bin` at all — the watch renders it itself.
  `Fit3NoContainerException` → `WatchFaceException.isUneditablePackage` → the
  catalogue marks the face permanently "Not editable". Do not treat it as a
  transient download failure.
* **A modal bottom sheet hides the snackbar.** Download failures happen while the
  face sheet is open, so the sheet renders its own error (`sheetError`).
* **Clearing the error before showing it cancels the snackbar.** Both routes keyed
  `LaunchedEffect` on `state.error?.id` and called `clearError` first, which changed
  the key while `showSnackbar` was still suspended — so every failure in the editor
  and the library appeared for one frame and vanished. It is why a refused background
  replacement read as a button that flickered and did nothing, with the real reason
  only in logcat. Show first, clear in a `finally`.
* **A style is not obliged to carry a full-panel background.** Fourteen of the 99
  catalogue faces have none in any style (`00022` is the one people hit), and `00011`
  style0 and `00108` styles 0–3 have none while their siblings do. Background
  replacement and tint therefore write **every style that has one** and skip the
  rest, failing only when no style does — the same rule the widget edits follow.
  The Background page reads `EditorSnapshot.backgroundStyles` and says so up front
  rather than letting an image be positioned against a face that cannot take it.
* **A face with no background can be given one, and the watch accepts it.**
  `StructuralEditor.addBackgrounds` appends a panel-sized `IMAGE_RGB565` raster and the
  40-byte Static that draws it, at widget index 0. Confirmed on an SM-R390 — which
  narrows the "image-record count must never change" rule to whatever appended *sprite
  frames* trip over, since this adds a record and renders. The raster goes at the **end**
  of the image section, never at index 0: putting it first shifts every raster, and that
  changes what relative offset `0x0` names — face `00019`'s two Value widgets both hold
  `words[3..4] = 0`, and after that insert its day-of-week stopped drawing on the watch
  while its date kept working. Appending moves nothing and rewrites no pointer.
* **The watch also ignores a container over 4 MiB, and that one is a size, not a shape.**
  `WATCH_CONTAINER_BYTE_CEILING` is 4 MiB exactly — **settled, not an estimate**. A panel
  background costs 205,880 bytes a style, which is the only edit big enough to matter, and
  the four faces tried on an SM-R390 split exactly across the line: `00008` (→ 2.22 MiB)
  and `00016` (→ 3.60 MiB) render the new background, `00019` (→ 4.16 MiB) and `00021`
  (→ 4.36 MiB) transfer, are accepted, and leave the old face up. Their bytes are as sound
  as the two that work. Every one of the 99 catalogue containers is under 4 MiB (`00072`,
  the largest, is 4,149,034), so the evidence closes on `4,149,034 .. 4,365,626` and 4 MiB
  is the limit inside it. Treat that as settled: do not hedge it back into "the only
  round number in the window", and do not spend a hardware run re-testing it. So a face
  too big for a background in every style gets one in **as many as fit, selected style
  first** (`backgroundStylesThatFit`); `00022` has room for none. `rebuild` refuses any
  growth past the ceiling and `validatedBytes()` refuses to send one, because the
  failure otherwise looks exactly like success. **This is also what the
  old "raising the resize ceiling failed on hardware" result was**: `00022` is 76,640 bytes
  short of the line, so restoring its 114×136 digits from a shrink crossed it.
* **Resizes step a ladder anchored on the original extent, never a factor on the current
  one.** ×0.875 then ×1.125 does not come back: 60×60 → 52×52 → 58×58 → 50×50, so no size
  was reachable twice and Smaller/Larger drifted a widget smaller every round trip. And
  clamping each side at 128 separately broke the aspect ratio the panel promises — growing
  57×68 repeatedly ended at 128×128. `spriteResizeLadder` offers fixed 5% fractions of
  `originalWidth`/`originalHeight` and **drops** rungs over the limit rather than clamping
  them; the background image's zoom steps 2 percentage points for the same reason. Do not
  reintroduce a multiplying step, and do not coarsen the step back to 10% — 6 px a tap on a
  60 px glyph was the complaint that shrank it.
* **A sprite resize is device-proven, and its bound is `spriteResizeLimit`, not a flat
  128.** The watch does redraw a resized sprite. 128 px per side is how far a sprite may
  grow *past what its face shipped*; the shipped extent itself is always reachable, because
  resampling to the original dimensions restores the original record lengths and hands the
  container back its shipped size. That is why `00022`'s 114×136 digits can be restored now
  and could not before. `resizeSpriteEntry` resolves the shipped extent through
  `pristineFrameOrigins` — without a `pristine` container it falls back to the current
  extent, which is all it can know.
* **Arc `words[4]` and LineBar `words[2]` are raster pointers.** All 30 and 16 corpus
  records resolve, none of them zero — the old relocation knew only Static, Sprite and
  Hand, so a moved image section left those stale, which draws nothing and fails no
  validation. `FaceRecordParser.imagePointerFields` is the single pointer map now;
  `referencedImages` stays narrower on purpose because an Arc's 310×310 raster is not its
  drawn extent. Anything else whose *nonzero* word lands on a moved raster still refuses
  the edit: `0x0` is image 0's own offset, so zeroed Pair and Comp fields resolve by
  coincidence, which the shipped background faces prove is harmless.
* **A resize resolves its pristine frames through widget identity, not image index.**
  Matching by index was guarded by "only if both containers hold the same number of
  records", and adding a background breaks both halves at once — so the pristine pixels
  were silently dropped and every resize resampled the previous resize. Smaller, Larger,
  Smaller came back visibly softer on a real watch. `pristineFrameOrigins` pairs the
  pointer lists of the same (type, sequence id) widget instead.
* **A background replacement writes colour only.** The alpha plane of an
  `IMAGE_RGB565_ALPHA` background is the panel's rounded-corner mask — 656 of face
  `00003`'s 102,912 pixels — so filling it in squares off the corners. Only the
  indexed path re-emits opacity, deliberately, and face `00002` is the one raster
  that pays for it. `BackgroundReplacementSweepTest` pins both.
* **`RepeatingNudgeButton` cannot step from the press alone.** The press-driven
  effect is launched by the recomposition the press causes and runs a frame later, so
  a tap shorter than that frame was cancelled before its first step — a control whose
  label promises "tap for 1 px" moving nothing. The click is the fallback, and a flag
  keeps the release of a longer press from adding a step the repeat already made.
* **Install is gated on `previewReviewed`, and every commit clears it.** Editing
  on the Validate page therefore has to re-mark it, or "Continue to install"
  becomes inert.
* **`preview.bin` is the vendor's render of the *unedited* face, and nothing
  rewrites it.** The composer's `reference` must be read from `originalContainer`,
  not `currentContainer`, or each edit diffs against the previous composite and
  drifts. And a removed widget's pixels are still in that raster, so
  `EditPreviewComposer` has to clear them explicitly.
* **Anything read out of that raster is measured with the *original* geometry.**
  `WidgetGuide` carries `originalWidth`/`originalHeight` beside `originalX`/`originalY`
  for exactly this: a Sprite resize rewrites every frame, so `width`/`height` follow
  the new raster the moment it commits while the reference still shows the old one.
  Clearing a shrunk widget with its new, smaller rectangle left the outer ring of the
  old sprite on the canvas — 3,634 stale pixels on face `00022` widget 2 alone, and 9
  corpus faces affected. `originalDrawLeft`/`originalDrawTop` and every ownership test
  in the composer use the original extent; `ResizedWidgetLeavesNoGhostTest` sweeps it.
* **The composer clears every relocated widget before it draws any of them.** Done one
  widget at a time, a widget dragged onto the rectangle another one is vacating was
  painted and then wiped out by that widget's own clear, so dragging several widgets
  around each other made them vanish one at a time. Keep the two passes separate.
* **The watch ignores a container whose image-record count changed.** Proven on
  hardware: a copy-on-write resize that appended private frames transferred fine, the
  install command was accepted, and the face never updated. The independent Python
  analyzer verifies those containers — CRCs, zero byte residual, exact rebuild — so the
  bytes are sound and this is firmware policy, not a format bug. **Never add or remove
  an image record.** `resizeSpriteEntry` asserts the count afterwards.
* **Sprites share their frames, so a resize moves the whole pool.** A face keeps one
  glyph pool and points several widgets at it: face `00022`'s hour tens digit addresses
  frames 2–4 and its units digit frames 2–11. Rewriting only the frames the selected
  sprite names left the neighbour drawing three small glyphs and seven large ones, its
  box still reporting the largest — a raster-backed extent is the max over its frames.
  **740 of the corpus's 859 resizable sprites share frames**, so refusing was not an
  option either. `FaceRecordParser.sharedFrameClosure` closes over every widget reaching
  into the pool and the records are rewritten **in place**; `canResize` validates the
  whole closure, so the UI never offers an edit whose commit would fail.
* **Every resize resamples the pristine container, never the current one.** Resampling
  is lossy, so chaining it destroys the artwork: 114×136 → 56×69 → 109×128 came back
  carrying only the detail that survived the small one. `resizeSprite` takes a
  `pristine` container and matches frames **by word position on the same sequence id**,
  not by image index — copy-on-write renumbers the records, so index matching would
  lose the origin the first time a shared sprite was resized. Same reason the composer's
  `reference` comes from `originalContainer`.
* **The thumbnail is re-rendered on request, but only once per edit.** Its widget
  pixels can only come from the vendor's smaller `preview.bin` render, so every
  pass resamples them and softens the result. `EditorSnapshot.canRefreshThumbnail`
  gates the button, and `Session.thumbnailContainer` holds a container identity
  rather than a flag so a later edit marks it stale on its own.
  `replacePreviewThumbnail` returns null — not an exception — when the stored
  raster already matches.
* **Discovery needs the plugin's channel; the transfer needs it released.** Those
  are opposite requirements in that order, which is the thing users get stuck on.
  Discovery without the plugin connected must land in the recoverable
  `NEEDS_WATCH_CONNECTION`, never in `FAILED`.
* **The handover has to be rewindable, or a failed transfer is a dead end.** A peer
  handle does not outlive the connection it was found on, so a transfer that fails
  after the plugin let go is only retryable by reconnecting, rediscovering and handing
  the channel over again. `rewoundToDiscovery()` / `restartDiscovery()` are the way
  back. Two rules keep it honest, and both have been broken before:
  [`docs/direct-install.md`](docs/direct-install.md#recovering-after-the-handover) has
  the full account, including what a committed edit does to a finished transfer.
* **Press-and-hold cannot re-read the snapshot.** A repeat fires faster than a
  container commit, so `EditorViewModel.nudgeWidget` accumulates the target and a
  single worker commits the latest one. Do not "simplify" it back to reading
  `snapshot.widgets` per tick — the widget stops moving.
* **`screenShotResolution` mixes 256×402 samplers with 512×512 promo art.** Filter
  to the watch aspect; do not `takeWhile`, which silently drops real styles.
* **Widget lists are not all drawable.** `WidgetPlacement` splits CANVAS /
  BACKGROUND (a panel-sized raster) / HIDDEN (no extent — clock hands). Hidden
  records are still editable; they just cannot be previewed.
* **A per-style picture comes from the package, never from a parse.** The Styles page
  and the projects list show `assets/SM-R390_<face>_<group>_<style>.png`, extracted to
  `projects/<id>/previews/style<N>.png` when a project is opened. Rendering them
  instead would mean decoding a raster section per row — and on the projects list,
  parsing every project's container to draw the screen. They are the *unedited* face,
  so the selected style is drawn from `composedPreview` and the rest stay stock. 98 of
  the 99 container-carrying faces ship exactly one per `styleN.bin`; `00031` ships
  none, so both screens must treat absence as normal. `StylePreviewSweepTest` and
  `StylePreviewProjectTest` pin this.
* **The canvas has one mode.** It used to toggle between the editable layout and a
  read-only "validated preview" — the same picture the Validate page shows, so the
  toggle only ever took away the ability to select a widget. Both chips are gone and
  `EditorUiState` no longer carries `showLayout`; what survived is
  `markPreviewReviewed()`, because install is still gated on having seen Validate.
* **A canvas sized only by width gets clipped.** `fillMaxWidth().aspectRatio(…)`
  ignores the height it was given, so the selection panel appearing under the face cut
  the top and bottom off it. `CanvasWorkspace` fits against `maxWidth`, `maxHeight ×
  aspect` and the cap, so selecting a widget shrinks the face instead.
* **A `pointerInput` block keeps the values it was started with.** It restarts only when
  one of its *keys* changes, and `DirectWatchCanvas` keys on the style, the pending image
  and `editing` — none of which move when an edit commits. So the gesture coroutine went
  on running the lambda it was launched with, holding that composition's
  `EditorSnapshot`: after a drag, a nudge or a resize the canvas drew the widget in its
  new place while `hitWidget` was still testing the old rectangles, so tapping the widget
  selected nothing and tapping where it used to be selected it — offset in whichever
  direction it had just been dragged, and the stale `preferredGlobalIndex` decided
  overlaps on an old selection too. Everything those handlers read comes through
  `rememberUpdatedState` — `latestSnapshot`, `latestSelectedGlobalIndex`, `latestEnabled`.
  Adding `snapshot` to the keys is **not** the fix: that restarts the detector mid-gesture
  and cancels the drag in progress.
* **A drag accumulates the finger's position, never the clamped one.** Folding
  `constrainDragCoordinate` into the running total made a widget stick: pushed past an
  edge and brought back, it resumed from the edge instead of from under the finger, so it
  trailed by the whole overshoot for the rest of the drag and the further you pushed the
  worse it got. `stepDragAxis` keeps `track` unclamped and clamps on the way out;
  `WidgetHitTest.aDragPushedPastTheEdgeComesBackUnderTheFinger` pins it.

## Invariants to preserve

The six are listed once, in
[`docs/architecture.md#invariants`](docs/architecture.md#invariants). Do not restate
them here. The one worth memorising: **`Session.validatedBytes()` is fail-closed and
is the only path to the watch.** Nothing may route around it.

## Still open

* Physical-watch delivery cannot be exercised here — the emulator has no Fit3 — so
  automated coverage stops at the bytes. Delivery, background add/replace, widget moves,
  sprite resizes and the 4 MiB container ceiling are all confirmed on a real SM-R390;
  timeout recovery is the one that is not. What a timeout *means* is now pinned in
  `TimeoutRecoveryTest` — the decision was lifted out of `armWatchdog` into a pure
  function so it could be — but no watch has yet been made to time out on purpose.
* No `androidTest` source set; the Room migration test runs under Robolectric.
  `:feature:editor` deliberately does **not** use Robolectric — it depends on
  `:core:delivery`, whose merged manifest declares a receiver from the accessory SDK JAR,
  and instantiating that pre-stackmap bytecode fails the JVM verifier before any test
  runs. Its ViewModel tests own `Dispatchers.Main` with a test dispatcher instead, and the
  module sets `unitTests.isReturnDefaultValues` so `android.util.Log` does not throw out
  of the failure path.
* The canvas gestures still have no test: `hitWidget`, `constrainDragCoordinate` and
  `stepDragAxis` are pure and covered, but nothing drives a real drag. Two canvas bugs
  came through that gap — the `pointerInput` block holding a stale `EditorSnapshot`,
  and the drag total accumulating the clamped coordinate instead of the finger's, both
  listed above. Neither was in those functions; both were in what the Composable *fed*
  them.
