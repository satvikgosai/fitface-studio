package dev.fitface.studio.core.data

import dev.fitface.studio.core.format.ContainerEntry
import dev.fitface.studio.core.format.FaceRecordParser
import dev.fitface.studio.core.format.ImageRecord
import dev.fitface.studio.core.format.WIDGET_COMP
import dev.fitface.studio.core.format.WIDGET_HAND
import dev.fitface.studio.core.format.WIDGET_PAIR
import dev.fitface.studio.core.format.WIDGET_SPRITE
import dev.fitface.studio.core.format.WIDGET_STATIC
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.WidgetImageLayer
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class AodPreview(
    val composed: PreviewFrame,
    val widgetOverlay: PreviewFrame,
    val widgetImageLayers: List<WidgetImageLayer>,
    /**
     * True when this render leaves out something the watch will draw.
     *
     * Two causes, and both are honest omissions rather than approximations: a Value or
     * Composite draws live text from a firmware font this app does not have, and a Hand
     * following a reading this sampler has no value for cannot be pointed anywhere. The
     * canvas says so — see `editor_aod_approximate`. Nothing invented is ever painted in
     * their place: a coloured box where the watch will put glyphs is a preview that
     * lies, and every other widget here is real decoded artwork.
     */
    val isApproximate: Boolean,
)

private const val OPAQUE_BLACK = 0xFF00_0000.toInt()

/**
 * Renders `aod.bin` directly from its own current bytes.
 *
 * [EditPreviewComposer] diffs the current entry against the vendor's `preview.bin`
 * render, which is how it decides which pixels belong to a widget and which are
 * background — but the package ships one `preview.bin` frame per numbered style and
 * never one for AOD, so there is nothing to diff against. This draws the scene instead:
 * the panel, then every widget in record order, which is the order the firmware creates
 * its objects in and therefore the order they stack.
 *
 * Because it always reflects the current record table, a moved, resized, recoloured,
 * duplicated or removed widget shows up correctly with no separate "ghost clearing"
 * pass — there is no stale reference for a removed widget's pixels to survive in.
 */
internal object AodPreviewComposer {
    /**
     * The time this render samples, which is the one the vendor's own style previews are
     * rendered at — Saturday 28 December 2024, 10:08. Matching it is what lets the Styles
     * page put the generated AOD row beside four packaged style pictures without the
     * clocks disagreeing.
     */
    private const val SAMPLE_HOUR = 10
    private const val SAMPLE_MINUTE = 8
    private const val SAMPLE_SECOND = 0
    private const val SAMPLE_DAY = 28
    private const val SAMPLE_MONTH = 12
    private const val SAMPLE_YEAR = 2024

