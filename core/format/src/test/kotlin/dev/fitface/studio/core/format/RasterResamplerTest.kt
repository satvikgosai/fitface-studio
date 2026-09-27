package dev.fitface.studio.core.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A resize shrinks by averaging and never aliases, and changes nothing a resize must keep.
 *
 * The defect this answers: shrinking by nearest neighbour kept one source pixel per new
 * pixel and dropped the rest, so a 95% rung deleted a row and a column in twenty and a
 * glyph's curves came back stepped. [aShrinkDropsNoSourceColumn] is that case exactly.
 */
class RasterResamplerTest {
    @Test
    fun anUnchangedSizeReturnsTheSamplesUntouched() {
        listOf(IMAGE_RGB565 to 2, IMAGE_RGB565_ALPHA to 3, IMAGE_INDEXED8 to 1).forEach { (format, bpp) ->
            val samples = ByteArray(7 * 5 * bpp) { (it * 37 + 11).toByte() }
            assertArrayEquals(
                "format 0x${format.toString(16)}",
                samples,
                RasterResampler.resample(samples, format, 7, 5, 7, 5),
            )
        }
    }

    @Test
    fun aFlatColourStaysExactlyThatColourBothWays() {
        // The extremes and a spread between them, including values that only survive the
        // round trip because quantising undoes the decoder's own expansion exactly.
        val colours = listOf(0x0000, 0xFFFF, 0x0821, 0xF81F, 0x07E0, 0x7BEF, 0x1234, 0xA5A5)
        colours.forEach { rgb565 ->
            listOf(20 to 13, 20 to 31, 9 to 9).forEach { (width, height) ->
                val opaque = raster(20, 20) { _, _ -> rgb565 to 0xFF }
                val shrunk = RasterResampler.resample(opaque.first, IMAGE_RGB565, 20, 20, width, height)
                assertTrue("0x${rgb565.toString(16)} → $width×$height", pixels(shrunk, false).all { it == rgb565 to 0xFF })

                val translucent = raster(20, 20, alpha = true) { _, _ -> rgb565 to 0x80 }
                val scaled = RasterResampler.resample(translucent.first, IMAGE_RGB565_ALPHA, 20, 20, width, height)
                assertTrue("0x${rgb565.toString(16)} at half alpha", pixels(scaled, true).all { it == rgb565 to 0x80 })
            }
        }
    }

    @Test
    fun aShrinkDropsNoSourceColumn() {
        // 20 → 19 is the 95% rung. Nearest neighbour reads columns 0..18 and never 19, so a
        // one-pixel line there disappeared from the widget altogether.
        val white = 0xFFFF
        val (samples, _) = raster(20, 4) { x, _ -> (if (x == 19) white else 0) to 0xFF }
        val shrunk = pixels(RasterResampler.resample(samples, IMAGE_RGB565, 20, 4, 19, 4), false)
        val lastColumn = (0 until 4).map { y -> shrunk[y * 19 + 18].first }
        lastColumn.forEach { rgb565 ->
            assertTrue("the line still contributes: 0x${rgb565.toString(16)}", rgb565 != 0)
        }
    }

    @Test
    fun aShrinkKeepsTheAverageBrightness() {
        // A checkerboard is the worst case for dropping pixels: nearest neighbour turns a
        // 2:1 shrink of it into a flat colour — whichever half it happened to keep.
        val (samples, _) = raster(16, 16) { x, y -> (if ((x + y) % 2 == 0) 0xFFFF else 0) to 0xFF }
        val shrunk = pixels(RasterResampler.resample(samples, IMAGE_RGB565, 16, 16, 8, 8), false)
        shrunk.forEach { (rgb565, _) ->
            val green = (rgb565 ushr 5) and 0x3F
            assertTrue("a grey, not black or white: $green of 63", green in 30..33)
        }
    }

    @Test
    fun transparentBlackDoesNotDarkenAnEdge() {
        // Every transparent pixel in the catalogue stores black. Averaged by colour alone,
        // that black bled into the edge beside it; weighted by alpha it contributes nothing.
        val white = 0xFFFF
        val (samples, _) = raster(3, 1, alpha = true) { x, _ -> if (x < 2) white to 0xFF else 0 to 0 }
        val shrunk = pixels(RasterResampler.resample(samples, IMAGE_RGB565_ALPHA, 3, 1, 2, 1), true)
        assertEquals(white to 0xFF, shrunk[0])
        val (edge, alpha) = shrunk[1]
        assertEquals("the edge keeps its colour", white, edge)
        // It covers a third of the last opaque pixel and two thirds of the transparent one.
        assertEquals("and fades by coverage instead", 85, alpha)
    }

    @Test
    fun aFullyTransparentPixelStoresBlack() {
        val (samples, _) = raster(4, 4, alpha = true) { _, _ -> 0xFFFF to 0 }
        val shrunk = pixels(RasterResampler.resample(samples, IMAGE_RGB565_ALPHA, 4, 4, 3, 3), true)
        assertTrue(shrunk.all { it == 0 to 0 })
    }

    @Test
    fun anIndexedRasterOnlyEverCopiesSamples() {
        // Its samples are palette indices, so a mean of two would name a third colour.
        val samples = ByteArray(10 * 10) { if (it % 3 == 0) 7 else 200.toByte() }
        listOf(6 to 6, 15 to 13).forEach { (width, height) ->
            val resized = RasterResampler.resample(samples, IMAGE_INDEXED8, 10, 10, width, height)
            assertTrue(resized.all { it == 7.toByte() || it == 200.toByte() })
        }
    }

    @Test
    fun everyNewPixelsWeightsSumToOne() {
        listOf(20 to 19, 52 to 36, 74 to 52, 7 to 3, 3 to 7, 36 to 52, 128 to 5, 5 to 128, 1 to 4, 4 to 1)
            .forEach { (old, new) ->
                RasterResampler.taps(old, new).forEachIndexed { index, taps ->
                    assertEquals("$old → $new pixel $index", 1f, taps.sumOf { it.second.toDouble() }.toFloat(), 1e-5f)
                    assertTrue(taps.all { it.first in 0 until old })
                }
            }
    }

    @Test
    fun aPayloadThatDoesNotMatchItsSizeIsRefused() {
        assertThrows(Fit3FormatException::class.java) {
            RasterResampler.resample(ByteArray(10), IMAGE_RGB565, 3, 3, 2, 2)
        }
    }

    /** Samples for a [width]×[height] raster whose pixel at (x, y) is `rgb565 to alpha`. */
    private fun raster(
        width: Int,
        height: Int,
        alpha: Boolean = false,
        pixel: (Int, Int) -> Pair<Int, Int>,
    ): Pair<ByteArray, Int> {
        val bpp = if (alpha) 3 else 2
        val out = ByteArray(width * height * bpp)
        for (y in 0 until height) for (x in 0 until width) {
            val (rgb565, a) = pixel(x, y)
            val offset = (y * width + x) * bpp
            out[offset] = rgb565.toByte()
            out[offset + 1] = (rgb565 ushr 8).toByte()
            if (alpha) out[offset + 2] = a.toByte()
        }
        return out to bpp
    }

    private fun pixels(samples: ByteArray, alpha: Boolean): List<Pair<Int, Int>> {
        val bpp = if (alpha) 3 else 2
        return List(samples.size / bpp) { index ->
            val offset = index * bpp
            val rgb565 = (samples[offset].toInt() and 0xFF) or ((samples[offset + 1].toInt() and 0xFF) shl 8)
            rgb565 to if (alpha) samples[offset + 2].toInt() and 0xFF else 0xFF
        }
    }
}
