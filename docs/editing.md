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
short of the ceiling. See [Resizing a Sprite](#resizing-a-sprite).

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
`00106`, **adding a full-panel background to a face that shipped without one**, and
identity or modified standalone-BIN installation.

Structural operations stay fail-closed on unclassified faces. The UI offers
resize only when the referenced Sprite frames match the proven schema — a unique
Sprite sequence, uniform RGB565+A frames, not the background — and every
remove or duplicate output is reparsed and validated before it is committed.

Unsupported: arbitrary widget construction, middle insertion, font replacement,
unknown pointer rewriting, and universal cross-firmware conversion.

## Resizing a Sprite

Four rules, each there because breaking it damaged a real face, a real watch, or the
user's ability to predict what a tap does.

**The image-record count must never change.** Proven on hardware. Frames are shared, so
an earlier attempt gave the resized sprite a private copy of its frames and left every
original record byte-identical — visually perfect, and the watch installed it and went
on showing the old face. The independent analyzer verifies those containers (CRCs, zero
byte residual, exact rebuild), so the bytes are sound and the refusal is firmware
policy. Records are therefore rewritten **in place**; `resizeSpriteEntry` asserts the
count afterwards.

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
`FaceRecordParser.sharedFrameClosure` closes over every widget reaching into the pool,
and `canResize` validates that whole closure — one uniform RGB565+A signature, no
background raster, nothing but Sprites reaching in — so the UI never offers an edit
whose commit would fail.

**Resampling always starts from the pristine container.** It is lossy, so resizing the
*current* frames chains loss onto loss — 114×136 → 56×69 → back up returned only the
detail that survived the smaller one. `StructuralEditor.resizeSprite` takes the unedited
container and resamples from it every time, so the result depends only on the size asked
for.

**Device-proven, and bounded by what the face shipped.** A resized sprite installs on an
SM-R390 and the watch redraws it. The bound is `spriteResizeLimit`: 128 px per side, *or*
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
smaller, no size reachable twice, and nothing the user could return to. `spriteResizeLadder`
instead offers fixed fractions of the extent the face shipped with, in **5% steps** from 20%
to 200%, and `resizeSprite` already resamples the pristine frames, so a rung always
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
the difference between the two is still unknown, so `resizeSpriteEntry` keeps asserting its
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
the editor's default is to apply a widget edit to all of them. **Styles are
independent colourways, not renderings of one shared layout**, so a widget in the
style being edited need not exist in its siblings at all. Face `00001` `style0`
carries Value widgets for data sources 17 and 18; `style1` has neither and draws
a Static plus data source 48 instead.

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

## Rules established across all 99 editable faces

`EveryFaceRendersTest` sweeps every container in the corpus. What it settled:

- **The panel is not raster 0.** The canvas size comes from the container's
  declared geometry, parsed from the entry path
  (`./SM-R390_00046_256x402/style0.bin` → 256 × 402). A style is not obliged to
  carry a full-panel background raster at all: face `00022` opens every style
  with a 37 × 28 icon, `00108` styles 0–3 with a 204 × 204 dial, and every
  `aod.bin` except `00046`'s with a digit sprite. Sizing the canvas from raster 0
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
  widget rectangle. Never call `displayCoordinate` on a widget directly.
- **A Hand's sprite is `words[1]`** — the one word that resolves to a raster in
  all 469 Hand records. It is resolved to give the record a real artwork size, but
  a Hand stays `HIDDEN`: the watch rotates it about the pivot in `+0x20`, so
  outlining a rectangle there would be a lie.
- **52 of the 99 faces have a variant with nothing selectable**, and in every case
  it is because all its non-background records are clock hands. That is the
  assertion that would catch a regression making more of the catalogue
  uneditable.
- **Widget type 6 exists** — 75 records, absent from the format census. It is
  uninterpreted, so it reports as `WidgetCategory.UNKNOWN`; its position is still
  editable and its bytes are preserved.
- **A Static's pointer is `+0x20` only.** `words[0]` is `0x0` in every corpus
  Static, and `0x0` is the background raster's own relative offset — so scanning
  the type-word list for "something that resolves" silently aliases unrelated
  widgets onto the background.
- **A Sprite addresses exactly `+0x20` frames.** Take that many words, no more.
- **A raster-backed widget's extent is its raster's**, not `0x1C`/`0x1E`. Face
  `00079` stores width 1 for sprites whose frames are 52 px wide; `00022` stores
  height 20 for frames 136 px tall. Only Pair, Comp, Badge, Arc and LineBar have
  no raster to measure, and only Badge reinterprets those fields.

## No field holds another widget's index

No field has ever been shown to hold another widget's global index.
Cross-record references go through `sequence_id` or image byte offsets, both of
which structural edits preserve.

This matters. An earlier version refused any removal where an opaque word
happened to equal an index in the renumbered range, which blocked **68% of
removals** and left 18 of 99 faces with nothing removable. It is now replaced by
post-edit invariants in `StructuralEditor.requireSurvivorsUnchanged`. Do not
reinstate the guess.

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
