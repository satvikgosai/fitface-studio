# Architecture

Kotlin, Compose, MVVM with unidirectional state, Hilt, Room, DataStore, Navigation 3,
OkHttp and Coil. Storage Access Framework handles picked images and optional project
archive import/export. [Format](bin-format.md) and [delivery](direct-install.md)
references own binary and transport details.

## Modules

| Module | Owns |
| --- | --- |
| `:app` | Application, dependency injection, navigation, app-menu dialogs |
| `:core:model` | Framework-free contracts and immutable state, repository interfaces |
| `:core:format` | Parse/validate/edit/serialize, schemas, layout, CRCs, project archive |
| `:core:data` | Catalogue/cache, private projects, Room, DataStore, image I/O, updater |
| `:core:delivery` | Companion probing, discovery, payload verification, RFCOMM |
| `:core:ui` | Theme and shared components |
| `:feature:library` | Catalogue, sorting, download, style selection, saved projects |
| `:feature:editor` | Canvas, Widgets, Background, Styles, Install; Project and Inspector |

Model and format use Android library build plugins but keep their implementations
framework-free and JVM-tested. Android APIs stop at data, delivery and UI modules;
framing, checksums, descriptors and install packet encoding remain pure Kotlin.

```text
Library UI → LibraryViewModel → FaceCatalogRepository → catalogue / PackageCache
                            → WatchFaceRepository → private project
Editor UI → EditorViewModel → WatchFaceRepository → lossless format core
                          → Fit3DirectInstaller → discovery → Bluetooth SPP
App menu → AppUpdater → GitHubReleaseFeed / UpdateInstaller
```

## State ownership

Compose renders immutable state and emits intent. ViewModels own interactions and
coroutine lifecycles; `WatchFaceRepository` owns original/edited containers. A unique
editor navigation destination per opened face prevents reuse of another session's
ViewModel. Committed edits produce reparsed snapshots; the delivery controller owns
its own state machine and freezes a copy of the validated bytes before transfer.

## Invariants

1. Repository edit commits roll back container, audit, active style, selected variant,
   import origins and lineage when snapshot/persistence fails. Reset is a
   separate path with its own persistence handling, not a bypass of validation.
2. Structural edits reparse and revalidate before acceptance.
3. `Session.validatedBytes()` is the only path to the watch for a face: size, magic, errors,
   blocking warnings, exact round trip and every style/AOD entry must pass. Photos for the
   Photos face go only through `PhotoAlbumRequest` and `AlbumPhoto`, which check every PNG
   and name before anything is sent; a container never travels that way, nor a photo this one.
4. Downloads have distinct allowlists and ceilings: face packages 32 MiB, app APKs
   64 MiB. Verify declared size and HTTPS host before request and after redirects.
5. Writes stay private except optional archive export to a user-chosen document.
   ZIP entry names never become filesystem paths; no storage permission is needed.
