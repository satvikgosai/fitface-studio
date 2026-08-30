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

## Writing the changelog

[`CHANGELOG.md`](CHANGELOG.md) is one entry per released version, newest first, headed
`## <versionName> (code <versionCode>) — <tag date>`, grouped under `Fixed:` and `Added:`.
A bullet is one or two lines, written in the words of someone using the app: a locale the
store refuses, not `resultCode=1005`. Name what a reader needs to recognise their own case
— the affected languages, the screen it happened on — and stop there.

Keep it short on purpose. Why a change was made goes in [`docs/`](docs/README.md), and the
trap behind it goes under "Traps that already bit this codebase" below; repeating either
here turns the file into a second commit history nobody reads. Nothing invisible to the
user earns an entry — refactors, tests, docs, dependency bumps. The version bump is not an
entry either, it is the heading. An initial release is one line, with no list at all.

A bug introduced and fixed inside the same unreleased cycle is not an entry either. A
release is one step from where the reader stands, so a fault no build of theirs ever had
gives them nothing to recognise — it describes the branch, not the app. The test is whether
the *cause* shipped, not whether the fix is listed above it: the clipped editor subtitle
earns its bullet, because the full-label **Report a problem** button that caused it went
out in 0.1.1, even though the header menu that fixes it is an `Added:` line in the same
release.

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
* **The top bar's actions slot is a width budget, and the title column pays for it.**
  `FitTopBar` gives its title/subtitle `Column` a `weight(1f)`, so it is the flexible
  child and every dp an action takes comes out of the title. "Report a problem" went in
  as a full-label `TextButton` — 109dp of a 360dp bar, wider than the title beside it —
  and the subtitle was ellipsized on **every editor page** with no exception, no lint
  warning and nothing in any test: `face 00112 · style0` rendered `face 00112 · styl…`
  and `reparse of the edited container` rendered `reparse of the edited conta…`. An
  action in this bar is a `FitIconButton`, 38dp square, and its label lives in the
  `contentDescription`. `FitTopBarLayoutTest` pins the budget.
  Two corollaries the same bug taught: **emit the always-present action last**, because
  as the final child its right edge is pinned to the padding and it stops sliding
  sideways when a conditional neighbour appears (the library's moved 189px on a tab
  switch); and **do not put an action in a Row that centres against a two-line Column**,
  or it aligns with neither line — the library's straddled the brand label above it and
  most of the headline below.
* **The top bar's three global actions are one menu, and a `DropdownMenu` is why that is
  affordable.** The width budget above is the reason there is a menu at all: report a
  problem, about and check for update cannot be three buttons in a bar whose title column
  is the flexible child. A `DropdownMenu` is a `Popup` — its own window, measured outside
  the anchoring composition — so the `Row` measures only the anchoring `Box`, which wraps
  to one 38dp square. Replacing it with an inline column would re-ellipsize every editor
  subtitle and nothing would look wrong until a device was in someone's hand, so
  `FitTopBarLayoutTest.theOpenMenuCostsTheBarNoWidth` measures the anchor and the title
  column with the menu open. Two corollaries. **Every entry closes the menu before it
  invokes its callback** — all three open a dialog, and a popup left standing behind one is
  the first thing to go wrong here. And the glyph is `≡` rather than the platform's `⋮`,
  because Canvas already carries a `⋯` that *navigates to a page*: two ellipses side by
  side read as one control with two behaviours. `⚙` and `ℹ` are worse — U+2699 and U+2139
  fall through to the emoji font on many builds and would be the only colour glyphs in the
  app.
* **`/releases/latest` returns 404 for this repository, and always will.** The release
  workflow publishes with `gh release create --prerelease`, and that endpoint skips
  prereleases — so the obvious URL reports that the app has never been released.
  `GitHubReleaseFeed.Endpoint` is `/releases?per_page=10` and the newest is chosen by
  **comparing parsed versions, not by taking the first element**: the API happens to
  return newest-created first, but that is not a contract and a re-cut tag breaks it.
  `AppVersion` compares part by part for a related reason — as strings, `0.1.10` sorts
  below `0.1.9`, so the release people most need would be the one never offered — and it
  refuses to parse anything that is not plain dotted digits, so `v0.2.0-rc1` is skipped
  rather than mis-ordered. And `newest == null` is **not** "up to date": a renamed asset
  or a reshaped response would otherwise leave a reader told they were current for as long
  as the mistake lasted.
