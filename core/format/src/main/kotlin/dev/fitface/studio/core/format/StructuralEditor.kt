package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.RASTER_RESIZE_CEILING
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.WIDGET_EXTENT_CEILING
import dev.fitface.studio.core.model.WidgetResizeKind
import dev.fitface.studio.core.model.widgetResizeLimit
import java.io.ByteArrayOutputStream
import kotlin.math.abs

data class StructuralEdit(
    val container: Fit3Container,
    val changedPayloadBytes: Int,
    val changedStyles: List<String>,
    val sizeDelta: Int,
    /**
     * For [StructuralEditor.removeWidget], the exact record bytes cut out of each
     * style entry, keyed by entry basename. Feeding these to
     * [StructuralEditor.appendWidget] puts the widget back at the end of the table.
     */
    val removedRecords: Map<String, ByteArray> = emptyMap(),
    /**
     * For [StructuralEditor.deleteWidget], the image records deleted from each entry, as
     * indices into the entry *before* the edit. Everything saved against that entry — a
     * removed widget's record, waiting to be restored — has to be relocated past them, and
     * this is how [StructuralEditor.relocateSavedWidget] knows which ones went.
     */
    val droppedImages: Map<String, Set<Int>> = emptyMap(),
)

/** Explicit provenance overrides index/record heuristics for widgets from another face. */
data class WidgetPristine(val entry: ContainerEntry, val sources: Map<Int, Int>)

object StructuralEditor {
    /**
     * Saved removals point at retained rasters; resize moves their offsets too.
     *
     * [dropped] names the image records an edit deleted, as indices into [before]. Every
     * other image keeps its order, so the saved record's pointers are carried to wherever
     * the survivors landed — and a pointer at a dropped image is refused, because
     * [deleteWidget] never drops artwork a saved removal still needs.
     */
    fun relocateSavedWidget(
        before: ContainerEntry,
        after: ContainerEntry,
        raw: ByteArray,
        dropped: Set<Int> = emptySet(),
    ): ByteArray {
        val oldImages = FaceRecordParser.scanImages(before)
        val newImages = FaceRecordParser.scanImages(after)
        fun offsets(images: List<ImageRecord>) = images.map { it.recordOffset - images.first().recordOffset }
        val oldOffsets = offsets(oldImages)
        val newOffsets = offsets(newImages)
        if (dropped.isEmpty() && oldOffsets == newOffsets.take(oldOffsets.size)) return raw
        if (newImages.size < oldImages.size - dropped.size) {
            throw Fit3FormatException("Saved widget artwork was removed.")
        }
        // Where each surviving image sits now: its old position, less the dropped ones before it.
        val landed = oldImages.indices.filter { it !in dropped }.withIndex().associate { (now, old) -> old to now }
        val header = StyleHeader.parse(before)
        val data = WidgetImporter.styleBytes(before.data, raw, 1,
            before.data.copyOfRange(header.storedImageOffset, before.data.size), header.fontBindingCount)
        val saved = before.copy(data = data, size = data.size)
        val record = FaceRecordParser.scanWidgets(saved).single()
        val result = raw.copyOf()
        FaceRecordParser.imagePointerFields(record, FaceRecordParser.imagesByRelativeOffset(saved)).forEach { field ->
            val now = landed[field.image.index]
                ?: throw Fit3FormatException("Saved widget artwork was removed.")
            result.putU32(field.offset - record.recordOffset, newOffsets[now])
        }
        return result
    }

    /** The image records a saved (removed) widget record points at, as indices into [entry]. */
    internal fun savedWidgetImages(entry: ContainerEntry, raw: ByteArray): Set<Int> {
        val header = StyleHeader.parse(entry)
        val data = WidgetImporter.styleBytes(entry.data, raw, 1,
            entry.data.copyOfRange(header.storedImageOffset, entry.data.size), header.fontBindingCount)
        val saved = entry.copy(data = data, size = data.size)
        val record = FaceRecordParser.scanWidgets(saved).single()
        if (record.widgetType !in FaceRecordParser.POINTER_BEARING_TYPES) return emptySet()
        return FaceRecordParser.imagePointerFields(record, FaceRecordParser.imagesByRelativeOffset(saved))
            .map { it.image.index }.toSet()
    }

    /** Every image record some live widget in [entry] points at. */
    private fun drawnImages(entry: ContainerEntry): Set<Int> {
        val relative = FaceRecordParser.imagesByRelativeOffset(entry)
        return FaceRecordParser.scanWidgets(entry)
            .filter { it.widgetType in FaceRecordParser.POINTER_BEARING_TYPES }
            .flatMap { FaceRecordParser.imagePointerFields(it, relative) }
            .map { it.image.index }
            .toSet()
    }

    /**
     * Removes a widget **and the artwork only it drew** — for a widget that was never part
     * of the face, one imported from another.
     *
     * [removeWidget] keeps a widget's rasters on purpose: its record goes to the Removed
     * list, and Restore appends that record verbatim, pointing at those rasters by offset.
     * An imported widget does not need that — it can be imported again — and keeping its
     * artwork only stranded every byte it brought in, drawn by nothing, for good: one test
     * project carried 1.3 MB of it.
     *
     * Which rasters go is two rules, and both fail closed:
     *  * **never one the face shipped with.** [shippedImageCount] is the pristine entry's
     *    count, and an import appends after it, so no delete here can take a style below
     *    the image count it shipped with or move a shipped raster;
     *  * **never one anything still points at** — a live widget (a duplicate of the one
     *    deleted shares its rasters) or a saved removal in [retained].
     */
    fun deleteWidget(
        source: Fit3Container,
        entryBasename: String,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        shippedImageCount: Int,
        retained: List<ByteArray> = emptyList(),
    ): StructuralEdit {
        val entry = source.entryByBasename(entryBasename)
        val record = FaceRecordParser.scanWidgets(entry).firstOrNull { it.globalIndex == globalIndex }
            ?: throw Fit3FormatException("$entryBasename: no widget $globalIndex to delete")
        val drew = if (record.widgetType in FaceRecordParser.POINTER_BEARING_TYPES) {
            FaceRecordParser.imagePointerFields(record, FaceRecordParser.imagesByRelativeOffset(entry))
                .map { it.image.index }.toSet()
        } else {
            emptySet()
        }
        val removal = removeWidget(
            source, listOf(entryBasename), globalIndex, widgetType, sequenceId, x, y,
            requireFinal = false,
        )
        // `removeWidget` writes the image section back verbatim, so these indices still
        // name the same rasters in its result.
        val after = removal.container.entryByBasename(entryBasename)
        val stillNeeded = drawnImages(after) + retained.flatMap { savedWidgetImages(after, it) }
        val orphaned = drew.filter { it >= shippedImageCount && it !in stillNeeded }.toSet()
        if (orphaned.isEmpty()) return removal
        val purge = dropImages(removal.container, entryBasename, orphaned, shippedImageCount)
        return purge.copy(
            changedPayloadBytes = removal.changedPayloadBytes + purge.changedPayloadBytes,
            changedStyles = removal.changedStyles,
            sizeDelta = removal.sizeDelta + purge.sizeDelta,
            removedRecords = removal.removedRecords,
            droppedImages = mapOf(entryBasename to orphaned),
        )
    }

    /**
     * The container with only its first [count] numbered styles.
     *
     * Three things count the styles and all three move together, because every one of the 99
     * catalogue containers has them agree: the `styleN.bin` entries themselves,
     * `setting.bin +0x34` (the count the watch reads), and `preview.bin`'s frames — one per
     * style at a fixed stride, which the face picker seeks by index. The *first* styles are
     * kept, never a selection, so the numbering stays `style0` upward with no gap for the
     * picker to index into. The default style (`+0x35`) goes to 0, the one style certain to be
     * left. `aod.bin`, the font bindings and the dictionaries are shared by every style and
     * are not touched.
     *
     * **No catalogue face has fewer than three styles**, so a container this produces with one
     * is a shape the watch has not been shown to accept. The install command names the style
     * to activate, so a watch holding a saved style index from before is not left pointing
     * past the end — but that is the argument, not a hardware result.
     */
    fun keepFirstStyles(source: Fit3Container, count: Int): StructuralEdit {
        requireValidAndTight(source)
        val styles = FaceResources.selectableStyles(source)
        if (count < 1 || count > styles.size) {
            throw Fit3FormatException("cannot keep $count of ${styles.size} styles")
        }
        if (styles.map { it.basename } != styles.indices.map { "style$it.bin" }) {
            throw Fit3FormatException("the styles are not numbered from style0 without a gap")
        }
        val setting = FaceResources.settingOrNull(source)
            ?: throw Fit3FormatException("container has no setting.bin")
        if (SettingRecord.parse(setting).styleCount != styles.size) {
            throw Fit3FormatException("setting.bin does not count the styles present")
        }
        val preview = FaceResources.previewOrNull(source)
            ?: throw Fit3FormatException("container has no preview.bin")
        if (PreviewStream.recordCount(preview) != styles.size) {
            throw Fit3FormatException("preview.bin does not hold one frame per style")
        }
        val replacements = linkedMapOf(
            setting.index to setting.data.copyOf().also {
                it[0x34] = count.toByte()
                it[0x35] = 0
            },
            preview.index to preview.data.copyOfRange(0, count * PreviewStream.RECORD_STRIDE),
        )
        val removed = styles.drop(count).map { it.index }.toSet()
        val before = crossResourceIssues(source).map { it.code }.toSet()
        val edit = rebuild(source, replacements, removedEntries = removed)
        val introduced = crossResourceIssues(edit.container).map { it.code }.toSet() - before
        if (introduced.isNotEmpty()) {
            throw Fit3FormatException("keeping $count styles introduced $introduced")
        }
        FaceResources.selectableStyles(edit.container).forEachIndexed { index, entry ->
            check(entry.data.contentEquals(styles[index].data)) { "${entry.basename} changed" }
        }
        return edit
    }

    /**
     * Deletes image records that nothing draws, and moves every pointer after them.
     *
     * The image section is rebuilt from the survivors in their original order, and every
     * pointer is rewritten through [relocatePointers] — the same map every other relocation
     * uses, so a field it knows about cannot be left behind and a word it does not know
     * that lands on a moved raster refuses the edit. Afterwards every widget must be
     * byte-identical apart from those pointers, and every pointer must name byte-identical
     * artwork: the face draws exactly what it drew, from fewer bytes.
     */
    fun dropImages(
        source: Fit3Container,
        entryBasename: String,
        imageIndices: Set<Int>,
        shippedImageCount: Int,
    ): StructuralEdit {
        requireValidAndTight(source)
        val entry = source.entryByBasename(entryBasename)
        val widgets = FaceRecordParser.scanWidgets(entry)
        val images = FaceRecordParser.scanImages(entry)
        requireContiguousWidgets(entry, widgets, images)
        val header = StyleHeader.parse(entry)
        if (header.storedImageOffset + header.imageBytes != entry.data.size) {
            throw Fit3FormatException("${entry.basename}: image section is not tightly packed")
        }
        if (imageIndices.isEmpty() || imageIndices.any { it !in images.indices }) {
            throw Fit3FormatException("${entry.basename}: no such image records to delete")
        }
        if (imageIndices.any { it < shippedImageCount }) {
            throw Fit3FormatException("${entry.basename}: refusing to delete artwork the face shipped with")
        }
        if (imageIndices.any { it in drawnImages(entry) }) {
            throw Fit3FormatException("${entry.basename}: refusing to delete artwork a widget still draws")
        }
        val base = images.first().recordOffset
        val relative = images.associateBy { (it.recordOffset - base).toLong() }
        val mapping = mutableMapOf<Long, Long>()
        val kept = ByteArrayOutputStream()
        images.forEachIndexed { index, image ->
            if (index in imageIndices) return@forEachIndexed
            val end = images.getOrNull(index + 1)?.recordOffset ?: entry.data.size
            mapping[(image.recordOffset - base).toLong()] = kept.size().toLong()
            kept.write(entry.data, image.recordOffset, end - image.recordOffset)
        }
        val moved = relative.keys.filter { mapping[it] != it }.toSet()
        val head = entry.data.copyOfRange(0, header.storedImageOffset)
        relocatePointers(entry, head, widgets, relative, moved) { _, value ->
            mapping[value] ?: throw Fit3FormatException("${entry.basename}: a pointer names deleted artwork")
        }
        val output = head + kept.toByteArray()
        output.putU32(0x0C, kept.size())
        val relocated = validateRelocatedEntry(entry, output)
        requireSameDrawing(entry, relocated, images.size - imageIndices.size)
        return rebuild(source, mapOf(entry.index to output))
    }

