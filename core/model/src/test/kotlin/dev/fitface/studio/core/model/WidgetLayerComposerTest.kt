package dev.fitface.studio.core.model

import org.junit.Assert.*
import org.junit.Test

class WidgetLayerComposerTest {
    private fun guide(index: Int = 0, x: Int = 0, y: Int = 0) = WidgetGuide(
        ordinal = index, globalIndex = index, type = 1, sequenceId = 0,
        x = x, y = y, width = 1, height = 1, recordSize = 40, isFinal = false,
        canEditPosition = true, colorArgb = null, supportMessage = "test",
    )
    private fun layer(index: Int = 0, color: Int = -65536) =
        WidgetImageLayer(index, PreviewFrame(1, 1, intArrayOf(color)))

    @Test fun movingAndRemovingNeverLeavesPixelsAtTheOldPosition() {
        val guides = listOf(guide(x = 2).copy(originalX = 0))
        val frame = WidgetLayerComposer.compose(4, 1, listOf(layer()), guides)
        assertEquals(-16777216, frame.argb[0])
        assertEquals(-65536, frame.argb[2])
        assertTrue(WidgetLayerComposer.compose(4, 1, emptyList(), guides).argb.all { it == -16777216 })
    }

    @Test fun originAndLayerOverhangAreAppliedExactlyOnce() {
        val guides = listOf(guide(x = -1).copy(originX = 3))
        val frame = WidgetLayerComposer.compose(4, 1, listOf(layer().copy(offsetX = -1)), guides)
        assertArrayEquals(intArrayOf(-16777216, -65536, -16777216, -16777216), frame.argb)
    }

    @Test fun clippingDoesNotStretchOrShiftTheRemainingArtwork() {
        val image = WidgetImageLayer(0, PreviewFrame(3, 1, intArrayOf(-65536, -16711936, -16776961)))
        val frame = WidgetLayerComposer.compose(2, 1, listOf(image), listOf(guide(x = -1)))
        assertArrayEquals(intArrayOf(-16711936, -16776961), frame.argb)
    }

    @Test fun listOrderNotGlobalIndexDeterminesWhichWidgetIsOnTop() {
        val frame = WidgetLayerComposer.compose(1, 1, listOf(layer(8), layer(2, -16776961)),
            listOf(guide(8), guide(2)))
        assertEquals(-16776961, frame.argb[0])
    }

    @Test fun alphaIsPreservedOnTheTransparentOverlayAndBlendsWithLowerWidgets() {
        assertEquals(0x80FF0000.toInt(), WidgetLayerComposer.over(0, 0x80FF0000.toInt()))
        assertEquals(0xC05500AA.toInt(), WidgetLayerComposer.over(0x80FF0000.toInt(), 0x800000FF.toInt()))
        assertEquals(0xFF80007F.toInt(), WidgetLayerComposer.over(0xFF0000FF.toInt(), 0x80FF0000.toInt()))
    }

    @Test fun rgb565LayersPaintTheirWholeRectangleEvenIfAnArgbArrayHasNoAlpha() {
        val frame = WidgetLayerComposer.compose(1, 1, listOf(layer(color = 0x123456).copy(isOpaque = true)),
            listOf(guide()))
        assertEquals(0xFF123456.toInt(), frame.argb[0])
    }
}
