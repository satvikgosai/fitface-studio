package dev.fitface.studio.core.format

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Resamples one raster's pixel samples to a new size, for a widget resize.
 *
 * Shrinking **averages**: each new pixel is the area-weighted mean of the source pixels its
 * footprint covers. Enlarging interpolates between the two nearest source pixels on each
 * axis. Nearest neighbour was used for both until a shrunk glyph came back with jagged
 * edges: it keeps one source pixel in each footprint and drops the rest, so a 95% rung
 * deletes one row and one column in twenty and every curve and anti-aliased edge steps
 * where it lost them.
 *
 * Two rules keep the result a picture the watch draws the way the face drew it.
 *
 * * **Colour is weighted by alpha.** Every fully transparent pixel in the catalogue stores
 *   black (none of 15 million is anything else) and edge pixels store straight colour, so
 *   averaging colour on its own pulls that black into every edge as a dark fringe. The
 *   average is taken premultiplied and divided back out; a pixel left fully transparent
 *   stores black, like the rest.
 * * **Only a palette raster keeps nearest neighbour.** An `IMAGE_INDEXED8` sample is a
 *   palette *index*, so an average would name a colour the palette does not hold; copying
 *   whole samples is exact and leaves the palette untouched. RGB565 has no palette — any
 *   averaged colour rounds to a valid 5/6/5 value — so nothing obliges it to alias.
 *
 * An unchanged size returns the samples untouched, which is what keeps a widget restored to
 * the size it shipped at byte-identical to what the face shipped.
 */
internal object RasterResampler {
    fun resample(
        samples: ByteArray,
        format: Int,
        oldWidth: Int,
        oldHeight: Int,
        newWidth: Int,
        newHeight: Int,
    ): ByteArray {
        val bytesPerPixel = when (format) {
            IMAGE_RGB565 -> 2
            IMAGE_RGB565_ALPHA -> 3
            IMAGE_INDEXED8 -> 1
            else -> throw Fit3FormatException("unsupported image format 0x${format.toString(16)}")
        }
        if (oldWidth <= 0 || oldHeight <= 0 || newWidth <= 0 || newHeight <= 0) {
            throw Fit3FormatException("raster dimensions must be positive")
        }
        if (samples.size != oldWidth * oldHeight * bytesPerPixel) {
            throw Fit3FormatException("raster payload does not match its dimensions")
        }
        if (oldWidth == newWidth && oldHeight == newHeight) return samples.copyOf()
        if (format == IMAGE_INDEXED8) {
            return nearest(samples, bytesPerPixel, oldWidth, oldHeight, newWidth, newHeight)
        }
        val hasAlpha = format == IMAGE_RGB565_ALPHA
        val source = premultiplied(samples, hasAlpha, oldWidth * oldHeight)
        val rows = filterRows(source, oldWidth, oldHeight, taps(oldWidth, newWidth))
        val filtered = filterColumns(rows, newWidth, taps(oldHeight, newHeight), newHeight)
        return encode(filtered, hasAlpha, newWidth * newHeight)
    }

    /** A turned frame: its samples, and the format they are in. */
    class Turned(val format: Int, val samples: ByteArray)