6. The container ceiling is 4 MiB, checked while editing and again before sending.
   [Editing contracts](bin-format.md#editing-contracts) own evidence and the separate
   image-count rules for resize, background addition and import/delete.

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

This composition also supplies Install, the selected Styles row and explicit
face-picker thumbnail refresh. `preview.bin` and packaged style PNGs remain useful as
**unedited references**, never as a source of editable widget pixels. Refreshing a
thumbnail cannot feed its scaled pixels back into the scene. Original-container
identity matching remains necessary for pristine resize resampling and resets,
but it is no longer a prerequisite for a widget to draw.

The sample is 28 December 2024, 10:08:00, with illustrative health readings.
Locale dictionaries follow supported phone languages with English fallback. Font
substitution and unavailable content are disclosed on Canvas and Install; this is a
resource-based preview, not a pixel-exact emulator of the watch's GUI or live sensors.

`CanvasIntegrityTest` exercises edit chains with the production text rasterizer;
`ResourceCanvasCorpusTest` checks every style and AOD without `preview.bin`.
`StockThumbnailAgreementTest` compares every stock style's render with its own picker
thumbnail in `preview.bin` and fails where the thumbnail shows artwork with nothing of
ours near it. The one exception (2026-11-06, 99 faces) is `00011` ("Big info") style 0:
its thumbnail and store screenshot show three activity rings drawn as a heart, but
`style0.bin` holds only the time and date (the same six records as its AOD) and the
package carries nothing else for it. Whatever the watch draws there comes from outside
the container, so the canvas cannot show or edit it; unverified on hardware.
Synthetic tests cover all seventeen constructors, compositing, pivots, text programs,
alpha, z-order, clipping and fixed stroke width. Set `FITFACE_CANVAS_CONTACT_SHEET`
to an output PNG path when running the resource corpus test to generate a stock/style/AOD
comparison locally. The test never writes to the corpus.

## Persistence

| Path | Holds |
| --- | --- |
| `filesDir/catalog-cache/catalog.json` | The catalogue, 7 day TTL, always rendered first on launch |
| `filesDir/catalog-cache/uneditable.json` | App IDs whose package carries no container |
| `filesDir/catalog-cache/packages/<appId>@<versionCode>.apk` | Downloaded packages; older versions evicted |
| `filesDir/projects/<id>/source.apk` | The package a project was opened from |
| `filesDir/projects/<id>/edited.bin` | Legacy edited container, read for backward compatibility |
| `filesDir/projects/<id>/session.json` | Legacy session state beside `edited.bin` (its removed records are read and ignored) |
| `filesDir/projects/<id>/edit-<UUID>.checkpoint` | Atomic edited BIN + session state and original widget identities; the row names the committed file, and the open session's undo and redo steps name the others |
| `filesDir/projects/<id>/previews/style<N>.png` | The package's own picture of each style, extracted on open |
| `filesDir/updates/fitface-studio-<version>-debug.apk` | A downloaded app update, swept once it is no longer the one on offer |
| `filesDir/photo-album/<face>/album.json` | The photos this app has sent the Photos face and not removed, in order, where each was placed and in what colour, and the one shown |
| `filesDir/photo-album/<face>/<md5>.png`, `<md5>.source.jpg` | Each sent photo exactly as sent, and the reduced original (at most 1,024 px a side) it was drawn from; deleted when the photo is removed or replaced |
| `filesDir/photo-album/<face>/draft/` | Photos picked or being changed and not sent yet: `draft.json` (order, placement, colour, the sent photo each replaces) and each one's reduced original, `<key>.jpg`; saved once changes settle and when the page closes, written on the repository's own scope so a closing page still gets its last change, cleared when they are sent or taken out |
| `filesDir/datastore/photo_album.preferences_pb` | The same photos' names alone: the only record before copies were kept, still written so an older build sees the same album |

The Photos face is not a project. `PhotoAlbumRepository` reads it from its catalogue
package, encodes each placed photo, and keeps the files above, written atomically, copies before
the list that names them, and only once a request has been handed over; `PhotoAlbumViewModel` owns
the picks and their placement, and the shared `Fit3DirectInstaller.sendAlbum` sends a
`PhotoAlbumRequest` (see [Direct install](direct-install.md#photos-for-the-photos-face)).
The installer is one per process, so a face install and a Photos request never overlap, and
each page clears the other kind's finished result when it opens.

### Commits and file ownership

`WatchFaceRepositoryImpl.commit` restores all session state if producing or saving
an edit fails. Every new edit writes a new immutable `edit-<UUID>.checkpoint`
under `NonCancellable`, containing the BIN, native identities, donor provenance
and thumbnail state, then swaps the Room pointer. A failed swap
removes the candidate file. No BIN can commit independently of the metadata needed to
interpret or restore it. Reset clears the pointer before removing old files. Legacy
BIN/session pairs remain readable and migrate on their next edit; missing checkpoints
fail closed.

### Undo and redo

The checkpoints are the history. A commit no longer deletes the checkpoint it replaces:
the state it left (that file, or the pristine package, plus the active style and canvas
variant) becomes an undo step. Undo and redo restore a step through the same reader
`openProject` uses and make the same Room pointer swap, rendering the state before the
row moves, so a step that cannot be shown is never made current.

- **Session-scoped, on disk too.** Opening any project (this one again included), Reset
  and delete end the history and delete its files. A process Android kills mid-session
  cannot, so each new process sweeps every project's checkpoints the row does not name the
  first time the library lists projects (the open session's project excepted), and opening
  a project sweeps its own. The library sweep is a background job beside the list, never
  ahead of it; it locks one project at a time and rereads that row before deleting, since
  the list it started from may be stale. Duplicates and archives carry only the current state.
- **Pristine by identity.** The bottom step reinstates the session's original container
  object, so `isDirty` (a reference comparison) is false again.
- **One step per gesture.** Commits carrying the same group in a row become one step: a
  nudge press however long it is held (`RepeatingButton` reports each press), a set's
  duplicates, removals or moves, an import batch. Groups are explicit ids, never times.
- **Waits for moves.** The editor drops queued moves and waits for the one in flight
  before asking for an undo, so a nudge cannot land after it.
- **Bounded.** 30 steps and 48 MiB of checkpoint files, oldest dropped first; a 4 MiB
  face therefore keeps about eight. History gives itself up for the session, before
  writing, when the phone lacks room for the next checkpoint and a 16 MiB margin.
- **Callers that change the session before committing** (thumbnail)
  pass the true before-state to `commit`.

`UndoRedoTest` walks every kind of edit back and forward byte for byte, and covers style
deletion, grouping, eviction, Reset, the sweep, duplicates and reopening.

Project creation inserts a row to obtain its directory ID, then writes files and
updates paths under `NonCancellable`. On failure remove files and call
`projectDao.deleteById`, not `deleteProject` (the latter reacquires the held mutex).
`EditPersistenceTest` and `WidgetImportRepositoryTest` cover failure/reopen paths.

`writeAtomically` uses a unique UUID scratch file for **each writer**, with a sweep
of scratch files older than an hour. A shared `.tmp` lets concurrent catalogue or
package writes interleave and rename each other's data. `PackageCacheTest` pins the
naming/sweep invariants; `loadCatalog` also has a Mutex to reuse the first refresh.

A duplicate gets independent paths and copies source, edited data/checkpoint, session
and previews. Clear `localApkPath`/`editedBinPath` until its new directory exists.
`ProjectDuplicationTest` edits/deletes the original to detect accidental shared paths.

### Project identity and catalogue versions

- `openPackage` always creates a project; `openProject` resumes one. The face sheet
  explicitly selects an existing project or creates another. Opening a store update
  creates a new project and leaves the previous version untouched.
- Every route into the editor opens the project first, so the editor takes that session
  (`openedProject`) and opens the project itself only when none is open for it, as after
  the process was killed. Opening it again doubled every open's load.
- A name is stored, never derived from a current package label. `ProjectNaming`
  re-stems only a name already taken, avoiding `Aurora 2 2`; a free `Aurora 2`
  stays unchanged. Renamed/custom names persist.
- `fit3-catalog://<product>/<version>/<style>` keys populate product/version/style
  columns. Unknown legacy keys retain NULL, meaning “unknown”, not “outdated”.
  Project outdated status compares recorded store version with cached catalogue data.
- Room is version 5. Auto migrations 1→2→3 precede manual 3→4 and 4→5; the first
  publicly released database was 4. The configured destructive downgrade fallback
  would erase projects if 4 were renumbered to 1. Migration 4→5 removes the UNIQUE
  source-key index; do not restore `findBySourceUri` reuse or a one-project-per-face rule.
- The package cache is shared but each project owns its source copy: newer packages
  evict older cache versions without breaking saved projects. `hasPackage` checks the
  actual file before UI promises no download; a database row alone proves nothing.
- The watch has one face per identity slot; another project using that identity
  replaces it. The only new firmware face IDs this code gives out are custom faces'
  numbers, 201–250 ([A custom face](#a-custom-face)).

### A custom face

`CustomFaceTemplate` builds on demand from the downloaded/cached Info_4 (`00006`)
package; no template container or artwork is bundled. Recipe version 3:

1. Verify the expected nine-record style shape, then `keepFirstStyles` retains one
   style and updates `styleN.bin`, `setting.bin +0x34`, and `preview.bin` together.
2. Remove the two readings and their labels, highest index first. Leave the clock
   and AOD intact; unexpected producer shape refuses instead of stripping blindly.
3. Redraw the remaining preview frame and package PNG from the stripped composition;
   discard removed-style previews. Pack exactly the members `Fit3Apk.parse` reads.
4. Under the repository lock, take the lowest number in 201–250 that no project's
   `faceId` and no cached-catalogue face uses, and renumber the container to it
   (`FaceIdentity.renumber`, [format reference](bin-format.md#renumbering-a-face)).
   `pack` files the container and style previews under that number, because the parser
   reads a project's face ID from the container's member name; Info_4's metadata is
   copied unchanged.
5. Save as an ordinary project. Its renumbered, stripped container is pristine, so Reset
   returns to the empty panel under its own number, not to Info_4.

The source key is `fit3-template://00006/v<recipe>/<uuid>`; product/version/style
columns stay NULL so a store update does not mark templates outdated. Archive export
retains that provenance. Existing recipe-1 projects keep their four styles, and
recipe-1 and -2 projects keep `00006` and still install over Info_4.
One style is absent from the vendor census (minimum three); activation names the
remaining style. A custom face started in the app installed and drew on a watch
(2026-10-22).
Numbering needs no schema change: the number is the row's existing `faceId`, and
archives already carry and check it. Deleting a custom face frees its number for the next
one. With all fifty in use, the library refuses before downloading and the repository
refuses again before writing anything (`CUSTOM_FACE_NUMBERS_FULL`). A duplicate or an
imported archive keeps its number, as any copy keeps its face's, so the two replace each
other on the watch. Custom faces are named against each other (`findCustomFaces`, by
`fit3-template://` key), not against their number's projects. Install names the face
"Custom face 201", because its package metadata still says Info_4. On a watch, `00201`
and `00202`, then `00210`, `00211` and a face numbered by the app, installed as faces of
their own ([recorded](direct-install.md#recorded-on-a-watch)); reinstalling one number
alone and a reboot have not been tried.

## The project archive

A ZIP archive is a package the existing parser can read plus a `fitface/` sidecar:

```text
assets/SM-R390_00046_256x402.bin  pristine container
assets/bandface_info.json       face name and sampler ID
assets/SM-R390_00046_2_0.png     default style previews
fitface/project.json            name, face, store version, selected style
fitface/edited.bin              optional edited container
fitface/session.json            widget identities and imported-resource provenance
```

`Fit3Apk.readsMember` is the single predicate shared by reader and exporter. When
parse adds a member, do not independently list it in `ProjectArchive`: the predicate
must keep both in sync. `ProjectArchiveTest` compares all parsed fields across the
corpus, not just BIN bytes (missing names/PNGs can otherwise go unnoticed).

Import keeps the archive unmodified as `source.apk`, creates a new independently
named project each time, and extracts previews immediately. Existing open/copy/pristine
resize/install paths then work unchanged. DEX, resources, manifest, signatures and
locale-specific preview copies are omitted. Recorded vendor exports shrink 333 MiB
to 31.5 MiB; a 571-member package becomes five to eight archive members.

Schemas 1 and 2 remain readable. New edited projects use schema 3, which requires
`SessionLineage` and an edited BIN together. It binds the original container digest,
current-to-original variant names and every non-imported widget to its original
index (or an explicit unknown/generated origin), including duplicate status.
Insertion and deletion supply index changes; subsequent edits never infer
identity from the new index. Legacy projects use the existing matching algorithm
once when upgrading; historical ambiguity cannot be reconstructed. Removed records
saved by builds that offered Restore are read and ignored. Database schema remains 5. Vendor
pristine bytes remain unchanged; Reset removes imports. `WidgetImportOrigins` tracks
an explicit origin ID, donor face, target variant, current indices and compact pristine
entry with original rasters. Deletion/insertion remaps indices; duplicates retain
origin links, and an origin with no live widget is pruned. Native matching excludes import indices. Donor cache eviction
cannot affect a saved import, and resize always uses donor originals.

Style deletion composes an injective, ordered survivor map from current variant
names to pristine names. Schema 3 validates that map, consecutive current names,
the retained AOD/non-style paths, picker count and bounded font-resource additions.
It records the digest of each added font or extended dictionary independently of
widget origins, so deleting the last donor-owning style does not require a fake
origin pointing at a missing variant. Native bindings and dictionary prefixes stay
unchanged. Earlier schema-3 checkpoints without this resource closure retain their
original identity-only path contract and upgrade on their next edit.

Schema 4 is schema 3 plus `SessionLineage.artworkTurns`: the angle of each turned
Static/Sprite artwork, which no byte of the container records and the pixels cannot
reveal. It is keyed by artwork — `native:<original variant>:<lowest original image index>`
or `import:<origin id>` — so deletion, duplication, reordering and added
backgrounds need no remap; style deletion drops keys of deleted original variants and a
commit drops keys of deleted imports. A project is written at schema 4 only while a turn
exists and returns to 3 when every turn is reset, because readers decode with
`ignoreUnknownKeys` and an older build would otherwise drop the turn and straighten the
artwork on its next resize. See [format: rotating widgets](bin-format.md#rotating-widgets).

A moving background ([format](bin-format.md#adding-a-moving-background)) or moving picture
([format](bin-format.md#adding-a-moving-picture)) needs no schema: its record is an added
widget with no original, like an added still background, its timing lives in the record,
and nothing about the GIF or video is kept. The snapshot tells a moving picture apart from
its record. Removing either deletes it with its frames, as every widget is deleted.
Video frames are read by `AndroidVideoSource` in `:core:data`, through the platform's
`MediaMetadataRetriever`, into a frame cache file under the app's cache directory
(`VideoFrameFiles`). The editor releases every read but the one being placed whenever that
changes, and all of them when it closes (`WatchFaceRepository.releaseVideoFrames`); a failed
or cancelled read deletes its own file, a new read keeps only the latest finished one, so no
more than two are ever cached, and a file being written is never deleted;
`:core:model`'s `VideoClip` plans which.

Schema 5 is schema 3 or 4 plus an added picture: a `WidgetImportOrigin` whose `kind` is
`PICTURE`, with an empty donor face and a baseline that is one Static placed on the panel.
It is written exactly while one exists, for the same reason: a build that reads 4 would
keep the widget, drop `kind` on its next save and call it an import from no face. Readers require schema 5 if and only if a picture origin is present
(`StoredSessionState.isConsistent`), and refuse anything newer. Deleting the last picture
drops a table that holds nothing else, returning the project to 3 or 4; Reset removes
pictures with every import. The session state declares `lineage` second and the
container last, so the projects list finds the style map before any import baseline
(a picture's can be hundreds of kilobytes). See
[format: adding a picture](bin-format.md#adding-a-picture).

The same checkpoint commits survivor identities and active-style fallback with the edited BIN. Packaged PNG files retain pristine
names; the editor and library resolve them through the survivor map. The library
reads only that map, from the head of each checkpoint (`readCheckpointVariants`): the
session state is written lineage first and container last, so the read stops within
the first few hundred bytes, and a schema before 3 has no map to look for. Decoding whole
checkpoints instead held the first list of 79 projects back 8–40 s on the emulator (debug
build). It caches the small map by immutable checkpoint path, bounded to 64 checkpoints,
and checks the chosen `style<N>.png` directly, listing the previews only as a fallback.
Reset restores the pristine variant identities and bytes. No database version
change or extra storage permission is required.

### What an import refuses

Validate before inserting a row. File output uses fixed names under a fresh row ID,
plus previews named from an integer captured by `\d{1,3}`; never extract ZIP names
as paths. JVM hostility tests treat traversal names as inert; Android may reject
those names earlier, but neither behaviour is the containment mechanism.

| Refusal | Contract |
| --- | --- |
| Read/inflation over 16 MiB | Enforce actual bounded reads, not ZIP-declared size |
| More than 1,024 entries | Prevent tiny-file entry-count bombs |
| Duplicate `fitface/` members | Refuse ambiguous manifests/session payloads |
| Missing, invalid or newer manifest schema | Unknown-key decoding must not silently discard required future data |
| Unopenable pristine container | Same validation/error funnel as package download |
| Invalid/oversized edited container | Must remain deliverable under the 4 MiB limit |
| Foreign entry paths | Schema 1: exact original list; schema 2: original paths in order plus bounded validated font additions; schema 3: declared ordered style survivors and validated shared-resource closure |
| Missing/inconsistent provenance | Schema 2 requires donor origins; schema 3 also requires complete native identities and the original digest; schema 4 also requires saved artwork turns naming existing original artwork or imports; schema 5 requires a picture origin, and any other schema refuses one |

Clamp manifest strings; an invalid selected-style name becomes no saved selection.
After validation, creation uses the row/files cleanup contract above. Failed import
leaves no project. `ProjectArchiveHostilityTest` covers traversal, bombs, duplicates
and schema boundaries; import repository tests cover provenance and rollback.

### Style preview files

Package PNGs (`assets/SM-R390_<face>_<group>_<style>.png`) avoid parsing every project
to draw a list. `Fit3Apk.stylePreviews` anchors matching at `assets/` to exclude
localized copies. They are unedited references; the selected Styles row uses the
current composed preview. `00031` has no PNGs, a normal case with a face-number
fallback, covered by `StylePreviewSweepTest`.

## Catalogue and diagnostics

The catalogue cache has a seven-day TTL and is rendered before refreshing. Only
result 1007 ends pagination; missing or other nonzero codes must throw so retries
and stale-cache fallback work instead of caching a silently truncated list.
`Fit3NoContainerException` → `isUneditablePackage` permanently marks `00254` Photos
as uneditable; its recorded 601-file customization package contains no BIN.

`CatalogLocale` repairs modern/legacy language codes (`in→id`, `iw→he`, `ji→yi`)
and numeric regions (`419→MX`, `001→US`, `150→GB`) while retaining language. Android
libcore can emit legacy language codes even when desktop Java does not. The store's
whitelist is case-sensitive and cannot be enumerated: bare languages and some valid
pairs (`qu_PE`) fail. `CatalogRetry` retries result 1005 once with `en_US`; do not
remove this after normalization or use empty locale (with `cc=KOR`, it yields Korean
names). Three-letter/unsupported languages such as `fil`, `tl`, `qu`, `gn` need the
fallback. `screenShotResolution` distinguishes 256×402 samplers from 512×512 promo art.

XML parsing disables external entities. Downloads validate identity and declared size,
check HTTPS allowlists on both sides of redirects, and use `Call.cancel()` plus Job
checks around blocking reads. Re-throw `CancellationException`, including through
ViewModel `runCatching`; suppress late progress/errors after cancellation.

`WatchFaceException.technicalDetail` reaches `DiagnosticsLog` through both UI funnels.
Reports use an allowlist, never serialized state: exclude Android ID/`extuk`, Bluetooth
addresses and bonded names, `csc/mcc/mnc`, picked URIs and signed URLs. Full request URLs
must never be logged. `DiagnosticsRedaction` is a second defence, not the data policy.
Persistent catalogue failures belong in `catalogFailure`; snackbar state is transient.

## App updates

| Concern | Contract |
| --- | --- |
| Feed | `/releases?per_page=10`, because releases are prereleases and `/releases/latest` excludes them |
| Ordering | Compare `AppVersion` numeric dotted components, not strings or API order; skip unsupported suffix forms like `v0.2.0-rc1` |
| Empty/changed feed | No usable release is an error/unknown result, never “up to date” |
| Download | Separate 64 MiB GitHub-host allowlist; reuse matching-size offered APK; no total `callTimeout` on slow transfers |
| Inspection | Package name, `longVersionCode` and SHA-256 of every readable signer; the feed itself has no version code |
| Signer mismatch | Explain inability to update without deleting projects; unreadable certificate defers to the package manager |
| Lifetime | Updater-owned scope survives navigation; progress changes per whole percent |
| Cancel | Check Job in read loop and before progress; cancel Call, delete scratch, abandon install session, rethrow cancellation |
| Permission | Re-read unknown-sources permission on resume and when returning from cancel; no network recheck required |
| Cleanup | Retain only an offered release newer than installed, not the installed release's cached APK |

`REQUEST_INSTALL_PACKAGES` is the only declaration in `:core:data`'s manifest.
Install status uses a runtime receiver to avoid propagating a manifest component
into test manifests. Below API 33 it is exported: use a fresh UUID action and verify
session ID, since the system sender cannot hold an app-defined receiver permission.
Use `FLAG_MUTABLE` from API 31. `STATUS_PENDING_USER_ACTION` is nonterminal; give
its confirmation Intent to an Activity rather than starting it in the background.
A successful self-update replaces the process. [Development](development.md#test-constraints-and-troubleshooting)
records automated coverage and manual verification limits.