    /**
     * After [dropImages]: the same widgets, byte for byte apart from their image pointers,
     * each pointer naming the same artwork byte for byte, and exactly the expected number
     * of image records left.
     */
    private fun requireSameDrawing(before: ContainerEntry, after: ContainerEntry, expectedImages: Int) {
        val beforeImages = FaceRecordParser.imagesByRelativeOffset(before)
        val afterImages = FaceRecordParser.imagesByRelativeOffset(after)
        if (FaceRecordParser.scanImages(after).size != expectedImages) {
            throw Fit3FormatException("${before.basename}: the wrong number of images were deleted")
        }
        fun artwork(entry: ContainerEntry, image: ImageRecord) =
            entry.data.copyOfRange(image.recordOffset, image.pixelOffset + image.dataSize)
        val old = FaceRecordParser.scanWidgets(before)
        val new = FaceRecordParser.scanWidgets(after)
        if (old.size != new.size) throw Fit3FormatException("${before.basename}: widget records went missing")
        old.zip(new).forEach { (b, a) ->
            val bRaw = before.data.copyOfRange(b.recordOffset, b.recordOffset + b.recordSize)
            val aRaw = after.data.copyOfRange(a.recordOffset, a.recordOffset + a.recordSize)
            if (b.widgetType in FaceRecordParser.POINTER_BEARING_TYPES) {
                val bFields = FaceRecordParser.imagePointerFields(b, beforeImages)
                val aFields = FaceRecordParser.imagePointerFields(a, afterImages)
                if (bFields.map { it.offset - b.recordOffset } != aFields.map { it.offset - a.recordOffset } ||
                    bFields.zip(aFields).any { (x, y) -> !artwork(before, x.image).contentEquals(artwork(after, y.image)) }
                ) {
                    throw Fit3FormatException("${before.basename}: widget ${b.globalIndex} no longer draws the same artwork")
                }
                bFields.forEach { bRaw.putU32(it.offset - b.recordOffset, 0) }
                aFields.forEach { aRaw.putU32(it.offset - a.recordOffset, 0) }
            }
            if (!bRaw.contentEquals(aRaw)) {
                throw Fit3FormatException("${before.basename}: widget ${b.globalIndex} changed beyond its pointers")
            }
        }
    }

    private const val StaticWidgetType = 1

    /** Fallback thickness for a Rule that stores an implausible one — as `drawnExtents`. */
    private const val RULE_FALLBACK_THICKNESS = 8

    /** Below this, a Rule's stored thickness is not the number its extent came from. */
    private const val RULE_MINIMUM_THICKNESS = 2

    /** Pixel formats [RasterResampler] can read, so the ones a resize may touch. */
    private val RESAMPLED_FORMATS = setOf(IMAGE_RGB565, IMAGE_RGB565_ALPHA, IMAGE_INDEXED8)

    /** 36 + one type-word, which is what all 348 corpus background Statics measure. */
    private const val BACKGROUND_STATIC_SIZE = 40

    fun resizeBackgrounds(
        source: Fit3Container,
        entryBasenames: List<String>,
        width: Int,
        height: Int,
        argb: IntArray,
    ): StructuralEdit {
        requireValidAndTight(source)
        if (width !in 1..512 || height !in 1..512 || argb.size != width * height) {
            throw Fit3FormatException(
                "background dimensions must each be 1..512 with matching pixels",
            )
        }
        val encoded = ByteArray(argb.size * 2)
        argb.forEachIndexed { index, color ->
            val red = color ushr 16 and 0xFF
            val green = color ushr 8 and 0xFF
            val blue = color and 0xFF
            val rgb565 = ((red * 31 + 127) / 255 shl 11) or
                ((green * 63 + 127) / 255 shl 5) or
                ((blue * 31 + 127) / 255)
            encoded[index * 2] = rgb565.toByte()
            encoded[index * 2 + 1] = (rgb565 ushr 8).toByte()
        }
        val replacements = linkedMapOf<Int, ByteArray>()
        selectedEntries(source, entryBasenames).forEach { entry ->
            replacements[entry.index] = resizeBackgroundEntry(
                entry = entry,
                width = width,
                height = height,
                encoded = encoded,
            )
        }
        return rebuild(source, replacements)
    }

    /**
     * Gives a style that has **no** full-panel raster one, plus the Static that draws it,
     * so a face like `00022` that paints its widgets straight onto the watch's black panel
     * can carry a background image.
     *
     * This adds an image record, which [resizeWidget] must never do — the watch ignores a
     * container whose frame count changed. That rule came from appending private frames to
     * a resized Sprite, and it turns out not to cover this: **a background added this way
     * installs and renders on an SM-R390.** Adding *a panel background plus its Static* is
     * therefore a proven edit; adding frames to a sprite is still not.
     *
     * ## Why exactly this shape
     *
     * All 348 style entries in the corpus that have a background are built the same way,
     * and the Static that draws it is copied from them field for field:
     *
     * | Observation | Corpus |
     * | --- | --- |
     * | The background is drawn by widget ordinal **0** | 348 / 348 |
     * | That widget is a **Static**, record size **40** | 348 / 348 |
     * | Its `+0x20` is the raster's relative offset, `0x0` | 348 / 348 |
     * | Its geometry is `x=0 y=0 w=0 h=0`, sequence `0` | 264 / 348 (rest differ only in `w=1`) |
     * | Its bytes are `01 00 00 00 …` with a zero tail | 347 / 348 |
     * | The raster's four trailer bytes are zero | 6,315 / 6,315 rasters |
     * | A background is `IMAGE_RGB565` | 309 / 348 |
     *
     * `IMAGE_RGB565` is also the only sane choice for a raster the app invents: it has no
     * alpha plane, so the watch paints the full rectangle and there is no panel mask to
     * fabricate.
     *
     * The one place this deliberately differs from the shipped faces is *where* the raster
     * goes. Theirs is image 0; this one is appended to the end of the image section, so no
     * existing offset moves and no pointer is rewritten — see `addBackgroundEntry` for the
     * face that made that necessary. The Static names it either way, which is what the
     * watch follows.
     *
     * Every entry in [entryBasenames] must currently have no panel-sized raster — a style
     * that has one is served by the same-size [FaceEditor.replaceBackgrounds] — and
     * [width] × [height] must be the style's declared panel geometry, or
     * [FaceRecordParser.backgroundImage] would not recognise the result.
     *
     * The other bound is size. A panel raster is [addedBackgroundBytes] per style, which
     * is enough to push a large face past [WATCH_CONTAINER_BYTE_CEILING] — and past it
     * the watch takes the container and keeps showing the old face, which is exactly the
     * silent failure this edit is otherwise free of. Use [backgroundStylesThatFit] to
     * choose the entries rather than discovering the refusal here.
     */
    fun addBackgrounds(
        source: Fit3Container,
        entryBasenames: List<String>,
        width: Int,
        height: Int,
        argb: IntArray,
    ): StructuralEdit {
        requireValidAndTight(source)
        if (width !in 1..512 || height !in 1..512 || argb.size != width * height) {
            throw Fit3FormatException(
                "background dimensions must each be 1..512 with matching pixels",
            )
        }
        val projected = source.fileSize + entryBasenames.size * addedBackgroundBytes(width, height)
        if (projected > WATCH_CONTAINER_BYTE_CEILING) {
            throw Fit3FormatException(
                "a ${width}x$height background in ${entryBasenames.size} styles would make " +
                    "this container $projected bytes, over the " +
                    "$WATCH_CONTAINER_BYTE_CEILING the watch accepts",
            )
        }
        val replacements = linkedMapOf<Int, ByteArray>()
        selectedEntries(source, entryBasenames).forEach { entry ->
            replacements[entry.index] = addBackgroundEntry(entry, width, height, argb)
        }
        return rebuild(source, replacements)
    }

    /**
     * What one added panel background costs a style entry: the `IMAGE_RGB565` record —
     * header, two bytes per pixel, the four zero trailer bytes — plus the 40-byte Static
     * that draws it. 205,880 bytes at the SM-R390's 256×402 panel.
     */
    fun addedBackgroundBytes(width: Int, height: Int): Int =
        IMAGE_HEADER_SIZE + width * height * 2 + OPAQUE_TRAILER_BYTES + BACKGROUND_STATIC_SIZE

    /**
     * As many of [entryBasenames] as an added background fits into without taking the
     * container past [WATCH_CONTAINER_BYTE_CEILING], [preferred] first.
     *
     * The order is the point. A face too large to carry a background in all of its styles
     * can still carry one in the style being edited — which is the style the install
     * activates and the only one the canvas shows — so the edit is offered for that one
     * rather than refused outright. Faces whose styles differ in panel geometry are
     * costed per entry, and a style already carrying a background is not a candidate:
     * that one takes a same-size replacement instead.
     *
     * Empty means there is no room for even one, which is true of face `00022`.
     */
    fun backgroundStylesThatFit(
        source: Fit3Container,
        entryBasenames: List<String>,
        preferred: String? = null,
    ): List<String> {
        val candidates = entryBasenames.sortedBy { it != preferred }
        var projected = source.fileSize
        return candidates.filter { basename ->
            val entry = source.entryByBasename(basename)
            if (FaceRecordParser.backgroundImage(entry) != null) return@filter false
            val panel = FaceRecordParser.panelSize(entry)
            if (panel.width <= 0 || panel.height <= 0) return@filter false
            val cost = addedBackgroundBytes(panel.width, panel.height)
            if (projected + cost > WATCH_CONTAINER_BYTE_CEILING) {
                false
            } else {
                projected += cost
                true
            }
        }
    }

    /**
     * Resizes one widget to [width] × [height], in the extent terms `WidgetGuide` reports
     * — which is the artwork's size for a Static, a Sprite or a Hand, and the stored box
     * for an image Arc, a LineBar or a vector arc.
     *
     * Which fields that rewrites comes from [WidgetSchema.ResizeModel], so this function
     * and the capability gate in [FaceRecordParser.widgetGuides] cannot drift into
     * disagreeing about what is resizable — a control the UI lights and the commit refuses
     * is the failure this path is arranged to avoid.
     *
     * Rasters are shared *records*, so a raster-backed resize closes over every widget
     * reaching into the same pool and moves all of it together — see
     * [FaceRecordParser.rasterPool]. Records are rewritten in place: the image-record count
     * never changes, because a container with more image records than it shipped with is
     * one the watch installs and then ignores.
     *
     * [pristine] is the unedited container, and when it is supplied every raster is
     * resampled from *its* pixels rather than from [source]'s. Resizing is lossy —
     * shrinking throws pixels away — so chaining resample onto resample destroys the
     * artwork: shrinking a 114×136 sprite to 56×69 and then pulling it back up returned a
     * picture carrying only the detail that survived the smaller one.
     *
     * It also sets the upper bound. A widget may be taken back to the extent its face
     * shipped — [widgetResizeLimit] — because resampling to the original dimensions
     * restores the original record lengths and with them the container's shipped size.
     * Growing *past* that is what [RASTER_RESIZE_CEILING] bounds, and what
     * [WATCH_CONTAINER_BYTE_CEILING] refuses when the rasters get big enough to matter.
     * Without [pristine] there is no shipped extent to read, so the current one stands in.
     *
     * The widget is named the way every other widget edit names one, and that is a fix
     * rather than a tidy-up. Selecting by `(type, sequenceId)` worked only while Sprites
     * were the only resizable type — a **Static's data source is `0` in 678 of the
     * catalogue's 681 records** — and selecting *entries* rather than *records* meant a
     * face whose styles carry different widgets failed the edit outright instead of
     * editing the ones that have it, which is the rule every other edit here follows.
     */
    fun resizeWidget(
        source: Fit3Container,
        entryBasenames: List<String>,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        pristine: Fit3Container? = null,
        pristineWidgets: Map<String, WidgetPristine> = emptyMap(),
    ): StructuralEdit {
        requireValidAndTight(source)
        // The precise per-side bound needs the widget's shipped extent, which only
        // `resizeWidgetEntry` can resolve; this is the sanity bound around it.
        if (width !in 1..WIDGET_EXTENT_CEILING || height !in 1..WIDGET_EXTENT_CEILING) {
            throw Fit3FormatException(
                "widget dimensions must each be between 1 and $WIDGET_EXTENT_CEILING",
            )
        }
        val replacements = linkedMapOf<Int, ByteArray>()
        selectedRecords(source, entryBasenames, globalIndex, widgetType, sequenceId, x, y)
            .forEach { (entry, target) ->
                replacements[entry.index] = resizeWidgetEntry(
                    entry = entry,
                    pristineEntry = pristineWidgets[entry.basename]?.entry ?: pristine?.entries?.singleOrNull {
                        it.basename == entry.basename
                    },
                    target = target,
                    width = width,
                    height = height,
                    sourceIndices = pristineWidgets[entry.basename]?.sources,
                )
            }
        return rebuild(source, replacements)
    }