    fun compose(entry: ContainerEntry): AodPreview {
        val panel = FaceRecordParser.panelSize(entry)
        val width = panel.width.coerceAtLeast(1)
        val height = panel.height.coerceAtLeast(1)
        val background = FaceRecordParser.backgroundImage(entry)
        // 67 of the corpus's 99 AOD entries carry no full-panel raster and compose over
        // the unlit panel, which is what the watch shows behind them.
        val composed = if (background != null) {
            FaceRecordParser.decodeImage(entry, background).argb.copyOf()
        } else {
            IntArray(width * height) { OPAQUE_BLACK }
        }
        val overlay = IntArray(width * height)
        val imageLayers = mutableListOf<WidgetImageLayer>()
        var approximate = false

        val widgets = FaceRecordParser.widgetGuides(entry)
        val records = FaceRecordParser.scanWidgets(entry).associateBy { it.globalIndex }
        val imagesByOffset = imagesByRelativeOffset(FaceRecordParser.scanImages(entry))

        widgets.forEach { widget ->
            // The panel raster is already the base of this canvas; the Static that draws
            // it must not be blitted over itself.
            if (widget.placement == WidgetPlacement.BACKGROUND) return@forEach
            val record = records[widget.globalIndex] ?: return@forEach
            when (record.widgetType) {
                WIDGET_STATIC -> {
                    // A Static's raster pointer is `+0x20`, never `words[0]`.
                    val image = imagesByOffset[record.unknown20] ?: return@forEach
                    blitRaster(
                        entry = entry,
                        image = image,
                        left = widget.drawLeft,
                        top = widget.drawTop,
                        globalIndex = widget.globalIndex,
                        composed = composed,
                        overlay = overlay,
                        imageLayers = imageLayers,
                        canvasWidth = width,
                        canvasHeight = height,
                    )
                }

                WIDGET_SPRITE -> {
                    val frameCount = record.frameCount ?: 0
                    if (frameCount <= 0) return@forEach
                    val image = record.words
                        .getOrNull(sampleSpriteFrame(record.sourceId, frameCount))
                        ?.let(imagesByOffset::get)
                        ?: return@forEach
                    blitRaster(
                        entry = entry,
                        image = image,
                        left = widget.drawLeft,
                        top = widget.drawTop,
                        globalIndex = widget.globalIndex,
                        composed = composed,
                        overlay = overlay,
                        imageLayers = imageLayers,
                        canvasWidth = width,
                        canvasHeight = height,
                    )
                }

                WIDGET_HAND -> {
                    val image = record.words.getOrNull(1)?.let(imagesByOffset::get)
                    val fraction = sampleHandFraction(record.sourceId)
                    if (image == null || fraction == null) {
                        // A needle on a reading this sampler has no value for — steps or
                        // battery, which use the same primitive. Drawing it at some
                        // arbitrary angle would be an invention, so it is left out and
                        // the canvas is told to say the render is partial.
                        approximate = true
                        return@forEach
                    }
                    drawRotatedRaster(
                        canvas = composed,
                        canvasWidth = width,
                        canvasHeight = height,
                        raster = FaceRecordParser.decodeImage(entry, image),
                        forceOpaque = !image.hasAlphaChannel,
                        // `+0x20`/`+0x22`: the rotation centre as an offset *inside the
                        // raster*, two signed halves of one word. `docs/bin-format.md`
                        // §7 proves the reading 14/14 — the offset added to the record's
                        // own `x,y` lands on `(128, 201)`, the panel's exact centre, for
                        // every Hand in the corpus. Signed because it may fall outside
                        // the artwork.
                        pivotX = (record.unknown20 and 0xFFFF).toShort().toInt(),
                        pivotY = ((record.unknown20 ushr 16) and 0xFFFF).toShort().toInt(),
                        left = widget.drawLeft,
                        top = widget.drawTop,
                        // `+0x24`/`+0x26` are the sweep this hand maps its reading across;
                        // corpus clock hands store 0..360, and a gauge could store less.
                        angleDegrees = handAngle(
                            startDegrees = (record.words.first() and 0xFFFF).toShort().toInt(),
                            endDegrees = ((record.words.first() ushr 16) and 0xFFFF)
                                .toShort().toInt(),
                            fraction = fraction,
                        ),
                    )
                }

                // Live text drawn by the watch from a font this app does not have. There
                // is no artwork to decode and nothing truthful to paint, so the record is
                // left to the canvas's own selection outline and the render is declared
                // partial. Painting a filled rectangle here — which an earlier pass did —
                // puts pixels on the canvas that the watch will never draw, in the one
                // picture the Validate page presents as what is about to be installed.
                WIDGET_PAIR, WIDGET_COMP -> approximate = true

                // No corpus AOD carries anything else. One that did would be preserved
                // verbatim by every edit and simply not previewed, which the flag says.
                else -> approximate = true
            }
        }

        return AodPreview(
            composed = PreviewFrame(width, height, composed),
            widgetOverlay = PreviewFrame(width, height, overlay),
            widgetImageLayers = imageLayers,
            isApproximate = approximate,
        )
    }

