package dev.fitface.studio.core.data

import dev.fitface.studio.core.format.ContainerEntry
import dev.fitface.studio.core.format.FaceRecordParser
import dev.fitface.studio.core.format.WidgetFields
import dev.fitface.studio.core.format.WidgetPreviewSample
import dev.fitface.studio.core.format.WidgetText
import dev.fitface.studio.core.format.WidgetTextResources
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.WidgetImageLayer
import dev.fitface.studio.core.model.WidgetLayerComposer
import dev.fitface.studio.core.model.WidgetPlacement
import kotlin.math.*

/** One scene for styles and AOD. Never reads a stock preview or a pristine widget. */
internal object WidgetPreviewComposer {
    fun compose(
        entry: ContainerEntry,
        resources: List<ContainerEntry> = emptyList(),
        textRasterizer: ((WidgetText, Int, Int) -> PreviewFrame)? = null,
        locale: String = "en",
    ): WidgetPreview {
        val panel = FaceRecordParser.panelSize(entry)
        val guides = FaceRecordParser.widgetGuides(entry)
        val byIndex = guides.associateBy { it.globalIndex }
        val textResources = WidgetTextResources(resources, locale)
        val decoded = mutableMapOf<Int, PreviewFrame>()
        val layers = mutableListOf<WidgetImageLayer>()
        var approximate = false
        for (record in FaceRecordParser.scanWidgets(entry)) {
            val guide = byIndex[record.globalIndex] ?: continue
            val f = WidgetFields(entry, record)
            fun raster(index: Int = 0): PreviewFrame? {
                val image = runCatching { FaceRecordParser.resourceImages(entry, record) }
                    .getOrNull()?.getOrNull(index) ?: return null
                return decoded.getOrPut(image.recordOffset) {
                    FaceRecordParser.decodeImage(entry, image)
                }
            }
            var dx = 0
            var dy = 0
            val frame: PreviewFrame? = when (record.widgetType) {
                1 -> raster()
                3 -> raster(WidgetPreviewSample.spriteFrame(record.sourceId, record.frameCount ?: 0))
                4 -> if (f.byte(0x25) != 0 && f.short(0x26) != 0) raster() else null
                2 -> {
                    val image = raster()
                    val fraction = WidgetPreviewSample.fraction(record.sourceId)
                    if (image == null || fraction == null) null else {
                        val angle = WidgetPreviewSample.handAngle(f.signed(0x24), f.signed(0x26), fraction)
                        val rotated = rotate(image, f.signed(0x20), f.signed(0x22), angle)
                        dx = rotated.offsetX; dy = rotated.offsetY
                        rotated.frame
                    }
                }
                5, 13 -> {
                    approximate = true // The package selects a ROM font; it does not embed glyphs.
                    val text = textResources.text(entry, record)
                    if (text == null || textRasterizer == null || guide.width !in 1..1024 ||
                        guide.height !in 1..1024) null else {
                        val label = textRasterizer(text, guide.width, guide.height)
                        val rotated = rotate(label, label.width / 2, label.height / 2,
                            text.rotationDegrees)
                        dx = rotated.offsetX; dy = rotated.offsetY
                        rotated.frame
                    }
                }
                6, 16, 17 -> {
                    val progress = WidgetPreviewSample.fraction(record.sourceId)
                    val texture = if (record.widgetType == 6) null else raster()
                    if (progress == null || (record.widgetType != 6 && texture == null) ||
                        guide.width !in 1..1024 || guide.height !in 1..1024) null else {
                        if (record.widgetType == 17) bar(guide.width, guide.height, progress,
                            f.byte(0x30), f.byte(0x31) == 1, requireNotNull(texture))
                        else arc(guide.width, guide.height, progress,
                            if (record.widgetType == 6) f.short(0x40) else f.short(0x24),
                            f.signed(0x28), f.signed(0x2A),
                            if (record.widgetType == 6) 0 else f.short(0x26),
                            if (record.widgetType == 6) f.short(0x44) != 0 else f.short(0x2C) != 0,
                            if (record.widgetType == 6) f.word(0x34) else -1, texture)
                    }
                }
                7 -> {
                    // A Badge stores endpoints, not an extent. Preserve direction on reversed rules.
                    val progress = WidgetPreviewSample.fraction(record.sourceId)
                    val thickness = f.byte(0x30)
                    if (progress == null || thickness == 0) null else {
                        val pad = (thickness + 1) / 2
                        val x1 = f.signed(0x18); val y1 = f.signed(0x1A)
                        val x2 = f.signed(0x1C); val y2 = f.signed(0x1E)
                        val width = abs(x2 - x1) + pad * 2 + 1
                        val height = abs(y2 - y1) + pad * 2 + 1
                        dx = -pad; dy = -pad
                        if (width > 1024 || height > 1024) null else {
                            val ax = (x1 - minOf(x1, x2) + pad).toDouble()
                            val ay = (y1 - minOf(y1, y2) + pad).toDouble()
                            val bx = ax + (x2 - x1) * progress
                            val by = ay + (y2 - y1) * progress
                            mask(width, height, f.word(0x28), null) { x, y ->
                                segment(x, y, ax, ay, bx, by, thickness / 2.0, f.byte(0x32) == 1)
                            }
                        }
                    }
                }
                // Firmware constructors for these reserved types are intentionally inert.
                8, 10, 11, 12, 14, 15 -> continue
                // Type 9 uses firmware-owned resources, absent from the face package.
                else -> null
            }
            if (frame == null) approximate = true
            else layers += WidgetImageLayer(record.globalIndex, frame, offsetX = dx, offsetY = dy)
        }
        // Background replacement edits the primary background raster, not every panel-sized
        // record. A second full-panel layer must remain above the replacement preview too.
        val primaryBackground = FaceRecordParser.backgroundImage(entry)
        val backgrounds = if (primaryBackground == null) emptySet() else {
            FaceRecordParser.scanWidgets(entry).filter { record ->
                record.widgetType == 1 && runCatching {
                    FaceRecordParser.resourceImages(entry, record)
                        .any { it.recordOffset == primaryBackground.recordOffset }
                }.getOrDefault(false)
            }.map { it.globalIndex }.toSet()
        }
        return WidgetPreview(
            WidgetLayerComposer.compose(panel.width, panel.height, layers, guides),
            WidgetLayerComposer.compose(panel.width, panel.height,
                layers.filterNot { it.globalIndex in backgrounds }, guides, transparent = true),
            layers,
            approximate,
        )
    }

