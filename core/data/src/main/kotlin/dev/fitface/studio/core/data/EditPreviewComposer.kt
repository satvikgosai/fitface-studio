package dev.fitface.studio.core.data

import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.RemovedWidget
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.originalDrawLeft
import dev.fitface.studio.core.model.originalDrawTop
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WidgetImageLayer
import kotlin.math.abs

internal data class EditPreview(
    val composed: PreviewFrame,
    val widgetOverlay: PreviewFrame,
)

/**
 * Whether the edit gave this widget a different rectangle from the one `preview.bin`
 * was rendered with — by moving it, by resizing it, or by both. A resize counts: the
 * old rectangle's pixels are still in the reference and have to be cleared even when
 * the widget never moved.
 */
private val WidgetGuide.rectangleChanged: Boolean
    get() = x != originalX || y != originalY ||
        width != originalWidth || height != originalHeight

internal object EditPreviewComposer {
    fun compose(
        currentBackground: PreviewFrame,
        originalBackground: PreviewFrame,
        reference: PreviewFrame?,
        widgets: List<WidgetGuide>,
        imageLayers: List<WidgetImageLayer> = emptyList(),
        removedWidgets: List<RemovedWidget> = emptyList(),
    ): EditPreview {
        val width = currentBackground.width
        val height = currentBackground.height
        if (reference == null) {
            return EditPreview(
                composed = currentBackground,
                widgetOverlay = PreviewFrame(width, height, IntArray(width * height)),
            )
        }

        val referencePixels = scale(reference, width, height)
        val originalPixels = scale(originalBackground, width, height)
        val overlay = IntArray(width * height)
        referencePixels.indices.forEach { index ->
            if (isWidgetPixel(referencePixels[index], originalPixels[index])) {
                overlay[index] = referencePixels[index]
            }
        }
        removedWidgets.forEach { removed ->
            clearRemovedWidgetPixels(
                overlay = overlay,
                reference = referencePixels,
                original = originalPixels,
                removed = removed,
                widgets = widgets,
                canvasWidth = width,
                canvasHeight = height,
            )
        }
        val widgetsByIndex = widgets.associateBy(WidgetGuide::globalIndex)
        imageLayers.forEach { layer ->
            widgetsByIndex[layer.globalIndex]?.let { widget ->
                if (widget.duplicateSourceGlobalIndex == null) {
                    clearEmbeddedLayerFallback(
                        overlay = overlay,
                        widget = widget,
                        widgets = widgets,
                        canvasWidth = width,
                        canvasHeight = height,
                    )
                }
            }
        }
        widgets.filter { widget ->
            widget.colorArgb != null && widget.colorArgb != widget.originalColorArgb
        }.forEach { widget ->
            recolorWidgetPixels(
                overlay = overlay,
                reference = referencePixels,
                original = originalPixels,
                widget = widget,
                widgets = widgets,
                canvasWidth = width,
                canvasHeight = height,
            )
        }

        val composed = currentBackground.argb.copyOf()
        blendOverlay(composed, overlay)

        val embeddedIndices = imageLayers.mapTo(mutableSetOf()) { it.globalIndex }
        val relocated = widgets.filter {
            it.globalIndex !in embeddedIndices && it.rectangleChanged
        }
        // Two passes, not one per widget. Interleaved, a widget dragged onto the
        // rectangle another one is vacating gets painted and then wiped out by that
        // widget's own clear — so moving several widgets around each other made them
        // disappear one at a time, in an order nothing on screen explained.
        relocated.filter { it.duplicateSourceGlobalIndex == null }.forEach { widget ->
            clearWidgetPixels(
                composed = composed,
                overlay = overlay,
                background = currentBackground.argb,
                reference = referencePixels,
                original = originalPixels,
                canvasWidth = width,
                canvasHeight = height,
                widget = widget,
                widgets = widgets,
            )
        }
        relocated.forEach { widget ->
            moveWidgetPixels(
                composed = composed,
                overlay = overlay,
                background = currentBackground.argb,
                reference = referencePixels,
                original = originalPixels,
                canvasWidth = width,
                canvasHeight = height,
                widget = widget,
                widgets = widgets,
            )
        }
        imageLayers.forEach { layer ->
            widgetsByIndex[layer.globalIndex]?.let { widget ->
                drawEmbeddedLayer(
                    composed = composed,
                    overlay = overlay,
                    background = currentBackground.argb,
                    frame = layer.frame,
                    widget = widget,
                    canvasWidth = width,
                    canvasHeight = height,
                )
            }
        }

        return EditPreview(
            composed = PreviewFrame(width, height, composed),
            widgetOverlay = PreviewFrame(width, height, overlay),
        )
    }