* **An APK signed with a different key cannot update this app, so the archive's signers
  are compared before the package manager is ever asked.** CI signs with a keystore
  restored from a secret and a local build uses the one AGP generated on that machine, so
  a development build and the published one routinely disagree — and the package manager's
  own answer, `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, sends people to the one action that
  destroys their work: uninstalling, which takes every saved project. `inspect` compares
  the package name, the archive's `longVersionCode` (the feed carries no version code at
  all, so this is the only place a re-cut tag is caught) and the SHA-256 of every signer,
  and turns a mismatch into a sentence that names the cost. **An unreadable certificate
  must not block**, though: the package manager is the real gate, and refusing on our own
  failed read would make the feature unusable for no gain in safety.
* **Cancelling the download has to be cooperative, and the progress callback has to be
  guarded.** `work?.cancel()` looked like it worked and did nothing: the read loop is
  blocking OkHttp I/O that never suspends, so a cancelled coroutine ran on to the last of
  the 36 MiB while the dialog claimed it had stopped. The loop now checks `job.isActive`
  each iteration and throws, which also lets the streaming write delete its `.tmp` on the
  way out. **That fix alone is not enough**, and this is the part worth remembering: a
  cancel lands *between two reads*, so one more `onProgress` follows it and writes
  `Downloading` back over the state `cancel()` has just restored — the dialog goes on
  counting up from a cancelled transfer. The callback checks the same job before it
  publishes. Two smaller rules fell out of it: `CancellationException` must be rethrown
  rather than caught by the `catch (error: Exception)` that turns everything else into "the
  download failed", in the fetcher *and* in the installer (where the session is abandoned
  first); and cancelling back to the offer re-reads the install permission instead of
  defaulting it, or it quietly tells someone installs are allowed when they are not.
  Verified on hardware the only way it can be: watch the `.tmp` grow, cancel, and confirm
  it is gone and nothing more arrives. The **face-package** download had none of this and
  now does — the Job check, the `Call.cancel()`, the `CancellationException` rethrow — plus
  the `runCatching` in `LibraryViewModel` that caught the cancellation and reported it as a
  failed download into a screen that was already going away. Nothing user-visible hangs on
  it, since that download has no cancel button, but two paths reading differently is how
  the next one gets written wrong.
* **The updater owns its own `CoroutineScope`, and the install permission has to be
  re-read.** The APK is 36 MiB and the menu is in both bars, so a download begun in the
  library has to survive opening a face; in a `viewModelScope` it dies with the nav entry.
  Two things that were wrong on the first pass and are worth not redoing: the permission
  verdict was baked in when the check ran, so after granting it in Settings and coming
  back the dialog still said "Android needs your permission" and the only way on was to
  close and start again (`recheckInstallPermission` fixes it without a second network
  round trip); and the stale-download sweep kept "the newest release", which after that
  release was installed meant keeping the 36 MiB file for ever — it has to keep only the
  release that is actually **newer than installed**. Progress is reported per whole
  percent, not per read: a 36 MiB download emits about 4,600 times otherwise, and each one
  is a state change the dialog recomposes on. Also: no `callTimeout` on that client, or a
  slow connection kills the download partway; and never put a redirected release-asset URL
  in a message, because it carries a signed access token and that string reaches the bug
  report.
* **`:core:data` has a manifest now, and it declares a permission and nothing else.**
  `REQUEST_INSTALL_PACKAGES` lives there because the code that needs it does, following
  `:core:delivery`. The install-status receiver is registered **at runtime** on purpose: a
  manifest receiver would add a component to the merged manifest of every module that
  depends on `:core:data`, which is the hazard that already stops `:feature:editor` running
  under Robolectric, and it would buy nothing — a successful self-install replaces this
  process before any receiver could report it. `STATUS_PENDING_USER_ACTION` is **not**
  terminal: it means the system wants to ask, and returning there would report an outcome
  before the reader had been shown anything. `FLAG_MUTABLE` is mandatory from API 31 or the
  `PendingIntent` throws, and the confirmation `Intent` is handed up to an Activity to
  launch rather than started from the updater, because a background activity start is what
  recent Android versions drop.
* **A header shared by two pages is as tall as the taller page, or its tabs move under the
  finger that switched them.** The library header sizes itself from the page it is showing,
  and the pages do not carry the same content: Watch faces has a REFRESH `TextButton`, which
  brings the 48dp touch target with it, and a longer subtitle that wraps to two lines on a
  narrow phone. Projects has neither, so the tab row sat 26px higher there on a 411dp phone
  and a further line higher on a 360dp one — you tapped a tab and it jumped away as the page
  changed. The actions row now keeps `ACTION_TOUCH_TARGET` as a floor whether or not REFRESH
  is in it, and the subtitle lays out **both** pages' strings, the one not showing at
  `alpha(0f)` with `clearAndSetSemantics`, so the box is as tall as the longer one wraps to at
  that width, font scale and translation. Reserving a fixed two lines instead would only be
  right at the widths someone checked by hand. `LibraryHeaderLayoutTest` pins both halves, and
  it asserts positions rather than line counts for the reason `FitTopBarLayoutTest` explains.
* **`onSurfaceVariant` is already the dim role — do not dim it again.** Nineteen call sites
  reached for `.copy(alpha = .68f)`, or `.72f`, or `.66f`, or `.48f`, each picked by hand,
  and on the small styles the second dimming took the text under the readable floor:
  `MicroLabel` at 9.5sp measured 4.08:1 in the dark theme and **3.38:1** in the light one,
  and a project's timestamp 2.58:1, against a 4.5:1 minimum that none of those sizes are
  large enough to be exempt from. A reader called the editor's small text almost unreadable.
  `MaterialTheme.fitText.secondary` and `.tertiary` are now the only ways to dim text and
  neither takes an alpha; `SmallTextContrastTest` holds both to the floor in both schemes.
  Disabled controls keep their own alphas and are exempt — an inoperable control should look
  inert. Note this is a different complaint from the tiny teal text buttons, which measure
  12.77:1: their problem is 11sp mono at Medium weight, so it needs a type change, not a
  colour one, and it is still open.
* **Robolectric's text metrics are not the device's, so do not assert on truncation.**
  `:core:ui` is the one module that can measure composables — it depends on nothing the
  JVM verifier chokes on, unlike `:feature:editor`. Two gotchas. The default graphics
  mode has stub fonts that collapse every string to a few pixels, so a text-labelled
  button measures tiny and a title column measures enormous; `@GraphicsMode(NATIVE)` is
  required for any layout assertion. And even in native mode the metrics differ enough
  that the very subtitle which clipped on a real phone measures as *fitting* — so a
  "the text is not ellipsized" assertion would have passed while the device still
  clipped. Assert layout geometry instead: declared sizes and the positions they
  produce.
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
* **An abandoned delivery worker is not a stopped one, and it used to still be able to
  speak.** `armWatchdog` changed the state and nothing else, so the transfer it had just
  declared dead went on transferring — and every delivery callback wrote `phase`
  unconditionally. Three wrong answers followed, all reachable on arithmetic rather than
  bad luck, since `MAX_WINDOW_RETRIES` is 3 over a `0..3` loop and one window can spend
  four `WINDOW_TIMEOUT_MS` waits — 48 s against a 20 s watchdog. An acknowledged window
  dragged `FAILED` back to `TRANSFERRING` and the transfer then reported success; a late
  accessory `onSent` turned an install timeout into `COMPLETE`, which is a face the watch
  never got reported as installed; and tapping **Reconnect the watch and discover again**
  cancelled the transfer, which made the abandoned worker throw within milliseconds — so
  its failure landed just *after* the rewind and put the page straight back into `FAILED`,
  leaving the whole four-step setup as the only way out of a timed-out transfer. Two
  mechanisms now, because neither covers the other's half: an **attempt token** in each
  agent — which replaced a shared `transferAborted` flag that the *next* attempt cleared,
  so an abandoned worker quietly un-aborted itself — and `DeliveryProgress.accepts` in the
  installer, tested inside the atomic update so a worker thread cannot read one phase and
  write against another. `abandonInFlight()` is what a timeout, a rewind and a reset all
  call. Cancelling deliberately does **not** join the worker: it is blocking on RFCOMM with
  half a second of teardown sleep behind it and `reset()` runs on the main thread, so
  joining would freeze the UI for as long as the failure being escaped. The token is what
  makes an unterminated worker harmless instead of waiting for one.
* **A watchdog that stops the work has to be re-armed by every wait, not just by an
  acknowledged window.** Making the transfer timeout real — `abandonInFlight()` from
  `armWatchdog` — turned a 20 s budget that had only ever mislabelled a phase into one that
  kills the transfer, and three stretches of a *healthy* transfer are longer than it. The
  opening handshake is 8 s + 8 s + 12 s with nothing reported in between; one window may be
  re-sent four times at 12 s each and an `SPP_WINDOW_RETRY` answer reported nothing at all;
  and the tail after the last window is 15 s of BIN verification, a 250 ms pause, an 8 s
  close handshake, 500 ms of teardown and a 1 s completion post — 24.75 s. Crossing the line
  now bumps the attempt token, which discards the queued `onTransferComplete`, so the install
  command is never sent and an install the watch **accepted and verified** reports as a
  timeout. Every wait ends in a `report` now and `onTransferStatus` re-arms; the budget lives
  in `TRANSFER_PROGRESS_GAPS` with `TransferWatchdogBudgetTest` on it. Do not "simplify" that
  by raising the constant instead: `SppResponseWait` already bounds every individual wait, so
  what this watchdog guards is the gaps between them, and stretching it to 48 s only delays
  noticing a watch that really has gone. Two corollaries. `transferProgressRearmsWatchdog`
  excludes `VERIFYING` — a status is *accepted* there, but `armWatchdog` replaces whatever is
  armed, so arming a TRANSFERRING watchdog in VERIFYING is a disarm. And the gap list cannot
  live in `OtaTransferDeliveryAgent`'s companion: a non-`const` `val` there forces the JVM to
  load that class, which extends the accessory SDK's `SAAgentV2`, and its pre-stackmap
  bytecode fails the verifier — so the test dies of `VerifyError` before asserting anything.
  `const val`s are inlined at their use sites, which is the only reason the timeouts can be
  read from a test at all.
* **Creating a project is two writes, and the second one is where it used to be abandoned.**
  The row goes in first because its id names the directory, so unlike `persistEdited` this
  cannot be one write — and a row whose `localApkPath` is null is one `openProject` can only
  refuse, with "This project's package is missing." It used to heal itself: `openPackage`
  looked the row up by `sourceKey` and reused it. It always starts a new project now, so a
  half-written row would sit in the list unopenable while every retry added a numbered
  sibling beside it. `NonCancellable` closes the cancellation window — the second `insert` is
  the only suspension point between the two writes, and backing out of the library while an
  open finished landed exactly there — and the `catch` deletes the row for a write that
  genuinely fails. Delete through **`projectDao.deleteById`, never `deleteProject`**: that
  takes `mutex`, which the block already holds and which is not reentrant.
* **A row that sets its own `contentDescription` says only what that string says.** The face
  sheet's project rows replace the label a screen reader would have assembled from their own
  text, so the OUTDATED badge was drawn and never announced — and it is the only thing
  telling two projects on one face apart. The grid's cards already carried a second string
  for this (`library_face_card_a11y_not_editable`); the sheet's rows now do too.
* **Only `1007` ends catalogue pagination.** The follow-up-page branch accepted *every*
  non-zero `resultCode` as "No Items" — and a null one too, since a missing or unparseable
  element also fails `!= 0`. So a locale rejection or a server error on page two read as a
  clean end of list: the faces gathered so far were written to `cache.writeCatalog()` as a
  successful refresh and served for the full seven-day TTL, with no `CatalogRejected`
  escaping for `CatalogRetry` or the stale-cache fallback to act on. This is the normal
  path, not a corner of it — `PageSize` is 100 and the catalogue is longer than that, so
  page two is fetched on every cold refresh.
* **The database pointer is written first, and the container second.** `persistEdited` did
  it the other way — `edited.bin`, then `session.json`, then the row — and the row is the
  only one of the three behind a cancellable suspension. A commit that threw or was
  cancelled at the DAO left the new container on disk while `commit()` rolled only memory
  back, and an already-edited project's row names that same pathname, so reopening it
  loaded the edit the app had just reported as failed. Row-first makes every failure land
  consistent instead, with no compensation machinery, because `writeAtomically` leaves the
  previous file intact when it throws and `loadSession` reads a path that is not a file as
  no edit at all. It also closes the cancellation window outright rather than compensating
  for it: past the DAO there is nothing left but blocking I/O. `EditPersistenceTest` pins
  it — and fails against the old order, which is the only reason to believe it. The other
  half of the same bug: `persistSessionState` swallowed every write failure in a bare
  `runCatching`, so the container and the row could commit a removal while `session.json`
  stayed stale, and the widget came back from the next launch with no way to restore it and
  nothing reported. Failures propagate now.
* **A dynamic receiver is exported below API 33, so the action string has to be the
  secret.** `RECEIVER_NOT_EXPORTED` arrived in Tiramisu; before it a dynamic receiver takes
  a matching broadcast from any app on the phone, and `UpdateInstaller`'s action was a
  constant sitting in the APK. `minSdk` is 28, so on API 28–32 any installed app could
  report an install success the package manager never gave while an update was running —
  or send `STATUS_PENDING_USER_ACTION` carrying an `Intent` of its own, which `AppMenuHost`
  then launched as though it were Android's install confirmation. The action carries a
  per-install `UUID` now and `awaitOutcome` checks the session id the genuine
  `PendingIntent` has always carried and nothing ever read. A receiver permission is **not**
  an option here: the sender is the package manager running as the system, and it holds no
  permission this app could define.
* **Discovery needs the plugin's channel; the transfer needs it released.** Those
  are opposite requirements in that order, which is the thing users get stuck on.
  Discovery without the plugin connected must land in the recoverable
  `NEEDS_WATCH_CONNECTION`, never in `FAILED`. **And how the plugin lets go depends on
  the phone**: `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` became runtime permissions in Android
  12, so below API 31 there is no per-app switch, `pluginNearbyGranted` is null on every
  such phone, and step 4 can only ever be the user's acknowledgement. Both halves are
  gated in one place each — `hasPluginNearbySwitch` in `EditorScreen.kt`,
  `Fit3DirectInstaller.restorePlugin` for the state messages — and **nothing else may
  name a Nearby switch, a freezing tool or `adb` in user-facing text**: a reader who does
  not know their own Android version cannot pick the half that applies to them. The one
  route that used to serve both, disconnecting the watch in the companion app, does not
  free the channel — it and a manual force stop were both tried on hardware.
* **What is installed does not decide whether the channel opens — discovery does.** The
  Install page used to AND three package names and replace the whole checklist with a dead
  end if any were absent, and every part of that was wrong. **The companion app has no
  single package name**: it ships as `com.samsung.android.app.watchmanager` on mainstream
  models and `…watchmanager2` on the entry-level ones (SM-A107M, SM-A115M), with the
  distribution exactly complementary, plus a firmware preload and two retired ids —
  `CompanionResolution` holds the list. Knowing one name told a reporter whose watch was
  paired, connected and holding a live accessory session that their companion app was not
  installed. **And the companion app was never the right question anyway**: neither
  `watchmanager` build declares a `REGISTER_AGENT` receiver or `AccessoryServicesLocation`,
  so it carries no accessory code at all — it is a launcher and setup shell, and the stock
  plugin is what owns the channel. That is why `accessoryFrameworkAvailable` counting the
  companion as a provider was unsound in both directions, and why the environment is an
  advisory (`EnvironmentAdvisory`) now and nothing anywhere may gate on it. Detect agents
  by capability — `queryBroadcastReceivers` on `REGISTER_AGENT` — not by name, or the next
  id Samsung forks reads as an empty phone. Two corollaries: an agent that fails to
  initialize must land in the recoverable `NEEDS_PLUGIN`, and it arrives through the
  **discovery listener's `agent_error`**, not through `requestAgent`'s own callback, which
  is where a first attempt at this fix put it and still ended in `FAILED`; and the plugin
  has **no launchable activity** — every activity in it is unexported — so
  `getLaunchIntentForPackage` on it is always null and `openCompanionApp` must fall back to
  the plugin's app-info screen.
* **`com.samsung.accessory` has to stay in `<queries>`.** Not for the app's own probe —
  for the SDK's. `SASdkConfig` calls `getPackageInfo("com.samsung.accessory")` and
  `SAAdapter` binds `Intent(ISAFrameworkManagerV2).setPackage("com.samsung.accessory")`,
  so without the declaration the SDK reports `LIBRARY_NOT_INSTALLED` and no channel opens
  at all, on a phone where the framework is installed and serving the stock plugin
  (`AppsFilter: dev.fitface.studio -> com.samsung.accessory BLOCKED`). Samsung's own
  plugin needs no declaration because it shares a signing certificate with the framework;
  this app shares it with neither. Do not use `SA.initialize()` as the presence check
  either — it sends a usage-survey broadcast carrying this app's package name to
  `com.samsung.android.providers.context`, and the verdict it computes is the one
  `getPackageInfo` already gives.
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
* **A dialog has no height to give in landscape either, and an `AlertDialog` clips rather
  than scrolls.** It caps its own height and hands the text slot whatever is left, which on
  a landscape phone is a few lines: the diagnostics blurb was cut mid-sentence, and About
  lost the link and the version — the two things it exists to show — entirely, with no
  scrollbar and nothing to suggest anything was missing. **The text slot of every dialog in
  this app scrolls**, and it is the slot that scrolls, not something inside it: the
  diagnostics report used to carry its own `verticalScroll` under a `heightIn(max = 320.dp)`,
  which left the blurb above it clipped and would fight the outer gesture if both were
  present. Scrolling is the guarantee, not the fix — About was also trimmed until it *fits*
  a landscape phone without scrolling, because content a reader has to go looking for is
  content most of them will not see. That is why its prose has a length bound in
  `AboutCopyTest` and why the project link has no label line of its own: the version needs
  that line. None of this can be caught by a test — an `AlertDialog` never reaches idle in
  this harness, so `createComposeRule` throws `AppNotIdleException` before it can measure
  one — so a dialog whose content grows has to be looked at in landscape by hand.
* **A stacked page has no height to give in landscape.** `CanvasWorkspace` handed the face
  `weight(1f)` under the hint and the selection panel, and a landscape phone leaves the
  three of them about 340dp: the face came out a third of its portrait size, and tapping a
  widget — which is what adds the panel — squeezed it to a dot with the hint clipped behind
  the panel's top edge. Under `CanvasStackedMinHeight` the page splits into two columns
  instead, where the face keeps the whole height; `canvasPageSplits` is the decision and
  `CanvasPageLayoutTest` pins it, because `:feature:editor` is the module that cannot
  measure a composable. The same arithmetic clipped `EditorUnavailable`: its 176x276dp
  placeholder is taller than a landscape phone leaves under the top bar, so the spinner and
  "decoding images" fell off the bottom edge and the screen read as a blank grey slab. Both
  fits derive the width from the height that is left, never the other way round. Still
  cramped rather than broken, and unfixed: the library header takes 41% of a landscape phone
  before a single face row, and Background and Validate put a full-height preview above
  every control they own — in the wide layout that preview is the second one on screen,
  since `CanvasSidePane` is already showing the same face.
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
* **Both library pages lay their controls out with one composable and one set of insets.**
  Assembled twice, they had already drifted: the catalogue inset its grid by 16dp and the
  projects list by 20dp, with 2dp between their top paddings, so the search field and every
  sort chip stepped sideways and up when you switched tabs. `LibraryPageControls` and
  `LibraryPageInsets` make that impossible rather than merely fixed.
* **Both labels of a sort direction pair are the same number of characters, and that is
  load-bearing.** `labelMedium` is `FontFamily.Monospace`, so equal length is equal width —
  which is what stops the selected chip resizing when it is reversed and shoving every chip
  after it sideways under the finger that just tapped it. The two pages also share one set
  of labels, because wording the same chip differently moved the whole row on a tab switch.
  `SortChipLayoutTest` measures the *neighbours* of the reversed chip, not the chip itself:
  the selected one is first in the row, so its own left edge cannot move whatever it does,
  and an assertion on it passes while the bug is present.
* **The editor leaves the Inspector on a removal *count*, never on "no widget is selected".**
  The second rule reads better and is wrong: `page` is local Compose state and moves in the
  same frame as the tap, while the selection arrives through `collectAsStateWithLifecycle` a
  frame later — so opening a widget from the list would find an Inspector that had not been
  told which widget yet and bounce straight back to the list. The counter only advances on a
  removal that committed, which also leaves a *failed* removal on the page it happened on,
  where its message is.
* **A project row is nearly copyable, and the two fields that are not are what make a
  duplicate independent.** `localApkPath` and `editedBinPath` are absolute paths into the
  project's *own* directory. A duplicate that kept them reads the original's edits, and
  stops opening at all the moment the original is deleted — and neither symptom appears
  until after the copy has been made and named. `duplicateProject` clears both on the copy
  and rewrites them once the new id names a directory. `session.json` and `previews/` are
  found by convention rather than by a column, so they are copied by name; the session file
  is not decoration, it holds the removed-widget records, and a copy without it shows a
  widget missing with nothing offering to put it back. `ProjectDuplicationTest` holds all of
  it, and the assertions that catch a shared path are the ones about editing the *original*
  and deleting it — editing the copy writes to its own directory either way, because
  `persistEdited` derives the path from the id rather than from the row.
* **`ProjectNaming.defaultName` numbers from the stem, not from whatever it was handed.**
  Downloading only ever passes a face's own name, so a base already ending in a counter
  never came up until duplication started passing an existing *project* name: copying
  "Aurora 2" produced "Aurora 2 2" and copying that "Aurora 2 3", a second series running
  beside the first. Only a base that is already taken is re-stemmed — duplicating "Aurora 2"
  onto a face with no "Aurora 2" keeps the name rather than promoting the copy to "Aurora".
* **The database version numbers start at 4 and can never be renumbered.** `v0.1.0`, the
  first public release, already shipped at version 4, so schemas 1–3 exist on no device and
  renumbering 4 to 1 looks free. It is the opposite: every install holds
  `PRAGMA user_version = 4`, a build declaring a lower number opens that as a **downgrade**,
  and the builder answers a downgrade with
  `fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)` — every saved project on
  every phone, gone on first launch. The reasoning is kept on `FitFaceDatabase` itself.
* **`openPackage` always creates a project; resuming one goes through `openProject`.** It
  used to look the package's `sourceKey` up first and silently re-enter whatever it found,
  which is what limited a face to one project and what made **Download & edit** open work
  already in progress while promising a download that never happened. Schema 4 pinned that
  with a UNIQUE index on `sourceUri`; `Migration4To5` drops it, and `findBySourceUri` is
  gone from the DAO on purpose — leaving it is an invitation to reinstate one project per
  face. The identity key is unchanged and still `fit3-catalog://<productId>/<versionCode>/<styleId>`,
  now also stored split across `productId`, `packageVersionCode` and `styleId` so nothing
  has to parse a string to compare it.