    /**
     * Cuts one widget out of the first entry of [entryBasenames], and out of every
     * later entry that carries the same widget.
     *
     * A variant that does not carry it keeps its table untouched rather than failing
     * the edit — see [StyleWidgetMatch]. [StructuralEdit.removedRecords] therefore
     * names only the variants actually cut, which is exactly what [appendWidget]
     * needs to put it back.
     */
    fun removeWidget(
        source: Fit3Container,
        entryBasenames: List<String>,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        requireFinal: Boolean,
    ): StructuralEdit {
        requireValidAndTight(source)
        val replacements = linkedMapOf<Int, ByteArray>()
        val removed = linkedMapOf<String, ByteArray>()
        selectedRecords(source, entryBasenames, globalIndex, widgetType, sequenceId, x, y)
            .forEach { (entry, target) ->
                val (replacement, record) = removeWidgetEntry(entry, target, requireFinal)
                replacements[entry.index] = replacement
                removed[entry.basename] = record
            }
        return rebuild(source, replacements).copy(removedRecords = removed)
    }

    /**
     * Appends a previously removed record back onto the end of each style's widget
     * table, renumbered to the next free global index. This is the inverse of
     * [removeWidget] for everything the container can observe: the record bytes are
     * restored verbatim and only the index field is rewritten.
     */
    fun appendWidget(
        source: Fit3Container,
        entryBasenames: List<String>,
        recordsByStyle: Map<String, ByteArray>,
    ): StructuralEdit {
        requireValidAndTight(source)
        val targets = selectedEntries(source, entryBasenames)
        val missing = targets.map { it.basename }.filterNot(recordsByStyle::containsKey)
        if (missing.isNotEmpty()) {
            throw Fit3FormatException(
                "no saved widget record for ${missing.joinToString()}",
            )
        }
        val replacements = linkedMapOf<Int, ByteArray>()
        targets.forEach { entry ->
            replacements[entry.index] =
                appendWidgetEntry(entry, recordsByStyle.getValue(entry.basename))
        }
        return rebuild(source, replacements)
    }

    /**
     * Appends a copy of one widget to the first entry of [entryBasenames], and to
     * every later entry that carries the same widget. A variant that does not carry
     * it is left alone — see [StyleWidgetMatch].
     */
    fun duplicateWidget(
        source: Fit3Container,
        entryBasenames: List<String>,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
    ): StructuralEdit {
        requireValidAndTight(source)
        val replacements = linkedMapOf<Int, ByteArray>()
        selectedRecords(source, entryBasenames, globalIndex, widgetType, sequenceId, x, y)
            .forEach { (entry, target) ->
                replacements[entry.index] = duplicateWidgetEntry(entry, target)
            }
        return rebuild(source, replacements)
    }

    /**
     * The records a widget-scoped structural edit should rewrite, one per variant
     * that actually carries the selected widget.
     */
    private fun selectedRecords(
        source: Fit3Container,
        entryBasenames: List<String>,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
    ): List<Pair<ContainerEntry, WidgetRecord>> =
        StyleWidgetMatch.resolve(source, entryBasenames) { _, records ->
            records.singleOrNull { it.globalIndex == globalIndex }?.takeIf {
                listOf(it.widgetType, it.sequenceId, it.x, it.y) ==
                    listOf(widgetType, sequenceId, x, y)
            }
        }

    private fun resizeBackgroundEntry(
        entry: ContainerEntry,
        width: Int,
        height: Int,
        encoded: ByteArray,
    ): ByteArray {
        val images = FaceRecordParser.scanImages(entry)
        val widgets = FaceRecordParser.scanWidgets(entry)
        val selected = FaceRecordParser.backgroundImage(entry)
            ?: throw Fit3FormatException(
                "${entry.basename}: style has no full-panel background raster to resize",
            )
        if (selected.format != IMAGE_RGB565 || selected.reserved != 0) {
            throw Fit3FormatException(
                "${entry.basename}: resized background requires plain RGB565 schema",
            )
        }
        val sectionStart = images.first().recordOffset
        val relativeImages = images.associateBy { (it.recordOffset - sectionStart).toLong() }
        val movedOffsets = relativeImages.filterValues {
            it.recordOffset > selected.recordOffset
        }.keys
        val oldEnd = selected.pixelOffset + selected.dataSize
        val trailer = entry.data.copyOfRange(
            selected.pixelOffset + selected.pixelDataSize,
            oldEnd,
        )
        val newDataSize = encoded.size + trailer.size
        val delta = newDataSize - selected.dataSize
        if (delta == 0) {
            throw Fit3FormatException("background relocation requires a dimension change")
        }
        var style = entry.data.copyOf()
        relocatePointers(
            entry = entry,
            target = style,
            widgets = widgets,
            relativeImages = relativeImages,
            movedOffsets = movedOffsets,
            mapOffset = { _, before -> if (before in movedOffsets) before + delta else before },
        )
        val newRecord = ByteArray(IMAGE_HEADER_SIZE + newDataSize)
        newRecord.putU16(0, width)
        newRecord.putU16(2, height)
        newRecord.putU16(4, selected.format)
        newRecord.putU16(6, selected.reserved)
        newRecord.putU32(8, newDataSize)
        encoded.copyInto(newRecord, IMAGE_HEADER_SIZE)
        trailer.copyInto(newRecord, IMAGE_HEADER_SIZE + encoded.size)
        style = replaceRange(style, selected.recordOffset, oldEnd, newRecord)
        style.putU32(0x0C, entry.data.u32(0x0C).checkedInt("image bytes") + delta)
        validateRelocatedEntry(entry, style)
        return style
    }

    private fun addBackgroundEntry(
        entry: ContainerEntry,
        width: Int,
        height: Int,
        argb: IntArray,
    ): ByteArray {
        FaceRecordParser.backgroundImage(entry)?.let {
            throw Fit3FormatException(
                "${entry.basename} already carries a ${it.width}x${it.height} background; " +
                    "replace it in place instead of adding a second one",
            )
        }
        val panel = FaceRecordParser.panelSize(entry)
        if (panel.width != width || panel.height != height) {
            throw Fit3FormatException(
                "${entry.basename}: a background must be the declared panel " +
                    "${panel.width}x${panel.height}, not ${width}x$height",
            )
        }
        val images = FaceRecordParser.scanImages(entry)
        val widgets = FaceRecordParser.scanWidgets(entry)
        requireContiguousWidgets(entry, widgets, images)
        if (widgets.size + 1 >= 0xFFFF) {
            throw Fit3FormatException("${entry.basename}: widget index space is exhausted")
        }
        val oldImageBytes = entry.data.u32(0x0C).checkedInt("image bytes")
        val oldImageOffset = entry.data.u32(0x14).checkedInt("image offset")
        val raster = backgroundRasterRecord(width, height, argb)
        val sectionStart = images.first().recordOffset
        val relativeImages = images.associateBy { (it.recordOffset - sectionStart).toLong() }
        // What every widget points at today, so the same rasters can be demanded
        // afterwards. Read through the pointer map rather than the extent model, so an
        // Arc's or a LineBar's artwork is covered too.
        val before = widgets.associate { widget ->
            widget.ordinal to pointerSignatures(entry, widget, relativeImages)
        }

        // The raster goes at the *end* of the image section, so no existing offset moves
        // and not one byte of the artwork or the pointers into it changes.
        //
        // It was at index 0 first, imitating the 348 shipped styles where the panel
        // raster is image 0. That installed and rendered on hardware, but it shifts every
        // raster — which also changes what offset `0x0` names, and `0x0` is a value that
        // turns up all over records that do not use it as a pointer: face 00019's two
        // Value widgets both hold `words[3..4] = 0`, and after the insert those pointed at
        // a 256×402 background instead of a 102×132 digit. On that face one of the two
        // stopped drawing on the watch. Appending removes the question: the only thing
        // that names the new raster is the Static written to name it.
        val newRasterOffset = oldImageBytes.toLong()
        val existingIndices = widgets.mapTo(mutableSetOf(), WidgetRecord::globalIndex)
        val output = ByteArrayOutputStream()
        output.write(entry.data, 0, STYLE_HEADER_SIZE)
        output.write(backgroundStaticRecord(newRasterOffset))
        widgets.forEach { widget ->
            val record = entry.data.copyOfRange(
                widget.recordOffset,
                widget.recordOffset + widget.recordSize,
            )
            // The new Static takes index 0, so everything already in the table moves up
            // one — and so does every reference to it.
            record.putU32(
                0x0C,
                ((widget.globalIndex + 1).toLong() shl 16) or (record.u32(0x0C) and 0xFFFF),
            )
            remapAlignmentTarget(record, widget) { old ->
                (old + 1).takeIf { old in existingIndices }
            }
            output.write(record)
        }
        output.write(entry.data, oldImageOffset, entry.data.size - oldImageOffset)
        output.write(raster)

        val style = output.toByteArray()
        style.putU32(0x04, widgets.size + 1)
        style.putU32(0x08, oldImageOffset - STYLE_HEADER_SIZE + BACKGROUND_STATIC_SIZE)
        style.putU32(0x0C, oldImageBytes + raster.size)
        style.putU32(0x14, oldImageOffset + BACKGROUND_STATIC_SIZE)
        requireAddedBackgroundIsSound(entry, style, widgets, before, width, height)
        return style
    }

    /**
     * An [IMAGE_RGB565] image record: the 12-byte header, two bytes per pixel, and the
     * four zero trailer bytes every one of the corpus's 6,315 rasters ends with.
     */
    private fun backgroundRasterRecord(width: Int, height: Int, argb: IntArray): ByteArray {
        val payload = ByteArray(argb.size * 2 + OPAQUE_TRAILER_BYTES)
        argb.forEachIndexed { index, color ->
            val rgb565 = (((color ushr 16 and 0xFF) * 31 + 127) / 255 shl 11) or
                (((color ushr 8 and 0xFF) * 63 + 127) / 255 shl 5) or
                (((color and 0xFF) * 31 + 127) / 255)
            payload[index * 2] = rgb565.toByte()
            payload[index * 2 + 1] = (rgb565 ushr 8).toByte()
        }
        val record = ByteArray(IMAGE_HEADER_SIZE + payload.size)
        record.putU16(0, width)
        record.putU16(2, height)
        record.putU16(4, IMAGE_RGB565)
        record.putU16(6, 0)
        record.putU32(8, payload.size)
        payload.copyInto(record, IMAGE_HEADER_SIZE)
        return record
    }

    /**
     * The 40-byte Static that draws the added background, copied from the 347 corpus
     * records that are byte-identical to each other: type 1, sequence 0, `x=y=w=h=0`, a
     * zero tail, and `+0x0C` holding `(index << 16) | record size` with index 0.
     *
     * The one field that differs from those records is `+0x20`, the raster's relative
     * offset — theirs is `0x0` because their panel raster is image 0, and
     * [pointerOffset] is wherever this one was appended.
     */
    private fun backgroundStaticRecord(pointerOffset: Long): ByteArray {
        val record = ByteArray(BACKGROUND_STATIC_SIZE)
        record.putU32(0x00, StaticWidgetType.toLong())
        record.putU32(0x0C, BACKGROUND_STATIC_SIZE.toLong())
        record.putU32(0x20, pointerOffset)
        return record
    }

    /**
     * A fingerprint of every raster a widget points at, in pointer order — dimensions,
     * format, length and a CRC of the pixels. Comparing these across a relocation is the
     * assertion that catches a pointer left behind: a stale offset either fails to
     * resolve or lands on different bytes, and both show up here instead of on the watch.
     */
    private fun pointerSignatures(
        entry: ContainerEntry,
        widget: WidgetRecord,
        relativeImages: Map<Long, ImageRecord>,
    ): List<String> {
        if (widget.widgetType !in FaceRecordParser.POINTER_BEARING_TYPES) return emptyList()
        return FaceRecordParser.imagePointerFields(widget, relativeImages)
            .map { rasterSignature(entry, it.image) }
    }

