package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.MovingBackground
import dev.fitface.studio.core.model.PixelBounds
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import java.io.ByteArrayOutputStream

/** The edit that added an animation — a moving background or picture — and the index its record took. */
class AnimationEdit(val edit: StructuralEdit, val globalIndex: Int)

/**
 * A moving background: one type-4 Animation drawing its frames under every widget.
 *
 * **Its size.** Frames are stored at the rectangle the picture covers, at that rectangle's
 * place on the panel (`+0x18/+0x1A`, as a moving picture's are); the rest of the panel is
 * black, the still background deleted (or painted black) when one is added. One that covers the whole panel
 * is stored exactly as before, at 0,0 with nothing else set, and is a background layer by its
 * size. One that covers less carries [ANIMATION_BACKGROUND_TAG] in `+0x1C..+0x1F`, bytes the
 * firmware never reads, because its size no longer says what it is.
 *
 * **What the watch showed (T1, 2026-10-14).** An Animation appended to a vendor face plays,
 * at `+0x26` milliseconds a table entry (T1a); it loops for ever whatever `+0x25` says (T1b);
 * twelve full-panel frames at 100 ms play smoothly (T1c); Indexed8 frames draw correctly,
 * palette alpha included (T1d); on the always-on display it plays about three times slower
 * (T1e). No catalogue face carries one, so the record is the firmware reading in
 * `docs/bin-format.md` §7: type 4, position at `+0x18`, mode 0, the table's length at `+0x24`,
 * repeat at `+0x25`, period at `+0x26`, then one pointer an entry from `+0x28`. A frame shown
 * for several periods is several entries naming one raster.
 *
 * **Where it goes.** The frames are appended to the end of the style's image section, so no
 * existing offset moves — the rule [StructuralEditor.addBackgrounds] learned on face `00019`.
 * The record goes directly above the style's last full-panel layer, so a still background
 * stays underneath it, or at index 0 on a style with none; every later record moves up one
 * and every alignment reference that named one of them is renumbered with it.
 *
 * **What is not "the background".** [FaceRecordParser.backgroundImage] skips rasters only an
 * Animation draws, so replacing, tinting or importing a background never edits one frame of
 * this, and a bare style still offers a still background, which goes underneath.
 */
object MovingBackgroundEditor {
    private const val HEAD = 0x28
    /** Finite and the largest the byte holds; the watch loops regardless (T1b). */
    private const val REPEAT = 0xFF