* **A `sourceUri` may not parse, and NULL is the answer.** Schema 1 rows came from the file
  picker and hold a `content://` document URI. `FacePackage.parseSourceKey` returns null for
  those and the schema 5 backfill leaves their three columns NULL — a migration that threw
  would strand the database on version 4 for good and the app would not open at all. NULL
  must read as "say nothing": `ProjectSummary.isOutdated` is false for an unknown version,
  never true, or a project that is already current gets badged and sent to re-download.
* **A project's name is stored, never derived.** `faceName` and `displayName` are the
  *face's* names and read identically on every project started from it, which is what made
  two projects on one face impossible to tell apart — same title, same face line, same
  vendor thumbnail, and they traded places on every open because the list sorted on
  `importedAtEpochMillis`, which `openProject` bumps. `projectName` is written once by
  `ProjectNaming.defaultName` against the face's other projects and thereafter only by a
  rename; nothing derived may overwrite it, or the next open would undo the rename. Sorting
  moved to `updatedAtEpochMillis`, which only a commit touches.
* **The face sheet's project list is a plain `Column`, never a `LazyColumn`.** That region
  is already inside a `verticalScroll`, and nesting a vertical lazy list in one throws at
  measure time. The `LazyRow` of style thumbnails beside it is fine because it scrolls the
  other way.
