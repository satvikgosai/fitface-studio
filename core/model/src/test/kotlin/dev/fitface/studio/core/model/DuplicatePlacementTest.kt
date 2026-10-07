package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatePlacementTest {
    private fun widget(x: Int, y: Int, width: Int, height: Int, placement: WidgetPlacement = WidgetPlacement.CANVAS) =
        WidgetGuide(
            ordinal = 1, globalIndex = 1, type = 3, sequenceId = 1, x = x, y = y, originX = 0, originY = 0,
            width = width, height = height, recordSize = 40, isFinal = false, canEditPosition = true,
            colorArgb = null, supportMessage = "", placement = placement,
        )

    @Test fun aCopyLandsRightAndBelowItsOriginal() {
        assertEquals(8 to 8, duplicateOffset(widget(20, 40, 60, 30), 256, 402))
    }

    @Test fun nearAnEdgeItMovesTheOtherWayAndWithNoRoomItStays() {
        assertEquals(-8 to 8, duplicateOffset(widget(190, 40, 60, 30), 256, 402))
        assertEquals(8 to -8, duplicateOffset(widget(20, 370, 60, 30), 256, 402))
        assertEquals(0 to 8, duplicateOffset(widget(0, 40, 256, 30), 256, 402))
    }

    /**
     * Duplicating the newest copy again and again walks a chain. Every copy stays inside the
     * visible, rounded face — the chain used to march down into a bottom corner it clips.
     */
    @Test fun aChainOfCopiesStaysInsideTheVisibleFace() {
        for ((x, y, w, h) in listOf(listOf(201, 190, 37, 23), listOf(32, 245, 158, 30), listOf(16, 124, 120, 48),
            listOf(156, 330, 100, 47))) {
            var copy = widget(x, y, w, h)
            repeat(60) { step ->
                val (dx, dy) = duplicateOffset(copy, 256, 402)
                assertTrue("step $step from $x,$y did not move", dx != 0 || dy != 0)
                copy = copy.copy(x = copy.x + dx, y = copy.y + dy)
                assertTrue("step $step: ${copy.x},${copy.y} ${w}x$h left the face",
                    insideRoundedPanel(copy.x, copy.y, w, h, 256, 402))
            }
        }
    }

    /**
     * At the bottom edge the chain used to bounce straight back onto the original — every
     * other copy invisible on top of an earlier one. A free visible spot is taken instead.
     */
    @Test fun aSpotAnotherCopyCoversIsPassedOverWhileAFreeOneExists() {
        val copy = widget(49, 353, 173, 40)
        assertEquals(-8 to 8, duplicateOffset(copy, 256, 402))
        assertEquals(8 to -8, duplicateOffset(copy, 256, 402, occupied = setOf(41 to 361)))
        // With every visible spot taken it still moves rather than refusing.
        val everywhere = listOf(8 to 8, -8 to 8, 8 to -8, -8 to -8, 8 to 0, -8 to 0, 0 to 8, 0 to -8)
            .map { (dx, dy) -> 49 + dx to 353 + dy }.toSet()
        assertEquals(-8 to 8, duplicateOffset(copy, 256, 402, occupied = everywhere))
    }

    /** A vendor's widget already in a corner the face clips still gets a copy beside it. */
    @Test fun aWidgetAlreadyInAClippedCornerFallsBackToThePanelEdges() {
        assertEquals(8 to 8, duplicateOffset(widget(0, 0, 30, 30), 256, 402))
    }

    @Test fun handsAndBackgroundsAreCopiedInPlace() {
        assertEquals(0 to 0, duplicateOffset(widget(120, 80, 16, 120, WidgetPlacement.HIDDEN), 256, 402))
        assertEquals(0 to 0, duplicateOffset(widget(0, 0, 256, 402, WidgetPlacement.BACKGROUND), 256, 402))
    }
}