    /**
     * An Indexed8 image record of one frame: the 12-byte header, a 256-colour palette from
     * median-cut quantisation, a byte a pixel and the four zero trailer bytes. Translucent
     * pixels are laid over black first, because a background has nothing under it.
     */
    fun frameRecord(width: Int, height: Int, argb: IntArray): ByteArray {
        require(width > 0 && height > 0 && argb.size == width * height) { "frame pixels must match its size" }
        val opaque = IntArray(argb.size) { index ->
            val color = argb[index]
            val alpha = color ushr 24
            if (alpha == 0xFF) color else {
                fun channel(shift: Int) = (color ushr shift and 0xFF) * alpha / 255
                (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
        }
        val payload = IndexedImage.quantize(opaque)
        val record = ByteArray(IMAGE_HEADER_SIZE + payload.size + OPAQUE_TRAILER_BYTES)
        record.putU16(0, width)
        record.putU16(2, height)
        record.putU16(4, IMAGE_INDEXED8)
        record.putU32(8, (payload.size + OPAQUE_TRAILER_BYTES).toLong())
        payload.copyInto(record, IMAGE_HEADER_SIZE)
        return record
    }

    /**
     * [frames], each [bounds]' size (the panel's when null), played as [table] (one frame index
     * an entry) at [periodMs] an entry, added as [variant]'s moving background drawing at
     * [bounds].
     *
     * Refuses a style that already has one, bounds off the panel, a frame of the wrong size, a
     * table naming a frame that does not exist or leaving one out, and a container past the
     * watch's ceiling.
     */
    fun insert(
        target: Fit3Container,
        variant: String,
        frames: List<IntArray>,
        table: List<Int>,
        periodMs: Int,
        bounds: PixelBounds? = null,
    ): AnimationEdit {
        StructuralEditor.requireValidAndTight(target)
        val entry = StyleWidgetMatch.requireVariantEntry(target.entryByBasename(variant))
        val panel = FaceRecordParser.panelSize(entry)
        if (panel.width <= 0 || panel.height <= 0) throw Fit3FormatException("$variant declares no panel size.")
        val area = bounds ?: PixelBounds(0, 0, panel.width, panel.height)
        if (area.width <= 0 || area.height <= 0 || area.x < 0 || area.y < 0 ||
            area.x + area.width > panel.width || area.y + area.height > panel.height) {
            throw Fit3FormatException("A moving background must lie on the panel.")
        }
        val partial = area != PixelBounds(0, 0, panel.width, panel.height)
        require(frames.isNotEmpty() && frames.all { it.size == area.width * area.height }) {
            "every frame must be ${area.width}x${area.height}"
        }
        require(table.size in 1..255 && table.all { it in frames.indices } && table.toSet().size == frames.size) {
            "the frame table must name every frame and nothing else"
        }
        require(periodMs in 1..0xFFFF) { "the period must fit 16 bits" }
        if (read(entry) != null) throw Fit3FormatException("This style already has a moving background. Remove it first.")

        val images = FaceRecordParser.scanImages(entry)
        val widgets = FaceRecordParser.scanWidgets(entry)
        StructuralEditor.requireContiguousWidgets(entry, widgets, images)
        if (widgets.size + 1 >= 0xFFFF) throw Fit3FormatException("$variant: widget index space is exhausted")
        val header = StyleHeader.parse(entry)
        if (header.computedImageOffset != header.storedImageOffset ||
            header.storedImageOffset + header.imageBytes != entry.data.size) {
            throw Fit3FormatException("The target style is not tightly packed.")
        }
        val at = FaceRecordParser.widgetGuides(entry).filter { it.placement == WidgetPlacement.BACKGROUND }
            .maxOfOrNull { it.globalIndex }?.plus(1) ?: 0
        val rasters = frames.map { frameRecord(area.width, area.height, it) }
        val offsets = rasters.runningFold(header.imageBytes.toLong()) { offset, raster -> offset + raster.size }
        val record = ByteArray(HEAD + table.size * 4).also {
            it.putU32(0x00, WIDGET_ANIMATION.toLong())
            it.putU32(0x0C, (at.toLong() shl 16) or (HEAD + table.size * 4).toLong())
            if (partial) {
                it.putU16(0x18, area.x)
                it.putU16(0x1A, area.y)
                it.putU32(ANIMATION_BACKGROUND_TAG_OFFSET, ANIMATION_BACKGROUND_TAG)
            }
            it[0x24] = table.size.toByte()
            it[0x25] = REPEAT.toByte()
            it.putU16(0x26, periodMs)
            table.forEachIndexed { entryIndex, frame -> it.putU32(HEAD + entryIndex * 4, offsets[frame]) }
        }

        val sectionStart = images.first().recordOffset
        val relative = images.associateBy { (it.recordOffset - sectionStart).toLong() }
        val before = widgets.associate { it.ordinal to StructuralEditor.pointerSignatures(entry, it, relative) }
        val existing = widgets.mapTo(mutableSetOf(), WidgetRecord::globalIndex)
        fun moved(index: Int) = if (index >= at && index in existing) index + 1 else index
        val records = ByteArrayOutputStream()
        widgets.forEach { widget ->
            if (widget.globalIndex == at) records.write(record)
            val raw = entry.data.copyOfRange(widget.recordOffset, widget.recordOffset + widget.recordSize)
            if (widget.globalIndex >= at) {
                raw.putU32(0x0C, ((widget.globalIndex + 1).toLong() shl 16) or (raw.u32(0x0C) and 0xFFFF))
            }
            StructuralEditor.remapAlignmentTarget(raw, widget) { old -> moved(old).takeIf { old in existing } }
            records.write(raw)
        }
        if (at == widgets.size) records.write(record)
        val oldImages = entry.data.copyOfRange(header.storedImageOffset, entry.data.size)
        val newImages = rasters.fold(oldImages) { all, raster -> all + raster }
        val data = WidgetImporter.styleBytes(entry.data, records.toByteArray(), widgets.size + 1, newImages, header.fontBindingCount)
        requireSound(entry, entry.copy(data = data, size = data.size), widgets, before, at, images.size, table.size, area)
        return AnimationEdit(StructuralEditor.rebuild(target, mapOf(entry.index to data)), at)
    }

    /**
     * Fails the edit unless every widget that was there still draws exactly the same bytes at
     * exactly the same place, the new record names the new frames in table order, and the
     * editor sees it as a full-panel layer.
     */
    private fun requireSound(
        before: ContainerEntry,
        after: ContainerEntry,
        widgets: List<WidgetRecord>,
        signatures: Map<Int, List<String>>,
        at: Int,
        shippedImages: Int,
        entries: Int,
        area: PixelBounds,
    ) {
        val records = FaceRecordParser.scanWidgets(after)
        check(records.size == widgets.size + 1 && records.map { it.globalIndex } == records.indices.toList()) {
            "the widget table did not grow by one"
        }
        val images = FaceRecordParser.scanImages(after)
        val relative = images.associateBy { (it.recordOffset - images.first().recordOffset).toLong() }
        val oldSection = before.data.copyOfRange(StyleHeader.parse(before).storedImageOffset, before.data.size)
        val start = StyleHeader.parse(after).storedImageOffset
        check(oldSection.contentEquals(after.data.copyOfRange(start, start + oldSection.size))) { "existing artwork moved" }
        val old = FaceRecordParser.placements(before)
        val now = FaceRecordParser.placements(after)
        val existing = widgets.mapTo(mutableSetOf(), WidgetRecord::globalIndex)
        widgets.forEach { widget ->
            val ordinal = if (widget.ordinal >= at) widget.ordinal + 1 else widget.ordinal
            check(StructuralEditor.pointerSignatures(after, records[ordinal], relative) == signatures[widget.ordinal]) {
                "widget ${widget.globalIndex} would draw different artwork"
            }
            val placed = old.getValue(widget.ordinal)
            val shifted = placed.copy(targetGlobalIndex = placed.targetGlobalIndex?.let {
                if (it >= at && it in existing) it + 1 else it
            })
            check(shifted == now.getValue(ordinal)) { "widget ${widget.globalIndex} would move" }
        }
        val added = records[at]
        check(added.widgetType == WIDGET_ANIMATION && added.frameCount == entries) { "the animation record is malformed" }
        val drawn = FaceRecordParser.imagePointerFields(added, relative).map { it.image.index }
        check(drawn.size == entries && drawn.all { it >= shippedImages } && drawn.toSet().size == images.size - shippedImages) {
            "the animation does not name exactly its own frames"
        }
        val guide = FaceRecordParser.widgetGuides(after)[at]
        check(guide.placement == WidgetPlacement.BACKGROUND) { "the animation is not a background layer" }
        check(guide.drawLeft == area.x && guide.drawTop == area.y && guide.width == area.width && guide.height == area.height) {
            "the moving background would not draw where it was placed"
        }
    }

    /** [entry]'s moving background — its Animation under the widgets — or null when it has none. */
    fun read(entry: ContainerEntry): MovingBackground? {
        val records = FaceRecordParser.scanWidgets(entry)
        if (records.none { it.widgetType == WIDGET_ANIMATION }) return null
        val guides = FaceRecordParser.widgetGuides(entry).associateBy { it.globalIndex }
        val record = records.firstOrNull {
            it.widgetType == WIDGET_ANIMATION && guides[it.globalIndex]?.placement == WidgetPlacement.BACKGROUND
        } ?: return null
        val images = FaceRecordParser.imagesByRelativeOffset(entry)
        val frames = runCatching { FaceRecordParser.imagePointerFields(record, images) }.getOrNull() ?: return null
        val guide = guides.getValue(record.globalIndex)
        val panel = FaceRecordParser.panelSize(entry)
        val bounds = PixelBounds(guide.drawLeft, guide.drawTop, guide.width, guide.height)
        return MovingBackground(
            globalIndex = record.globalIndex,
            frames = frames.map { it.image.index }.toSet().size,
            entries = frames.size,
            periodMs = entry.data.u16(record.recordOffset + 0x26),
            bounds = bounds.takeIf { it != PixelBounds(0, 0, panel.width, panel.height) },
        )
    }
}
