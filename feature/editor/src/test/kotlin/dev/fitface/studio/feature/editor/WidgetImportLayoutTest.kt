package dev.fitface.studio.feature.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The importer's two face pages split in landscape for the same reason the canvas page
 * does, and it was measured on a phone before it was written down: stacked, the review
 * page put the face, the facts card and **Add widget** in the ~340dp a landscape phone
 * leaves under the top bar, so the face was cut off at the bottom and the button — the
 * only thing that page exists for — was below the fold with nothing saying so.
 *
 * Pinned here rather than by measuring: `:feature:editor` cannot run Robolectric, because
 * its merged manifest declares a receiver from the accessory SDK JAR whose pre-stackmap
 * bytecode fails the JVM verifier. Same thresholds as `canvasPageSplits`, deliberately —
 * two pages that show the same face at the same size in the same window must not disagree
 * about when there is room for a second column.
 */
class WidgetImportLayoutTest {
    @Test
    fun aLandscapePhonePutsTheFaceBesideItsFacts() {
        // 914x411dp window, less the top bar and the experimental strip.
        assertTrue(importPageSplits(maxWidth = 914.dp, maxHeight = 300.dp))
    }

    @Test
    fun aPortraitPhoneStacksThem() {
        assertFalse(importPageSplits(maxWidth = 411.dp, maxHeight = 780.dp))
        assertFalse(importPageSplits(maxWidth = 320.dp, maxHeight = 500.dp))
    }

    @Test
    fun aTabletStacksThemToo() {
        // Wide with height to spare: the stacked layout centres the face, and splitting on
        // width alone would push it off centre on every large screen.
        assertFalse(importPageSplits(maxWidth = 1184.dp, maxHeight = 736.dp))
    }

    @Test
    fun aShortNarrowWindowStaysStacked() {
        // Two columns of 200dp hold neither the face nor the facts card.
        assertFalse(importPageSplits(maxWidth = 400.dp, maxHeight = 300.dp))
    }

    @Test
    fun itAgreesWithTheCanvasPageAtEveryBoundary() {
        listOf(
            559.dp to 479.dp, 560.dp to 479.dp, 560.dp to 480.dp, 561.dp to 300.dp,
            411.dp to 780.dp, 914.dp to 300.dp,
        ).forEach { (width, height) ->
            assertTrue(
                "$width x $height",
                importPageSplits(width, height) == canvasPageSplits(width, height),
            )
        }
    }
}