* **"Update" starts a *new* project on the newer version and leaves the old one alone.** An
  edit cannot be carried across a version change — the container may have changed shape — so
  `UPDATE` and `NEW_PROJECT` run exactly the same code and differ only in what the button
  says. That wording is the whole feature: `downloadPackage` always fetches
  `face.versionCode`, which *is* the newest, so the old button was already doing the right
  thing while describing it wrong.
* **A caption that promises no download has to check, not infer.** "Already on your device"
  was first derived from the projects on the face, which is nearly always right and wrong
  exactly when it matters: `PackageCache.readPackage` deletes a package it cannot read, so a
  project can still record a version whose bytes are gone. It reads `PackageCache.hasPackage`
  now — a file check, not a 32 MiB read. The same run is why there is a `FaceAction.OPENING`:
  "Downloading…" over a caption saying nothing would be downloaded is the same dishonesty
  the screen was being fixed for.
* **The watch keeps one face per slot, and no code can change that.**
  `DirectInstallPayload` requires `fileName == "SM-R390_<faceId>_256x402.bin"` and a single
  faceId protocol byte, so two projects on one face replace each other on the wrist however
  separate they are on the phone. The Projects page says so once, above the list, when any
  two projects share a face. Do not try to fix it in code; it is firmware.
* **Reverse the comparator, never the sorted list — and reverse only the primary key.**
  `Comparator.reversed()` flips the tiebreak with it, so faces sharing a name swapped places
  for a reason nothing on screen explains. Every sort is
  `compareBy(primary).maybeReversed(reversed).thenBy(stable)`, with the tiebreak outside the
  reversal. `CatalogSort.RECENT` reversed genuinely reverses the list: the store already
  serves newest first, so treating it as a no-op in both directions leaves the chip inert.
  The labels are **not** on the enums — `:core:model` has no resources, and a reversible sort
  needs two labels per option in the reader's language.
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
  **The other half of that gap is what a timeout must *not* do.** Now that it stops the
  worker, its budget has to cover every wait the protocol allows, and
  `TransferWatchdogBudgetTest` can only check the arithmetic: whether the state machine
  really reports after each wait is unassertable, because `OtaTransferDeliveryAgent`
  extends the accessory SDK's `SAAgentV2` and cannot be instantiated in a JVM test. So
  `TRANSFER_PROGRESS_GAPS` is maintained by hand, and a slow-but-healthy watch — the case
  that turns an unreported wait into a lost install — has never been staged either.
