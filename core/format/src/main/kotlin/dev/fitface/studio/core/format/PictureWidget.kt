package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.WidgetResizeKind
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.pictureAddedBytes
import dev.fitface.studio.core.model.widgetSizeAt

/**
 * A picture added as a widget: one appended Static and the raster it draws.
 *
 * The same shape as an import. The record goes at the end of the variant's table, so it
 * draws on top, positioned against the panel (alignment code 0, target
 * [WidgetImporter.ROOT_TARGET]); its raster goes after every existing image, so nothing
 * already in the style moves. Appended Statics are proven on the watch through imports, and
 * an appended picture with soft, partly transparent edges by watch test T7.
 *
 * Its baseline, the source every later resize and turn is drawn from, is the picture at the
 * size it was prepared at, as large as the panel allows. The widget is inserted at a rung of
 * that baseline's resize ladder, sampled exactly as a resize to that rung would be, so the
 * size it arrives at is one Smaller and Larger step back to.
 */
object PictureWidget {
    /**
     * The picture as an image record: `IMAGE_RGB565` when every pixel is opaque, otherwise
     * `IMAGE_RGB565_ALPHA`. A fully transparent pixel stores black, as every transparent
     * pixel in the catalogue does, and colour is rounded to 5/6/5 bits.
     */
    fun imageRecord(width: Int, height: Int, argb: IntArray): ByteArray {
        require(width > 0 && height > 0 && argb.size == width * height) { "picture pixels must match its size" }
        val opaque = argb.all { it ushr 24 == 0xFF }
        val bytesPerPixel = if (opaque) 2 else 3
        val samples = ByteArray(argb.size * bytesPerPixel)
        argb.forEachIndexed { index, color ->
            val alpha = color ushr 24
            val value = if (alpha == 0) 0 else rgb565(color)
            samples[index * bytesPerPixel] = value.toByte()
            samples[index * bytesPerPixel + 1] = (value ushr 8).toByte()
            if (!opaque) samples[index * bytesPerPixel + 2] = alpha.toByte()
        }
        return image(width, height, if (opaque) IMAGE_RGB565 else IMAGE_RGB565_ALPHA, samples)
    }

    /** What adding a [width] × [height] picture costs: [pictureAddedBytes], the growth [insert] produces. */
    fun addedBytes(width: Int, height: Int, opaque: Boolean): Int = pictureAddedBytes(width, height, opaque)

