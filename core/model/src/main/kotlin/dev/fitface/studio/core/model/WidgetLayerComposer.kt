package dev.fitface.studio.core.model

/** Source-over composition in record order, shared by the resting and dragging canvas. */
object WidgetLayerComposer {
    fun compose(
        width: Int,
        height: Int,
        layers: List<WidgetImageLayer>,
        widgets: List<WidgetGuide>,
        transparent: Boolean = false,
    ): PreviewFrame {
        val pixels = IntArray(width * height) { if (transparent) 0 else 0xFF000000.toInt() }
        val guides = widgets.associateBy { it.globalIndex }
        for (layer in layers) {
            val guide = guides[layer.globalIndex] ?: continue
            val left = guide.drawLeft + layer.offsetX
            val top = guide.drawTop + layer.offsetY
            for (y in maxOf(0, top) until minOf(height, top + layer.frame.height)) {
                for (x in maxOf(0, left) until minOf(width, left + layer.frame.width)) {
                    val raw = layer.frame.argb[(y - top) * layer.frame.width + x - left]
                    val color = if (layer.isOpaque) raw or 0xFF000000.toInt() else raw
                    val i = y * width + x
                    pixels[i] = over(pixels[i], color)
                }
            }
        }
        return PreviewFrame(width, height, pixels)
    }

    /** Straight-alpha output, including when the destination itself is transparent. */
    fun over(background: Int, foreground: Int): Int {
        val a = foreground ushr 24
        if (a == 0) return background
        if (a == 255) return foreground
        val b = background ushr 24
        val alpha = a * 255 + b * (255 - a)
        fun channel(shift: Int): Int = (
            ((foreground ushr shift) and 255) * a * 255 +
                ((background ushr shift) and 255) * b * (255 - a) + alpha / 2
            ) / alpha
        return ((alpha + 127) / 255 shl 24) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }
}
