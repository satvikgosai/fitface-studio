package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetColorTest {
    @Test fun hexIsSixDigitsWithoutAlpha() {
        assertEquals("#75D8D6", colorHex(0xFF75D8D6.toInt()))
        assertEquals("#000000", colorHex(0x00000000))
        assertEquals("#00FF00", colorHex(0x8000FF00.toInt()))
    }

    @Test fun parsingAcceptsWhatPeopleTypeAndNothingElse() {
        assertEquals(0xFFFF8800.toInt(), parseColorHex("#FF8800"))
        assertEquals(0xFFFF8800.toInt(), parseColorHex("ff8800"))
        assertEquals(0xFFFF8800.toInt(), parseColorHex("  #ff8800 "))
        for (bad in listOf("", "#", "#FFF", "#FF88001", "#GG8800", "FF 880", "##FF8800", "0xFF8800")) {
            assertNull(bad, parseColorHex(bad))
        }
    }

    /** Every 8-bit channel value survives a trip through hue, saturation and brightness. */
    @Test fun hsvRoundTripsEveryChannelLevel() {
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            assertEquals(colorHex(color), colorHex(colorOf(hsvOf(color))))
        }
        for (level in 0..255) {
            val grey = (0xFF shl 24) or (level shl 16) or (level shl 8) or level
            assertEquals(grey, colorOf(hsvOf(grey)))
        }
    }

    @Test fun hsvReadsThePrimariesAndClampsWhatItIsGiven() {
        assertEquals(Hsv(0f, 1f, 1f), hsvOf(0xFFFF0000.toInt()))
        assertEquals(Hsv(120f, 1f, 1f), hsvOf(0xFF00FF00.toInt()))
        assertEquals(Hsv(240f, 1f, 1f), hsvOf(0xFF0000FF.toInt()))
        assertEquals(0xFFFF0000.toInt(), colorOf(Hsv(360f, 1f, 1f)))
        assertEquals(0xFFFFFFFF.toInt(), colorOf(Hsv(42f, -1f, 2f)))
        assertEquals(0xFF000000.toInt(), colorOf(Hsv(42f, 1f, 0f)))
    }
}