    /**
     * Adds the [width] × [height] picture [argb] to [variant] at [percent] of that size, its
     * top-left at panel coordinates [x], [y] — centred when they are null.
     *
     * Refuses a picture that would cover the whole panel both ways, which the editor would
     * take for a background layer, and a size off the picture's resize ladder.
     */
    fun insert(
        target: Fit3Container,
        variant: String,
        width: Int,
        height: Int,
        argb: IntArray,
        percent: Int,
        x: Int? = null,
        y: Int? = null,
    ): WidgetImportEdit {
        StructuralEditor.requireValidAndTight(target)
        val destination = StyleWidgetMatch.requireVariantEntry(target.entryByBasename(variant))
        val panel = FaceRecordParser.panelSize(destination)
        if (argb.size != width * height || width !in 1..panel.width || height !in 1..panel.height ||
            (width >= panel.width && height >= panel.height)) {
            throw Fit3FormatException("A picture must fit on the face without covering all of it.")
        }
        val size = widgetSizeAt(width, height, percent, WidgetResizeKind.RASTER)
            ?: throw Fit3FormatException("That size is not one this picture can be resized to.")
        val records = FaceRecordParser.scanWidgets(destination)
        if (records.map { it.globalIndex } != records.indices.toList() || records.size >= WidgetImporter.ROOT_TARGET) {
            throw Fit3FormatException("The target widget index table cannot accept another record.")
        }
        val header = StyleHeader.parse(destination)
        if (header.computedImageOffset != header.storedImageOffset ||
            header.storedImageOffset + header.imageBytes != destination.data.size) {
            throw Fit3FormatException("The target style is not tightly packed.")
        }
        val baselineImage = imageRecord(width, height, argb)
        val format = baselineImage.u16(4)
        val inserted = if (size.width == width && size.height == height) baselineImage else {
            val samples = baselineImage.copyOfRange(IMAGE_HEADER_SIZE, baselineImage.size - OPAQUE_TRAILER_BYTES)
            image(size.width, size.height, format,
                RasterResampler.resample(samples, format, width, height, size.width, size.height))
        }
        val left = x ?: ((panel.width - size.width) / 2)
        val top = y ?: ((panel.height - size.height) / 2)
        val raw = staticRecord(left, top, records.size, header.imageBytes)
        val oldImages = destination.data.copyOfRange(header.storedImageOffset, destination.data.size)
        val payload = WidgetImporter.styleBytes(destination.data,
            destination.data.copyOfRange(STYLE_HEADER_SIZE, header.storedImageOffset) + raw,
            records.size + 1, oldImages + inserted, header.fontBindingCount)
        val edit = StructuralEditor.rebuild(target, mapOf(destination.index to payload))
        val after = edit.container.entryByBasename(variant)
        val afterRecords = FaceRecordParser.scanWidgets(after)
        records.forEachIndexed { index, old ->
            val now = afterRecords[index]
            check(destination.data.copyOfRange(old.recordOffset, old.recordOffset + old.recordSize)
                .contentEquals(after.data.copyOfRange(now.recordOffset, now.recordOffset + now.recordSize)))
        }
        val start = StyleHeader.parse(after).storedImageOffset
        check(oldImages.contentEquals(after.data.copyOfRange(start, start + oldImages.size)))
        val guide = FaceRecordParser.widgetGuides(after).last()
        check(guide.drawLeft == left && guide.drawTop == top && guide.width == size.width && guide.height == size.height &&
            guide.placement != WidgetPlacement.BACKGROUND)
        val baselineRaw = staticRecord((panel.width - width) / 2, (panel.height - height) / 2, 0, 0)
        val baselineData = WidgetImporter.styleBytes(destination.data, baselineRaw, 1, baselineImage, header.fontBindingCount)
        return WidgetImportEdit(edit, records.size, destination.copy(data = baselineData, size = baselineData.size), emptyMap())
    }

    /**
     * A 40-byte Static drawing the image at [pointer] with its top-left at panel
     * coordinates [x], [y]: alignment code 0 against [WidgetImporter.ROOT_TARGET], which
     * names no record and so resolves to the panel. Source and interaction flag stay zero,
     * as in 678 of the catalogue's 681 Statics.
     */
    private fun staticRecord(x: Int, y: Int, globalIndex: Int, pointer: Int): ByteArray = ByteArray(STATIC_SIZE).also {
        if (x !in Short.MIN_VALUE..Short.MAX_VALUE || y !in Short.MIN_VALUE..Short.MAX_VALUE) {
            throw Fit3FormatException("The picture's position exceeds the coordinate range.")
        }
        it.putU32(0x00, WIDGET_STATIC.toLong())
        it.putU32(0x0C, (globalIndex.toLong() shl 16) or STATIC_SIZE.toLong())
        it.putU16(0x18, x and 0xFFFF)
        it.putU16(0x1A, y and 0xFFFF)
        it.putU16(0x1C, 0)
        it.putU16(0x1E, WidgetImporter.ROOT_TARGET)
        it.putU32(0x20, pointer.toLong())
    }

    private fun image(width: Int, height: Int, format: Int, samples: ByteArray): ByteArray {
        val record = ByteArray(IMAGE_HEADER_SIZE + samples.size + OPAQUE_TRAILER_BYTES)
        record.putU16(0, width)
        record.putU16(2, height)
        record.putU16(4, format)
        record.putU32(8, (samples.size + OPAQUE_TRAILER_BYTES).toLong())
        samples.copyInto(record, IMAGE_HEADER_SIZE)
        return record
    }

    private fun rgb565(color: Int): Int =
        (((color ushr 16 and 0xFF) * 31 + 127) / 255 shl 11) or
            (((color ushr 8 and 0xFF) * 63 + 127) / 255 shl 5) or
            (((color and 0xFF) * 31 + 127) / 255)

    private const val STATIC_SIZE = 40
}
