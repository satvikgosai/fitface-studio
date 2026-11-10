package dev.fitface.studio.feature.editor

import dev.fitface.studio.core.model.Hsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Colour page's hex field and the slider pop-up's hue, which a grey has none of. */
class QuickColourTest {
    private val teal = 0xFF75D8D6.toInt()

    @Test
    fun applyTakesAValidHexThatChangesTheColour() {
        assertEquals(0xFFFF8800.toInt(), applicableHex("#FF8800", teal))
        assertEquals(0xFFFF8800.toInt(), applicableHex("ff8800", teal))
    }

    @Test
    fun applyIsOffForAnInvalidHexOrTheColourAlreadyThere() {
        assertNull(applicableHex("#FF88", teal))
        assertNull(applicableHex("orange", teal))
        assertNull(applicableHex("#75D8D6", teal))
        assertNull("a widget without a colour", applicableHex("#FF8800", null))
    }

    @Test
    fun aGreyKeepsTheHueTheSlidersHad() {
        val green = Hsv(120f, 1f, 1f)
        assertEquals(Hsv(120f, 0f, 1f), followHue(Hsv(0f, 0f, 1f), green))
        assertEquals(Hsv(120f, 1f, 0f), followHue(Hsv(0f, 1f, 0f), green))
        assertEquals(Hsv(200f, .5f, .5f), followHue(Hsv(200f, .5f, .5f), green))
    }
}