    private fun rasterSignature(entry: ContainerEntry, image: ImageRecord): String =
        "${image.width}x${image.height}:${image.format}:${image.dataSize}:" +
            Crc16.ccittFalse(
                entry.data,
                image.pixelOffset,
                image.pixelOffset + image.dataSize,
            )

    /**
     * Fails the edit unless the style now looks exactly like one that shipped with a
     * background, and nothing that was already drawing has changed what it draws.
     *
     * The last part is the one that matters: every original widget must still resolve to
     * rasters with the same dimensions, format and **bytes**, so a pointer left behind by
     * the relocation is a failed edit here rather than a widget that quietly stops drawing
     * on the watch.
     */
    private fun requireAddedBackgroundIsSound(
        original: ContainerEntry,
        style: ByteArray,
        expected: List<WidgetRecord>,
        before: Map<Int, List<String>>,
        width: Int,
        height: Int,
    ) {
        val parsed = validateRelocatedEntry(original, style)
        val images = FaceRecordParser.scanImages(parsed)
        val widgets = FaceRecordParser.scanWidgets(parsed)
        if (widgets.size != expected.size + 1) {
            throw Fit3FormatException("${original.basename}: widget table did not grow by one")
        }
        if (widgets.map { it.globalIndex } != widgets.indices.toList()) {
            throw Fit3FormatException("${original.basename}: widget indices are not contiguous")
        }
        if (images.size != FaceRecordParser.scanImages(original).size + 1) {
            throw Fit3FormatException("${original.basename}: image section did not grow by one")
        }
        val background = FaceRecordParser.backgroundImage(parsed)
            ?: throw Fit3FormatException(
                "${original.basename}: the added raster is not recognised as a background",
            )
        if (background.index != images.lastIndex ||
            background.width != width ||
            background.height != height ||
            background.format != IMAGE_RGB565
        ) {
            throw Fit3FormatException(
                "${original.basename}: the added background is not the panel-sized " +
                    "RGB565 raster at the end of the section",
            )
        }
        val sectionStart = images.first().recordOffset
        val relativeImages = images.associateBy { (it.recordOffset - sectionStart).toLong() }
        val drawer = widgets.first()
        if (drawer.widgetType != StaticWidgetType ||
            FaceRecordParser.referencedImages(drawer, relativeImages)
                .singleOrNull()?.index != background.index
        ) {
            throw Fit3FormatException(
                "${original.basename}: widget 0 does not draw the added background",
            )
        }
        val originalIndices = expected.mapTo(mutableSetOf(), WidgetRecord::globalIndex)
        expected.forEach { widget ->
            val after = widgets[widget.ordinal + 1]
            // `+0x1E` is the alignment target on a Static or a Hand, and that field has
            // just been renumbered on purpose. Everywhere else the halfword is geometry
            // and must not have moved at all.
            val referenceField =
                WidgetSchema.spec(widget.widgetType).alignment?.targetOffset.takeIf {
                    widget.liveAlignment != null
                }
            val geometryHeld = widget.raw1C == after.raw1C &&
                (referenceField == 0x1E || widget.raw1E == after.raw1E)
            val referenceHeld = after.liveAlignment?.targetGlobalIndex ==
                shiftedTarget(widget, originalIndices)
            if (widget.widgetType != after.widgetType ||
                widget.sequenceId != after.sequenceId ||
                widget.x != after.x ||
                widget.y != after.y ||
                widget.recordSize != after.recordSize ||
                widget.liveAlignment?.code != after.liveAlignment?.code ||
                !geometryHeld ||
                !referenceHeld
            ) {
                throw Fit3FormatException(
                    "${original.basename}: widget ${widget.ordinal} changed beyond its pointers",
                )
            }
            val redrawn = pointerSignatures(parsed, after, relativeImages)
            if (redrawn != before.getValue(widget.ordinal)) {
                throw Fit3FormatException(
                    "${original.basename}: widget ${widget.ordinal} no longer draws the " +
                        "same rasters after relocation",
                )
            }
        }
        // Every raster the style already held has to be exactly where it was, byte for
        // byte: the new one is appended, so the old section is an untouched prefix. This
        // is what makes the edit safe without relocating a single pointer.
        val originalImageSection = original.data.copyOfRange(
            original.data.u32(0x14).checkedInt("image offset"),
            original.data.size,
        )
        val newSectionStart = style.u32(0x14).checkedInt("image offset")
        val keptImageSection = style.copyOfRange(
            newSectionStart,
            newSectionStart + originalImageSection.size,
        )
        if (!originalImageSection.contentEquals(keptImageSection)) {
            throw Fit3FormatException(
                "${original.basename}: the original rasters did not survive verbatim",
            )
        }
        // And every widget record apart from the new one has to be byte-identical bar its
        // renumbered index, which is stronger than comparing the decoded fields.
        expected.forEach { widget ->
            val after = widgets[widget.ordinal + 1]
            val was = original.data.copyOfRange(
                widget.recordOffset,
                widget.recordOffset + widget.recordSize,
            )
            val now = style.copyOfRange(after.recordOffset, after.recordOffset + after.recordSize)
            was.putU32(0x0C, 0)
            now.putU32(0x0C, 0)
            // Both the index and the reference to a renumbered widget are meant to
            // differ; the decoded check above is what holds them to the right values, so
            // the byte comparison zeroes them and speaks for everything else.
            widget.liveAlignment?.let {
                WidgetSchema.spec(widget.widgetType).alignment?.let { field ->
                    was.putU16(field.targetOffset, 0)
                    now.putU16(field.targetOffset, 0)
                }
            }
            if (!was.contentEquals(now)) {
                throw Fit3FormatException(
                    "${original.basename}: widget ${widget.ordinal} was rewritten",
                )
            }
        }
    }

    private fun resizeWidgetEntry(
        entry: ContainerEntry,
        pristineEntry: ContainerEntry?,
        target: WidgetRecord,
        width: Int,
        height: Int,
        sourceIndices: Map<Int, Int>?,
    ): ByteArray {
        val spec = WidgetSchema.spec(target.widgetType)
        return when (val model = spec.resize) {
            null -> throw Fit3FormatException(
                "${entry.basename}: a ${spec.name} widget cannot be resized",
            )
            is WidgetSchema.ResizeModel.Box ->
                resizeBoxEntry(entry, target, width, height)
            is WidgetSchema.ResizeModel.Endpoint ->
                resizeEndpointEntry(entry, pristineEntry, target, width, height, sourceIndices)
            else -> resizeRasterEntry(entry, pristineEntry, target, model, width, height, sourceIndices)
        }
    }

    /**
     * Resizes a vector arc, whose `+0x1C`/`+0x1E` box *is* its size.
     *
     * This and [resizeEndpointEntry] are the two resizes that are **same-size patches**:
     * nothing is resampled, the image section is not touched, no pointer is rewritten, and
     * the container does not change length by a single byte. So neither can cross
     * [WATCH_CONTAINER_BYTE_CEILING], neither can disturb the image-record count, and
     * neither can leave a pointer stale — the three things that have actually gone wrong
     * on hardware here. What they rest on instead is the constructor's own reading of
     * these fields: a vector arc is drawn from a signed bounding box, an angle range and a
     * thickness, and it names no raster at all.
     *
     * Thickness is deliberately left alone. It is its own field at `+0x40` and face `00108`
     * ships one box at three different thicknesses across its styles, so a resize that
     * scaled it would be inventing a second edit the user did not ask for.
     */
    private fun resizeBoxEntry(
        entry: ContainerEntry,
        target: WidgetRecord,
        width: Int,
        height: Int,
    ): ByteArray {
        if (target.storedWidth == width && target.storedHeight == height) {
            throw Fit3FormatException("${entry.basename}: a resize requires a dimension change")
        }
        val replacement = entry.data.copyOf()
        replacement.putU16(target.recordOffset + 0x1C, width and 0xFFFF)
        replacement.putU16(target.recordOffset + 0x1E, height and 0xFFFF)
        validateStructuralEntry(
            entry,
            replacement,
            FaceRecordParser.scanWidgets(entry).size,
        )
        return replacement
    }

    /**
     * Resizes a Rule by scaling its endpoint vector.
     *
     * The span is scaled rather than replaced, because `+0x1C`/`+0x1E` is the *second
     * endpoint* and not an extent. Writing `x + width` into it would flip the 52 of the
     * catalogue's 84 Rules whose stored endpoint is the far one across their own start
     * point, and it would turn each of the 32 exactly-horizontal Rules into a diagonal the
     * moment the ladder asked for a height. Scaling keeps the sign of each delta and keeps
     * a zero span at zero, so a Rule stays the line it was and only its length changes.
     *
     * The reference is the pristine record where there is one, so a rung always lands on
     * the same geometry however many times it has been stepped — the same reason a raster
     * resize resamples the pristine pixels. `moveWidget` already translates both endpoints
     * together, so a Rule that has only been dragged has the same span in both containers.
     */
    private fun resizeEndpointEntry(
        entry: ContainerEntry,
        pristineEntry: ContainerEntry?,
        target: WidgetRecord,
        width: Int,
        height: Int,
        sourceIndices: Map<Int, Int>?,
    ): ByteArray {
        val origin = pristineEntry?.let { pristineRecord(entry, it, target, sourceIndices) } ?: target
        val spanX = origin.raw1C.toShort().toInt() - origin.x
        val spanY = origin.raw1E.toShort().toInt() - origin.y
        // The extent a Rule reports is its span **or its thickness**, whichever is larger —
        // the floor `drawnExtents` applies — so that is what the requested size is a
        // fraction of, and the thickness has to scale with it.
        //
        // Leaving the thickness alone is what made the resize ladder throw at the user.
        // 32 of the catalogue's 84 Rules are exactly horizontal, so one axis of their
        // reported extent *is* the thickness and no endpoint write can move it; a further
        // 24 have a short axis the thickness floors. The rung the editor offered was
        // therefore never the extent the widget came back with, `nextWidgetSize` re-offered
        // the rung already in force, and the no-change guard below refused it — on 56 of
        // the 84 records, within two taps on face `00049`. Scaling the thickness by the
        // same ratio makes the whole extent scale linearly, which is exactly what the
        // ladder assumes: `max(|dx|, t)` and `max(|dy|, t)` both multiply by the ratio, so
        // the extent the guide reports afterwards is the rung that was asked for.
        val storedThickness = origin.ruleThickness
        val thickness = storedThickness?.takeIf { it >= RULE_MINIMUM_THICKNESS }
            ?: RULE_FALLBACK_THICKNESS
        val reportedWidth = maxOf(abs(spanX), thickness)
        val reportedHeight = maxOf(abs(spanY), thickness)
        val endX = target.x + scaledSpan(spanX, width, reportedWidth)
        val endY = target.y + scaledSpan(spanY, height, reportedHeight)
        // Only when the record's own byte is the one the extent was measured from. A record
        // storing something below the floor is not describing a thickness this layer
        // understands, and scaling the substituted value would write a number the producer
        // never had. No catalogue Rule does that — they run 4 to 59.
        // Where an axis of the reported extent *is* the thickness, the requested value for
        // that axis is what the thickness has to become — not a second rounding of the same
        // number. The ladder scales the reported extent by a percentage and this scales it
        // by a ratio of pixels, and on face `00049` the two disagreed by one: the rung asked
        // for 92×10 and a ratio-scaled thickness came back 92×9, which is the same
        // off-the-ladder state the throw used to be, one step later.
        val scaledThickness = storedThickness
            ?.takeIf { it >= RULE_MINIMUM_THICKNESS }
            ?.let {
                val scaled = if (reportedHeight == thickness) {
                    height
                } else {
                    scaledExtent(it, width, reportedWidth)
                }
                scaled.coerceIn(RULE_MINIMUM_THICKNESS, 0xFF)
            }
        val unchanged = endX == target.raw1C.toShort().toInt() &&
            endY == target.raw1E.toShort().toInt() &&
            (scaledThickness == null || scaledThickness == target.ruleThickness)
        if (unchanged) {
            throw Fit3FormatException("${entry.basename}: a resize requires a dimension change")
        }
        if (endX !in Short.MIN_VALUE..Short.MAX_VALUE ||
            endY !in Short.MIN_VALUE..Short.MAX_VALUE
        ) {
            throw Fit3FormatException(
                "${entry.basename}: resized Rule endpoints must fit signed 16-bit integers",
            )
        }
        val replacement = entry.data.copyOf()
        replacement.putU16(target.recordOffset + 0x1C, endX and 0xFFFF)
        replacement.putU16(target.recordOffset + 0x1E, endY and 0xFFFF)
        scaledThickness?.let { replacement[target.recordOffset + 0x30] = it.toByte() }
        validateStructuralEntry(
            entry,
            replacement,
            FaceRecordParser.scanWidgets(entry).size,
        )
        return replacement
    }

