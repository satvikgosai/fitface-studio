package dev.fitface.studio.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Arrange layers' four tiles sit in one row while the widest word fits each, and fall back to
 * two rows of two rather than squeezing or wrapping a word.
 */
class ArrangeTilesTest {
    @Test
    fun fourTilesFitAcrossAPhoneAtNormalText() {
        // 300 px across with 8 px gaps leaves 69 px a tile; "Backward" at 55 px plus 8 px fits.
        assertEquals(4, arrangeTilesPerRow(availablePx = 300, widestWordPx = 55, tilePaddingPx = 8, gapPx = 8))
    }

    @Test
    fun aWordThatJustFitsKeepsFourAcross() {
        assertEquals(4, arrangeTilesPerRow(availablePx = 300, widestWordPx = 61, tilePaddingPx = 8, gapPx = 8))
    }

    @Test
    fun largeTextFallsBackToTwoAcross() {
        assertEquals(2, arrangeTilesPerRow(availablePx = 300, widestWordPx = 62, tilePaddingPx = 8, gapPx = 8))
        assertEquals(2, arrangeTilesPerRow(availablePx = 300, widestWordPx = 110, tilePaddingPx = 8, gapPx = 8))
    }
}
