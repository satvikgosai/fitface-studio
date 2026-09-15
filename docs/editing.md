# The editing model

What `:core:format` will and will not change, and what the catalogue sweep proved.
[bin-format.md](bin-format.md) is the byte-level reference this rests on.

## Preservation

The format layer preserves:

- the exact 32-byte OPPO header;
- all exact 74-byte directory records;
- original paths, order, gaps, trailers and unknown entries;
- raw widget records and opaque record tails;
- image trailers and unsupported type-specific fields.

Known fields are decoded as *views* over preserved bytes. Same-size edits patch
allowlisted ranges. Structural changes are allowed only for profiles whose index
and pointer dependencies are classified. Unknown integer values are never
globally rewritten as presumed pointers.

## Validation boundaries

1. Downloaded-package identity, magic, sizes, directory bounds and CRCs.
2. Mutation dimensions, exact selectors, offsets and schemas.
3. Output reparse and byte-identical second emission.
4. One-byte face and sampler ID limits, and the canonical filename.
5. Final byte-copy size and SHA-256 immediately before transfer.
6. Total container size against the watch's 4 MiB ceiling, at the edit and again
   before transfer — see [The size ceiling](#the-size-ceiling).

Warnings retain unusual but bounded opaque data. Errors block editing and
delivery.

## The size ceiling

`WATCH_CONTAINER_BYTE_CEILING` is 4 MiB, and no edit may take a container past it.
**Confirmed on an SM-R390.** Nothing in the format asks for it: every size field in the
container and in its style entries is a `u32`, and a bigger container parses, validates and
round-trips exactly the same. It is firmware policy, of the same kind as the image-record
rule and with the same symptom — the transfer completes, the install command is accepted,
and the watch carries on showing the old face.

Two independent observations land on it:

* **Every one of the 99 catalogue containers fits inside 4 MiB.** The largest, `00072`,
  is 4,149,034 bytes — 98.9% of it.
* **Adding a full-panel background is the only edit big enough to cross it**, at 205,880
  bytes a style, and the faces tried on an SM-R390 split exactly across it:

  | Face | Styles given one | Result | Watch |
  | --- | --- | --- | --- |
  | `00008` | 4 | 2,332,582 (2.22 MiB) | renders the new background |
  | `00016` | 3 | 3,776,058 (3.60 MiB) | renders the new background |
  | `00019` | 3 | 4,365,627 (4.16 MiB) | **ignored**, old face stays |
  | `00021` | 3 | 4,573,874 (4.36 MiB) | **ignored**, old face stays |

  The two refused containers are as sound as the two that work — same edit, same
  assertions, verified by the independent analyzer — so the difference is size and
  nothing else. The evidence closes on `4,149,034 .. 4,365,626`, and the limit inside that
  window is 4 MiB. The table above is the evidence for that figure, not the reasoning
  towards it: the ceiling is settled and the app enforces it as such.

One hardware result that would otherwise look like a separate rule about resizing falls
out of this one — a sprite grown past its shipped extent on a face already 76,640 bytes
short of the ceiling. See [Resizing a widget](#resizing-a-widget).

So a face too large to carry a background in every style carries one in **as many styles
as fit**, selected style first, because that is the style the install activates and the
only one the canvas shows. `StructuralEditor.backgroundStylesThatFit` chooses them and
the Background page names them before an image is picked. Of the 16 faces with a
backgroundless style, ten take one everywhere, five lose some styles to the ceiling
(`00007`, `00019`, `00021`, `00024`, `00104`) and `00022` — 4,117,664 bytes on its own —
has room for none. `Session.validatedBytes()` re-checks the size on the way to the watch,
so a project saved by an older build cannot install an oversized container either.

## What is supported

Device-proven: same-size background replacement, RGB565 and RGB565+A marker and
tint changes, Pair position and colour edits, type-aware `00106` background and
Sprite relocation, exact non-final widget removal and append duplication on
`00106`, **adding a full-panel background to a face that shipped without one**, widget
resize, and identity or modified standalone-BIN installation.

Structural operations stay fail-closed on unclassified faces. The UI offers resize only
where the widget's own artwork matches a shape this app can rewrite — one uniform pixel
format across the whole shared raster pool, not the panel background — and every remove or
duplicate output is reparsed and validated before it is committed.

Unsupported: arbitrary widget construction, middle insertion, font replacement,
unknown pointer rewriting, and universal cross-firmware conversion.

## Resizing a widget

**Resize is three mechanisms, not one feature**, and which one a widget takes is decided by
[`WidgetSchema.ResizeModel`](../core/format/src/main/kotlin/dev/fitface/studio/core/format/WidgetSchema.kt)
— one table, read by the capability gate and by the edit, because a control the UI lights
and the commit refuses is the failure this whole path is arranged to avoid.

| Mechanism | Types | Records | What else moves with the size |
| --- | --- | ---: | --- |
| Resample the raster in place | Static, Sprite, Hand, image Arc, LineBar | 2,714 | see below |
| Rewrite a stored box | vector arc, Rule | 159 | nothing — no bytes are added at all |
| Change the firmware font size | Value, Composite | 1,161 | **not offered** — see below |

Of the catalogue's 4,034 records, 859 were resizable when only Sprites were; **2,478 are
now**, and **every one of the 99 faces has something resizable** where 36 of them had
nothing. The remainder is the 1,161 text records, the 388 Statics that draw the panel
background, and 7 Sprites whose pooled frames disagree about their own size — for those,
"the largest frame is the extent" does not hold and one pair of numbers cannot describe the
pool. `WidgetResizeCensusTest` pins every one of those counts;
`WidgetPlacementTest.resizableSpritesAreAcceptedByTheStructuralEditor` commits a real resize
for every record the gate offers one on, across all 99 faces; and `ResizeLadderWalkTest`
walks several rungs in both directions, which is a different guarantee — see the Rule below.

### What else moves with the artwork

Each of these is a field that would otherwise be left describing artwork that no longer
exists — and a stale one fails no validation. The container parses, the CRCs match, the
install is accepted, and the widget simply draws wrong.

* **A Static and a Sprite carry no size at all**, so resampling is the whole edit. A Static
  is also the type that proves the *selector*: its data source is `0` in 678 of the
  catalogue's 681 records, so `(type, sequenceId)` cannot name one. Resize takes the same
  identity tuple as every other widget edit now, through `StyleWidgetMatch`, which also
  means a face whose styles carry different widgets is edited where it has them instead of
  failing outright.
* **A Hand's pivot scales with its artwork, and `x`/`y` absorbs the difference**, because
  the watch rotates the hand about `x + pivot` and that point must not move. `pivot_x` is
  the artwork's horizontal middle in 448 of 469 records, so this is what the producer does
  too. The pivot is scaled from the **pristine** pivot and read out of the **pristine
  entry** — reading it at the pristine record's offset in the *current* entry is a bug that
  looks like it works, since the offsets coincide until something changes record lengths,
  and it re-scales the pivot from whatever the last resize left.
  `WidgetResizeByTypeTest.aHandResizeRoundTripIsExact` is what caught that.
* **An image Arc's and a LineBar's stored box scales with the raster**, and the box is what
  a requested size *means* for them. The watch draws their artwork at native size centred
  in that box rather than scaling it into it — face `00108` settles it from the vendor's own
  render, where a 204×204 ring in a 256×256 box draws at 204 px, and styles 4–5 reach the
  identical picture with a 256×256 raster whose ring is inset 26 px. So the raster is scaled
  by the box's ratio rather than set to the requested numbers. A LineBar's `+0x30` thickness
  equals its stored height in all 16 catalogue records and has to go on equalling it; it is
  what the watch derives the bar's corner radius from.
* **The pool rule extends to those fields, not just to the pixels.** 12 of the 16 LineBars
  share one raster three ways and 18 of the 469 Hands share theirs, so resizing the artwork
  under one of them leaves the others' own boxes and pivots describing a size that is gone.
  Every widget in the pool is rewritten, each scaled from its own pristine record — face
  `00028` puts an 84×84 box and an 88×88 box on the same face, so setting them all to one
  figure would resize a neighbour to a size nobody chose.

### The two field-only resizes

A vector arc and a Rule name no raster, so their resize is a **same-size patch**: nothing is
resampled, the image section is untouched, no pointer is rewritten and the container does not
change length by a single byte. That makes them the only resizes that cannot cross
[the size ceiling](#the-size-ceiling), cannot disturb the image-record count and cannot leave
a pointer stale — the three things that have actually gone wrong here on hardware. What they
rest on instead is the constructor's own reading of those fields, which is why they are also
the two with the thinnest evidence behind them: **every other edit here rewrites bytes some
hardware run pinned down, and these two rest on a reading of the schema.**

A Rule's `+0x1C`/`+0x1E` is its second *endpoint*, so the resize scales the endpoint vector
rather than replacing it. Writing `x + width` there would flip the 52 of the 84 Rules whose
stored endpoint is the far one across their own start point, and would turn each of the 32
exactly-horizontal Rules into a diagonal the moment the ladder asked for a height. Scaling
keeps the sign of each delta and keeps a zero span at zero.

**And its `+0x30` thickness scales with the span**, which is the one place a Rule differs
from the vector arc beside it. A Rule's extent is `max(|span|, thickness)` per axis — the
floor `drawnExtents` applies — so on an exactly horizontal Rule one axis of the reported
extent *is* the thickness and no endpoint write can move it. While the thickness was left
alone, the extent that came back was never the rung the editor had offered, `nextWidgetSize`
re-offered the rung already in force, and the format layer refused it with the button still
lit: **56 of the 84 Rules reached that within a few taps**, face `00049` on the second one.
Scaling the thickness by the same ratio makes the whole extent scale linearly, which is
exactly what the ladder assumes. Where an axis of the reported extent is the thickness, the
thickness becomes the value *requested* for that axis rather than a second rounding of the
same number — the two disagreed by one pixel on `00049`, which is the same off-the-ladder
state one step later. `ResizeLadderWalkTest` walks every Rule in the catalogue three rungs
in both directions, and it is in `:core:format` because the ladder now lives in
`:core:model` for exactly that reason: a rung the format layer would refuse is a button that
fails, and one step of a sweep cannot see it.

A vector arc's thickness is deliberately *not* scaled: it is its own field at `+0x40`, and
face `00108` ships one box at three different thicknesses across its styles, so it is a
design property rather than part of the size.

Their growth is bounded by `WIDGET_EXTENT_CEILING` rather than by `RASTER_RESIZE_CEILING`,
because the reason raster growth is capped at 128 px past the shipped extent is the
container's 4 MiB limit, and these add nothing to it. The catalogue ships a 400 × 400 vector
arc box on a 256 × 402 panel, so the panel is not the bound either.

### Why a Value and a Composite are refused

**Their stored box is not their size.** Face `00005` proves it from the vendor's own render:
the same Composite — source 0, font binding 0, alignment code 0, position (42,351) — is
stored with a box of 180×**40** in `style0` and 180×**60** in styles 1–3, and the rendered
text is pixel-identical in all of them, 129×39 at (63,360). Across the catalogue the box
height equals the requested font size in only 32 of 1,161 records; the median ratio is 1.2,
so the producer sized the box *from* the font.

The size is four bytes in `font_N.bin` at `+0x58`, and that is a **container-level**
resource. 122 of the 180 referenced bindings never serve two widgets in one style, so
editing one would be exactly the cross-style edit the editor already defaults to — but 58
serve two to five different widgets inside a single style, **32 serve `aod.bin` and numbered
styles at once**, which no `Session.editTargets` can separate, and a size that is not on the
family's supported list falls back silently to another face. On top of that the app cannot
render firmware text at all, so there would be no preview of the result: the composer
resamples the vendor's rendered pixels into a widget's new rectangle, which for a box-only
"resize" would show scaled text the watch will never draw — precisely the invented pixel the
AOD renderer was fixed for. It is a separate feature with its own shared-resource model, and
it is not offered as a resize.

### The rules that hold for every raster resize

Four rules, each there because breaking it damaged a real face, a real watch, or the
user's ability to predict what a tap does.

**The image-record count must never change.** Proven on hardware. Frames are shared, so
an earlier attempt gave the resized sprite a private copy of its frames and left every
original record byte-identical — visually perfect, and the watch installed it and went
on showing the old face. The independent analyzer verifies those containers (CRCs, zero
byte residual, exact rebuild), so the bytes are sound and the refusal is firmware
policy. Records are therefore rewritten **in place**; the edit asserts the count
afterwards.

That rule is narrower than it first looked: a container that gains *a panel background and
the Static that draws it* installs and renders — see the next section. So the refusal is
not "more image records than it shipped with"; something about appended sprite frames
specifically is what the watch dislikes, and nobody knows what yet. Until someone does,
the resize keeps its count.

**A resize moves the whole glyph pool.** A style keeps one pool and points several
widgets into it: face `00022` `style0` gives the hour's tens digit frames 2–4 and its
units digit frames 2–11, and **740 of the corpus's 859 resizable sprites overlap like
this**, most often four widgets deep. They are the same *records*, so there is no
resizing one widget's copy. Rewriting only the frames the selected sprite named left the
neighbour drawing three small glyphs and seven large ones, with its box still reporting
114×136 because a raster-backed extent is the largest frame it addresses.
`FaceRecordParser.rasterPool` closes over every widget reaching into the pool, and
`canResize` validates that whole closure — one uniform signature in a format the resampler
can read, no background raster, nothing but widgets of the same type reaching in — so the
UI never offers an edit whose commit would fail. That format condition used to be RGB565+A
alone, which was never about safety: it was the one format the resampler could read, and it
refused **620 Sprites, 41% of the catalogue's**, for it. No pool in the catalogue spans two
widget types, so the same-type rule has never fired on a real face; it is there because a
pool that did span two would not be the thing this edit takes it for.

**Resampling always starts from the pristine container.** It is lossy, so resizing the
*current* frames chains loss onto loss — 114×136 → 56×69 → back up returned only the
detail that survived the smaller one. `StructuralEditor.resizeWidget` takes the unedited
container and resamples from it every time, so the result depends only on the size asked
for.

**Device-proven, and bounded by what the face shipped.** A resized widget installs on an
SM-R390 and the watch redraws it. The bound is `widgetResizeLimit`: 128 px per side, *or*
the extent the frames shipped at when the face ships something larger. A sprite can
therefore always be taken back to its own artwork — `00022`'s hour digits are 114×136 —
because that size is the one whose bytes the store shipped: resampling to the original
dimensions rewrites each frame record at its original length, so the container returns to
exactly the size it came with. Growth *past* the shipped extent is what stays capped at 128.

That bound used to be a flat 128 in both directions, and a shrunk `00022` digit was stuck
below its own artwork. Raising it looked like a firmware refusal — the watch installed the
result and carried on showing the old face — but `00022` is 4,117,664 bytes, 76,640 short of
4 MiB, and frames grown past what it shipped took it over
[the size ceiling](#the-size-ceiling). Restoring cannot: it hands the container back its
shipped size. `SpriteResizeFidelityTest.aShrunkSpriteCanBeRestoredToTheExtentItShipped`
pins both halves.

**The sizes come from a ladder, not from scaling what is on screen.** Smaller and Larger
used to multiply the current extent by 0.875 and 1.125, which is not reversible: a 60×60
sprite went to 52×52, back up to 58×58, down to 50×50 — every round trip a little
smaller, no size reachable twice, and nothing the user could return to. `widgetResizeLadder`
instead offers fixed fractions of the extent the face shipped with, in **5% steps** from 20%
to 200%, and `resizeWidget` already resamples the pristine artwork, so a rung always
produces the same pixels. Two properties follow: Smaller then Larger is exactly the size
it started from, and the panel can say *90% of the original 60 × 60* instead of only a
pixel count. A step of 10% was the first cut and moved a 60 px sprite 6 px at a time, which
is too coarse to place a glyph with; 5% halves it, and on anything up to 20 px it is a
single pixel.

Rungs past the 128 px ceiling are **dropped, not clamped**. Clamping each side separately
broke the aspect ratio the panel's own label promises — repeatedly growing a 57×68 sprite
used to end at 128×128, a square — so a face whose frames ship larger than the ceiling
simply tops out below 100%. An extent that is on no rung, from a project edited by an
older build, snaps onto the ladder in the direction of the tap.

The background image's zoom follows the same rule, for the milder version of the same
problem: the SIZE buttons multiplied by 1.02, so the step grew with the zoom — 100 → 102 →
… → 110 → 113, with 111% and 112% unreachable — and after a pinch the grid was wherever the
gesture left it, 137 → 140 → 143 and never a round number again. They now step the
percentage by 2 points and snap onto that grid, so every zoom is reachable and every step is
the same size. The pinch itself stays continuous.

## Adding a background to a face that has none

Fourteen of the 99 editable faces carry no panel-sized raster in any style, and `00011`
and `00108` carry none in some of theirs. Those faces paint their widgets straight onto
the watch's black panel, so there is nothing to replace — the Background page offers to
*add* one instead, and `StructuralEditor.addBackgrounds` writes it.

**Device-proven, up to a size.** A container that gains a panel background and the Static
that draws it installs on an SM-R390 and the watch renders the image — on `00008` and
`00016`. That refines the resize rule above rather than contradicting it: what the watch
rejects is not "any container with more image records", because this is one. The only edit
known to be ignored for its *records* is appending private frames to a resized Sprite, and
the difference between the two is still unknown, so the resize keeps asserting its
count.

What did turn out to be a second limit is total size. The same edit on `00019` and `00021`
produced containers the watch accepted and then ignored, and the only thing separating them
from the two that work is that they cross 4 MiB — see
[The size ceiling](#the-size-ceiling), which is why this edit now writes only the styles
that fit.

**What it writes.** Every style entry in the corpus that has a background is built the
same way, and the new Static is copied from them field for field:

| Observation | Corpus |
| --- | --- |
| The background is drawn by widget ordinal **0** | 348 / 348 style entries |
| That widget is a Static of record size **40** | 348 / 348 |
| Its `+0x20` is the raster's relative offset | 348 / 348 |
| Its geometry is `x=0 y=0 w=0 h=0`, sequence 0 | 264 / 348 (the rest differ only in `w=1`) |
| Its whole record is `01 00 00 00 …` with a zero tail | 347 / 348 |
| The raster's four trailer bytes are zero | 6,315 / 6,315 rasters |
| The background is `IMAGE_RGB565` | 309 / 348 |

`IMAGE_RGB565` is also the only sane choice for a raster the app invents: no alpha plane
means no panel mask to fabricate and the watch paints the full rectangle.

**The raster is appended, not inserted at index 0.** This is the one place the result
deliberately differs from a shipped face, and it was learned on hardware. The first
version put it at image 0 to match those 348 entries, which shifts every other raster and
therefore changes what relative offset `0x0` names. `0x0` turns up all over records that
do not use it as a pointer — 681 Static `words[0]`, 734 Pair colour words, hundreds of
zeroed Comp fields — and face `00019`'s two Value widgets both hold `words[3..4] = 0`.
After that insert those named a 256×402 background instead of the 102×132 digit they had
always named, and on the watch **the day-of-week Value stopped drawing while the date
kept working**. Appending removes the question: no existing offset moves, no pointer is
rewritten, and `AddBackgroundTest.everyOriginalOffsetStillNamesTheSameRaster` pins it.
Only the widget table changes shape, because the Static takes index 0 and everything
already there moves up one.

**Arc and LineBar address rasters.** Found while auditing the relocation this edit needed:
`words[4]` of an Arc and `words[2]` of a LineBar resolve to a real image record in all 30
and 16 corpus records, and none of those values is zero. The old relocation knew only
about Static, Sprite and Hand, so an edit that moved the image section under an Arc left
its pointer stale — which draws nothing and fails no validation.
`FaceRecordParser.imagePointerFields` is now the one pointer map every relocation and
every post-edit check reads, and `referencedImages` stays narrower on purpose: an Arc's
310×310 raster is not its drawn extent, and measuring it that way would report the widget
as the background layer and make it unselectable.

## Applying an edit to every style

A container holds several `styleN.bin` variants plus an optional `aod.bin`, and
the editor's default is to apply a widget edit to every **numbered style**. It
never reaches `aod.bin` — see [Editing the always-on display](#editing-the-always-on-display).
**Styles are independent colourways, not renderings of one shared layout**, so a
widget in the style being edited need not exist in its siblings at all. Face
`00001` `style0` carries Value widgets for data sources 17 and 18; `style1` has
neither and draws a Static plus data source 48 instead.

So a cross-style edit resolves rather than asserts. `StyleWidgetMatch` pairs the
same widget across variants — by global index first, since that is the identity
the selected style used, then by data source plus stored position, which is what
matches variants numbering their tables differently. `aod.bin` numbers its own
much shorter table independently, so it is matched on data source and position
only.

The selected variant is strict: it must match, and it must change, or the edit
fails loudly. Every other variant is best effort — edited where the same widget
is unambiguously present, left byte-identical where it is not. `changedStyles`
therefore reports what was actually rewritten, not what was offered.

Requiring a match in *every* variant is what made 183 of the corpus's 2,833
selectable widgets across 20 faces refuse to move, and 785 refuse removal or
duplication across 43 faces. On face `00001` that was every selectable widget, so
the face could not be edited at all: the canvas showed the drag and then snapped
back. `EveryFaceRendersTest` now sweeps the all-variant path for the whole
corpus.

## Editing the always-on display

`aod.bin` is a face entry like a style — same 24-byte header, same widget
records, same rasters, same pointer rules — so the format layer edits it with the
same calls, and every rule in this document applies to it unchanged. All 99
corpus containers carry one: 442 widgets and 991 rasters between them, 66 digital
(sprite sources 2/3/10/11) and 33 analog (hands on sources 1 and 9), with no face
in both groups and none in neither.

What is *not* like a style is everything above the format layer, and the whole of
it reduces to two rules.

**AOD is edited alone, and never by a style edit.** `Session.editTargets` is the
single place that decides: with AOD selected the target list is `aod.bin` and
nothing else, whatever the apply-to-every-style switch says, and a style edit's
target list never contains it. This is enforced in the repository, not by the UI
hiding the switch — `AodIsolationTest` asserts the untouched entries are
byte-identical in the container written to disk, in both directions. It is a
regression test as much as a guarantee: `moveWidget` used to append `aod.bin` to
its own apply-to-all list, so a style-wide move silently moved the matching AOD
widget too.

**AOD is not an installable style.** It has no `preview.bin` frame, no packaged
`assets/…png`, no style index and no sampler id — `preview.bin` holds exactly one
frame per numbered style, which `CorpusParityTest` pins. So the editor keeps two
selections: `selectedVariant` is what the canvas shows and edits, and
`activeStyleName` is what installs. Selecting AOD moves only the first. Three
things follow, and each one was a bug before it was a rule:

* the sampler id and the persisted project style come from `activeStyleName`, so
  looking at AOD cannot change what the watch is asked to activate;
* the face-picker thumbnail is rendered *from the canvas* into the active style's
  `preview.bin` frame, so refreshing it while AOD is selected would paint the
  always-on face into a style's picker entry. `refreshThumbnail` refuses;
* `EditorSnapshot.styleNames` counts styles only, so a four-style face reads
  "4 styles · always-on display" and never "5 styles".

The picture uses `WidgetPreviewComposer`, the same current-resource renderer as
numbered styles. It draws every supported record in order, including sampled hands,
gauges and text assembled from the container's dictionaries and font bindings.
Firmware glyphs are not embedded in the package, so text uses a disclosed Android-font
approximation at the binding's pixel size. Fixed preview readings are not watch data.
Firmware-only resources and unavailable sources remain unrendered and are disclosed;
there is no stock-preview crop fallback. See [the preview pipeline](architecture.md#the-preview-pipeline).

## Rules established across all 99 editable faces

`EveryFaceRendersTest` sweeps every container in the corpus. What it settled:

- **The panel is not raster 0.** The canvas size comes from the container's
  declared geometry, parsed from the entry path
  (`./SM-R390_00046_256x402/style0.bin` → 256 × 402). A style is not obliged to
  carry a full-panel background raster at all: face `00022` opens every style
  with a 37 × 28 icon and `00108` styles 0–3 with a 204 × 204 dial. `aod.bin` is
  no different and no more uniform: 32 of the 99 carry a full-panel raster (26
  RGB565, 6 RGB565+A) and the other 67 compose over black, independently of
  whether the face is digital or analog. Sizing the canvas from raster 0
  shrank those faces to the icon, after which every larger widget matched the
  "covers the whole canvas" test, was reported as the background layer, and could
  not be selected or dragged. Use `FaceRecordParser.panelSize` and
  `FaceRecordParser.backgroundImage` — null means "draws onto black".
- **A style may stack more than one full-panel raster.** Faces `00076` and
  `00089` each have two 256×402 RGB565+A layers, each drawn by its own Static.
  "At most one background layer" is not a rule; the rule is that the BACKGROUND
  widgets are exactly the ones drawing a panel-sized raster.
- **A Badge's `0x1C`/`0x1E` are its second endpoint, and the stored coordinate is
  the *far* one in 52 of the corpus's 84 Badges.** The span is `|x2 − x|` and the
  rectangle starts a whole span earlier when reversed. Without that correction
  those Badges landed off the panel — face `00089` had one at x=271 on a 256-wide
  panel — and could not be selected. `WidgetGuide.drawOffsetX/Y` carries it, and
  `drawLeft`/`drawTop` in `:core:model` are the only correct way to derive a
  widget rectangle.
- **A Hand's sprite is `words[1]`** — the one word that resolves to a raster in
  all 469 Hand records. It is resolved to give the record a real artwork size, but
  a Hand stays `HIDDEN`: the watch rotates it about the pivot in `+0x20`, so
  outlining a rectangle there would be a lie.
- **52 of the 99 faces have a variant with nothing selectable**, and in every case
  it is because all its non-background records are clock hands. That is the
  assertion that would catch a regression making more of the catalogue
  uneditable.
- **Widget type 6 is a vector arc** — 75 records across nine faces. It draws a
  gauge from a stored colour, thickness and angle range and names no raster, which
  is what separates it from type 16, the arc that draws from artwork. It used to
  report as `WidgetCategory.UNKNOWN`.
- **A Static's pointer is `+0x20` only.** `words[0]` is `0x0` in every corpus
  Static, and `0x0` is the background raster's own relative offset — so scanning
  the type-word list for "something that resolves" silently aliases unrelated
  widgets onto the background.
- **A Sprite addresses exactly `+0x20` frames.** Take that many words, no more.
- **A raster-backed widget's extent is its raster's**, not `0x1C`/`0x1E`. Face
  `00079` stores width 1 for sprites whose frames are 52 px wide; `00022` stores
  height 20 for frames 136 px tall. `0x1C`/`0x1E` is a signed extent only on
  Value, Composite, both arcs and LineBar; on a Static or a Hand it is the
  alignment pair below, and on a Badge it is the second endpoint. That is why
  `WidgetRecord` calls the halfwords `raw1C`/`raw1E` and exposes
  `storedWidth`/`storedHeight` only where they mean one.

## Four fields hold another widget's index

Static and Hand keep an alignment code at `+0x1C` and the global index it is
measured from at `+0x1E`; Value and Composite keep the same pair at `+0x20` and
`+0x22`. `0xFFFF` in the code makes the coordinates absolute — and **no record in
the catalogue does that**. All 2,311 of them position themselves against
something, which is why the app's old sign-based anchoring survived so long: 1,914
of those references name widget 0, and in every one of those styles widget 0 is a
full-panel background at the origin, where "inset from the panel edge" and "offset
from the target's edge" are the same sum. The other 397 name 5, 10, 20, 30 or 40,
which are records in no style, and an unresolvable reference falls back to the
whole face.

Three rules follow, and all three are enforced:

- **A renumbering carries the references with it.** `remapAlignmentTarget` rewrites
  exactly those fields, only where the reference names a record that existed
  before, so the producer's non-referencing values are left alone.
- **A widget others are positioned against cannot be removed.** On face `00106`
  twelve of nineteen widgets are measured from widget 0; renumbering alone would
  leave all twelve naming index 0, which by then is a different widget. They would
  be laid out against it and land somewhere else on the watch, with the container
  parsing, validating and installing perfectly.
- **The survivor invariant compares what a reference names, not its integer.**
  `requireSurvivorsUnchanged` takes the same mapping the edit applied.

The guess this replaces was the *opposite* claim — that no field holds an index —
and the guard built on the guess before that scanned every word for a value that
looked like one, which blocked **68% of removals** and left 18 of 99 faces with
nothing removable. Neither extreme is right: four named fields are references, and
nothing else is.

Image references are offsets **relative to the style's image-section start**, so
a style entry can be relocated wholesale without touching a widget word.

## Alpha is not cosmetic

| Format | Layout | Alpha? |
| --- | --- | --- |
| `0x0082` | RGB565, 2 B/px | **No** — the watch paints the full rectangle |
| `0x0080` | RGB565 + 1 alpha byte, 3 B/px | Yes |
| `0x0088` | 256-entry **BGRA** palette (1024 B) then 1 index byte/px | Per-palette-entry |

The editor must **not** mask an `0x0082` sprite's backdrop, or the preview shows
transparent digits that install with a black box behind them — face `00106`,
widgets 7–10. `WidgetImageLayer.isOpaque` carries this through to the composer
and the inspector.

`0x0088` appears exactly once in the whole live catalogue — face `00002` style0's
background — and its absence made that face impossible to open. Because the
palette is fixed-length, an indexed background can be replaced as a same-size
patch; `IndexedImage.quantize` does the median-cut.
