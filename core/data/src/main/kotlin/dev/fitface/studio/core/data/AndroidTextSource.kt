package dev.fitface.studio.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dev.fitface.studio.core.format.ClockDigits
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.TextFont
import dev.fitface.studio.core.model.TextPicture
import dev.fitface.studio.core.model.WatchFaceException
import java.io.File
import java.util.UUID
import kotlin.math.ceil

/**
 * Text drawn as a picture by the phone's own text renderer, white on clear and cropped to
 * its ink: the watch is sent these pixels, never a font.
 *
 * A phone font is resolved by family name. A picked font file is copied to private cache,
 * loaded and deleted straight away — the loaded font maps its own copy — and the last few
 * are kept loaded while the process lives, so redrawing at every size step reads nothing.
 */
internal class AndroidTextSource(private val context: Context) {
    sealed interface Drawing {
        class Drawn(val frame: PreviewFrame) : Drawing

        /** Nothing but spaces: no pixel was drawn. */
        data object Empty : Drawing

        /** Wider or taller than [render] was allowed. */
        data object TooBig : Drawing
    }

    private val loaded = object : LinkedHashMap<String, Typeface>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>) = size > KEPT_FONTS
    }

    @Synchronized
    fun typeface(font: TextFont): Typeface = when (font) {
        is TextFont.Phone -> Typeface.create(font.font.family, if (font.font.bold) Typeface.BOLD else Typeface.NORMAL)
        is TextFont.File -> loaded.getOrPut(font.uri) { load(font.uri) }
    }

    /** Loads the font file at [uri], keeping it loaded, and names it by its file name. */
    @Synchronized
    fun readFont(uri: String): TextFont.File {
        loaded[uri] = load(uri)
        return TextFont.File(uri, displayName(uri))
    }

    /**
     * [text] in [typeface], at most [maxWidth] × [maxHeight] once cropped to its ink.
     *
     * Each line is centred on the widest. The canvas leaves a whole em around the lines, since
     * a script font's swashes and a tall accent reach outside the advance and the line box.
     */
    fun render(text: TextPicture, typeface: Typeface, maxWidth: Int, maxHeight: Int): Drawing {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = text.sizePx.toFloat()
            this.typeface = typeface
        }
        val lines = text.lines
        if (lines.isEmpty()) return Drawing.Empty
        val advances = lines.map(paint::measureText)
        val widest = advances.max()
        val spacing = paint.fontSpacing
        // Refused before anything is allocated: 80 characters at 200 px would be a canvas
        // sixteen thousand pixels wide. Spaces have advance but no ink, so the bound is loose.
        if (widest > maxWidth * 2 || spacing * lines.size > maxHeight * 2) return Drawing.TooBig
        val pad = text.sizePx
        val width = ceil(widest).toInt() + 2 * pad
        val height = ceil(spacing * lines.size).toInt() + 2 * pad
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = try {
            val canvas = Canvas(bitmap)
            var baseline = pad - paint.fontMetrics.ascent
            lines.forEachIndexed { index, line ->
                canvas.drawText(line, pad + (widest - advances[index]) / 2f, baseline, paint)
                baseline += spacing
            }
            IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        } finally {
            bitmap.recycle()
        }
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (pixels[y * width + x] ushr 24 == 0) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        if (right < 0) return Drawing.Empty
        val inkWidth = right - left + 1
        val inkHeight = bottom - top + 1
        if (inkWidth > maxWidth || inkHeight > maxHeight) return Drawing.TooBig
        val ink = IntArray(inkWidth * inkHeight)
        for (y in 0 until inkHeight) System.arraycopy(pixels, (top + y) * width + left, ink, y * inkWidth, inkWidth)
        return Drawing.Drawn(PreviewFrame(inkWidth, inkHeight, ink))
    }

    /** Glyph coverage, one byte a pixel a frame, and the text size it was drawn at. */
    class Glyphs(val coverage: List<ByteArray>, val textSize: Float)

    /**
     * [texts] as one byte of coverage a pixel, each in its own [width] × [height] frame, at one
     * size and on one baseline — a clock's digits drawn again ([ClockDigits]). Null when none
     * of them inks a pixel.
     *
     * They fill the box [look] says the face's own glyphs inked: together exactly as tall,
     * centred on it, and each glyph centred across it, so a narrow 1 sits where the old one did.
     * The set is made smaller when its widest glyph, or its height, would not fit the frame.
     * Given a [textSize] — the digits', for their colon — they are drawn at it instead, still
     * centred on [look] and still made smaller only to fit. Ink is measured on the glyphs'
     * outlines at a large size, so rounding a small one's bounds to whole pixels does not shift
     * digits against each other.
     */
    fun glyphs(texts: List<String>, typeface: Typeface, width: Int, height: Int, look: ClockDigits.Look,
        textSize: Float? = null): Glyphs? {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = REFERENCE_PX
            this.typeface = typeface
        }
        val bounds = texts.map { text ->
            RectF().also { Path().apply { paint.getTextPath(text, 0, text.length, 0f, 0f, this) }.computeBounds(it, true) }
        }
        val inked = bounds.filter { it.width() > 0f && it.height() > 0f }
        if (inked.isEmpty()) return null
        val top = inked.minOf { it.top }
        val bottom = inked.maxOf { it.bottom }
        val fits = minOf((width - 2f) / inked.maxOf { it.width() }, (height - 2f) / (bottom - top))
        val scale = minOf(textSize?.div(REFERENCE_PX) ?: ((look.bottom - look.top) / (bottom - top)), fits)
        val centreX = (look.left + look.right) / 2f
        val centreY = (look.top + look.bottom) / 2f
        paint.textSize = REFERENCE_PX * scale
        val baseline = centreY - (top + bottom) / 2f * scale
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val pixels = IntArray(width * height)
            return Glyphs(texts.mapIndexed { index, text ->
                bitmap.eraseColor(Color.TRANSPARENT)
                val ink = bounds[index]
                canvas.drawText(text, centreX - (ink.left + ink.right) / 2f * scale, baseline, paint)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }
            }, paint.textSize)
        } finally {
            bitmap.recycle()
        }
    }

    private fun load(uri: String): Typeface {
        val scratch = File(context.cacheDir, "font-${UUID.randomUUID()}")
        try {
            val input = try {
                context.contentResolver.openInputStream(Uri.parse(uri))
            } catch (error: Exception) {
                throw WatchFaceException(UNREADABLE, "font: open failed", error)
            } ?: throw WatchFaceException(UNREADABLE, "font: no stream")
            input.use { source ->
                scratch.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FONT_BYTES) {
                            throw WatchFaceException("This font file is too large. Choose one under 32 MB.", "font: over $MAX_FONT_BYTES bytes")
                        }
                        target.write(buffer, 0, read)
                    }
                }
            }
            return Typeface.Builder(scratch).build()
                ?: throw WatchFaceException(NOT_A_FONT, "font: not loaded")
        } catch (error: WatchFaceException) {
            throw error
        } catch (error: Exception) {
            throw WatchFaceException(NOT_A_FONT, "font: ${error.javaClass.simpleName}", error)
        } finally {
            scratch.delete()
        }
    }

    private fun displayName(uri: String): String {
        val parsed = Uri.parse(uri)
        val named = runCatching {
            context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return named?.takeIf { it.isNotBlank() } ?: parsed.lastPathSegment?.substringAfterLast('/') ?: "Font file"
    }

    private companion object {
        const val KEPT_FONTS = 4
        /** The size glyph outlines are measured at before they are scaled to their box. */
        const val REFERENCE_PX = 400f
        const val MAX_FONT_BYTES = 32L * 1024 * 1024
        const val UNREADABLE = "This font file could not be opened. Choose it again."
        const val NOT_A_FONT = "This file is not a font this phone can read. Choose a TTF or OTF file."
    }
}