    private fun clearEmbeddedLayerFallback(
        overlay: IntArray,
        widget: WidgetGuide,
        widgets: List<WidgetGuide>,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        val sourceLeft = widget.originalDrawLeft(canvasWidth)
        val sourceTop = widget.originalDrawTop(canvasHeight)
        for (localY in 0 until widget.originalHeight.coerceAtLeast(0)) {
            for (localX in 0 until widget.originalWidth.coerceAtLeast(0)) {
                val x = sourceLeft + localX
                val y = sourceTop + localY
                if (x !in 0 until canvasWidth || y !in 0 until canvasHeight) continue
                if (coveredBySmallerWidget(
                        x = x,
                        y = y,
                        selected = widget,
                        widgets = widgets,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                    )
                ) {
                    continue
                }
                overlay[y * canvasWidth + x] = 0
            }
        }
    }

    private fun drawEmbeddedLayer(
        composed: IntArray,
        overlay: IntArray,
        background: IntArray,
        frame: PreviewFrame,
        widget: WidgetGuide,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        val targetLeft = widget.drawLeft(canvasWidth)
        val targetTop = widget.drawTop(canvasHeight)
        for (localY in 0 until frame.height) {
            for (localX in 0 until frame.width) {
                val pixel = frame.argb[localY * frame.width + localX]
                if (pixel ushr 24 == 0) continue
                val x = targetLeft + localX
                val y = targetTop + localY
                if (x !in 0 until canvasWidth || y !in 0 until canvasHeight) continue
                val index = y * canvasWidth + x
                overlay[index] = pixel
                composed[index] = blend(background[index], pixel)
            }
        }
    }

    private fun moveWidgetPixels(
        composed: IntArray,
        overlay: IntArray,
        background: IntArray,
        reference: IntArray,
        original: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
        widget: WidgetGuide,
        widgets: List<WidgetGuide>,
    ) {
        val sourceLeft = widget.originalDrawLeft(canvasWidth)
        val sourceTop = widget.originalDrawTop(canvasHeight)
        val targetLeft = widget.drawLeft(canvasWidth)
        val targetTop = widget.drawTop(canvasHeight)
        val sourceWidth = widget.originalWidth
        val sourceHeight = widget.originalHeight
        if (widget.width <= 0 || widget.height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return
        }
        for (row in 0 until widget.height) {
            for (column in 0 until widget.width) {
                // Walk the new rectangle and sample the old one. A resize has no
                // render of its own in `preview.bin` to copy, so the vendor's pixels
                // are resampled into it; for a plain move the two extents are equal
                // and this is the identity mapping.
                val sourceX = sourceLeft + column * sourceWidth / widget.width
                val sourceY = sourceTop + row * sourceHeight / widget.height
                val targetX = targetLeft + column
                val targetY = targetTop + row
                if (sourceX !in 0 until canvasWidth ||
                    sourceY !in 0 until canvasHeight ||
                    targetX !in 0 until canvasWidth ||
                    targetY !in 0 until canvasHeight
                ) {
                    continue
                }
                val sourceIndex = sourceY * canvasWidth + sourceX
                if (!isWidgetPixel(reference[sourceIndex], original[sourceIndex])) {
                    continue
                }
                if (coveredBySmallerWidget(
                        x = sourceX,
                        y = sourceY,
                        selected = widget,
                        widgets = widgets,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                    )
                ) {
                    continue
                }
                val targetIndex = targetY * canvasWidth + targetX
                val pixel = renderWidgetPixel(widget, reference[sourceIndex])
                overlay[targetIndex] = pixel
                composed[targetIndex] = blend(background[targetIndex], pixel)
            }
        }
    }

    private fun recolorWidgetPixels(
        overlay: IntArray,
        reference: IntArray,
        original: IntArray,
        widget: WidgetGuide,
        widgets: List<WidgetGuide>,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        val sourceLeft = widget.originalDrawLeft(canvasWidth)
        val sourceTop = widget.originalDrawTop(canvasHeight)
        for (row in 0 until widget.originalHeight.coerceAtLeast(0)) {
            for (column in 0 until widget.originalWidth.coerceAtLeast(0)) {
                val x = sourceLeft + column
                val y = sourceTop + row
                if (x !in 0 until canvasWidth || y !in 0 until canvasHeight) continue
                val index = y * canvasWidth + x
                if (!isWidgetPixel(reference[index], original[index])) continue
                if (coveredBySmallerWidget(
                        x = x,
                        y = y,
                        selected = widget,
                        widgets = widgets,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                    )
                ) {
                    continue
                }
                overlay[index] = renderWidgetPixel(widget, reference[index])
            }
        }
    }

    private fun clearWidgetPixels(
        composed: IntArray,
        overlay: IntArray,
        background: IntArray,
        reference: IntArray,
        original: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
        widget: WidgetGuide,
        widgets: List<WidgetGuide>,
    ) {
        val sourceLeft = widget.originalDrawLeft(canvasWidth)
        val sourceTop = widget.originalDrawTop(canvasHeight)
        for (row in 0 until widget.originalHeight.coerceAtLeast(0)) {
            for (column in 0 until widget.originalWidth.coerceAtLeast(0)) {
                val x = sourceLeft + column
                val y = sourceTop + row
                if (x !in 0 until canvasWidth || y !in 0 until canvasHeight) continue
                val index = y * canvasWidth + x
                if (!isWidgetPixel(reference[index], original[index])) continue
                if (coveredBySmallerWidget(
                        x = x,
                        y = y,
                        selected = widget,
                        widgets = widgets,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                    )
                ) {
                    continue
                }
                composed[index] = background[index]
                overlay[index] = 0
            }
        }
    }

