package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which entry a name is.
 *
 * The number here is the *container's*, counted from zero; the one-based number a reader
 * sees is `:core:ui`'s `styleLabel` and lives nowhere else, so that the editor and the
 * library cannot arrive at different answers for one colourway.
 */
class StyleNumberTest {
    @Test
    fun aStyleEntryCarriesTheContainersOwnNumber() {
        assertEquals(0, EditorVariant.styleNumberOf("style0.bin"))
        assertEquals(10, EditorVariant.styleNumberOf("style10.bin"))
    }

    @Test
    fun theNumberComesFromTheNameRatherThanFromThePosition() {
        // `styleIndex` is a position — it indexes `preview.bin`'s frames and the package's
        // extracted PNGs — so a face that skipped a number would have the two disagree.
        // The catalogue's style id is the name's number, so the name is what is read.
        assertEquals(2, EditorVariant("style2.bin", VariantKind.STYLE, styleIndex = 1).styleNumber)
    }

    @Test
    fun theAlwaysOnDisplayIsNotANumberedStyle() {
        // It is named in words instead. A number here would put it in a series it is not
        // part of: it is not installable and carries no sampler id.
        assertNull(EditorVariant(AOD_ENTRY_NAME, VariantKind.AOD).styleNumber)
    }

    @Test
    fun anEntryThatIsNotANumberedStyleHasNoNumber() {
        assertNull(EditorVariant.styleNumberOf("setting.bin"))
        assertNull(EditorVariant.styleNumberOf("style.bin"))
        assertNull(EditorVariant.styleNumberOf("style0"))
        assertNull(EditorVariant.styleNumberOf("prefix_style0.bin"))
    }
}
