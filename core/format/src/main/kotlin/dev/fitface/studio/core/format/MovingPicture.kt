package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop

/**
 * A moving picture: a GIF, or a clip of a video, added as a widget — one type-4 Animation
 * appended on top of the style, drawing frames of its own.
 *
 * The shape of [PictureWidget]'s Static with an Animation's frame table where the Static has
 * one pointer. The record goes at the end of the table, so it draws on top and nothing is
 * renumbered; its frames go after every existing image, so nothing already in the style
 * moves. An Animation has no alignment fields: its position is panel coordinates at
 * `+0x18/+0x1A`, where the T1 probes put theirs (a 64 px animation at 96, 200 played there,
 * 2026-10-14). Timing is [MovingBackgroundEditor]'s: a period at `+0x26` and one pointer an
 * entry, a frame held for several periods named by several entries.
 *
 * Frames are Indexed8. When any pixel is see-through, palette entry 0 is clear and the other
 * 255 are median cut from the rest: T1d showed a clear entry drawing the face through it.
 * Partial palette alpha has not been on a watch, so alpha is cut at half — a GIF's own
 * transparency is all or nothing anyway, and only edges softened by resampling lose anything.
 */
object MovingPicture {
    private const val HEAD = 0x28
    /** Finite and the largest the byte holds; the watch loops regardless (T1b). */
    private const val REPEAT = 0xFF

    /**
     * An Indexed8 image record of one frame: the 12-byte header, the palette (entry 0 clear
     * when anything is see-through), a byte a pixel and the four zero trailer bytes.
     */
    fun frameRecord(width: Int, height: Int, argb: IntArray): ByteArray {
        require(width > 0 && height > 0 && argb.size == width * height) { "frame pixels must match its size" }
        val payload = IndexedImage.quantizeWithClear(argb)
        val record = ByteArray(IMAGE_HEADER_SIZE + payload.size + OPAQUE_TRAILER_BYTES)
        record.putU16(0, width)
        record.putU16(2, height)
        record.putU16(4, IMAGE_INDEXED8)
        record.putU32(8, (payload.size + OPAQUE_TRAILER_BYTES).toLong())
        payload.copyInto(record, IMAGE_HEADER_SIZE)
        return record
    }

