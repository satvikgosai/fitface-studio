package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.SPRITE_RESIZE_CEILING
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.spriteResizeLimit
import java.io.ByteArrayOutputStream

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
)

object StructuralEditor {
    private const val StaticWidgetType = 1
    private const val SpriteWidgetType = 3

    /**
     * Outer bound on a resized Sprite frame, before the per-side
     * [spriteResizeLimit] is applied. The panel is 256×402, so a frame larger than this
     * cannot be a glyph however large the face shipped it.
     */
    private const val MAX_SPRITE_EXTENT = 512

    /** 36 + one type-word, which is what all 348 corpus background Statics measure. */
    private const val BACKGROUND_STATIC_SIZE = 40

    /** Every raster in the corpus ends with these four zero bytes. */
    private const val OPAQUE_TRAILER_BYTES = 4

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
     * This adds an image record, which [resizeSprite] must never do — the watch ignores a
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
     * Resizes every frame a Sprite references.
     *
     * Frames are shared *records*, so the edit closes over every widget reaching into
     * the same glyph pool and moves the whole pool together — see [sharedFrameClosure].
     * Resizing only the frames the selected sprite names left its neighbour drawing
     * three small glyphs and seven large ones. Records are rewritten in place: the
     * frame count never changes, because a container with more image records than it
     * shipped with is one the watch installs and then ignores.
     *
     * [pristine] is the unedited container, and when it is supplied every frame is
     * resampled from *its* pixels rather than from [source]'s. Resizing is lossy —
     * shrinking throws pixels away — so chaining resample onto resample destroys the
     * artwork: shrinking a 114×136 sprite to 56×69 and then pulling it back up
     * returned a picture carrying only the detail that survived the smaller one.
     *
     * It also sets the upper bound. A sprite may be taken back to the extent its face
     * shipped — [spriteResizeLimit] — because resampling to the original dimensions
     * restores the original record lengths and with them the container's shipped size.
     * Growing *past* that is what [SPRITE_RESIZE_CEILING] bounds, and what
     * [WATCH_CONTAINER_BYTE_CEILING] refuses when the frames get big enough to matter.
     * Without [pristine] there is no shipped extent to read, so the current one stands in.
     */
    fun resizeSprite(
        source: Fit3Container,
        entryBasenames: List<String>,
        sequenceId: Int,
        width: Int,
        height: Int,
        pristine: Fit3Container? = null,
    ): StructuralEdit {
        requireValidAndTight(source)
        // The precise per-side bound needs the frames' shipped extent, which only
        // `resizeSpriteEntry` can resolve; this is the sanity bound around it.
        if (width !in 1..MAX_SPRITE_EXTENT || height !in 1..MAX_SPRITE_EXTENT) {
            throw Fit3FormatException(
                "Sprite dimensions must each be between 1 and $MAX_SPRITE_EXTENT",
            )
        }
        val replacements = linkedMapOf<Int, ByteArray>()
        selectedEntries(source, entryBasenames).forEach { entry ->
            replacements[entry.index] = resizeSpriteEntry(
                entry = entry,
                pristineEntry = pristine?.entries?.singleOrNull { it.basename == entry.basename },
                sequenceId = sequenceId,
                width = width,
                height = height,
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
        val output = ByteArrayOutputStream()
        output.write(entry.data, 0, STYLE_HEADER_SIZE)
        output.write(backgroundStaticRecord(newRasterOffset))
        widgets.forEach { widget ->
            val record = entry.data.copyOfRange(
                widget.recordOffset,
                widget.recordOffset + widget.recordSize,
            )
            // The new Static takes index 0, so everything already in the table moves up
            // one. No field holds another widget's index — see `requireSurvivorsUnchanged`
            // — so this is the whole of the renumbering.
            record.putU32(
                0x0C,
                ((widget.globalIndex + 1).toLong() shl 16) or (record.u32(0x0C) and 0xFFFF),
            )
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
        expected.forEach { widget ->
            val after = widgets[widget.ordinal + 1]
            if (widget.widgetType != after.widgetType ||
                widget.sequenceId != after.sequenceId ||
                widget.x != after.x ||
                widget.y != after.y ||
                widget.width != after.width ||
                widget.height != after.height ||
                widget.recordSize != after.recordSize
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
            if (!was.contentEquals(now)) {
                throw Fit3FormatException(
                    "${original.basename}: widget ${widget.ordinal} was rewritten",
                )
            }
        }
    }

    private fun resizeSpriteEntry(
        entry: ContainerEntry,
        pristineEntry: ContainerEntry?,
        sequenceId: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val images = FaceRecordParser.scanImages(entry)
        val widgets = FaceRecordParser.scanWidgets(entry)
        val target = widgets.filter {
            it.widgetType == SpriteWidgetType && it.sequenceId == sequenceId
        }.singleOrNull() ?: throw Fit3FormatException(
            "${entry.basename}: expected exactly one Sprite widget with sequence $sequenceId",
        )
        val sectionStart = images.firstOrNull()?.recordOffset
            ?: throw Fit3FormatException("${entry.basename}: style contains no images")
        val relativeImages = images.associateBy { (it.recordOffset - sectionStart).toLong() }
        if (target.words.isEmpty() || target.words.any { it !in relativeImages }) {
            throw Fit3FormatException("${entry.basename}: Sprite contains a non-image word")
        }
        // Every frame the edit has to touch, not just the ones this sprite names.
        //
        // A face keeps one glyph pool and points several widgets into it — on 00022 the
        // hour's tens digit addresses frames 2–4 and its units digit 2–11 — so resizing
        // only the named frames left the neighbour drawing three small glyphs and seven
        // large ones, its box still reporting the largest. The frames are shared
        // records; there is no resizing one widget's copy, because there is only one
        // copy. So the set is closed over every widget that reaches into it and the
        // whole pool moves together.
        val targetIndices = sharedFrameClosure(target, widgets, relativeImages)
        val backgroundIndex = FaceRecordParser.backgroundImage(entry)?.index
        if (backgroundIndex != null && backgroundIndex in targetIndices) {
            throw Fit3FormatException("Sprite resize refuses the full-panel background raster")
        }
        // Closing over the pool can only pull in other Sprites in the whole corpus, and
        // a Static or a Hand sharing a digit frame would mean the pool is not what this
        // edit thinks it is.
        widgets.filter { other ->
            other.widgetType != SpriteWidgetType &&
                FaceRecordParser.referencedImages(other, relativeImages)
                    .any { it.index in targetIndices }
        }.forEach {
            throw Fit3FormatException(
                "${entry.basename}: widget ${it.ordinal} shares a frame with the Sprite " +
                    "but is type ${it.widgetType}",
            )
        }
        val selected = targetIndices.sorted().map(images::get)
        val signatures = selected.map {
            listOf(it.width, it.height, it.format, it.reserved, it.opaqueTrailerSize)
        }.toSet()
        if (signatures.size != 1) {
            throw Fit3FormatException("${entry.basename}: Sprite frames do not share one format")
        }
        val signature = signatures.single()
        if (signature[2] != IMAGE_RGB565_ALPHA || signature[3] != 0 || signature[4] != 4) {
            throw Fit3FormatException(
                "${entry.basename}: Sprite requires RGB565+A with the proven trailer schema",
            )
        }
        if (signature[0] == width && signature[1] == height) {
            throw Fit3FormatException("Sprite relocation requires a dimension change")
        }

        // The unedited record behind each frame, resolved through the widget that names
        // it rather than by image index.
        //
        // Index matching held only while nothing ever changed the record count, and
        // adding a background breaks both halves of that: the count differs by one, so
        // the pristine frames were dropped entirely and every resize resampled the
        // *previous* resize — Smaller, Larger, Smaller came back visibly softer — and
        // even with the count patched up, index i would name the raster before it.
        val pristineOrigins = pristineFrameOrigins(entry, pristineEntry, widgets, relativeImages)

        // The frames' shipped extent, and with it the largest this resize may go: a sprite
        // must be able to come back to what the face shipped — `00022`'s digits are 114×136
        // — and growing past that is what SPRITE_RESIZE_CEILING bounds. The whole pool
        // shares one signature (asserted above), so this is a single pair of numbers.
        val shippedWidth = targetIndices.mapNotNull { pristineOrigins[it]?.width }.maxOrNull()
            ?: signature[0]
        val shippedHeight = targetIndices.mapNotNull { pristineOrigins[it]?.height }.maxOrNull()
            ?: signature[1]
        if (width > spriteResizeLimit(shippedWidth) || height > spriteResizeLimit(shippedHeight)) {
            throw Fit3FormatException(
                "${entry.basename}: a Sprite that shipped at ${shippedWidth}x$shippedHeight " +
                    "may be resized up to ${spriteResizeLimit(shippedWidth)}x" +
                    "${spriteResizeLimit(shippedHeight)}, not ${width}x$height",
            )
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
                    width = width,
                    height = height,
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
        val section = newSection.toByteArray()
        val replacement = prefix + section
        replacement.putU32(0x0C, section.size)
        val parsed = validateRelocatedEntry(entry, replacement)
        val parsedImages = FaceRecordParser.scanImages(parsed)
        // The record count is exactly what the watch refuses to see change, so it is
        // asserted rather than assumed.
        if (parsedImages.size != images.size) {
            throw Fit3FormatException("Sprite resize changed the frame count")
        }
        targetIndices.forEach { index ->
            if (parsedImages[index].width != width || parsedImages[index].height != height) {
                throw Fit3FormatException("resized Sprite dimensions did not persist")
            }
        }
        // Every frame outside the pool keeps the size it had.
        images.filterNot { it.index in targetIndices }.forEach { before ->
            val after = parsedImages[before.index]
            if (after.width != before.width || after.height != before.height) {
                throw Fit3FormatException("Sprite resize disturbed a frame outside the pool")
            }
        }
        val resizedTarget = FaceRecordParser.scanWidgets(parsed).single {
            it.widgetType == SpriteWidgetType && it.sequenceId == sequenceId
        }
        val oldIds = target.words.map { relativeImages.getValue(it).index }
        val newByOffset = parsedImages.associate {
            (it.recordOffset - parsedImages.first().recordOffset).toLong() to it.index
        }
        val newIds = resizedTarget.words.map(newByOffset::getValue)
        if (oldIds != newIds) {
            throw Fit3FormatException("Sprite duplicate-frame mapping changed")
        }
        return replacement
    }

    /**
     * Every frame the resize has to rewrite: the sprite's own, plus every frame reached
     * by a widget that shares one of them, closed over until nothing new appears.
     *
     * The frames are shared *records*, so there is no such thing as resizing one
     * widget's copy — a face keeps a single glyph pool and points the hour and minute
     * digits into it. Rewriting only the frames the selected sprite happens to name
     * left its neighbour drawing two sizes at once.
     */
    private fun sharedFrameClosure(
        target: WidgetRecord,
        widgets: List<WidgetRecord>,
        relativeImages: Map<Long, ImageRecord>,
    ): Set<Int> {
        val framesOf = widgets.associateWith { widget ->
            FaceRecordParser.referencedImages(widget, relativeImages).map(ImageRecord::index)
        }
        val closure = framesOf.getValue(target).toMutableSet()
        while (true) {
            val grown = framesOf.entries
                .filter { (_, frames) -> frames.any { it in closure } }
                .flatMap { (_, frames) -> frames }
            if (!closure.addAll(grown)) return closure
        }
    }


    /**
     * Current image index → the record it came from in the unedited container.
     *
     * Resolved through widget identity, because that is the only thing a structural edit
     * preserves: for each pointer-bearing widget, the pristine container's widget with
     * the same type and sequence id is found, and their pointer lists are paired by
     * position. Face `00022`'s hour digits name frames 2–4 and 2–11 in both containers
     * whatever the records were renumbered to, so this survives an inserted background,
     * a removal, and a duplicate.
     *
     * A frame two widgets disagree about is dropped rather than guessed, which falls back
     * to resampling the current pixels for that frame alone.
     */
    private fun pristineFrameOrigins(
        entry: ContainerEntry,
        pristineEntry: ContainerEntry?,
        widgets: List<WidgetRecord>,
        relativeImages: Map<Long, ImageRecord>,
    ): Map<Int, ImageRecord> {
        if (pristineEntry == null) return emptyMap()
        val pristineImages = FaceRecordParser.scanImages(pristineEntry)
        if (pristineImages.isEmpty()) return emptyMap()
        val pristineStart = pristineImages.first().recordOffset
        val pristineRelative = pristineImages.associateBy {
            (it.recordOffset - pristineStart).toLong()
        }
        val pristineWidgets = FaceRecordParser.scanWidgets(pristineEntry)
        val origins = mutableMapOf<Int, ImageRecord>()
        val ambiguous = mutableSetOf<Int>()
        widgets.filter { it.widgetType in FaceRecordParser.POINTER_BEARING_TYPES }
            .forEach { widget ->
                val match = pristineWidgets.singleOrNull {
                    it.widgetType == widget.widgetType && it.sequenceId == widget.sequenceId
                } ?: return@forEach
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
        val resized = nearestRgb565Alpha(
            data.copyOfRange(from.pixelOffset, from.pixelOffset + from.pixelDataSize),
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
        header.putU32(8, resized.size + trailer.size)
        out.write(header)
        out.write(resized)
        out.write(trailer)
    }

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
            replacement.write(raw)
        }
        replacement.write(entry.data, oldImageOffset, entry.data.size - oldImageOffset)
        val removedRecord = entry.data.copyOfRange(
            target.recordOffset,
            target.recordOffset + target.recordSize,
        )
        val output = replacement.toByteArray().also {
            it.putU32(0x04, widgets.size - 1)
            it.putU32(0x08, oldImageOffset - STYLE_HEADER_SIZE - target.recordSize)
            it.putU32(0x14, oldImageOffset - target.recordSize)
            validateStructuralEntry(entry, it, widgets.size - 1)
            requireSurvivorsUnchanged(entry, it, survivors)
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
        if (widgets.isEmpty() || images.isEmpty()) {
            throw Fit3FormatException("${entry.basename}: style needs widgets and images")
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
     * from its renumbered `global_index`, and the image section must not move at all.
     *
     * This replaces an older pre-check that refused the edit whenever any opaque
     * widget word happened to equal an index in the renumbered range. That heuristic
     * had no support in the format: `+0x08`, `+0x10` and `+0x14` are zero in every
     * one of the 4,038 widget records across the live 100-face catalogue, `+0x20` is
     * a Hand pivot / Sprite frame count / Pair anchor mode, and the type-specific
     * words hold image byte offsets, colours, or `(glyph_group << 16) | sequence_id`.
     * Nothing references another widget by global index — cross-record references go
     * through `sequence_id`, which removal never rewrites. Meanwhile the heuristic
     * blocked 68% of removals and left 18 of 99 faces with no removable widget at all,
     * which is what "sometimes removing a widget does nothing" looked like.
     */
    private fun requireSurvivorsUnchanged(
        original: ContainerEntry,
        replacement: ByteArray,
        expected: List<WidgetRecord>,
    ) {
        val parsed = original.copy(size = replacement.size, checksum = 0, data = replacement)
        val actual = FaceRecordParser.scanWidgets(parsed)
        // Appends put the new record last, so the survivors are always the prefix.
        if (actual.size < expected.size) {
            throw Fit3FormatException("${original.basename}: widget records went missing")
        }
        expected.forEachIndexed { ordinal, before ->
            val after = actual[ordinal]
            if (before.widgetType != after.widgetType ||
                before.sequenceId != after.sequenceId ||
                before.x != after.x ||
                before.y != after.y ||
                before.width != after.width ||
                before.height != after.height ||
                before.recordSize != after.recordSize ||
                before.unknown20 != after.unknown20 ||
                before.words != after.words
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

    private fun nearestRgb565Alpha(
        source: ByteArray,
        oldWidth: Int,
        oldHeight: Int,
        newWidth: Int,
        newHeight: Int,
    ): ByteArray {
        if (source.size != oldWidth * oldHeight * 3) {
            throw Fit3FormatException("RGB565+A frame payload does not match dimensions")
        }
        val output = ByteArray(newWidth * newHeight * 3)
        repeat(newHeight) { y ->
            val sourceY = minOf(oldHeight - 1, y * oldHeight / newHeight)
            repeat(newWidth) { x ->
                val sourceX = minOf(oldWidth - 1, x * oldWidth / newWidth)
                val oldOffset = (sourceY * oldWidth + sourceX) * 3
                val newOffset = (y * newWidth + x) * 3
                source.copyInto(output, newOffset, oldOffset, oldOffset + 3)
            }
        }
        return output
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

    private fun requireValidAndTight(source: Fit3Container) {
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

    private fun rebuild(
        source: Fit3Container,
        replacements: Map<Int, ByteArray>,
    ): StructuralEdit {
        val original = source.toByteArray()
        val header = original.copyOfRange(0, CONTAINER_HEADER_SIZE)
        val directory = source.entries.map { it.rawRecord.copyOf() }
        val body = ByteArrayOutputStream()
        var cursor = source.bodyOffset
        source.entries.forEach { entry ->
            val payload = replacements[entry.index] ?: entry.data
            directory[entry.index].putU32(0x40, cursor)
            directory[entry.index].putU32(0x44, payload.size)
            directory[entry.index].putU16(0x48, Crc16.ccittFalse(payload))
            body.write(payload)
            cursor += payload.size
        }
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
            before.indices.take(minOf(before.size, bytes.size)).count {
                before[it] != bytes[it]
            } + kotlin.math.abs(before.size - bytes.size)
        }
        return StructuralEdit(
            container = parsed,
            changedPayloadBytes = changed,
            // The variants actually rewritten, not the ones the edit was offered:
            // a widget missing from a sibling style leaves that style untouched.
            changedStyles = replacements.keys.map { source.entries[it].basename },
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
