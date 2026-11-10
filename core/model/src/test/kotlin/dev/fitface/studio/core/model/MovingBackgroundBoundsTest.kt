package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovingBackgroundBoundsTest {
    private fun bounds(placement: ImagePlacement, width: Int = 100, height: Int = 75) =
        movingBackgroundBounds(width, height, 256, 402, placement)

    @Test fun coverAndStretchFillThePanelAndContainOnlyItsShare() {
        assertEquals(PixelBounds(0, 0, 256, 402), bounds(ImagePlacement(fit = ImageFit.COVER)))
        assertEquals(PixelBounds(0, 0, 256, 402), bounds(ImagePlacement(fit = ImageFit.STRETCH)))
        // 4:3 contained in 256 wide is 192 high, centred: 105 above and below.
        assertEquals(PixelBounds(0, 105, 256, 192), bounds(ImagePlacement(fit = ImageFit.CONTAIN)))
    }

    @Test fun nativeIsThePicturesOwnPixelsCentred() {
        // 402 − 75 is odd: the corner is snapped to a whole pixel rather than left half-way.
        assertEquals(PixelBounds(78, 164, 100, 75), bounds(ImagePlacement(fit = ImageFit.NATIVE)))
        assertEquals(PixelBounds(28, 126, 200, 150), bounds(ImagePlacement(fit = ImageFit.NATIVE, zoom = 2f)))
        // A large picture at its own size is cut to the panel.
        assertEquals(PixelBounds(0, 0, 256, 402), bounds(ImagePlacement(fit = ImageFit.NATIVE), 1920, 1080))
    }

    @Test fun movedPartlyOffItIsCutToThePanelAndWhollyOffItIsNothing() {
        // Centre moved half a panel right: 128 + 128 = 256, the picture's left half on the edge.
        assertEquals(PixelBounds(206, 164, 50, 75), bounds(ImagePlacement(fit = ImageFit.NATIVE, offsetX = 0.5f)))
        assertNull(bounds(ImagePlacement(fit = ImageFit.NATIVE, offsetX = 1f)))
        // A fractional edge is rounded outward, so a filtered edge pixel is kept.
        assertEquals(PixelBounds(0, 105, 256, 193), bounds(ImagePlacement(fit = ImageFit.CONTAIN, offsetY = 0.001f)))
    }

    /**
     * CONTAIN, COVER and STRETCH land exactly where they did before NATIVE was added, as the
     * decoder and the preview both computed them: fitted and centred, zoomed about the centre,
     * then moved by a share of the frame. Stills and the Photos album depend on this.
     */
    @Test fun theStillFitsAreWhereTheyAlwaysWere() {
        val sources = listOf(32 to 50, 100 to 75, 1920 to 1080, 1080 to 1920, 256 to 402, 7 to 3)
        val targets = listOf(256f to 402f, 192f to 240f, 615.5f to 966.4f)
        val zooms = listOf(0.25f, 0.5f, 1f, 1.37f, 8f)
        val offsets = listOf(-2f, -0.31f, 0f, 0.5f, 2f)
        for ((sw, sh) in sources) for ((tw, th) in targets) for (fit in ImageFit.STILL)
            for (zoom in zooms) for (ox in offsets) for (oy in offsets) {
                val placement = ImagePlacement(fit = fit, zoom = zoom, offsetX = ox, offsetY = oy)
                val rect = placedRect(sw, sh, tw, th, placement)
                val (left, top, width, height) = previous(sw, sh, tw, th, placement)
                val case = "$sw×$sh on $tw×$th, $fit, zoom $zoom, offset $ox,$oy"
                assertEquals(case, left, rect.left, 1e-3f)
                assertEquals(case, top, rect.top, 1e-3f)
                assertEquals(case, width, rect.width, 1e-3f)
                assertEquals(case, height, rect.height, 1e-3f)
            }
    }

    /** The geometry as it was written before `placedRect` existed, kept here verbatim to pin it. */
    private fun previous(sw: Int, sh: Int, tw: Float, th: Float, placement: ImagePlacement): List<Float> {
        val widthScale = tw / sw
        val heightScale = th / sh
        val baseScale = when (placement.fit) {
            ImageFit.CONTAIN -> minOf(widthScale, heightScale)
            ImageFit.COVER -> maxOf(widthScale, heightScale)
            else -> 1f
        }
        val baseWidth = if (placement.fit == ImageFit.STRETCH) tw else sw * baseScale
        val baseHeight = if (placement.fit == ImageFit.STRETCH) th else sh * baseScale
        val width = baseWidth * placement.zoom
        val height = baseHeight * placement.zoom
        val centerX = tw / 2f + placement.offsetX * tw
        val centerY = th / 2f + placement.offsetY * th
        return listOf(centerX - width / 2f, centerY - height / 2f, width, height)
    }
}