    /** [extent] scaled by `requested / from`, never below one pixel. */
    private fun scaledExtent(extent: Int, requested: Int, from: Int): Int =
        if (from <= 0) {
            extent
        } else {
            ((extent.toLong() * requested + from / 2) / from).toInt().coerceAtLeast(1)
        }

    /** [span] scaled by `requested / from`, keeping its sign and keeping zero at zero. */
    private fun scaledSpan(span: Int, requested: Int, from: Int): Int {
        if (span == 0 || from <= 0) return span
        val scaled = ((abs(span).toLong() * requested + from / 2) / from).toInt()
        return if (span < 0) -scaled else scaled
    }

    /**
     * Resizes a widget whose size is its artwork's: Static, Sprite, Hand, image Arc and
     * LineBar.
     *
     * Every raster in the widget's pool is rewritten **in place** at the new size, and the
     * record count is asserted afterwards, because a container with more image records
     * than it shipped with is one the watch installs and then goes on ignoring.
     *
     * [model] decides what else moves with the pixels, and each of the three answers is a
     * field that would otherwise be left describing the old artwork:
     *
     * * [WidgetSchema.ResizeModel.Raster] — nothing. A Static and a Sprite carry no size.
     * * [WidgetSchema.ResizeModel.RasterWithPivot] — a Hand's rotation pivot scales with
     *   the artwork and `x`/`y` absorbs the difference, so the point the watch rotates
     *   about does not move.
     * * [WidgetSchema.ResizeModel.RasterWithBox] — the stored box scales with it, and the
     *   *box* is what the caller's [width] × [height] means: an image Arc draws its raster
     *   at native size centred in a box that is a different size again (face `00108` ships
     *   a 204×204 ring in a 256×256 box), so the raster is scaled by the box's ratio rather
     *   than set to the requested numbers.
     */
    private fun resizeRasterEntry(
        entry: ContainerEntry,
        pristineEntry: ContainerEntry?,
        target: WidgetRecord,
        model: WidgetSchema.ResizeModel,
        width: Int,
        height: Int,
        sourceIndices: Map<Int, Int>?,
    ): ByteArray {
        val images = FaceRecordParser.scanImages(entry)
        val widgets = FaceRecordParser.scanWidgets(entry)
        val sectionStart = images.firstOrNull()?.recordOffset
            ?: throw Fit3FormatException("${entry.basename}: style contains no images")
        val relativeImages = images.associateBy { (it.recordOffset - sectionStart).toLong() }
        // Throws when a pointer this type is defined to carry does not resolve, which is
        // the schema check every structural edit wants before it moves anything.
        FaceRecordParser.imagePointerFields(target, relativeImages)
        // Every raster the edit has to touch, not just the ones this record names.
        //
        // A face keeps one glyph pool and points several widgets into it — on 00022 the
        // hour's tens digit addresses frames 2–4 and its units digit 2–11 — so resizing
        // only the named frames left the neighbour drawing three small glyphs and seven
        // large ones, its box still reporting the largest. The frames are shared records;
        // there is no resizing one widget's copy, because there is only one copy.
        val pool = FaceRecordParser.rasterPool(target, widgets, relativeImages)
        val targetIndices = pool.images
        val backgroundIndex = FaceRecordParser.backgroundImage(entry)?.index
        if (backgroundIndex != null && backgroundIndex in targetIndices) {
            throw Fit3FormatException(
                "${entry.basename}: a resize refuses the full-panel background raster",
            )
        }
        // Only one type may reach into the pool: a Static or a Hand sharing a digit frame
        // would mean the pool is not what this edit thinks it is.
        pool.widgets.firstOrNull { it.widgetType != target.widgetType }?.let {
            throw Fit3FormatException(
                "${entry.basename}: widget ${it.ordinal} shares a raster with the " +
                    "${WidgetSchema.spec(target.widgetType).name} but is type ${it.widgetType}",
            )
        }
        val selected = targetIndices.sorted().map(images::get)
        val signatures = selected.map {
            listOf(it.width, it.height, it.format, it.reserved, it.opaqueTrailerSize)
        }.toSet()
        if (signatures.size != 1) {
            throw Fit3FormatException("${entry.basename}: pooled rasters do not share one format")
        }
        val signature = signatures.single()
        if (signature[2] !in RESAMPLED_FORMATS ||
            signature[3] != 0 ||
            signature[4] != OPAQUE_TRAILER_BYTES
        ) {
            throw Fit3FormatException(
                "${entry.basename}: a resize requires the proven raster trailer schema",
            )
        }

        // The unedited record behind each raster, resolved through the widget that names
        // it rather than by image index.
        //
        // Index matching held only while nothing ever changed the record count, and
        // adding a background breaks both halves of that: the count differs by one, so
        // the pristine frames were dropped entirely and every resize resampled the
        // *previous* resize — Smaller, Larger, Smaller came back visibly softer — and
        // even with the count patched up, index i would name the raster before it.
        val pristineOrigins = pristineFrameOrigins(entry, pristineEntry, widgets, relativeImages, sourceIndices)
        val pristineRecords = pristineRecords(entry, pristineEntry, pool.widgets, sourceIndices)
        val pristineTarget = pristineRecords[target.globalIndex]
        val pristineRaster = targetIndices.mapNotNull { pristineOrigins[it] }

        // **One reference frame, never a mix of the two.** Everything below is measured
        // either entirely against the unedited container or entirely against the current
        // one, and which is decided once, here.
        //
        // Mixing them is a bug with no symptom at the time. A duplicated widget has no
        // pristine counterpart of its own — that is what `duplicateSourceGlobalIndex` is
        // for — while the raster it shares with its source pairs perfectly, so the pristine
        // artwork size resolved and the pristine *record* did not. Scaling one against the
        // other left a cloned image Arc's raster 102 px inside a 64 px box after two taps,
        // and left a cloned Hand's pivot at its shipped value inside half-size artwork,
        // which is the 8×76 px slide off the dial that AGENTS.md already has a name for.
        val pristineFrame = targetIndices.isNotEmpty() &&
            pristineRaster.size == targetIndices.size
        val shippedRasterWidth =
            if (pristineFrame) pristineRaster.maxOf(ImageRecord::width) else signature[0]
        val shippedRasterHeight =
            if (pristineFrame) pristineRaster.maxOf(ImageRecord::height) else signature[1]

        // What the caller's numbers mean, and what the rasters therefore become. They are
        // the same thing for every type whose extent *is* its artwork, and they are not
        // for the two that draw their artwork inside a box of another size.
        val boxed = model as? WidgetSchema.ResizeModel.RasterWithBox
        val boxFrame = pristineFrame && pristineTarget != null
        val shippedWidth: Int
        val shippedHeight: Int
        val rasterWidth: Int
        val rasterHeight: Int
        if (boxed == null) {
            shippedWidth = shippedRasterWidth
            shippedHeight = shippedRasterHeight
            rasterWidth = width
            rasterHeight = height
        } else {
            val box = (if (boxFrame) pristineTarget else target)
                ?: throw Fit3FormatException(
                    "${entry.basename}: widget ${target.ordinal} has no record to resize",
                )
            shippedWidth = box.storedWidth?.takeIf { it > 0 }
                ?: throw Fit3FormatException(
                    "${entry.basename}: widget ${target.ordinal} stores no box to resize",
                )
            shippedHeight = box.storedHeight?.takeIf { it > 0 }
                ?: throw Fit3FormatException(
                    "${entry.basename}: widget ${target.ordinal} stores no box to resize",
                )
            // The raster and the box are scaled from the *same* frame, so their ratio is
            // the one the face shipped however many rungs have been stepped since.
            val rasterBaseWidth = if (boxFrame) shippedRasterWidth else signature[0]
            val rasterBaseHeight = if (boxFrame) shippedRasterHeight else signature[1]
            rasterWidth = scaledExtent(rasterBaseWidth, width, shippedWidth)
            rasterHeight = scaledExtent(rasterBaseHeight, height, shippedHeight)
        }
        // A widget must be able to come back to what the face shipped — `00022`'s digits
        // are 114×136 — and growing past that is what RASTER_RESIZE_CEILING bounds.
        //
        // The bound is on the *extent*, which for a boxed type is its box rather than its
        // raster, so an image Arc whose artwork overhangs its box carries that overhang
        // past the limit with it: face `00028`'s 90 px ring in an 84 px box reaches 137 px
        // of raster at the 128 px rung. Clamping the raster instead would break the ratio
        // the whole model rests on, the widest overhang in the catalogue is 7%, and
        // `rebuild` still holds the container to 4 MiB — which is what the limit is for.
        val widthLimit = widgetResizeLimit(shippedWidth, WidgetResizeKind.RASTER)
        val heightLimit = widgetResizeLimit(shippedHeight, WidgetResizeKind.RASTER)
        if (width > widthLimit || height > heightLimit) {
            throw Fit3FormatException(
                "${entry.basename}: a widget that shipped at ${shippedWidth}x$shippedHeight " +
                    "may be resized up to ${widthLimit}x$heightLimit, not ${width}x$height",
            )
        }
        val boxUnchanged = boxed == null ||
            (target.storedWidth == width && target.storedHeight == height)
        if (signature[0] == rasterWidth && signature[1] == rasterHeight && boxUnchanged) {
            throw Fit3FormatException("${entry.basename}: a resize requires a dimension change")
        }

        val newSection = ByteArrayOutputStream()
        val mappedOffsets = linkedMapOf<Long, Long>()
        images.forEach { image ->
            val oldRelative = (image.recordOffset - sectionStart).toLong()
            mappedOffsets[oldRelative] = newSection.size().toLong()
            val recordEnd = image.pixelOffset + image.dataSize
            if (image.index !in targetIndices) {
                newSection.write(entry.data, image.recordOffset, recordEnd - image.recordOffset)
            } else {
                writeResizedFrame(
                    out = newSection,
                    entry = entry,
                    image = image,
                    origin = pristineOrigins[image.index]?.takeIf {
                        it.format == image.format &&
                            it.reserved == image.reserved &&
                            it.opaqueTrailerSize == image.opaqueTrailerSize
                    },
                    originEntry = pristineEntry,
                    width = rasterWidth,
                    height = rasterHeight,
                )
            }
        }

        val moved = mappedOffsets.filter { (old, new) -> old != new }.keys
        val prefix = entry.data.copyOfRange(0, sectionStart)
        relocatePointers(
            entry = entry,
            target = prefix,
            widgets = widgets,
            relativeImages = relativeImages,
            movedOffsets = moved,
        ) { _, before -> mappedOffsets.getValue(before) }
        // Whatever else the model says has to describe the new artwork rather than the old
        // — for **every** widget in the pool, not just the one that was selected. They
        // share the raster records, so they all now draw artwork of a different size.
        pool.widgets.forEach { pooled ->
            rewriteResizedFields(
                entry = entry,
                pristineEntry = pristineEntry,
                prefix = prefix,
                record = pooled,
                pristineRecord = pristineRecords[pooled.globalIndex]?.takeIf { pristineFrame },
                isTarget = pooled.globalIndex == target.globalIndex,
                model = model,
                width = width,
                height = height,
                rasterWidth = rasterWidth,
                rasterHeight = rasterHeight,
                shippedRasterWidth = shippedRasterWidth,
                shippedRasterHeight = shippedRasterHeight,
                currentRasterWidth = signature[0],
                currentRasterHeight = signature[1],
                shippedWidth = shippedWidth,
                shippedHeight = shippedHeight,
            )
        }
        val section = newSection.toByteArray()
        val replacement = prefix + section
        replacement.putU32(0x0C, section.size)
        val parsed = validateRelocatedEntry(entry, replacement)
        val parsedImages = FaceRecordParser.scanImages(parsed)
        // The record count is exactly what the watch refuses to see change, so it is
        // asserted rather than assumed.
        if (parsedImages.size != images.size) {
            throw Fit3FormatException("a resize changed the image-record count")
        }
        targetIndices.forEach { index ->
            if (parsedImages[index].width != rasterWidth ||
                parsedImages[index].height != rasterHeight
            ) {
                throw Fit3FormatException("resized dimensions did not persist")
            }
        }
        // Every raster outside the pool keeps the size it had.
        images.filterNot { it.index in targetIndices }.forEach { before ->
            val after = parsedImages[before.index]
            if (after.width != before.width || after.height != before.height) {
                throw Fit3FormatException("a resize disturbed a raster outside the pool")
            }
        }
        // The pointers still name the same rasters, in the same order. A Sprite whose
        // frame table repeats a raster — face 00046's weather set reuses three — has to go
        // on repeating exactly the same one, or the relocation has quietly repointed a frame.
        val resizedTarget = FaceRecordParser.scanWidgets(parsed)
            .single { it.globalIndex == target.globalIndex }
        val parsedRelative = parsedImages.associateBy {
            (it.recordOffset - parsedImages.first().recordOffset).toLong()
        }
        val oldIds = FaceRecordParser.imagePointerFields(target, relativeImages)
            .map { it.image.index }
        val newIds = FaceRecordParser.imagePointerFields(resizedTarget, parsedRelative)
            .map { it.image.index }
        if (oldIds != newIds) {
            throw Fit3FormatException("a resize changed the raster mapping")
        }
        return replacement
    }