    /**
     * [samples] turned clockwise by [tenths] about their centre into a
     * `canvasWidth × canvasHeight` image, for turning a widget's artwork.
     *
     * Quarter turns into a canvas of the swapped size are pixel permutations, exact,
     * reversible and in the [format] they came in — an opaque RGB565 picture stays opaque
     * and the same size. Any other angle uncovers corners outside the picture, which must
     * be transparent, so the result is always RGB565+A: an opaque source keeps alpha 255
     * over its whole rectangle, which therefore turns with it, and only the corners are
     * clear. That is the opaque picture drawn as before, tilted — the watch reads each
     * image's format from its own header, and face `00046` ships one Static as RGB565 in
     * three styles and RGB565+A in the fourth.
     *
     * Sampling is bilinear, premultiplied by alpha for the reason [resample] is, so turned
     * edges fade into the corners instead of picking up the black transparent pixels store.
     * The caller scales first with [resample]: turning never shrinks, so a turned glyph
     * keeps the area-averaged quality a resize gives it.
     */
    fun turn(
        samples: ByteArray,
        format: Int,
        width: Int,
        height: Int,
        tenths: Int,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Turned {
        val bytesPerPixel = when (format) {
            IMAGE_RGB565 -> 2
            IMAGE_RGB565_ALPHA -> 3
            else -> throw Fit3FormatException("unsupported image format 0x${format.toString(16)}")
        }
        if (width <= 0 || height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            throw Fit3FormatException("raster dimensions must be positive")
        }
        if (samples.size != width * height * bytesPerPixel) {
            throw Fit3FormatException("raster payload does not match its dimensions")
        }
        val angle = ((tenths % 3600) + 3600) % 3600
        val swapped = angle == 900 || angle == 2700
        val fits = if (swapped) canvasWidth == height && canvasHeight == width
        else canvasWidth == width && canvasHeight == height
        if (angle % 900 == 0 && fits) {
            val out = ByteArray(canvasWidth * canvasHeight * bytesPerPixel)
            for (y in 0 until canvasHeight) for (x in 0 until canvasWidth) {
                val (sx, sy) = when (angle) {
                    0 -> x to y
                    900 -> y to height - 1 - x
                    1800 -> width - 1 - x to height - 1 - y
                    else -> width - 1 - y to x
                }
                val from = (sy * width + sx) * bytesPerPixel
                samples.copyInto(out, (y * canvasWidth + x) * bytesPerPixel, from, from + bytesPerPixel)
            }
            return Turned(format, out)
        }
        val source = premultiplied(samples, hasAlpha = format == IMAGE_RGB565_ALPHA, width * height)
        val out = FloatArray(canvasWidth * canvasHeight * 4)
        val radians = Math.toRadians(angle / 10.0)
        val c = kotlin.math.cos(radians); val s = kotlin.math.sin(radians)
        for (y in 0 until canvasHeight) for (x in 0 until canvasWidth) {
            // The output pixel's centre, turned back into the source: the inverse of a
            // clockwise turn on a panel whose y runs down, as `WidgetPreviewComposer` does.
            val dx = x + 0.5 - canvasWidth / 2.0
            val dy = y + 0.5 - canvasHeight / 2.0
            val sx = width / 2.0 + dx * c + dy * s - 0.5
            val sy = height / 2.0 - dx * s + dy * c - 0.5
            val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
            val fx = (sx - x0).toFloat(); val fy = (sy - y0).toFloat()
            val target = (y * canvasWidth + x) * 4
            // The four neighbours, unrolled: this runs per output pixel of every frame.
            for (corner in 0 until 4) {
                val px = x0 + (corner and 1); val py = y0 + (corner shr 1)
                val weight = (if (corner and 1 == 1) fx else 1 - fx) * (if (corner shr 1 == 1) fy else 1 - fy)
                // Outside the source is transparent, which is what fades the edges.
                if (weight == 0f || px !in 0 until width || py !in 0 until height) continue
                val from = (py * width + px) * 4
                for (channel in 0 until 4) out[target + channel] += source[from + channel] * weight
            }
        }
        return Turned(IMAGE_RGB565_ALPHA, encode(out, hasAlpha = true, canvasWidth * canvasHeight))
    }

    /**
     * For each new pixel along one axis, the source pixels it reads and their weights, which
     * sum to one.
     *
     * Shrinking gives new pixel `i` the source interval `[i·old/new, (i+1)·old/new)` and
     * weights each source pixel by how much of it that interval covers. It is computed in
     * units of `1/new`, so the bounds are integers and the coverage is exact rather than
     * rounded to whole pixels. Enlarging places the new pixel's centre in source space and
     * splits it between the two pixels either side, clamped at the edges.
     */
    internal fun taps(old: Int, new: Int): List<List<Pair<Int, Float>>> = List(new) { i ->
        when {
            new == old -> listOf(i to 1f)
            new < old -> {
                val start = i.toLong() * old
                val end = (i + 1).toLong() * old
                val first = (start / new).toInt()
                val last = ((end - 1) / new).toInt()
                (first..last).map { j ->
                    val covered = minOf((j + 1).toLong() * new, end) - maxOf(j.toLong() * new, start)
                    j to covered.toFloat() / old
                }
            }
            else -> {
                val centre = (i + 0.5) * old / new - 0.5
                val left = floor(centre).toInt()
                val fraction = (centre - left).toFloat()
                val a = left.coerceIn(0, old - 1)
                val b = (left + 1).coerceIn(0, old - 1)
                if (a == b) listOf(a to 1f) else listOf(a to 1f - fraction, b to fraction)
            }
        }
    }

    /** Four floats a pixel: red, green and blue multiplied by alpha, then alpha, all 0..255. */
    private fun premultiplied(samples: ByteArray, hasAlpha: Boolean, count: Int): FloatArray {
        val bytesPerPixel = if (hasAlpha) 3 else 2
        val out = FloatArray(count * 4)
        repeat(count) { index ->
            val offset = index * bytesPerPixel
            val rgb565 = (samples[offset].toInt() and 0xFF) or
                ((samples[offset + 1].toInt() and 0xFF) shl 8)
            // The decoder's own expansion, so a flat colour reads back as the value it was.
            val red = (((rgb565 ushr 11) and 0x1F) * 255 + 15) / 31
            val green = (((rgb565 ushr 5) and 0x3F) * 255 + 31) / 63
            val blue = ((rgb565 and 0x1F) * 255 + 15) / 31
            val alpha = if (hasAlpha) samples[offset + 2].toInt() and 0xFF else 0xFF
            val weight = alpha / 255f
            out[index * 4] = red * weight
            out[index * 4 + 1] = green * weight
            out[index * 4 + 2] = blue * weight
            out[index * 4 + 3] = alpha.toFloat()
        }
        return out
    }

    private fun filterRows(
        source: FloatArray,
        width: Int,
        height: Int,
        taps: List<List<Pair<Int, Float>>>,
    ): FloatArray {
        val newWidth = taps.size
        val out = FloatArray(newWidth * height * 4)
        for (y in 0 until height) {
            for (x in 0 until newWidth) {
                val target = (y * newWidth + x) * 4
                for ((sourceX, weight) in taps[x]) {
                    val from = (y * width + sourceX) * 4
                    for (channel in 0 until 4) out[target + channel] += source[from + channel] * weight
                }
            }
        }
        return out
    }

    private fun filterColumns(
        source: FloatArray,
        width: Int,
        taps: List<List<Pair<Int, Float>>>,
        newHeight: Int,
    ): FloatArray {
        val out = FloatArray(width * newHeight * 4)
        for (y in 0 until newHeight) {
            for ((sourceY, weight) in taps[y]) {
                for (x in 0 until width) {
                    val target = (y * width + x) * 4
                    val from = (sourceY * width + x) * 4
                    for (channel in 0 until 4) out[target + channel] += source[from + channel] * weight
                }
            }
        }
        return out
    }

    private fun encode(filtered: FloatArray, hasAlpha: Boolean, count: Int): ByteArray {
        val bytesPerPixel = if (hasAlpha) 3 else 2
        val out = ByteArray(count * bytesPerPixel)
        repeat(count) { index ->
            val alpha = if (hasAlpha) filtered[index * 4 + 3].roundToInt().coerceIn(0, 255) else 0xFF
            val rgb565 = if (alpha == 0) {
                0
            } else {
                // Divided by the unrounded alpha: that is the one the colour was weighted by.
                val weight = filtered[index * 4 + 3] / 255f
                fun channel(offset: Int, levels: Int) =
                    (filtered[index * 4 + offset] / weight * levels / 255f).roundToInt().coerceIn(0, levels)
                (channel(0, 31) shl 11) or (channel(1, 63) shl 5) or channel(2, 31)
            }
            val offset = index * bytesPerPixel
            out[offset] = rgb565.toByte()
            out[offset + 1] = (rgb565 ushr 8).toByte()
            if (hasAlpha) out[offset + 2] = alpha.toByte()
        }
        return out
    }

    private fun nearest(
        source: ByteArray,
        bytesPerPixel: Int,
        oldWidth: Int,
        oldHeight: Int,
        newWidth: Int,
        newHeight: Int,
    ): ByteArray {
        val output = ByteArray(newWidth * newHeight * bytesPerPixel)
        repeat(newHeight) { y ->
            val sourceY = minOf(oldHeight - 1, y * oldHeight / newHeight)
            repeat(newWidth) { x ->
                val sourceX = minOf(oldWidth - 1, x * oldWidth / newWidth)
                val oldOffset = (sourceY * oldWidth + sourceX) * bytesPerPixel
                val newOffset = (y * newWidth + x) * bytesPerPixel
                source.copyInto(output, newOffset, oldOffset, oldOffset + bytesPerPixel)
            }
        }
        return output
    }
}