    /**
     * A removed widget is gone from the container but not from `preview.bin` — that
     * raster is the vendor's render of the unedited face and no edit rewrites it — so
     * its pixels have to be dropped from the overlay explicitly. Without this the
     * widget keeps showing on the canvas after being cut out, which is exactly what
     * removal is supposed to demonstrate.
     */
    private fun clearRemovedWidgetPixels(
        overlay: IntArray,
        reference: IntArray,
        original: IntArray,
        removed: RemovedWidget,
        widgets: List<WidgetGuide>,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        if (removed.width <= 0 || removed.height <= 0) return
        val left = anchoredCoordinate(removed.x, removed.width, canvasWidth)
        val top = anchoredCoordinate(removed.y, removed.height, canvasHeight)
        val removedArea = removed.width.toLong() * removed.height
        for (row in 0 until removed.height) {
            for (column in 0 until removed.width) {
                val x = left + column
                val y = top + row
                if (x !in 0 until canvasWidth || y !in 0 until canvasHeight) continue
                val index = y * canvasWidth + x
                if (!isWidgetPixel(reference[index], original[index])) continue
                // A surviving widget drawn inside the removed rectangle keeps its
                // pixels; only the removed record's own area is cleared.
                if (coveredBySmallerWidget(
                        x = x,
                        y = y,
                        selectedArea = removedArea,
                        selectedGlobalIndex = null,
                        widgets = widgets,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                    )
                ) {
                    continue
                }
                overlay[index] = 0
            }
        }
    }

    private fun coveredBySmallerWidget(
        x: Int,
        y: Int,
        selected: WidgetGuide,
        widgets: List<WidgetGuide>,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Boolean = coveredBySmallerWidget(
        x = x,
        y = y,
        selectedArea = selected.originalWidth.toLong() * selected.originalHeight,
        selectedGlobalIndex = selected.globalIndex,
        widgets = widgets,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
    )

    /**
     * Which widget a reference pixel belongs to, resolved smallest-rectangle-first.
     *
     * Every rectangle here is the *original* one. The reference is the vendor's render
     * of the unedited face, so the only geometry that says anything about where its
     * pixels came from is the geometry that produced them — an edited extent describes
     * the container, not the raster being read.
     */
    private fun coveredBySmallerWidget(
        x: Int,
        y: Int,
        selectedArea: Long,
        selectedGlobalIndex: Int?,
        widgets: List<WidgetGuide>,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Boolean {
        return widgets.any { other ->
            if (other.globalIndex == selectedGlobalIndex ||
                other.originalWidth <= 0 ||
                other.originalHeight <= 0 ||
                other.originalWidth.toLong() * other.originalHeight >= selectedArea
            ) {
                return@any false
            }
            val left = other.originalDrawLeft(canvasWidth)
            val top = other.originalDrawTop(canvasHeight)
            x in left until left + other.originalWidth &&
                y in top until top + other.originalHeight &&
                x in 0 until canvasWidth &&
                y in 0 until canvasHeight
        }
    }

    private fun blendOverlay(target: IntArray, overlay: IntArray) {
        overlay.indices.forEach { index ->
            if (overlay[index] ushr 24 != 0) {
                target[index] = blend(target[index], overlay[index])
            }
        }
    }

    private fun renderWidgetPixel(widget: WidgetGuide, pixel: Int): Int {
        val target = widget.colorArgb
        if (target == null || target == widget.originalColorArgb) return pixel
        return (pixel and 0xFF00_0000.toInt()) or (target and 0x00FF_FFFF)
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

    private fun isWidgetPixel(reference: Int, background: Int): Boolean {
        val alpha = reference ushr 24 and 0xFF
        if (alpha == 0) return false
        val difference =
            abs((reference ushr 16 and 0xFF) - (background ushr 16 and 0xFF)) +
                abs((reference ushr 8 and 0xFF) - (background ushr 8 and 0xFF)) +
                abs((reference and 0xFF) - (background and 0xFF))
        return difference >= 18
    }

    /**
     * Nearest-neighbour resample to the canvas. Returns the source pixels untouched
     * when it is already the right size — which the background always is now that the
     * canvas is the declared panel — so a full-canvas copy is not made on every
     * commit. Callers only read the result.
     */
    private fun scale(source: PreviewFrame, width: Int, height: Int): IntArray {
        if (source.width == width && source.height == height) return source.argb
        return IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val sourceX = (x * source.width / width).coerceAtMost(source.width - 1)
            val sourceY = (y * source.height / height).coerceAtMost(source.height - 1)
            source.argb[sourceY * source.width + sourceX]
        }
    }

    private fun anchoredCoordinate(value: Int, extent: Int, canvasExtent: Int): Int =
        if (value < 0) canvasExtent + value - extent else value
}
