package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateTest {
    private fun guide(
        x: Int,
        y: Int,
        originX: Int = 0,
        originY: Int = 0,
        width: Int = 30,
        height: Int = 20,
        drawOffsetX: Int = 0,
        drawOffsetY: Int = 0,
    ) = WidgetGuide(
        ordinal = 0,
        globalIndex = 0,
        type = 1,
        sequenceId = 0,
        x = x,
        y = y,
        width = width,
        height = height,
        originX = originX,
        originY = originY,
        drawOffsetX = drawOffsetX,
        drawOffsetY = drawOffsetY,
        recordSize = 40,
        isFinal = false,
        canEditPosition = true,
        colorArgb = null,
        supportMessage = "",
    )

    /**
     * A widget aligned to the right edge of something stores a negative offset, and its
     * drawn position is that offset from the edge it is measured from.
     */
    @Test
    fun anOffsetFromAnEdgeIsMeasuredFromThatEdge() {
        val widget = guide(x = -12, y = 8, originX = 226, originY = 0)

        assertEquals(214, widget.drawLeft)
        assertEquals(8, widget.drawTop)
        assertEquals(-12, widget.drawLeft - widget.originX)
    }

    /**
     * The whole point of carrying the origin rather than inferring it: a negative
     * coordinate on a widget measured from the panel is simply a widget clipped at the
     * left edge, and the sign rule used to throw it across the face instead.
     */
    @Test
    fun aNegativeCoordinateOnAnUnalignedWidgetStaysNegative() {
        val widget = guide(x = -2, y = 206)

        assertEquals(-2, widget.drawLeft)
        assertEquals(206, widget.drawTop)
    }

    /** Display and stored are inverses through the origin, for every widget. */
    @Test
    fun displayAndStoredRoundTrip() {
        listOf(guide(x = 42, y = 7), guide(x = -4, y = 47, originX = 170)).forEach { widget ->
            assertEquals(widget.x, widget.drawLeft - widget.originX - widget.drawOffsetX)
            assertEquals(widget.y, widget.drawTop - widget.originY - widget.drawOffsetY)
        }
    }

    /** A Rule stored far-end-first draws a whole span earlier, and still round-trips. */
    @Test
    fun aFarEndRuleKeepsItsStoredCoordinate() {
        val widget = guide(x = 242, y = 256, width = 228, drawOffsetX = -228)

        assertEquals(14, widget.drawLeft)
        assertEquals(242, widget.drawLeft - widget.originX - widget.drawOffsetX)
    }
}
