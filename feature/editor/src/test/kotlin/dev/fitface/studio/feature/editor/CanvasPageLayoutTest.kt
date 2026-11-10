package dev.fitface.studio.feature.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canvas page stacks the face over its controls, and stacked it is only as tall as
 * they leave it. A landscape phone leaves the three of them about 340dp between the
 * header and the screen edge, so the face came out a third of its portrait size — and
 * tapping a widget, which is what adds the nudge panel, shrank it to a dot.
 *
 * The sizes below are the boxes real windows hand the page: `:feature:editor` cannot run
 * Robolectric (its merged manifest declares a receiver from the accessory SDK JAR, whose
 * pre-stackmap bytecode fails the JVM verifier), so the decision is pinned here rather
 * than by measuring the composable.
 */
class CanvasPageLayoutTest {
    @Test
    fun aLandscapePhoneSplitsTheFaceFromItsControls() {
        // 914x411dp window, less the 96dp rail and the ~64dp header.
        assertTrue(canvasPageSplits(maxWidth = 818.dp, maxHeight = 347.dp))
    }

    @Test
    fun aPortraitPhoneStacksThem() {
        // 411x914dp window, less the header and the horizontal rail.
        assertFalse(canvasPageSplits(maxWidth = 411.dp, maxHeight = 792.dp))
        // The narrowest phone this app runs on, at the largest font scale.
        assertFalse(canvasPageSplits(maxWidth = 320.dp, maxHeight = 500.dp))
    }

    @Test
    fun aTabletStacksThemToo() {
        // Landscape 1280x800dp: wide, but with height to spare, and the stacked layout
        // centres the face in it. Splitting on width alone would have moved it off centre
        // on every large screen.
        assertFalse(canvasPageSplits(maxWidth = 1184.dp, maxHeight = 736.dp))
    }

    @Test
    fun aShortWindowWithNoWidthToSplitStaysStacked() {
        // A freeform or split-screen window can be short *and* narrow. Two columns of
        // 200dp hold neither the face nor the nudge row, so it keeps stacking: the face
        // is squeezed, but every control stays where it is and stays reachable.
        assertFalse(canvasPageSplits(maxWidth = 400.dp, maxHeight = 300.dp))
    }

    // -- Pages that adjust the face keep it pinned while their controls scroll ----------

    private val panel = 256f / 402f

    @Test
    fun aPortraitPhonePinsTheFaceAtItsCap() {
        // 411x914dp window, less the header and the horizontal rail: room for the full face.
        assertEquals(186.dp, pinnedFaceWidth(379.dp, 792.dp, 186.dp, panel))
        assertEquals(140.dp, pinnedFaceWidth(379.dp, 792.dp, 140.dp, panel))
    }

    @Test
    fun aPinnedFaceLeavesMostOfTheHeightToTheControls() {
        // A small phone at a large font scale: the face shrinks to under half the height.
        val width = requireNotNull(pinnedFaceWidth(288.dp, 500.dp, 186.dp, panel, extra = 48.dp))
        assertTrue(width < 186.dp)
        assertTrue(width / panel + 48.dp <= 500.dp * 0.45f + 1.dp)
    }

    @Test
    fun aShortWindowScrollsTheWholePage() {
        // Landscape phone: pinning would leave the controls a sliver, so nothing is pinned.
        assertNull(pinnedFaceWidth(690.dp, 347.dp, 186.dp, panel))
    }
}
