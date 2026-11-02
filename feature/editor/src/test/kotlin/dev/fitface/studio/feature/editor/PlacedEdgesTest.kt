package dev.fitface.studio.feature.editor

import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.PreparedPicture
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.pictureSizes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The placing pages share Edit widget's Position control, so an image's left and top edges are
 * shown and typed in panel pixels. These pin where the edges are read from and that a typed
 * edge, handed back as the pan a drag would make, lands exactly there.
 */
class PlacedEdgesTest {
    private val panelWidth = 256
    private val panelHeight = 402

    @Test
    fun aBackgroundsEdgesAreWhereTheFaceDrawsIt() {
        // A square covering the panel is 402 px a side, centred: 73 px off the left edge.
        val centred = imageEdges(100, 100, panelWidth, panelHeight, ImagePlacement(fit = ImageFit.COVER))
        assertEquals(-73, centred.left)
        assertEquals(0, centred.top)
        // The pinch's clamp, two panels either way, is the typed range.
        assertEquals(-73 - 2 * panelWidth..-73 + 2 * panelWidth, centred.leftRange)
        assertEquals(-2 * panelHeight..2 * panelHeight, centred.topRange)

        val moved = imageEdges(100, 100, panelWidth, panelHeight, ImagePlacement(fit = ImageFit.COVER, offsetX = 0.1f))
        assertEquals(-47, moved.left)
    }

    @Test
    fun aTypedEdgeLandsTheBackgroundExactlyThere() {
        var placement = ImagePlacement(fit = ImageFit.CONTAIN, zoom = 1.3f, offsetX = -0.05f, offsetY = 0.2f)
        for ((left, top) in listOf(0 to 0, 17 to -40, -120 to 300, 255 to 401)) {
            val edges = imageEdges(640, 480, panelWidth, panelHeight, placement)
            // What PlacementControls hands the view model: the difference, as a fraction of the panel.
            placement = placement.copy(
                offsetX = placement.offsetX + (left - edges.left) / panelWidth.toFloat(),
                offsetY = placement.offsetY + (top - edges.top) / panelHeight.toFloat(),
            )
            val landed = imageEdges(640, 480, panelWidth, panelHeight, placement)
            assertEquals(left to top, landed.left to landed.top)
        }
    }

    @Test
    fun aPicturesEdgesAreItsRungKeptOnTheFace() {
        // A 200 × 100 picture kept whole opens at its 60% rung, 120 × 60, centred.
        val prepared = PreparedPicture("p", 200, 100, opaque = false,
            sizes = pictureSizes(200, 100, panelWidth, panelHeight, kept = true),
            preview = PreviewFrame(200, 100, IntArray(200 * 100)))
        val pending = PendingPicture(prepared, 0.6f, panelWidth / 2f, panelHeight / 2f)
        val edges = pictureEdges(pending, panelWidth, panelHeight)
        assertEquals((panelWidth - 120) / 2, edges.left)
        assertEquals((panelHeight - 60) / 2, edges.top)
        // As far off the face as a widget drag allows, and no further.
        assertEquals(offFaceKeepVisible(120) - 120..panelWidth - offFaceKeepVisible(120), edges.leftRange)
        assertEquals(offFaceKeepVisible(60) - 60..panelHeight - offFaceKeepVisible(60), edges.topRange)
    }
}