    /**
     * Which frame of a sprite's table the sampled time selects.
     *
     * Derived from the sample rather than tabulated, so the digits cannot disagree with
     * each other or with the hands. The readings are `DataSourceLabels`', and which of
     * them index artwork positionally is settled in `docs/bin-format.md` §7 — including
     * month artwork being zero-based where the digit pairs are not. Reduced modulo the
     * table length the way the watch's own out-of-range handling does, so a shorter
     * table than expected still names a real frame.
     *
     * An unrecognised source falls to frame 0, and so does one whose frame *order* is not
     * established — weekday (17) is the case that matters, and one corpus face opening on
     * Monday is not evidence of where Saturday sits in every face's table. Drawing the
     * wrong day would be worse than drawing the first frame, and either way it is real
     * artwork out of the container rather than an approximation, so it does not make the
     * render partial.
     */
    internal fun sampleSpriteFrame(sourceId: Int, frameCount: Int): Int {
        if (frameCount <= 0) return 0
        val frame = when (sourceId) {
            2 -> SAMPLE_HOUR / 10
            3 -> SAMPLE_HOUR % 10
            10 -> SAMPLE_MINUTE / 10
            11 -> SAMPLE_MINUTE % 10
            14 -> SAMPLE_SECOND / 10
            15 -> SAMPLE_SECOND % 10
            19 -> SAMPLE_DAY / 10
            20 -> SAMPLE_DAY % 10
            21 -> SAMPLE_MONTH - 1
            22 -> SAMPLE_MONTH / 10
            23 -> SAMPLE_MONTH % 10
            25 -> SAMPLE_YEAR / 1000
            26 -> SAMPLE_YEAR / 100 % 10
            27 -> SAMPLE_YEAR / 10 % 10
            28 -> SAMPLE_YEAR % 10
            else -> 0
        }
        return frame.coerceAtLeast(0) % frameCount
    }

    /**
     * How far along its own cycle the sampled time puts a hand's reading, or null for a
     * reading this sampler cannot supply.
     *
     * The three clock hands are sources 1, 9 and 13 — hour, minute and second, ordered
     * that way by the sprite lengths above their pivots in `docs/bin-format.md` §7. The
     * same primitive also sweeps steps, battery, heart rate and calories, and those have no
     * value here: a needle drawn at a made-up angle is a preview stating a reading the
     * watch never took.
     */
    internal fun sampleHandFraction(sourceId: Int): Double? = when (sourceId) {
        // The hour hand moves with the minutes. Pinned to the hour alone it would draw
        // 10:08 with the hand exactly on the ten.
        1 -> ((SAMPLE_HOUR % 12) + SAMPLE_MINUTE / 60.0) / 12.0
        9 -> SAMPLE_MINUTE / 60.0
        13 -> SAMPLE_SECOND / 60.0
        else -> null
    }

    /**
     * Where along `startDegrees..endDegrees` a reading sits, clockwise from twelve
     * o'clock — the convention the watch's own rotation call uses.
     */
    internal fun handAngle(startDegrees: Int, endDegrees: Int, fraction: Double): Double =
        startDegrees + fraction * (endDegrees - startDegrees)

    private fun blitRaster(
        entry: ContainerEntry,
        image: ImageRecord,
        left: Int,
        top: Int,
        globalIndex: Int,
        composed: IntArray,
        overlay: IntArray,
        imageLayers: MutableList<WidgetImageLayer>,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        val frame = FaceRecordParser.decodeImage(entry, image)
        // Plain RGB565 carries no alpha, so the watch paints the whole rectangle
        // including the black behind the glyphs, and so does this.
        val forceOpaque = !image.hasAlphaChannel
        for (localY in 0 until frame.height) {
            for (localX in 0 until frame.width) {
                val x = left + localX
                val y = top + localY
                if (x !in 0 until canvasWidth || y !in 0 until canvasHeight) continue
                val pixel = opaqueIfNeeded(frame.argb[localY * frame.width + localX], forceOpaque)
                if (pixel ushr 24 == 0) continue
                val index = y * canvasWidth + x
                overlay[index] = pixel
                composed[index] = blend(composed[index], pixel)
            }
        }
        imageLayers += WidgetImageLayer(
            globalIndex = globalIndex,
            frame = frame,
            isOpaque = forceOpaque,
        )
    }

