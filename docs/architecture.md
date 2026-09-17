# Architecture

Kotlin, Jetpack Compose, unidirectional data flow, MVVM, Hilt, Room, DataStore,
Navigation 3, OkHttp and Coil. The Storage Access Framework is used only when the
user picks a replacement image.

```text
Watch faces UI -> LibraryViewModel -> FaceCatalogRepository
                                         |
                                         +-> catalogue / update / download
                                         +-> PackageCache (catalogue + packages)
                                         +-> WatchFaceRepository -> private project
                                                                    |
Editor UI -> EditorViewModel ---------------------------------------+
    |                                                               |
    +-> lossless format core                                        |
    +-> Fit3DirectInstaller -> accessory discovery -> Bluetooth SPP -+
```

## Modules

| Module | Owns |
| --- | --- |
| `:app` | Application root, Hilt entry point, theme, navigation, and the two app-menu dialogs |
| `:core:model` | Framework-free contracts and immutable state. Both repository interfaces live here. |
| `:core:format` | Container parse, validate, edit, CRC, serialize, the per-type record schema and layout resolver, and the project archive. Pure Kotlin, JVM-tested. |
| `:core:data` | Catalogue client, on-disk caches, private projects, Room, DataStore, image I/O, self-update |
| `:core:delivery` | Companion probing, accessory discovery, payload verification, RFCOMM |
| `:core:ui` | Theme tokens and shared components |
| `:feature:library` | Catalogue browsing, sorting, download, style selection, projects |
| `:feature:editor` | Canvas, inspector, validate, install |

Android APIs stop at `:core:data`, `:core:delivery` and the UI modules. Binary
parsing, protocol framing, CRC calculation, descriptor generation and install
packet encoding stay pure Kotlin and are JVM-tested.

## State ownership

- Compose renders immutable state and emits user intent.
- ViewModels own interaction state and coroutine lifecycles.
- `WatchFaceRepository` owns the original and edited container snapshots.
- Every committed edit produces a new reparsed snapshot.
- Every successful commit updates the private project BIN; imported-widget projects
  atomically commit the BIN and required provenance together in a checkpoint. A failed
  commit rolls the in-memory session back — container, audit, selected style and origins.
- `Fit3DirectInstaller` owns the delivery state machine.
- Transfer bytes are copied and identity-frozen before delivery begins.

Each catalogue selection creates a unique editor navigation destination, so a
previous face's ViewModel state can never be reused for a new repository session.

## Invariants

These six hold everywhere and are the reason a malformed container cannot reach
the watch. They are the list a change has to preserve:

1. Every mutator commits through `WatchFaceRepositoryImpl.commit`, which rolls
   back container, audit and selected style if the resulting snapshot throws.
2. Structural edits reparse and revalidate before they are accepted.
3. `Session.validatedBytes()` is fail-closed and is the **only** path to the
   watch: magic, validation errors, blocking warnings, a byte-identical round
   trip, and a re-walk of every style and AOD entry.
4. Downloads are bounded, must match the declared size, and must resolve over
   HTTPS to a host on an allowlist — checked both before the request and again on
   the post-redirect URL. There are two, with different ceilings and different
   allowlists: a face package at 32 MiB from the store hosts, and an app update at
   64 MiB from GitHub. Neither limit is a measurement; the published APK is 36 MiB,
   which is why the package ceiling could not simply be reused for it.
5. Nothing is written outside app-private storage except a project archive, and only
   to a document the reader chose in the system picker. The app holds no storage
   permission and picks no path of its own.
6. A container may not pass `WATCH_CONTAINER_BYTE_CEILING` — 4 MiB exactly.
   `rebuild` refuses any growth past it and `validatedBytes()` refuses to send
   one, because a container the watch ignores otherwise looks exactly like a
   successful install. The limit is a measured firmware behaviour, not a format
   rule; [editing.md](editing.md) records the hardware runs that closed it.

