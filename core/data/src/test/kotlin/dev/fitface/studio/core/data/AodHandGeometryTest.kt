package dev.fitface.studio.core.data

import dev.fitface.studio.core.model.PreviewFrame
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a rotated hand's pixels land.
 *
 * The pivot at `+0x20`/`+0x22` is a point *inside the raster*, and `+0x18`/`+0x1A` — what
 * `drawLeft`/`drawTop` resolve — positions the raster's top-left. So the pivot's own place
 * on the panel is `left + pivot`, and it is the one point rotation leaves alone. An
 * earlier pass placed the pivot *at* `left, top` instead, which slid every hand off the
 * dial by the pivot's own offset — a hand pivoting at its base by most of its length.
 * Nothing else covers this: a Hand has no drawable rectangle, so the canvas census and
 * the widget-placement tests have nothing to say about it.
 *
 * Three pixels in a row are enough to pin all of it: which point stays put, which way the
 * rotation goes, and that the result has no gaps.
 */
class AodHandGeometryTest {
    private val left = 2
    private val top = 2

    /** A 3×1 strip whose middle pixel is the pivot: left, centre, right. */
    private val strip = PreviewFrame(3, 1, intArrayOf(LEFT_END, CENTRE, RIGHT_END))

    @Test
    fun unrotatedTheStripSitsWithItsTopLeftAtTheDrawPosition() {
        val canvas = blank()

        rotate(canvas, degrees = 0.0)

        // Not `left - pivotX`: the position is the artwork's own top-left.
        assertEquals("left end", LEFT_END, canvas.at(2, 2))
        assertEquals("centre", CENTRE, canvas.at(3, 2))
        assertEquals("right end", RIGHT_END, canvas.at(4, 2))
    }

    @Test
    fun theStripTurnsAboutItsPivotAndTheOtherPixelsSweepClockwise() {
        val canvas = blank()

        rotate(canvas, degrees = 90.0)

        // The pivot is the fixed point, and it is at `left + pivotX, top + pivotY`.
        assertEquals("centre moved", CENTRE, canvas.at(3, 2))
        // Clockwise from the reader's side: what pointed right now points down.
        assertEquals("right end did not sweep to the bottom", RIGHT_END, canvas.at(3, 3))
        assertEquals("left end did not sweep to the top", LEFT_END, canvas.at(3, 1))
        // And it left where it came from.
        assertEquals("the unrotated row was left behind", 0, canvas.at(2, 2))
        assertEquals("the unrotated row was left behind", 0, canvas.at(4, 2))
    }

    /** Half a turn, so the ends swap and the pivot still does not move. */
    @Test
    fun aHalfTurnReversesTheStripInPlace() {
        val canvas = blank()

        rotate(canvas, degrees = 180.0)

        assertEquals(RIGHT_END, canvas.at(2, 2))
        assertEquals(CENTRE, canvas.at(3, 2))
        assertEquals(LEFT_END, canvas.at(4, 2))
    }

    /**
     * Inverse-mapped from the destination, so a diagonal cannot leave holes the way a
     * forward per-pixel rotation does. At 45° the three pixels still occupy three cells.
     */
    @Test
    fun aDiagonalTurnDrawsEveryPixelOfTheStrip() {
        val canvas = blank()

        rotate(canvas, degrees = 45.0)

        assertEquals(
            "a rotated strip lost or duplicated pixels",
            3,
            canvas.count { it != 0 },
        )
        assertEquals("centre moved", CENTRE, canvas.at(3, 2))
    }

    private fun blank() = IntArray(CANVAS * CANVAS)

    private fun rotate(canvas: IntArray, degrees: Double) {
        AodPreviewComposer.drawRotatedRaster(
            canvas = canvas,
            canvasWidth = CANVAS,
            canvasHeight = CANVAS,
            raster = strip,
            forceOpaque = false,
            pivotX = 1,
            pivotY = 0,
            left = left,
            top = top,
            angleDegrees = degrees,
        )
    }

    private fun IntArray.at(x: Int, y: Int): Int = this[y * CANVAS + x]

    private companion object {
        const val CANVAS = 5
        const val LEFT_END = 0xFFFF0000.toInt()
        const val CENTRE = 0xFF00FF00.toInt()
        const val RIGHT_END = 0xFF0000FF.toInt()
    }
}
