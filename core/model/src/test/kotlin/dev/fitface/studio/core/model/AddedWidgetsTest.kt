package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sizes a new picture is kept at and offered at, its cost, and the colour rule. */
class AddedWidgetsTest {
    @Test
    fun aLargePictureIsFittedWithinThePanelAndASmallOneKeptWhole() {
        assertEquals(256 to 205, pictureBaselineSize(1000, 800, 256, 402))
        assertEquals("a tall photo is limited by height", 255 to 401, pictureBaselineSize(3000, 4720, 256, 402))
        assertEquals(32 to 32, pictureBaselineSize(32, 32, 256, 402))
    }

    /** Covering the panel both ways would make a picture a background layer. */
    @Test
    fun noPictureIsEverThePanelsFullSize() {
        listOf(256 to 402, 512 to 804, 2560 to 4020, 256 to 403).forEach { (w, h) ->
            val (width, height) = pictureBaselineSize(w, h, 256, 402)
            assertTrue("$w×$h became $width×$height", width < 256 || height < 402)
        }
    }

    /** White takes the colour, black keeps its own, alpha is untouched: one rule for both pages. */
    @Test
    fun aTintRecoloursByBrightnessAndKeepsAlphaAndBlack() {
        val red = 0xFFFF0000.toInt()
        assertEquals(red, tintedColor(0xFFFFFFFF.toInt(), red))
        assertEquals(0x80FF0000.toInt(), tintedColor(0x80FFFFFF.toInt(), red))
        assertEquals(0xFF000000.toInt(), tintedColor(0xFF000000.toInt(), red))
        val grey = tintedColor(0xFF808080.toInt(), 0xFF00FF00.toInt())
        assertEquals("mid grey is half the tint", 0x80, grey ushr 8 and 0xFF)
        assertEquals(0, grey ushr 16 and 0xFF)
        assertEquals("record, header, three bytes a pixel, trailer", 40 + 12 + 300 + 4, pictureAddedBytes(10, 10, opaque = false))
        assertEquals(40 + 12 + 200 + 4, pictureAddedBytes(10, 10, opaque = true))
    }

    @Test
    fun presetsAreRungsOfTheLadderAndEachSizeIsOfferedOnce() {
        val photo = pictureSizes(256, 205, 256, 402, kept = false)
        assertEquals(listOf(PictureSizePreset.SMALL, PictureSizePreset.MEDIUM, PictureSizePreset.LARGE,
            PictureSizePreset.FULL), photo.map { it.preset })
        val ladder = widgetResizeLadder(256, 205)
        photo.forEach { size ->
            assertTrue("$size is not a rung", ladder.any { it.width == size.width && it.height == size.height &&
                it.percentOfOriginal == size.percent })
        }
        assertEquals(256 to 205, photo.last().width to photo.last().height)
        assertEquals(PictureSizePreset.MEDIUM, PreparedPicture("h", 256, 205, false, photo, PreviewFrame(1, 1, intArrayOf(0))).defaultSize?.preset)

        val icon = pictureSizes(32, 32, 256, 402, kept = true)
        assertEquals(listOf(PictureSizePreset.ORIGINAL to 32, PictureSizePreset.SMALL to 64),
            icon.map { it.preset to it.width })
        assertEquals(PictureSizePreset.ORIGINAL, PreparedPicture("h", 32, 32, false, icon, PreviewFrame(1, 1, intArrayOf(0))).defaultSize?.preset)
        // Within 128 px already, so not resampled to Medium's 126 × 95.
        val small = pictureSizes(120, 90, 256, 402, kept = true)
        assertEquals(PictureSizePreset.ORIGINAL, PreparedPicture("h", 120, 90, false, small, PreviewFrame(1, 1, intArrayOf(0))).defaultSize?.preset)
        // Kept whole but larger than 128 px: Medium.
        val mid = pictureSizes(200, 150, 256, 402, kept = true)
        assertEquals(PictureSizePreset.MEDIUM, PreparedPicture("h", 200, 150, false, mid, PreviewFrame(1, 1, intArrayOf(0))).defaultSize?.preset)
    }
}