    /**
     * Composites [raster] rotated by [angleDegrees] about the point ([pivotX], [pivotY])
     * *inside it*, with the raster's own top-left at ([left], [top]).
     *
     * The pivot is where the rotation centre sits within the artwork, not where the
     * artwork sits on the panel: the firmware positions the image by `+0x18`/`+0x1A` —
     * which is what [drawLeft]/[drawTop] resolve — and then rotates it about the pivot,
     * so the pivot's own panel position is `left + pivotX, top + pivotY` and it is the one
     * point the rotation leaves alone. An earlier pass put the pivot *at* `left, top`,
     * which slid every hand off the dial by the pivot's own offset.
     *
     * Inverse-mapped from each destination pixel back into the source, so the result has
     * none of the gaps a forward per-pixel rotation leaves.
     */
    internal fun drawRotatedRaster(
        canvas: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
        raster: PreviewFrame,
        forceOpaque: Boolean,
        pivotX: Int,
        pivotY: Int,
        left: Int,
        top: Int,
        angleDegrees: Double,
    ) {
        val theta = Math.toRadians(angleDegrees)
        val cosT = cos(theta)
        val sinT = sin(theta)
        val centreX = left + pivotX
        val centreY = top + pivotY
        // The rotated artwork's bounding box, as offsets from the pivot.
        val corners = listOf(
            -pivotX to -pivotY,
            (raster.width - pivotX) to -pivotY,
            -pivotX to (raster.height - pivotY),
            (raster.width - pivotX) to (raster.height - pivotY),
        )
        val rotatedX = corners.map { (dx, dy) -> dx * cosT - dy * sinT }
        val rotatedY = corners.map { (dx, dy) -> dx * sinT + dy * cosT }
        val boxLeft = (centreX + floor(rotatedX.min())).toInt().coerceAtLeast(0)
        val boxRight = (centreX + ceil(rotatedX.max())).toInt().coerceAtMost(canvasWidth - 1)
        val boxTop = (centreY + floor(rotatedY.min())).toInt().coerceAtLeast(0)
        val boxBottom = (centreY + ceil(rotatedY.max())).toInt().coerceAtMost(canvasHeight - 1)
        if (boxLeft > boxRight || boxTop > boxBottom) return
        for (py in boxTop..boxBottom) {
            for (px in boxLeft..boxRight) {
                val dx = (px - centreX).toDouble()
                val dy = (py - centreY).toDouble()
                // Rotate the destination offset back by -theta to find its source pixel.
                val sourceX = (pivotX + dx * cosT + dy * sinT).roundToInt()
                val sourceY = (pivotY - dx * sinT + dy * cosT).roundToInt()
                if (sourceX !in 0 until raster.width || sourceY !in 0 until raster.height) {
                    continue
                }
                val pixel = opaqueIfNeeded(
                    raster.argb[sourceY * raster.width + sourceX],
                    forceOpaque,
                )
                if (pixel ushr 24 == 0) continue
                val index = py * canvasWidth + px
                canvas[index] = blend(canvas[index], pixel)
            }
        }
    }

    private fun opaqueIfNeeded(pixel: Int, forceOpaque: Boolean): Int =
        if (forceOpaque) pixel or (0xFF shl 24) else pixel

    /**
     * The same relative-offset map [FaceRecordParser] resolves pointers through. Repeated
     * here because that helper is `internal` to `:core:format`; the arithmetic is the
     * whole of it — a pointer is a byte offset from the first image record.
     */
    private fun imagesByRelativeOffset(images: List<ImageRecord>): Map<Long, ImageRecord> {
        val firstOffset = images.firstOrNull()?.recordOffset ?: return emptyMap()
        return images.associateBy { (it.recordOffset - firstOffset).toLong() }
    }

    private fun blend(background: Int, foreground: Int): Int {
        val alpha = foreground ushr 24 and 0xFF
        if (alpha == 0xFF) return foreground
        if (alpha == 0) return background
        val inverse = 0xFF - alpha
        val red = ((foreground ushr 16 and 0xFF) * alpha +
            (background ushr 16 and 0xFF) * inverse) / 0xFF
        val green = ((foreground ushr 8 and 0xFF) * alpha +
            (background ushr 8 and 0xFF) * inverse) / 0xFF
        val blue = ((foreground and 0xFF) * alpha + (background and 0xFF) * inverse) / 0xFF
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