    /**
     * Rewrites the fields that describe the artwork's size, for the models that have any.
     *
     * Called for **every widget in the pool**, because a pool is shared *records*: 12 of
     * the catalogue's 16 LineBars share one raster three ways and 18 of its 469 Hands share
     * theirs, so resizing the artwork under one of them leaves the others' own fields
     * describing a size that no longer exists. That fails no validation — the container
     * parses, the CRCs match, the install is accepted — and the widget simply draws wrong,
     * which is the same shape as the bug that left a neighbour sprite drawing three small
     * glyphs and seven large ones.
     *
     * Each field is scaled from the **pristine** record where there is one, so stepping the
     * ladder down and back up returns the exact bytes it started from rather than drifting
     * a pixel a time — the same reason the pixels are resampled from the pristine container.
     */
    private fun rewriteResizedFields(
        entry: ContainerEntry,
        pristineEntry: ContainerEntry?,
        prefix: ByteArray,
        record: WidgetRecord,
        pristineRecord: WidgetRecord?,
        isTarget: Boolean,
        model: WidgetSchema.ResizeModel,
        width: Int,
        height: Int,
        rasterWidth: Int,
        rasterHeight: Int,
        shippedRasterWidth: Int,
        shippedRasterHeight: Int,
        currentRasterWidth: Int,
        currentRasterHeight: Int,
        shippedWidth: Int,
        shippedHeight: Int,
    ) {
        val base = record.recordOffset
        when (model) {
            is WidgetSchema.ResizeModel.RasterWithPivot -> {
                // The pivot is a point *inside* the artwork, so it scales with it — and
                // then `x`/`y` has to absorb the difference, because the watch rotates the
                // hand about `x + pivot` and that point must not move. Getting this half
                // wrong is how the AOD renderer first slid a hand off the dial by the
                // pivot's own offset: 8×76 px on face `00046`.
                val reference = pristineRecord ?: record
                // ...and read out of the container that record came from. Reading the
                // pristine record's *offset* out of the current entry's bytes is a bug that
                // looks like it works: the offsets coincide while only rasters have changed
                // size, so it silently re-scales the pivot from the value the last resize
                // left, and a Hand stepped down and back up came back with a pivot two
                // thirds of the size of the artwork it belongs to.
                val pristineFrame = pristineRecord != null && pristineEntry != null
                val referenceData = if (pristineFrame) requireNotNull(pristineEntry).data
                else entry.data
                // The denominator has to be the artwork the reference pivot sits *inside*.
                // Using the post-resize size here scaled the pivot by 1 and wrote back the
                // value that was already there, which resamples a Hand's artwork and leaves
                // its rotation pivot describing the size it used to be — silently, on a type
                // the canvas draws no rectangle for.
                val referenceWidth = if (pristineFrame) shippedRasterWidth else currentRasterWidth
                val referenceHeight =
                    if (pristineFrame) shippedRasterHeight else currentRasterHeight
                val pivot = model.pivotOffset
                val referencePivotX = referenceData.u16(reference.recordOffset + pivot)
                    .toShort().toInt()
                val referencePivotY = referenceData.u16(reference.recordOffset + pivot + 2)
                    .toShort().toInt()
                val currentPivotX = entry.data.u16(base + pivot).toShort().toInt()
                val currentPivotY = entry.data.u16(base + pivot + 2).toShort().toInt()
                val pivotX = scaledSpan(referencePivotX, rasterWidth, referenceWidth)
                val pivotY = scaledSpan(referencePivotY, rasterHeight, referenceHeight)
                val x = record.x + (currentPivotX - pivotX)
                val y = record.y + (currentPivotY - pivotY)
                if (listOf(pivotX, pivotY, x, y).any { it !in Short.MIN_VALUE..Short.MAX_VALUE }) {
                    throw Fit3FormatException(
                        "${entry.basename}: a resized Hand's pivot and position must fit " +
                            "signed 16-bit integers",
                    )
                }
                prefix.putU16(base + 0x18, x and 0xFFFF)
                prefix.putU16(base + 0x1A, y and 0xFFFF)
                prefix.putU16(base + pivot, pivotX and 0xFFFF)
                prefix.putU16(base + pivot + 2, pivotY and 0xFFFF)
            }

            is WidgetSchema.ResizeModel.RasterWithBox -> {
                // The selected widget gets exactly the box that was asked for. A widget
                // sharing its raster gets its *own* box scaled by the same ratio, which is
                // not always the same number: face `00028` puts an 84×84 box and an 88×88
                // box on the same face, so setting them all to one figure would resize a
                // neighbour to a size nobody chose.
                // A sibling refuses on an unreadable box exactly as the target does. It
                // used to `return` instead, which abandoned the whole function for that
                // record — including the two `putU16`s below — after the shared rasters had
                // already been rewritten: the silent stale-field outcome this function
                // exists to prevent, in the one branch meant to prevent it.
                val boxSource = (if (pristineRecord != null) pristineRecord else record)
                val boxWidth = if (isTarget) {
                    width
                } else {
                    scaledExtent(
                        boxSource.storedWidth?.takeIf { it > 0 } ?: throw Fit3FormatException(
                            "${entry.basename}: widget ${record.ordinal} shares this raster " +
                                "and stores no box to scale with it",
                        ),
                        width,
                        shippedWidth,
                    )
                }
                val boxHeight = if (isTarget) {
                    height
                } else {
                    scaledExtent(
                        boxSource.storedHeight?.takeIf { it > 0 } ?: throw Fit3FormatException(
                            "${entry.basename}: widget ${record.ordinal} shares this raster " +
                                "and stores no box to scale with it",
                        ),
                        height,
                        shippedHeight,
                    )
                }
                if (boxWidth !in 1..WIDGET_EXTENT_CEILING ||
                    boxHeight !in 1..WIDGET_EXTENT_CEILING
                ) {
                    throw Fit3FormatException(
                        "${entry.basename}: widget ${record.ordinal} would take a " +
                            "${boxWidth}x$boxHeight box, outside 1..$WIDGET_EXTENT_CEILING",
                    )
                }
                prefix.putU16(base + 0x1C, boxWidth and 0xFFFF)
                prefix.putU16(base + 0x1E, boxHeight and 0xFFFF)
                // A LineBar's thickness equals its stored height in all 16 catalogue
                // records, and it is the field the watch derives the bar's corner radius
                // from, so it has to keep equalling it.
                model.thicknessOffset?.let {
                    prefix[base + it] = boxHeight.coerceIn(1, 0xFF).toByte()
                }
            }

            // A Static and a Sprite carry no size field, so there is nothing to follow the
            // artwork. Spelled out rather than left to an `else`, because this is the file
            // whose rule is that a type added to one `when` and missed in another is how a
            // valid container becomes a blank widget: a sixth model would fail to compile
            // here instead of resizing the pixels and writing none of its own fields.
            is WidgetSchema.ResizeModel.Raster -> Unit

            is WidgetSchema.ResizeModel.Box,
            is WidgetSchema.ResizeModel.Endpoint,
            -> throw Fit3FormatException(
                "${entry.basename}: ${WidgetSchema.spec(record.widgetType).name} stores its " +
                    "own extent and must not reach the raster path",
            )
        }
    }

    /**
     * Each of [records] paired with the record it came from in the unedited container.
     *
     * One [FaceRecordParser.originalWidgetSources] pass for the whole pool rather than one
     * per widget: it walks both tables several times over, and a resize can reach eight
     * widgets at once.
     */
    private fun pristineRecords(
        entry: ContainerEntry,
        pristineEntry: ContainerEntry?,
        records: List<WidgetRecord>,
        sourceIndices: Map<Int, Int>?,
    ): Map<Int, WidgetRecord> {
        if (pristineEntry == null) return emptyMap()
        val sources = sourceIndices ?: FaceRecordParser.originalWidgetSources(entry, pristineEntry)
        val pristine = FaceRecordParser.scanWidgets(pristineEntry)
            .associateBy(WidgetRecord::globalIndex)
        return records.mapNotNull { record ->
            sources[record.globalIndex]
                ?.let(pristine::get)
                ?.let { record.globalIndex to it }
        }.toMap()
    }

    /**
     * The record [target] came from in the unedited container, or null when nothing
     * recoverable says which one that is.
     *
     * [FaceRecordParser.originalWidgetSources] rather than `(type, sequenceId)`, for the
     * reason [pristineFrameOrigins] gives: a global index is not an identity across a
     * structural edit and a Static's data source is `0` in 678 of 681 records, so neither
     * on its own names a record.
     */
    private fun pristineRecord(
        entry: ContainerEntry,
        pristineEntry: ContainerEntry,
        target: WidgetRecord,
        sourceIndices: Map<Int, Int>?,
    ): WidgetRecord? {
        val index = (sourceIndices ?: FaceRecordParser.originalWidgetSources(entry, pristineEntry))[
            target.globalIndex,
        ] ?: return null
        return FaceRecordParser.scanWidgets(pristineEntry)
            .firstOrNull { it.globalIndex == index }
    }

    /**
     * Current image index → the record it came from in the unedited container.
     *
     * Resolved through widget identity, because that is the only thing a structural edit
     * preserves: each pointer-bearing widget is paired with its pristine counterpart and
     * their pointer lists are matched by position. Face `00022`'s hour digits name frames
     * 2–4 and 2–11 in both containers whatever the records were renumbered to, so this
     * survives an inserted background, a removal, and a duplicate.
     *
     * **The pairing has to be [FaceRecordParser.originalWidgetSources], not `(type,
     * sequenceId)`.** That key was written when only Sprites could be resized, where it
     * is unique in 1,486 of 1,518 records. It is not an identity in general: a **Static's
     * source word is `0` in 678 of the catalogue's 681 records**, so on any style carrying
     * two Statics the `singleOrNull` below found nothing, every origin was dropped, and
     * the resize silently resampled the *previous* resize — the same chained-loss defect
     * that made Smaller, Larger, Smaller come back visibly softer on a real watch, arriving
     * by a different route the moment resize reached a second type.
     *
     * A frame two widgets disagree about is dropped rather than guessed, which falls back
     * to resampling the current pixels for that frame alone.
     */
    private fun pristineFrameOrigins(
        entry: ContainerEntry,
        pristineEntry: ContainerEntry?,
        widgets: List<WidgetRecord>,
        relativeImages: Map<Long, ImageRecord>,
        sourceIndices: Map<Int, Int>?,
    ): Map<Int, ImageRecord> {
        if (pristineEntry == null) return emptyMap()
        val pristineImages = FaceRecordParser.scanImages(pristineEntry)
        if (pristineImages.isEmpty()) return emptyMap()
        val pristineStart = pristineImages.first().recordOffset
        val pristineRelative = pristineImages.associateBy {
            (it.recordOffset - pristineStart).toLong()
        }
        val pristineWidgets = FaceRecordParser.scanWidgets(pristineEntry)
            .associateBy(WidgetRecord::globalIndex)
        val pristineSources = sourceIndices ?: FaceRecordParser.originalWidgetSources(entry, pristineEntry)
        val origins = mutableMapOf<Int, ImageRecord>()
        val ambiguous = mutableSetOf<Int>()
        widgets.filter { it.widgetType in FaceRecordParser.POINTER_BEARING_TYPES }
            .forEach { widget ->
                val match = pristineSources[widget.globalIndex]?.let(pristineWidgets::get)
                    ?: return@forEach
                val current = runCatching {
                    FaceRecordParser.imagePointerFields(widget, relativeImages)
                }.getOrNull() ?: return@forEach
                val before = runCatching {
                    FaceRecordParser.imagePointerFields(match, pristineRelative)
                }.getOrNull() ?: return@forEach
                if (current.size != before.size) return@forEach
                current.forEachIndexed { position, field ->
                    val origin = before[position].image
                    val existing = origins[field.image.index]
                    if (existing != null && existing.recordOffset != origin.recordOffset) {
                        ambiguous += field.image.index
                    }
                    origins[field.image.index] = origin
                }
            }
        ambiguous.forEach(origins::remove)
        return origins
    }