    /**
     * [frames], each [width] × [height], played as [table] (one frame index an entry) at
     * [periodMs] an entry, added on top of [variant] with its top-left at panel coordinates
     * [x], [y].
     *
     * Refuses a picture that would cover the whole panel both ways, which the editor would
     * take for a background layer, one larger than the panel, a table naming a frame that
     * does not exist or leaving one out, and a container past the watch's ceiling.
     */
    fun insert(
        target: Fit3Container,
        variant: String,
        width: Int,
        height: Int,
        frames: List<IntArray>,
        table: List<Int>,
        periodMs: Int,
        x: Int,
        y: Int,
    ): AnimationEdit {
        StructuralEditor.requireValidAndTight(target)
        val entry = StyleWidgetMatch.requireVariantEntry(target.entryByBasename(variant))
        val panel = FaceRecordParser.panelSize(entry)
        if (width !in 1..panel.width || height !in 1..panel.height || (width >= panel.width && height >= panel.height)) {
            throw Fit3FormatException("A moving picture must fit on the face without covering all of it.")
        }
        require(frames.isNotEmpty() && frames.all { it.size == width * height }) { "every frame must be $width x $height" }
        require(table.size in 1..255 && table.all { it in frames.indices } && table.toSet().size == frames.size) {
            "the frame table must name every frame and nothing else"
        }
        require(periodMs in 1..0xFFFF) { "the period must fit 16 bits" }
        if (x !in Short.MIN_VALUE..Short.MAX_VALUE || y !in Short.MIN_VALUE..Short.MAX_VALUE) {
            throw Fit3FormatException("The moving picture's position exceeds the coordinate range.")
        }
        val records = FaceRecordParser.scanWidgets(entry)
        if (records.map { it.globalIndex } != records.indices.toList() || records.size + 1 >= 0xFFFF) {
            throw Fit3FormatException("The target widget index table cannot accept another record.")
        }
        val header = StyleHeader.parse(entry)
        if (header.computedImageOffset != header.storedImageOffset ||
            header.storedImageOffset + header.imageBytes != entry.data.size) {
            throw Fit3FormatException("The target style is not tightly packed.")
        }
        val shippedImages = FaceRecordParser.scanImages(entry).size
        val rasters = frames.map { frameRecord(width, height, it) }
        val offsets = rasters.runningFold(header.imageBytes.toLong()) { offset, raster -> offset + raster.size }
        val at = records.size
        val record = ByteArray(HEAD + table.size * 4).also {
            it.putU32(0x00, WIDGET_ANIMATION.toLong())
            it.putU32(0x0C, (at.toLong() shl 16) or (HEAD + table.size * 4).toLong())
            it.putU16(0x18, x and 0xFFFF)
            it.putU16(0x1A, y and 0xFFFF)
            it[0x24] = table.size.toByte()
            it[0x25] = REPEAT.toByte()
            it.putU16(0x26, periodMs)
            table.forEachIndexed { entryIndex, frame -> it.putU32(HEAD + entryIndex * 4, offsets[frame]) }
        }
        val oldImages = entry.data.copyOfRange(header.storedImageOffset, entry.data.size)
        val data = WidgetImporter.styleBytes(entry.data,
            entry.data.copyOfRange(STYLE_HEADER_SIZE, header.storedImageOffset) + record,
            records.size + 1, rasters.fold(oldImages) { all, raster -> all + raster }, header.fontBindingCount)
        val after = entry.copy(data = data, size = data.size)

        val afterRecords = FaceRecordParser.scanWidgets(after)
        check(afterRecords.size == records.size + 1) { "the widget table did not grow by one" }
        records.forEachIndexed { index, old ->
            val now = afterRecords[index]
            check(entry.data.copyOfRange(old.recordOffset, old.recordOffset + old.recordSize)
                .contentEquals(after.data.copyOfRange(now.recordOffset, now.recordOffset + now.recordSize))) {
                "widget ${old.globalIndex} would change"
            }
        }
        val start = StyleHeader.parse(after).storedImageOffset
        check(oldImages.contentEquals(after.data.copyOfRange(start, start + oldImages.size))) { "existing artwork moved" }
        val added = afterRecords.last()
        check(added.widgetType == WIDGET_ANIMATION && added.frameCount == table.size) { "the animation record is malformed" }
        val images = FaceRecordParser.imagesByRelativeOffset(after)
        val drawn = FaceRecordParser.imagePointerFields(added, images).map { it.image.index }
        check(drawn == table.map { shippedImages + it }) { "the animation does not name exactly its own frames" }
        val guide = FaceRecordParser.widgetGuides(after).last()
        check(guide.drawLeft == x && guide.drawTop == y && guide.width == width && guide.height == height &&
            guide.placement != WidgetPlacement.BACKGROUND) { "the moving picture would not draw where it was placed" }
        return AnimationEdit(StructuralEditor.rebuild(target, mapOf(entry.index to data)), at)
    }

    /**
     * How many of [entry]'s Animations draw on top of the face rather than as its background,
     * whoever put them there: what Install says plays on top, since its preview is still.
     */
    fun countOnTop(entry: ContainerEntry): Int {
        val records = FaceRecordParser.scanWidgets(entry)
        if (records.none { it.widgetType == WIDGET_ANIMATION }) return 0
        val guides = FaceRecordParser.widgetGuides(entry).associateBy { it.globalIndex }
        return records.count {
            it.widgetType == WIDGET_ANIMATION && guides[it.globalIndex]?.placement != WidgetPlacement.BACKGROUND
        }
    }
}