The image-record count is a seventh rule of the same weight, but it is a format
constraint rather than a pipeline one — see [editing.md](editing.md).
## The preview pipeline

The canvas is reconstructed from the current container, not from the stock picture.
`WidgetPreviewComposer` renders styles and AOD through the same path:

- Static images, sprites, animations and clock/gauge hands decode the record's own
  raster pointers through `WidgetSchema`. Sprites use a deterministic sample frame;
  animations show frame zero. Hands rotate about the pivot inside their raster.
- Value and Composite labels read the numbered font binding, localized dictionary,
  formatting program, colour, spacing and text alignment. The glyphs are in watch ROM,
  **not in the package**: `WidgetTextRasterizer` substitutes Android fonts at the
  requested pixel size. Resizing the label box does not scale that font.
- Vector arcs and rules use their stored geometry, thickness and colour. Image arcs
  and bars mask their native-size raster with the sampled progress geometry.
- Reserved types 8/10/11/12/14/15 are inert, as on the firmware. Type 9's firmware-only
  resource and unavailable readings have no fabricated artwork; the preview is marked
  approximate. Sensor readings and animation state are illustrative, never live data.

Each drawable record becomes a `WidgetImageLayer`, with transparent pixels and an
offset for rotation or stroke overhang. `WidgetLayerComposer` paints those layers in
record order onto the black panel, **including all** full-panel background records.
Source-over blending preserves alpha on the transparent overlay as well as the final
opaque image. Layout comes from `WidgetLayout`/`drawLeft`/`drawTop`, not from signs or
guessed anchors.

The canvas, widget thumbnails and drag artwork share these isolated layers. Dragging
recomposes the records below and above the selected one separately: the old position
reveals underlying widgets intact, and records above it stay above it while it moves.
Removing or resizing cannot leave pixels from a stock preview behind. A sprite guide
can bound several differently sized frames; the chosen frame stays at its native size.

This composition also supplies Validate, Install, the selected Styles row and explicit
face-picker thumbnail refresh. `preview.bin` and packaged style PNGs remain useful as
**unedited references**, never as a source of editable widget pixels. Refreshing a
thumbnail cannot feed its scaled pixels back into the scene. Original-container
identity matching remains necessary for pristine resize resampling and restoration,
but it is no longer a prerequisite for a widget to draw.

The sample is 28 December 2024, 10:08:00, with illustrative health readings.
Locale dictionaries follow supported phone languages with English fallback. Font
substitution and unavailable content are disclosed on Canvas and Validate; this is a
resource-based preview, not a pixel-exact emulator of the watch's GUI or live sensors.

`CanvasIntegrityTest` exercises edit chains with the production text rasterizer;
`ResourceCanvasCorpusTest` checks every style and AOD without `preview.bin`.
Synthetic tests cover all seventeen constructors, compositing, pivots, text programs,
alpha, z-order, clipping and fixed stroke width. Set `FITFACE_CANVAS_CONTACT_SHEET`
to an output PNG path when running the resource corpus test to generate a stock/style/AOD
comparison locally. The test never writes to the corpus.

## Caches and storage

| Path | Holds |
| --- | --- |
| `filesDir/catalog-cache/catalog.json` | The catalogue, 7 day TTL, always rendered first on launch |
| `filesDir/catalog-cache/uneditable.json` | App IDs whose package carries no container |
| `filesDir/catalog-cache/packages/<appId>@<versionCode>.apk` | Downloaded packages; older versions evicted |
| `filesDir/projects/<id>/source.apk` | The package a project was opened from |
| `filesDir/projects/<id>/edited.bin` | The current edited container |
| `filesDir/projects/<id>/session.json` | Removed widget records, base64, so restore survives process death |
| `filesDir/projects/<id>/edit-<UUID>.checkpoint` | Atomic edited BIN + session state for imported artwork; the row names the committed file |
| `filesDir/projects/<id>/previews/style<N>.png` | The package's own picture of each style, extracted on open |
| `filesDir/updates/fitface-studio-<version>-debug.apk` | A downloaded app update, swept once it is no longer the one on offer |