    internal data class Rotated(val frame: PreviewFrame, val offsetX: Int, val offsetY: Int)

    /** Inverse-sampled native artwork; the pivot is inside its raster, not the panel. */
    internal fun rotate(frame: PreviewFrame, pivotX: Int, pivotY: Int, angle: Double): Rotated {
        if (angle % 360 == 0.0) return Rotated(frame, 0, 0)
        val c = cos(Math.toRadians(angle)); val s = sin(Math.toRadians(angle))
        val bounds = dev.fitface.studio.core.model.rotationBounds(frame.width, frame.height, pivotX, pivotY, angle)
        val left = bounds.left; val top = bounds.top
        val w = bounds.width; val h = bounds.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x + left - pivotX; val dy = y + top - pivotY
            val sx = (pivotX + dx * c + dy * s).roundToInt()
            val sy = (pivotY - dx * s + dy * c).roundToInt()
            if (sx in 0 until frame.width && sy in 0 until frame.height) {
                pixels[y * w + x] = frame.argb[sy * frame.width + sx]
            }
        }
        return Rotated(PreviewFrame(w, h, pixels), left, top)
    }

    private fun arc(w: Int, h: Int, progress: Double, thickness: Int, start: Int, end: Int,
        orientation: Int, rounded: Boolean, color: Int, texture: PreviewFrame?): PreviewFrame {
        val outer = minOf(w, h) / 2.0
        val stroke = thickness.toDouble().coerceIn(0.0, outer)
        val radius = outer - stroke / 2
        val cx = w / 2.0; val cy = h / 2.0
        val sweep = ((end - start).toDouble().let { if (it < 0) it + 360 else it }) * progress
        val begin = start + orientation.toDouble()
        fun cap(x: Double, y: Double, angle: Double): Boolean {
            val theta = Math.toRadians(angle)
            return hypot(x - cx - radius * cos(theta), y - cy - radius * sin(theta)) <= stroke / 2
        }
        return mask(w, h, color, texture) { x, y ->
            val distance = hypot(x - cx, y - cy)
            val angle = ((Math.toDegrees(atan2(y - cy, x - cx)) - begin) % 360 + 360) % 360
            sweep > 0 && ((distance in (outer - stroke)..outer && angle <= sweep) ||
                (rounded && (cap(x, y, begin) || cap(x, y, begin + sweep))))
        }
    }

    private fun bar(w: Int, h: Int, progress: Double, thickness: Int, rounded: Boolean,
        texture: PreviewFrame): PreviewFrame {
        val right = if (w >= h) w * progress else w.toDouble()
        val top = if (h > w) h * (1 - progress) else 0.0
        val r = if (rounded) minOf(thickness / 2.0, right / 2, (h - top) / 2) else 0.0
        return mask(w, h, -1, texture) { x, y ->
            x in 0.0..right && y in top..h.toDouble() &&
                hypot(x - x.coerceIn(r, right - r), y - y.coerceIn(top + r, h - r)) <= r
        }
    }

    private fun segment(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double,
        radius: Double, rounded: Boolean): Boolean {
        val dx = bx - ax; val dy = by - ay
        val length = dx * dx + dy * dy
        if (length == 0.0) return false
        val t = ((x - ax) * dx + (y - ay) * dy) / length
        if (!rounded && t !in 0.0..1.0) return false
        return hypot(x - ax - dx * t.coerceIn(0.0, 1.0),
            y - ay - dy * t.coerceIn(0.0, 1.0)) <= radius
    }

    /** Small coverage mask, with native-size centred texture and no background contamination. */
    private inline fun mask(w: Int, h: Int, color: Int, texture: PreviewFrame?,
        contains: (Double, Double) -> Boolean): PreviewFrame {
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var coverage = 0
            for (sy in 0..1) for (sx in 0..1) {
                if (contains(x + 0.25 + sx * 0.5, y + 0.25 + sy * 0.5)) coverage++
            }
            if (coverage == 0) continue
            val pixel = if (texture == null) color or 0xFF000000.toInt() else {
                val tx = x - (w - texture.width) / 2; val ty = y - (h - texture.height) / 2
                if (tx !in 0 until texture.width || ty !in 0 until texture.height) continue
                texture.argb[ty * texture.width + tx]
            }
            pixels[y * w + x] = (pixel and 0xFFFFFF) or ((pixel ushr 24) * coverage / 4 shl 24)
        }
        return PreviewFrame(w, h, pixels)
    }
}
