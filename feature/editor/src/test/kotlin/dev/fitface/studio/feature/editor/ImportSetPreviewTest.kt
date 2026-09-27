package dev.fitface.studio.feature.editor

import dev.fitface.studio.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture a set's review shows, which is the one picture of that edit there can be
 * before it is committed.
 *
 * A single widget's review is the repository's render of the edited container, because
 * that edit exists. A set's cannot exist yet, so its review composes each pick's own donor
 * layer over the face as it is — exact because the importer copies the rasters byte for
 * byte and checks the widget lands where it sat. What this pins is the part the screen
 * decides: which layers, in which order, over what.
 */
class ImportSetPreviewTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    /** A 4×4 face, solid red, standing in for the face being edited. */
    private val before = PreviewFrame(4, 4, IntArray(16) { red })

    private fun guide(globalIndex: Int, x: Int, y: Int) = WidgetGuide(
        ordinal = globalIndex, globalIndex = globalIndex, type = 3, sequenceId = globalIndex,
        x = x, y = y, width = 2, height = 2, recordSize = 0, isFinal = false,
        canEditPosition = true, colorArgb = null, supportMessage = "",
    )

    private fun layer(globalIndex: Int, color: Int) =
        WidgetImageLayer(globalIndex, PreviewFrame(2, 2, IntArray(4) { color }))

    /** Widget 3 at (0,0) and widget 5 at (1,1), overlapping on pixel (1,1). */
    private val donor = WidgetDonorVariant(
        widgets = listOf(guide(3, 0, 0), guide(5, 1, 1), guide(8, 2, 0)),
        layers = listOf(layer(3, green), layer(5, blue), layer(8, green)),
        unavailable = emptyMap(),
        composed = PreviewFrame(4, 4, IntArray(16)),
    )

    private fun PreviewFrame.at(x: Int, y: Int) = argb[y * width + x]

    /**
     * Pick order is add order is z-order. Record order on the donor face is not: widget 3
     * comes first there, but picked second it is added second and so drawn on top.
     */
    @Test
    fun theLastPickIsOnTopWhereverItSatOnItsOwnFace() {
        assertEquals(blue, composeImportSet(before, donor, listOf(3, 5)).at(1, 1))
        assertEquals(green, composeImportSet(before, donor, listOf(5, 3)).at(1, 1))
    }

    @Test
    fun onlyThePicksArePaintedAndTheFaceShowsEverywhereElse() {
        val after = composeImportSet(before, donor, listOf(5, 3))
        // Widget 8 is on the donor face and was not picked.
        assertEquals(red, after.at(3, 0))
        assertEquals(red, after.at(0, 3))
        assertEquals(green, after.at(0, 0))
        assertEquals(blue, after.at(2, 2))
    }

    /** A widget's transparent pixels are the face showing through, not a black hole in it. */
    @Test
    fun aTransparentPixelLeavesTheFaceUnderIt() {
        val holed = donor.copy(layers = listOf(
            WidgetImageLayer(3, PreviewFrame(2, 2, intArrayOf(green, 0, 0, green))),
        ))
        val after = composeImportSet(before, holed, listOf(3))
        assertEquals(green, after.at(0, 0))
        assertEquals(red, after.at(1, 0))
        assertEquals(green, after.at(1, 1))
    }

    /** The face being edited is the base, and it is not written to. */
    @Test
    fun theFaceBeingEditedIsNotModified() {
        composeImportSet(before, donor, listOf(3, 5))
        assertEquals(List(16) { red }, before.argb.toList())
    }
}

/**
 * A pick's number has to be readable wherever the widget sits, including in a corner of a
 * face that is clipped to a rounded outline — which is where face `00016` keeps the battery
 * gauge that, picked first, is number 1.
 */
class PickBadgePlacementTest {

    private val radius = 9f
    private val outline = 32f
    private val width = 200f
    private val height = 300f

    private fun centre(x: Float, y: Float) =
        pickBadgeCentre(androidx.compose.ui.geometry.Offset(x, y), radius, width, height, outline)

    /** Inside the arc means within `outline - radius` of that corner's arc centre. */
    private fun assertInsideTheOutline(x: Float, y: Float) {
        val c = centre(x, y)
        val arcX = if (c.x < width / 2) outline else width - outline
        val arcY = if (c.y < height / 2) outline else height - outline
        val inCornerSquare = (c.x < outline || c.x > width - outline) &&
            (c.y < outline || c.y > height - outline)
        if (inCornerSquare) {
            val distance = kotlin.math.hypot(c.x - arcX, c.y - arcY)
            assertTrue("badge at $c pokes out of the rounded corner", distance <= outline - radius + 1e-3f)
        }
        assertTrue(c.x - radius >= 0f && c.x + radius <= width)
        assertTrue(c.y - radius >= 0f && c.y + radius <= height)
    }

    @Test
    fun aWidgetInTheMiddleKeepsItsBadgeInItsCorner() {
        assertEquals(androidx.compose.ui.geometry.Offset(59f, 69f), centre(50f, 60f))
    }

    @Test
    fun aWidgetInACornerHasItsBadgeMovedInsideTheOutline() {
        assertInsideTheOutline(0f, 0f)
        assertInsideTheOutline(-12f, -4f)
        assertInsideTheOutline(width - 4f, 0f)
        assertInsideTheOutline(0f, height - 4f)
        assertInsideTheOutline(width - 4f, height - 4f)
    }

    /** Along an edge but clear of the corners, it is only pulled in off the edge. */
    @Test
    fun aWidgetOnAnEdgeIsOnlyPulledOffThatEdge() {
        assertEquals(androidx.compose.ui.geometry.Offset(radius, 109f), centre(-20f, 100f))
    }
}