* No `androidTest` source set; the Room migration test runs under Robolectric.
  `:feature:editor` deliberately does **not** use Robolectric — it depends on
  `:core:delivery`, whose merged manifest declares a receiver from the accessory SDK JAR,
  and instantiating that pre-stackmap bytecode fails the JVM verifier before any test
  runs. Its ViewModel tests own `Dispatchers.Main` with a test dispatcher instead, and the
  module sets `unitTests.isReturnDefaultValues` so `android.util.Log` does not throw out
  of the failure path.
* **The update flow's UI has no automated coverage, by construction.** Every *decision*
  in it is pure and tested below `:app` — version comparison, feed parsing, the host
  allowlist, the signing verdict — but the two dialogs and the single `when` that maps
  `AppUpdateState` onto them live in `:app`, which has no test source set and is only
  linted. What has been exercised by hand on an emulator, end to end against the real
  GitHub release: the offer, the 36 MiB download, the signature and version checks, the
  system installer's confirmation, cancelling it (which must return to "ready to install"
  and not a stuck spinner), the unknown-sources hand-off, the unreachable-network message,
  and a real self-install from 0.1.0 to 0.1.1 that left every saved project in place. What
  has **not** been exercised is a signing-key mismatch on a real device — this machine's
  debug keystore happens to be the one CI signs with, so the `SIGNATURE_DIFFERS` path has
  only its unit test.
* The canvas gestures still have no test: `hitWidget`, `constrainDragCoordinate` and
  `stepDragAxis` are pure and covered, but nothing drives a real drag. Two canvas bugs
  came through that gap — the `pointerInput` block holding a stale `EditorSnapshot`,
  and the drag total accumulating the clamped coordinate instead of the finger's, both
  listed above. Neither was in those functions; both were in what the Composable *fed*
  them.
