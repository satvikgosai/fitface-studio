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
| `:app` | Application root, Hilt entry point, theme, navigation |
| `:core:model` | Framework-free contracts and immutable state. Both repository interfaces live here. |
| `:core:format` | Container parse, validate, edit, CRC, serialize. Pure Kotlin, JVM-tested. |
| `:core:data` | Catalogue client, on-disk caches, private projects, Room, DataStore, image I/O |
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
- Every successful commit atomically updates the private project BIN; a failed
  commit rolls the in-memory session back — container, audit and selected style.
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
4. Downloads are bounded to 32 MiB, must match the declared size, and must
   resolve over HTTPS to a trusted store host.
5. Nothing is ever written outside app-private storage.
6. A container may not pass `WATCH_CONTAINER_BYTE_CEILING` — 4 MiB exactly.
   `rebuild` refuses any growth past it and `validatedBytes()` refuses to send
   one, because a container the watch ignores otherwise looks exactly like a
   successful install. The limit is a measured firmware behaviour, not a format
   rule; [editing.md](editing.md) records the hardware runs that closed it.

The image-record count is a seventh rule of the same weight, but it is a format
constraint rather than a pipeline one — see [editing.md](editing.md).

## The preview pipeline

The canvas is not a screenshot; it is composed, and knowing where each pixel
comes from explains most of the editor's behaviour.

- **Base layer** — the style's own full-panel raster, decoded from the container.
  A style is not obliged to have one; face `00022` opens every style with a 37×28
  icon and faces that have no panel raster simply draw onto black.
- **Widget overlay** — `preview.bin` is the vendor's *rendered* image of the
  unedited face. Pixels that differ from the base layer are the widgets, and that
  difference is what gives Value, Composite, Badge, Arc and Bar widgets — drawn by
  the watch from live data, with no artwork in the file — something to show.
- **Decoded layers** — Static and Sprite widgets *do* have artwork, so
  `FaceRecordParser.widgetImageLayers` decodes the exact frame the watch would
  blit and draws it at the widget's current position. The widget list uses the
  same decoded frame for its thumbnails, falling back to a crop of the composite
  for the widgets that have no raster.

That composite is what the canvas draws, and it is reused everywhere the app has to
show *this* edit rather than the stock face: the Validate page, the current row on
the Styles page, and the plate on the Install page — which stands for the payload,
because nothing in the transport can report what the watch is wearing right now.

Two consequences that are easy to get wrong:

- The composer's reference must be read from `originalContainer`, never from
  `currentContainer`. Re-rendering the face-picker thumbnail rewrites the edited
  container's `preview.bin`, and reading that back would diff each edit against
  the previous composite instead of against the vendor render, drifting a little
  further every pass.
- A removed widget's pixels are still in that raster, so the composer clears them
  explicitly. Otherwise a widget keeps showing after being cut out.

The face-picker thumbnail is re-rendered on request, but only once per edit: its
widget pixels can only come from the vendor's smaller `preview.bin` render, so
every pass resamples them and visibly softens the result.

## Caches and storage

| Path | Holds |
| --- | --- |
| `filesDir/catalog-cache/catalog.json` | The catalogue, 12 h TTL, always rendered first on launch |
| `filesDir/catalog-cache/uneditable.json` | App IDs whose package carries no container |
| `filesDir/catalog-cache/packages/<appId>@<versionCode>.apk` | Downloaded packages; older versions evicted |
| `filesDir/projects/<id>/source.apk` | The package a project was opened from |
| `filesDir/projects/<id>/edited.bin` | The current edited container |
| `filesDir/projects/<id>/session.json` | Removed widget records, base64, so restore survives process death |
| `filesDir/projects/<id>/previews/style<N>.png` | The package's own picture of each style, extracted on open |

A package is re-downloaded only when its `versionCode` changes.

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
