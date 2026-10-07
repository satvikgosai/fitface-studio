package dev.fitface.studio.core.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import dev.fitface.studio.core.format.WidgetText
import dev.fitface.studio.core.model.PreviewFrame

/** Font size, colour and strings are from the package; Android substitutes the ROM glyphs. */
internal object WidgetTextRasterizer {
    fun render(text: WidgetText, width: Int, height: Int): PreviewFrame {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            // Left transparent at every angle: the rotated Comp canvas is RGB565 + alpha,
            // cleared to opacity 0 before each draw, so only the glyphs cover the face.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = text.color
                textSize = text.font.pixelSize.toFloat()
                letterSpacing = text.letterSpacing / textSize
                typeface = when (text.font.family) {
                    2 -> Typeface.create("sans-serif", Typeface.BOLD)
                    3 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    15 -> Typeface.MONOSPACE
                    else -> Typeface.create("sans-serif", Typeface.NORMAL)
                }
            }
            val canvas = Canvas(bitmap)
            var baseline = -paint.fontMetrics.ascent
            // Shape whole runs, preserving kerning and joined scripts. Android spacing is
            // measured in em; the firmware's signed spacing above is measured in pixels.
            for (line in text.text.split('\n')) {
                val lineWidth = paint.measureText(line)
                val left = when (text.alignment) {
                    1 -> (width - lineWidth) / 2f
                    2 -> width - lineWidth
                    else -> 0f
                }
                canvas.drawText(line, left, baseline, paint)
                baseline += paint.fontSpacing
            }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return PreviewFrame(width, height, pixels)
        } finally {
            bitmap.recycle()
        }
    }
}