    private fun writeResizedFrame(
        out: ByteArrayOutputStream,
        entry: ContainerEntry,
        image: ImageRecord,
        origin: ImageRecord?,
        originEntry: ContainerEntry?,
        width: Int,
        height: Int,
    ) {
        val from = if (origin != null && originEntry != null) origin else image
        val data = if (origin != null && originEntry != null) originEntry.data else entry.data
        // An indexed raster's payload is a 1,024-byte BGRA palette followed by one index
        // per pixel. The palette is fixed-length and describes colours, not geometry, so
        // it is copied through untouched and only the sample plane is resampled — which
        // is also why that one format is resampled by nearest neighbour.
        val palette = data.copyOfRange(from.pixelOffset, from.samplesOffset)
        val resized = RasterResampler.resample(
            data.copyOfRange(from.samplesOffset, from.pixelOffset + from.pixelDataSize),
            from.format,
            from.width,
            from.height,
            width,
            height,
        )
        val trailer = entry.data.copyOfRange(
            image.pixelOffset + image.pixelDataSize,
            image.pixelOffset + image.dataSize,
        )
        val header = ByteArray(IMAGE_HEADER_SIZE)
        header.putU16(0, width)
        header.putU16(2, height)
        header.putU16(4, image.format)
        header.putU16(6, image.reserved)
        header.putU32(8, palette.size + resized.size + trailer.size)
        out.write(header)
        out.write(palette)
        out.write(resized)
        out.write(trailer)
    }

    /**
     * Rewrite a record's alignment reference through an index renumbering.
     *
     * Four types position themselves against another widget by its global index —
     * Static and Hand at `+0x1E`, Value and Composite at `+0x22` — and every one of the
     * catalogue's 2,311 such records has a live reference. So a structural edit that
     * renumbers the table has to carry those references with it, or a survivor ends up
     * measured from a different widget than the one it was authored against.
     *
     * [renumber] returns the new index for an old one, or null for a value that named no
     * record before the edit. Those are left alone on purpose: the catalogue's own faces
     * store targets — 5, 10, 20, 30, 40 — that match nothing in the style, which the
     * watch treats as "measure from the whole face". Rewriting them would invent a
     * reference the producer never made.
     *
     * This is emphatically **not** a return of the guard that scanned every word for a
     * value that looked like an index and blocked 68% of removals. Only these two fields
     * on these four types are references; nothing else is touched.
     */
    private fun remapAlignmentTarget(
        record: ByteArray,
        widget: WidgetRecord,
        renumber: (Int) -> Int?,
    ) {
        val alignment = widget.liveAlignment ?: return
        val field = WidgetSchema.spec(widget.widgetType).alignment ?: return
        val moved = renumber(alignment.targetGlobalIndex) ?: return
        if (moved != alignment.targetGlobalIndex) {
            record.putU16(field.targetOffset, moved and 0xFFFF)
        }
    }

    /**
     * Where a survivor's reference should point after an insert at index 0: one higher
     * when it named a record, and untouched when it named nothing.
     */
    private fun shiftedTarget(widget: WidgetRecord, originalIndices: Set<Int>): Int? =
        widget.liveAlignment?.targetGlobalIndex?.let { old ->
            if (old in originalIndices) old + 1 else old
        }

    /** The widgets whose live alignment reference names [globalIndex]. */
    private fun dependentsOf(widgets: List<WidgetRecord>, globalIndex: Int): List<WidgetRecord> =
        widgets.filter { it.liveAlignment?.targetGlobalIndex == globalIndex }

    private fun removeWidgetEntry(
        entry: ContainerEntry,
        target: WidgetRecord,
        requireFinal: Boolean,
    ): Pair<ByteArray, ByteArray> {
        val widgets = FaceRecordParser.scanWidgets(entry)
        val images = FaceRecordParser.scanImages(entry)
        val globalIndex = target.globalIndex
        if (requireFinal && target.ordinal != widgets.lastIndex) {
            throw Fit3FormatException("${entry.basename}: selected widget is not the final widget")
        }
        requireContiguousWidgets(entry, widgets, images)
        val survivors = widgets.filter { it.ordinal != target.ordinal }
        // A widget other records are positioned against cannot simply go: they would
        // fall back to being measured from the whole face and land somewhere else. In
        // practice this is the style's background, which every aligned widget on the
        // face refers to.
        val dependents = dependentsOf(survivors, globalIndex)
        if (dependents.isNotEmpty()) {
            val names = dependents.take(3).joinToString { "widget ${it.globalIndex}" }
            throw Fit3WidgetIsAnchorException(
                globalIndex = globalIndex,
                dependentGlobalIndices = dependents.map(WidgetRecord::globalIndex),
                message = "${entry.basename}: ${dependents.size} widget(s) are positioned " +
                    "against widget $globalIndex ($names), so removing it would move them",
            )
        }
        val existingIndices = widgets.mapTo(mutableSetOf(), WidgetRecord::globalIndex)
        val oldImageOffset = entry.data.u32(0x14).checkedInt("image offset")
        val replacement = ByteArrayOutputStream()
        replacement.write(entry.data, 0, STYLE_HEADER_SIZE)
        survivors.forEach { widget ->
            val raw = entry.data.copyOfRange(
                widget.recordOffset,
                widget.recordOffset + widget.recordSize,
            )
            if (widget.globalIndex > globalIndex) {
                val indexSize = raw.u32(0x0C)
                raw.putU32(
                    0x0C,
                    ((widget.globalIndex - 1).toLong() shl 16) or (indexSize and 0xFFFF),
                )
            }
            remapAlignmentTarget(raw, widget) { old ->
                when {
                    old !in existingIndices -> null
                    old > globalIndex -> old - 1
                    else -> old
                }
            }
            replacement.write(raw)
        }
        replacement.write(entry.data, oldImageOffset, entry.data.size - oldImageOffset)
        val removedRecord = entry.data.copyOfRange(
            target.recordOffset,
            target.recordOffset + target.recordSize,
        ).also { saved ->
            // The saved bytes are what a restore writes back, so its own reference has
            // to be expressed in the numbering that will be in force then. On every
            // catalogue face this changes nothing — a target is either 0 or names no
            // record — but a face that referred forwards would otherwise come back
            // measured from the wrong widget.
            remapAlignmentTarget(saved, target) { old ->
                when {
                    old !in existingIndices -> null
                    old > globalIndex -> old - 1
                    else -> old
                }
            }
        }
        val output = replacement.toByteArray().also {
            it.putU32(0x04, widgets.size - 1)
            it.putU32(0x08, oldImageOffset - STYLE_HEADER_SIZE - target.recordSize)
            it.putU32(0x14, oldImageOffset - target.recordSize)
            validateStructuralEntry(entry, it, widgets.size - 1)
            requireSurvivorsUnchanged(entry, it, survivors) { old ->
                when {
                    old !in existingIndices -> old
                    old > globalIndex -> old - 1
                    else -> old
                }
            }
        }
        return output to removedRecord
    }

    private fun appendWidgetEntry(entry: ContainerEntry, record: ByteArray): ByteArray {
        val widgets = FaceRecordParser.scanWidgets(entry)
        val images = FaceRecordParser.scanImages(entry)
        requireContiguousWidgets(entry, widgets, images)
        if (record.size < WIDGET_FIXED_SIZE || record.size % 2 != 0 || record.size > 600) {
            throw Fit3FormatException("${entry.basename}: saved widget record is malformed")
        }
        val declaredSize = (record.u32(0x0C) and 0xFFFF).toInt()
        if (declaredSize != record.size) {
            throw Fit3FormatException(
                "${entry.basename}: saved widget record declares $declaredSize bytes " +
                    "but is ${record.size}",
            )
        }
        if (widgets.size >= 0xFFFF) {
            throw Fit3FormatException("${entry.basename}: widget index space is exhausted")
        }
        val newIndex = widgets.size.toLong()
        val restored = record.copyOf()
        restored.putU32(0x0C, (newIndex shl 16) or (record.u32(0x0C) and 0xFFFF))
        val oldImageOffset = entry.data.u32(0x14).checkedInt("image offset")
        val replacement = ByteArrayOutputStream()
        replacement.write(entry.data, 0, oldImageOffset)
        replacement.write(restored)
        replacement.write(entry.data, oldImageOffset, entry.data.size - oldImageOffset)
        return replacement.toByteArray().also {
            it.putU32(0x04, widgets.size + 1)
            it.putU32(0x08, oldImageOffset - STYLE_HEADER_SIZE + restored.size)
            it.putU32(0x14, oldImageOffset + restored.size)
            validateStructuralEntry(entry, it, widgets.size + 1)
            requireSurvivorsUnchanged(entry, it, widgets)
            // A restored Static or Sprite still has to point at real image records.
            validateRelocatedEntry(entry, it)
        }
    }

    private fun duplicateWidgetEntry(entry: ContainerEntry, source: WidgetRecord): ByteArray {
        val widgets = FaceRecordParser.scanWidgets(entry)
        val images = FaceRecordParser.scanImages(entry)
        requireContiguousWidgets(entry, widgets, images)
        if (widgets.size >= 0xFFFF) {
            throw Fit3FormatException("${entry.basename}: widget index space is exhausted")
        }
        val newIndex = widgets.size.toLong()
        val oldImageOffset = entry.data.u32(0x14).checkedInt("image offset")
        val clone = entry.data.copyOfRange(
            source.recordOffset,
            source.recordOffset + source.recordSize,
        )
        val indexSize = clone.u32(0x0C)
        clone.putU32(0x0C, (newIndex shl 16) or (indexSize and 0xFFFF))
        val replacement = ByteArrayOutputStream()
        replacement.write(entry.data, 0, oldImageOffset)
        replacement.write(clone)
        replacement.write(entry.data, oldImageOffset, entry.data.size - oldImageOffset)
        return replacement.toByteArray().also {
            it.putU32(0x04, widgets.size + 1)
            it.putU32(0x08, oldImageOffset - STYLE_HEADER_SIZE + source.recordSize)
            it.putU32(0x14, oldImageOffset + source.recordSize)
            validateStructuralEntry(entry, it, widgets.size + 1)
            requireSurvivorsUnchanged(entry, it, widgets)
        }
    }

    /**
     * Rewrites every field that holds an image-section offset, and only those.
     *
     * The pointer map lives in [FaceRecordParser.imagePointerFields] so that this and
     * the background insert cannot disagree about what a pointer is — they did, and the
     * cost was Arc and LineBar rasters going unrelocated, which draws nothing and fails
     * no validation. A type whose schema is not known still refuses the edit outright
     * when one of its words lands on a raster that moved, so an unrecognised pointer is
     * a refusal rather than a silently broken widget.
     *
     * A Static is the reason this cannot be driven off `words` alone: its pointer is
     * `+0x20`, and `words[0]` is `0x0` in every corpus Static — which only looks like a
     * pointer because `0x0` is the first image's own relative offset. Relocating the word
     * and leaving `+0x20` stale is what made faces 00010 and 00061 each lose a Static
     * when an in-place resize shifted the section under it.
     */
    private fun relocatePointers(
        entry: ContainerEntry,
        target: ByteArray,
        widgets: List<WidgetRecord>,
        relativeImages: Map<Long, ImageRecord>,
        movedOffsets: Set<Long>,
        mapOffset: (WidgetRecord, Long) -> Long,
    ) {
        widgets.forEach { widget ->
            if (widget.widgetType !in FaceRecordParser.POINTER_BEARING_TYPES) {
                // A *nonzero* word landing on a raster that moved is an unknown pointer
                // schema, and relocating what we do not understand is worse than
                // refusing. Zero is exempt: `0x0` is image 0's own relative offset, so
                // 734 Pair colour words and hundreds of zeroed Comp fields "resolve" by
                // coincidence — and the faces that ship with a background carry those
                // same zeros beside a real raster at offset 0 and render correctly, on
                // hardware, which is what proves they are not pointers.
                val collisions = widget.words.filter { it != 0L && it in movedOffsets }
                if (collisions.isNotEmpty()) {
                    throw Fit3FormatException(
                        "${entry.basename}: unsupported widget type ${widget.widgetType} " +
                            "contains moved image-like words",
                    )
                }
                return@forEach
            }
            val fields = try {
                FaceRecordParser.imagePointerFields(widget, relativeImages)
            } catch (error: Fit3FormatException) {
                throw Fit3FormatException("${entry.basename}: ${error.message}", error)
            }
            fields.forEach { field ->
                val moved = mapOffset(widget, field.value)
                if (field.value != moved) {
                    target.putU32(field.offset, moved)
                }
            }
        }
    }

