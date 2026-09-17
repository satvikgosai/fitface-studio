# Importing widgets from another watch face

This feature is **experimental**. Local format and resource-rendering tests do not
prove that every cross-face combination activates or behaves correctly on an SM-R390.
The UI says so before a widget can be added. The settled 4 MiB container ceiling is
enforced, not offered as another experiment.

## User flow and scope

From **Widgets → Add from another watch face**, pick a face out of the **Watch faces**
grid — the library's own card, because what someone is choosing between is a picture.
Every card says whether its package is already here or what the download costs, read for
the whole catalogue when it loads (`isPackageCached` is a file check, not a read), so
opening a donor is one tap rather than a tap to find out and a second to confirm. The
existing package cache is authoritative: `downloadPackage` reuses the current download
when present and downloads it otherwise. No project is a donor, and inspecting a donor
never opens a project or changes the active editing session.

The donor's widgets are then picked **off the donor face itself**: the same picture the
editor's canvas draws, composed from the face's own resources, with a rectangle round
every importable widget and `hitWidget` — the editor's own hit test — deciding overlaps.
`WidgetDonorVariant.composed` carries it, and the repository was already building it to
obtain the layers. The list behind **List ›** stays for what the face cannot offer: a
rotating hand has no axis-aligned rectangle, and a widget the importer refuses must look
refused rather than merely fail to respond. Rows quote `WidgetImporter.addedBytesEstimate`
— a floor, marked `≈` — so the cost is visible before the pick; picking builds the real
edit, and the panel under the face then quotes the exact figure and how much of the 4 MiB
the face would be using.

The review shows the target's resource-composed canvas at the size the window allows,
everything but the addition dimmed, and the container as it is now for as long as a finger
is held on it — both pictures are real renders of the same container, so the comparison
invents nothing. Add appends on top at the donor's resolved position. Nothing
on the target is replaced. It edits **only the selected target variant**; subsequent
edits to imported widgets remain variant-local even if a caller requests apply-all.
Native apply-all also skips matches that belong to an import in another style.

Both face pages split into two columns in a short window, by the same rule and the same
thresholds as the canvas page (`importPageSplits`, pinned by `WidgetImportLayoutTest`):
stacked, a landscape phone left the face clipped and **Add widget** below the fold.
The experimental notice, the progress bar and its **Cancel** are pinned under the top bar
rather than scrolled with the content, because a transfer someone has to scroll back up to
stop is one they cannot stop.

Failures stay on the import screen. Download/preparation can be cancelled and retried.
Saving is single-flight. A preview ticket names the exact session, container and variant;
changing any of those invalidates it rather than applying a stale preview to another face.

## What is supported

The nine types produced by the catalogue: Static, Sprite, Hand, Value, Composite,
vector Arc, Rule, image Arc and LineBar. Each keeps its live source and all fields
not explicitly relocated. Full-panel backgrounds use the Background page instead.
The remaining eight constructors have no stock producer samples and are not offered.

`WidgetImporter` copies the transitive resources it understands:

- Named raster pointers from `WidgetSchema`, including every sprite frame. Shared
  references within one imported record remain shared. Separate imports receive separate
  pools; duplicating an imported widget retains the editor's normal shared-pool behavior.
- Static/Hand/Value/Composite alignment is resolved in the donor, then rebased to the
  panel with an unresolvable target index `0xFFFF`. The live alignment **code** is retained
  because Value and Composite also use it for justification. Future appends cannot make
  this target resolve: the widget-count bound leaves index 65535 unavailable.
- Text imports reuse identical numbered ROM-font bindings or append a binding, up to the
  firmware's ten-binding limit. All variants' font-count declarations are synchronized;
  their widgets and rasters are unchanged.
- Locale dictionaries retain every target prefix and append donor tables. Missing locales
  use the corresponding English fallback, or fail when there is no fallback. Only actual
  dictionary references are rebased; Composite numeric bases are presence flags.

Unknown alignment, unsupported dynamic dictionary indexing, incomplete references,
incompatible panels, invalid UTF-8, exhausted font/index space or excessive size refuse
the import. The importer does not guess a resource closure from words resembling offsets.
Value source 116 (configured second time zone) is explicitly unavailable: its exceptional
firmware constructor bypasses numbered fonts and can create a fixed clickable overlay.

## Identity and saved projects

An import is not identified by its global index, type/source tuple or donor cache path.
`WidgetImportOrigins` stores an explicit origin ID, donor face, target variant, current
indices and a compact pristine entry containing the imported record and its original
rasters. Removal/insertion operations renumber these indices explicitly. Removed widgets
retain their origin link; restored and duplicated instances resolve the same pristine
artwork. Native original matching excludes imported indices.

`WidgetPristine` supplies the resize engine with explicit current-record → original-record
mapping. Every resize starts from saved donor artwork, not the previous resize or a
same-numbered native widget. Saved removed records' pointers are also relocated when an
unrelated raster resize moves their retained images.

The downloaded vendor container remains the real original. **Reset** removes imports
and restores those exact bytes. Donor cache eviction has no effect on saved imports.

Projects with imported artwork use an immutable app-private
`edit-<UUID>.checkpoint`: one bounded JSON file carries both the edited BIN and its
session state. The complete file is written before a single database pointer swap commits
it. Database failure retains the previous file and in-memory state. The next successful
edit removes the previous checkpoint; legacy projects retain their existing storage shape.

Archives containing imports use manifest schema **2**, with the same package members,
`fitface/edited.bin` and required `fitface/session.json`. The session carries origins, not
a second copy of the edited BIN. Old builds refuse schema 2. Schema 1 remains readable and
is still used for ordinary projects. Both project duplication and archive transfer retain
independent source bytes and checkpoint data.

Before accepting provenance, the repository checks the original container hash, original
entry paths in order, allowed added font resource names, unchanged native font bindings and
dictionary prefixes, unique origin ownership, baseline record/raster bounds, and live widget
identities. Missing provenance is an error, not a reason to silently fall back to a native
widget. The private checkpoint size is checked before creating an imported project row:
base64 encoding makes it larger than the archive's separate edited-BIN member, so ZIP
inflation limits alone are insufficient. ZIP member names still never become filesystem paths.

## Regression coverage

`WidgetImporterTest` covers additive preservation, all nine types, source/alignment profiles,
locale semantics, isolated raster pools, pristine resize round trips and size refusal.
`WidgetImportRepositoryTest` covers resource pixels, edit/reopen chains, apply-all collisions,
saved-pointer relocation, archive/copy/reset independence, AOD isolation, stale tickets,
missing/foreign provenance and database-write rollback. `WidgetImportViewModelTest` covers
cache use, cancellation, visible/retryable failures, uneditable faces, lifecycle re-entry
and single-flight saving. The normal canvas, archive and persistence suites remain required.