A package is re-downloaded only when its `versionCode` changes, and so is an update:
a file already on disk at exactly the declared size is reused rather than fetched
again, which is the difference between retrying a failed install and spending
another 36 MiB.

A face may carry **more than one project**, and each one keeps its own `source.apk` —
including one made by duplicating another, which copies the package, the edited container,
the removed-widget records and the extracted style previews into a directory of its own. A
duplicate costs the same disk as the project it came from and shares nothing with it: that
is the point, and it is why it cannot be a row copy.
The shared `catalog-cache/packages/` entry is what the second project is opened from,
so starting one costs no network — but it does cost another full copy of the package
beside it, because that copy is what `openProject` reads and the shared cache is
evicted the moment a newer version lands. `PackageCache.hasPackage` is the check
behind the face sheet's promise that nothing will be downloaded.

The project row records the three facts that tell one project from its siblings: the
name someone gave it, the style it was started on, and the store `versionCode` it was
built from — which is what "the store has published a newer version of this face"
compares against, with no network call. A row written before schema 5 whose
`sourceUri` was not one of this app's keys keeps NULL for the parsed columns, and NULL
means "say nothing", never "out of date".

### The project archive

Imported-widget projects use schema 2 and require their pristine donor artwork in
`fitface/session.json`; ordinary projects still use schema 1. The vendor original remains
unchanged, so Reset removes every import and restores the stock bytes.

An imported widget is **not** identified by its global index, its type/source tuple or the
donor package it came from — all three change or go away. `WidgetImportOrigins` stores an
explicit origin id, the donor face, the target variant, the current indices and a compact
pristine entry holding the imported record with its original rasters; removals and inserts
renumber those indices explicitly, a removed import keeps its origin link, and a restored or
duplicated one resolves the same pristine artwork. `WidgetPristine` hands the resize engine
that mapping, so every resize of an imported widget starts from the donor's artwork rather
than from the previous resize or a same-numbered native widget, and native original matching
excludes imported indices entirely. Evicting the donor from the package cache has no effect
on a saved import.

A project carrying imports keeps its edit in an immutable app-private `edit-<UUID>.checkpoint`
— one bounded JSON file holding both the edited container and its session state, written
whole before a single database pointer swap commits it, so a failed write leaves the previous
file and the in-memory state intact. The next successful edit removes the one before it;
projects with no imports keep their existing storage shape. `WidgetImportRepositoryTest`
covers the edit/reopen chains, apply-all collisions, saved-pointer relocation, archive, copy
and reset independence, AOD isolation, stale tickets, missing or foreign provenance and
rollback on a database failure.

A project leaves the device as a zip that **is a watch-face package**: the members
`Fit3Apk.parse` reads, under exactly the names the package gave them, plus a `fitface/`
sidecar no package has.

```
assets/SM-R390_00046_256x402.bin   the pristine container, byte for byte
assets/bandface_info.json          the face's name and its sampler id
assets/SM-R390_00046_2_0.png       the default style previews
fitface/project.json               name, face, store version, selected style
fitface/edited.bin                 the current container, when there is an edit
fitface/session.json               the removed-widget records
```

Because the parser cannot tell the two apart, an import stores the archive **as the
project's `source.apk`** unaltered, and everything below the repository runs unmodified —
opening, duplicating, extracting style previews, resolving the pristine container a resize
resamples from, building the install payload. A tidier layout would have meant a second
reader, and a second reader is a second copy of the pointer rules in
[bin-format.md](bin-format.md).