    /**
     * Every pointer in the rewritten entry has to land on a real image record again.
     *
     * Checked through the same pointer map the relocation used, so a field the map knows
     * about cannot be left behind: the old version only looked at a Static's `words[0]`
     * and a Sprite's words, which meant a stale `+0x20`, Hand, Arc or LineBar pointer
     * passed unnoticed.
     */
    private fun validateRelocatedEntry(
        original: ContainerEntry,
        replacement: ByteArray,
    ): ContainerEntry {
        val temporary = original.copy(
            size = replacement.size,
            checksum = 0,
            data = replacement,
        )
        val images = FaceRecordParser.scanImages(temporary)
        val relative = images.associateBy {
            (it.recordOffset - images.first().recordOffset).toLong()
        }
        FaceRecordParser.scanWidgets(temporary)
            .filter { it.widgetType in FaceRecordParser.POINTER_BEARING_TYPES }
            .forEach { widget ->
                try {
                    FaceRecordParser.imagePointerFields(widget, relative)
                } catch (error: Fit3FormatException) {
                    throw Fit3FormatException(
                        "${original.basename}: relocated pointers do not resolve — " +
                            "${error.message}",
                        error,
                    )
                }
            }
        return temporary
    }

    private fun requireContiguousWidgets(
        entry: ContainerEntry,
        widgets: List<WidgetRecord>,
        images: List<ImageRecord>,
    ) {
        // An empty widget table is a real state, not a malformed one: removing the last
        // widget is allowed, and refusing the append that follows is what made a style
        // emptied by hand impossible to restore — "style needs widgets and images", on the
        // one edit that exists to put a widget back. The image section is what the
        // boundary check below is measured against, so that still has to be there.
        if (images.isEmpty()) {
            throw Fit3FormatException("${entry.basename}: style has no image section")
        }
        if (widgets.map { it.globalIndex } != widgets.indices.toList()) {
            throw Fit3FormatException("${entry.basename}: widget indices are not contiguous")
        }
        val imageOffset = entry.data.u32(0x14).checkedInt("image offset")
        if (images.first().recordOffset != imageOffset ||
            entry.data.u32(0x08).checkedInt("widget bytes") != imageOffset - STYLE_HEADER_SIZE
        ) {
            throw Fit3FormatException("${entry.basename}: section boundaries disagree")
        }
    }

    /**
     * Every surviving widget must come out of a structural edit byte-identical apart
     * from its renumbered `global_index` and its renumbered alignment reference, and the
     * image section must not move at all.
     *
     * [renumber] is the same mapping the edit applied, so the check is not "the
     * reference did not change" — it is "the reference still names the widget it named
     * before". Passing null means the edit renumbers nothing, and then no reference may
     * have moved either.
     *
     * This replaces an older pre-check that refused the edit whenever any opaque widget
     * word happened to equal an index in the renumbered range. That heuristic had no
     * support in the format — `+0x08`, `+0x10` and `+0x14` are zero in every one of the
     * catalogue's 4,034 records, and the type-specific words hold image offsets, colours
     * and format selectors — and it blocked 68% of removals, leaving 18 of 99 faces with
     * no removable widget at all, which is what "sometimes removing a widget does
     * nothing" looked like. The four typed reference fields are the only ones that name
     * another widget, and they are handled by name rather than by scanning.
     */
    private fun requireSurvivorsUnchanged(
        original: ContainerEntry,
        replacement: ByteArray,
        expected: List<WidgetRecord>,
        renumber: ((Int) -> Int?)? = null,
    ) {
        val parsed = original.copy(size = replacement.size, checksum = 0, data = replacement)
        val actual = FaceRecordParser.scanWidgets(parsed)
        // Appends put the new record last, so the survivors are always the prefix.
        if (actual.size < expected.size) {
            throw Fit3FormatException("${original.basename}: widget records went missing")
        }
        expected.forEachIndexed { ordinal, before ->
            val after = actual[ordinal]
            val reference = WidgetSchema.spec(before.widgetType).alignment
                ?.takeIf { before.liveAlignment != null }
            val expectedTarget = before.liveAlignment?.targetGlobalIndex?.let { old ->
                renumber?.invoke(old) ?: old
            }
            val referenceHeld = after.liveAlignment?.targetGlobalIndex == expectedTarget &&
                before.liveAlignment?.code == after.liveAlignment?.code
            // The reference lives inside one of the fields compared below, so it is
            // masked out of those and checked by name instead: `+0x1E` for an Image or a
            // Clock hand, and inside the `+0x20` word for a Value or a Composite.
            val mask: (WidgetRecord) -> Pair<Int, Long> = { record ->
                when (reference?.targetOffset) {
                    0x1E -> 0 to record.unknown20
                    0x22 -> record.raw1E to (record.unknown20 and 0xFFFFL)
                    else -> record.raw1E to record.unknown20
                }
            }
            if (before.widgetType != after.widgetType ||
                before.sequenceId != after.sequenceId ||
                before.x != after.x ||
                before.y != after.y ||
                before.raw1C != after.raw1C ||
                mask(before) != mask(after) ||
                before.recordSize != after.recordSize ||
                before.words != after.words ||
                !referenceHeld
            ) {
                throw Fit3FormatException(
                    "${original.basename}: widget $ordinal changed beyond its index",
                )
            }
        }
        val imageOffset = replacement.u32(0x14).checkedInt("image offset")
        val originalImageOffset = original.data.u32(0x14).checkedInt("image offset")
        if (
            !replacement.copyOfRange(imageOffset, replacement.size)
                .contentEquals(original.data.copyOfRange(originalImageOffset, original.data.size))
        ) {
            throw Fit3FormatException("${original.basename}: image section must not change")
        }
    }

    private fun validateStructuralEntry(
        original: ContainerEntry,
        replacement: ByteArray,
        expectedWidgets: Int,
    ) {
        val parsed = original.copy(size = replacement.size, checksum = 0, data = replacement)
        val widgets = FaceRecordParser.scanWidgets(parsed)
        FaceRecordParser.scanImages(parsed)
        if (widgets.size != expectedWidgets ||
            widgets.map { it.globalIndex } != widgets.indices.toList()
        ) {
            throw Fit3FormatException("structural widget edit did not preserve invariants")
        }
    }

    private fun selectedEntries(
        source: Fit3Container,
        names: List<String>,
    ): List<ContainerEntry> {
        if (names.isEmpty() || names.distinct().size != names.size) {
            throw Fit3FormatException("style entry names must be nonempty and unique")
        }
        return names.map(source::entryByBasename)
    }

    internal fun requireValidAndTight(source: Fit3Container) {
        val report = source.validate()
        if (!report.isValid) {
            throw Fit3FormatException(
                "refusing to structurally edit invalid container: " +
                    report.errors.joinToString { it.code },
            )
        }
        var cursor = source.bodyOffset
        source.entries.forEach { entry ->
            if (entry.offset != cursor) {
                throw Fit3FormatException(
                    "relocation requires a tightly packed body; " +
                        "${entry.basename} starts at ${entry.offset}, expected $cursor",
                )
            }
            cursor = entry.end
        }
        if (cursor != source.fileSize) {
            throw Fit3FormatException("relocation refuses trailing unreferenced bytes")
        }
    }

    internal fun rebuild(
        source: Fit3Container,
        replacements: Map<Int, ByteArray>,
        addedResources: Map<String, ByteArray> = emptyMap(),
        /** Entries to leave out, by index. Only [keepFirstStyles] removes any. */
        removedEntries: Set<Int> = emptySet(),
    ): StructuralEdit {
        val original = source.toByteArray()
        val header = original.copyOfRange(0, CONTAINER_HEADER_SIZE)
        val kept = source.entries.filter { it.index !in removedEntries }
        val directory = kept.map { it.rawRecord.copyOf() }.toMutableList()
        val body = ByteArrayOutputStream()
        var cursor = CONTAINER_HEADER_SIZE + (kept.size + addedResources.size) * DIRECTORY_ENTRY_SIZE
        kept.forEachIndexed { position, entry ->
            val payload = replacements[entry.index] ?: entry.data
            directory[position].putU32(0x40, cursor)
            directory[position].putU32(0x44, payload.size)
            directory[position].putU16(0x48, Crc16.ccittFalse(payload))
            body.write(payload)
            cursor += payload.size
        }
        val prefix = source.entries.first().path.substringBeforeLast('/') + "/"
        addedResources.forEach { (name, payload) ->
            if (!Regex("font_[A-Za-z0-9_]+\\.bin").matches(name) ||
                source.entries.any { it.basename == name }) {
                throw Fit3FormatException("invalid or duplicate added resource $name")
            }
            val path = (prefix + name).toByteArray(Charsets.UTF_8)
            if (path.size >= 64) throw Fit3FormatException("resource path is too long")
            directory += ByteArray(DIRECTORY_ENTRY_SIZE).also {
                path.copyInto(it)
                it.putU32(0x40, cursor)
                it.putU32(0x44, payload.size)
                it.putU16(0x48, Crc16.ccittFalse(payload))
            }
            body.write(payload)
            cursor += payload.size
        }
        header.putU32(0x0C, directory.size)
        header.putU32(0x08, cursor - CONTAINER_HEADER_SIZE)
        val output = ByteArrayOutputStream()
        output.write(header)
        directory.forEach(output::write)
        output.write(body.toByteArray())
        val assembled = output.toByteArray()
        assembled.putU16(
            0x10,
            Crc16.ccittFalse(assembled, CONTAINER_HEADER_SIZE, assembled.size),
        )
        val parsed = Fit3Container.parse(assembled)
        val report = parsed.validate()
        if (!report.isValid) {
            throw Fit3FormatException(
                "structural edit failed validation: ${report.errors.joinToString { it.code }}",
            )
        }
        // Every structural edit funnels through here, so the size ceiling is checked once,
        // here, for all of them. Only growth is refused: a container that is already over
        // the limit must still be shrinkable back under it.
        if (assembled.size > WATCH_CONTAINER_BYTE_CEILING && assembled.size > original.size) {
            throw Fit3FormatException(
                "the edit would make this container ${assembled.size} bytes, over the " +
                    "$WATCH_CONTAINER_BYTE_CEILING the watch accepts — it would install and " +
                    "the watch would keep showing the old face",
            )
        }
        val changed = replacements.entries.sumOf { (index, bytes) ->
            val before = source.entries[index].data
            // No boxed index list: a style can be hundreds of kilobytes, and the old
            // take(...).count allocated one Integer per byte during every structural edit.
            var count = kotlin.math.abs(before.size - bytes.size)
            for (offset in 0 until minOf(before.size, bytes.size)) {
                if (before[offset] != bytes[offset]) count++
            }
            count
        }
        return StructuralEdit(
            container = parsed,
            changedPayloadBytes = changed + addedResources.values.sumOf { it.size } +
                removedEntries.sumOf { source.entries[it].data.size },
            // The variants actually rewritten, not the ones the edit was offered:
            // a widget missing from a sibling style leaves that style untouched.
            changedStyles = (replacements.keys + removedEntries).map { source.entries[it].basename },
            sizeDelta = assembled.size - original.size,
        )
    }

    private fun replaceRange(
        source: ByteArray,
        start: Int,
        end: Int,
        replacement: ByteArray,
    ): ByteArray {
        val output = ByteArray(source.size - (end - start) + replacement.size)
        source.copyInto(output, 0, 0, start)
        replacement.copyInto(output, start)
        source.copyInto(output, start + replacement.size, end, source.size)
        return output
    }
}
