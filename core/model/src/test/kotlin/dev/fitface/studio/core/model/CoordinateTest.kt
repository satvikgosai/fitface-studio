package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateTest {
    @Test
    fun endAnchoredCoordinatesRoundTrip() {
        val encoded = -12
        val extent = 30
        val canvas = 256
        val display = displayCoordinate(encoded, extent, canvas)

        assertEquals(214, display)
        assertEquals(
            encoded,
            encodeCoordinate(display, extent, canvas, anchoredFromEnd = true),
        )
    }

    @Test
    fun startAnchoredCoordinatesRoundTrip() {
        assertEquals(
            42,
            encodeCoordinate(
                displayCoordinate(42, extent = 20, canvasExtent = 256),
                extent = 20,
                canvasExtent = 256,
                anchoredFromEnd = false,
            ),
        )
    }
}