The rule that keeps it true: **the archive holds exactly the members the parser reads.**
`Fit3Apk.readsMember` is the one predicate both sides call, and a corpus sweep compares
every field `Fit3Apk` reports out of a package with the same field out of its archive — a
dropped `bandface_info.json` leaves the parse succeeding and the container intact, so a
bytes-only round trip would not catch it.

Left out: the dex, the resources, the manifest, the signature block, the accessory JARs and
the `assets/<locale>/` copies of the previews, which are localised artwork the preview
pattern is anchored to exclude. Across the 99 container-carrying corpus packages, 333 MiB
becomes 31.5 MiB — 571 members become five to eight.

An import always creates a **new** project, named against the importing library rather than
the exporting one, so the same archive imported twice gives two projects. Style previews are
extracted at import rather than at the first open, or the row sits in the list with no
thumbnail.

### What an import refuses

An archive is a zip a stranger could have written, which is a different threat from a store
package or a picked image. Everything is checked **before a row is written**, because a
project someone spends an evening on must not turn out on the Install page to have never
been sendable.

The structural defence is that **no entry name ever becomes a filesystem path**. An import
writes three fixed names into a directory named by a freshly-inserted row id, and the style
previews are named from an integer a `\d{1,3}` capture produced. There is nothing for `../`
to traverse and no symlink to follow, because nothing is extracted by name at all. Android's
own `ZipInputStream` refuses a `..` segment before that even matters — the desktop JDK the
format tests run on does not, so the JVM assertion is that such names are *inert* while on a
device the file is refused as unreadable. Neither is relied on. The rest is depth behind it:

| Refused | Because |
| --- | --- |
| a read past 16 MiB, or a sidecar inflating past it | a zip's declared size says nothing about what it becomes, and `OutOfMemoryError` is an `Error` the loop's `catch` would miss |
| more than 1,024 entries | a file under the ceiling can declare ~220,000 empty ones |
| a repeated `fitface/` member | which one is real is decided by whichever the reader takes; both answers are defensible and neither is in the file |
| a manifest that is missing, unparseable, or of a **newer schema** | `ignoreUnknownKeys` would decode a newer one cleanly while dropping whatever the new field carried |
| a container the app cannot open, through the download funnel | same failure, same wording |
| an `edited.bin` that does not validate, or exceeds `WATCH_CONTAINER_BYTE_CEILING` | `validatedBytes()` would refuse to send it, and a container over the ceiling transfers, is accepted and leaves the old face up |
| an `edited.bin` with foreign entry paths | schema 1 requires exact equality; schema 2 requires the original paths in order and validates explicitly bounded added font resources plus import provenance |
| schema 2 missing or carrying inconsistent imported artwork | imported widgets must never silently resolve to unrelated native resize sources |

Manifest strings are clamped rather than refused: they reach a database row and a list title,
but a long name does not make an archive unusable. A `selectedStyle` that is not a style name
reads as "no style was recorded".

A refusal leaves nothing behind — the row goes in first because its id names the directory,
under `NonCancellable`, and any failure past that deletes both.

### Why the style previews are files

The Styles page and the projects list both have to show a watch face per row, and
neither can afford to render one. A style's own artwork means decoding its raster
section, and the projects list would have to do that for every project on the way
into the screen — a whole library parsed to draw a column of thumbnails.

The package answers it directly: it ships the vendor's render of every style as
`assets/SM-R390_<face>_<group>_<style>.png`, at the panel's own 256 × 402. Those are
copied out beside the project when it is opened, and the UI loads them through Coil
like any other image file. `Fit3Apk.stylePreviews` keeps them even when the rest of
the package's members are dropped.

Two consequences worth keeping in mind. They are pictures of the **unedited** face,
so the Styles page draws the selected style from the composed preview instead and
leaves the others stock. And 1 of the 99 container-carrying catalogue faces (`00031`)
ships none at all, so absence is a normal case: the Styles row says so and the
projects list falls back to the face number. `StylePreviewSweepTest` holds both facts
against the corpus.
