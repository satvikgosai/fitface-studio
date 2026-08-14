package dev.fitface.studio.feature.editor

import androidx.compose.ui.geometry.Offset
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.WidgetGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetHitTest {
    @Test
    fun choosingEveryFitModeCreatesAFreshCenteredPlacement() {
        ImageFit.entries.forEach { fit ->
            val placement = centeredPlacement(fit)

            assertEquals(fit, placement.fit)
            assertEquals(1f, placement.zoom)
            assertEquals(0f, placement.offsetX)
            assertEquals(0f, placement.offsetY)
        }
    }

    @Test
    fun overlapChoosesTheMostSpecificTopRecord() {
        val lower = guide(globalIndex = 3, x = 20, y = 20, width = 80, height = 80)
        val upper = guide(globalIndex = 9, x = 30, y = 30, width = 60, height = 60)

        val hit = hitWidget(
            widgets = listOf(lower, upper),
            point = Offset(50f, 50f),
            canvasWidth = 256,
            canvasHeight = 402,
            faceWidth = 256,
            faceHeight = 402,
        )

        assertEquals(upper.globalIndex, hit?.globalIndex)
    }

    @Test
    fun selectedRecordKeepsPriorityInsideAnOverlap() {
        val selected = guide(globalIndex = 3, x = 20, y = 20, width = 60, height = 60)
        val upper = guide(globalIndex = 9, x = 30, y = 30, width = 60, height = 60)

        val hit = hitWidget(
            widgets = listOf(selected, upper),
            point = Offset(50f, 50f),
            canvasWidth = 256,
            canvasHeight = 402,
            faceWidth = 256,
            faceHeight = 402,
            preferredGlobalIndex = selected.globalIndex,
        )

        assertEquals(selected.globalIndex, hit?.globalIndex)
    }

    @Test
    fun selectedLargeGuideDoesNotBlockASpecificChild() {
        val large = guide(globalIndex = 12, x = 10, y = 10, width = 220, height = 180)
        val child = guide(globalIndex = 4, x = 30, y = 30, width = 40, height = 30)

        val hit = hitWidget(
            widgets = listOf(large, child),
            point = Offset(50f, 40f),
            canvasWidth = 256,
            canvasHeight = 402,
            faceWidth = 256,
            faceHeight = 402,
            preferredGlobalIndex = large.globalIndex,
        )

        assertEquals(child.globalIndex, hit?.globalIndex)
    }

    @Test
    fun partiallyOffCanvasWidgetCanStillBeSelectedThroughItsVisibleArea() {
        val partial = guide(
            globalIndex = 7,
            x = 20,
            y = 390,
            width = 80,
            height = 40,
        )

        assertEquals(
            partial.globalIndex,
            hitWidget(
                widgets = listOf(partial),
                point = Offset(40f, 400f),
                canvasWidth = 256,
                canvasHeight = 402,
                faceWidth = 256,
                faceHeight = 402,
            )?.globalIndex,
        )
        assertNull(
            hitWidget(
                widgets = listOf(partial),
                point = Offset(40f, 380f),
                canvasWidth = 256,
                canvasHeight = 402,
                faceWidth = 256,
                faceHeight = 402,
            ),
        )
    }

    @Test
    fun offCanvasStartingPositionMovesGraduallyBackInsideWithoutSnapping() {
        assertEquals(
            369f,
            constrainDragCoordinate(
                proposed = 369f,
                starting = 370f,
                extent = 40,
                canvasExtent = 402,
            ),
        )
        assertEquals(
            370f,
            constrainDragCoordinate(
                proposed = 371f,
                starting = 370f,
                extent = 40,
                canvasExtent = 402,
            ),
        )
        assertEquals(
            362f,
            constrainDragCoordinate(
                proposed = 362f,
                starting = 370f,
                extent = 40,
                canvasExtent = 402,
            ),
        )
    }

    /**
     * A widget pushed past an edge and brought back stays under the finger.
     *
     * The clamp is applied to the finger's running total on the way out, never folded
     * into it. Accumulating the clamped value made the widget stick: the four steps below
     * used to leave it at 20, because the two that were refused at the edge were also
     * dropped from the total — so it trailed the finger by the whole overshoot for the
     * rest of the drag, and the further you pushed the worse it got.
     */
    @Test
    fun aDragPushedPastTheEdgeComesBackUnderTheFinger() {
        var axis = DragAxis(track = 10f, position = 10f)
        val edge = { track: Float, delta: Float ->
            stepDragAxis(track, delta, starting = 10f, extent = 40, canvasExtent = 402)
        }
        // Two steps off the left edge: the drawn position holds at 0, the total does not.
        axis = edge(axis.track, -30f)
        assertEquals(0f, axis.position)
        axis = edge(axis.track, -30f)
        assertEquals(0f, axis.position)
        assertEquals(-50f, axis.track)
        // And the same distance back is the same distance back, not a fresh start from 0.
        axis = edge(axis.track, 30f)
        assertEquals(0f, axis.position)
        axis = edge(axis.track, 30f)
        assertEquals(10f, axis.position)
        assertEquals(10f, axis.track)
    }

    /**
     * A far-end Badge is clamped by the rectangle it draws, not by its stored endpoint.
     *
     * A Badge stores two endpoints and either may be the larger, so when the stored one is
     * the far end its rectangle begins a whole width earlier — `drawOffsetX = -width`, which
     * is how 52 of the catalogue's 84 Badges are stored. Clamping the stored value to
     * `[0, canvasExtent - width]` was that whole width out: 0 put the rectangle entirely off
     * the left edge, and the right edge was unreachable.
     */
    @Test
    fun aFarEndBadgeIsHeldByItsDrawnRectangleNotItsStoredEndpoint() {
        val width = 40
        val panel = 256
        val clamp = { proposed: Float ->
            constrainDragCoordinate(
                proposed = proposed,
                starting = 120f,
                extent = width,
                canvasExtent = panel,
                drawOffset = -width,
            )
        }
        // The stored endpoint may run all the way to the panel's far side, because the
        // rectangle it anchors ends there too.
        assertEquals(panel.toFloat(), clamp(400f))
        // And it may not go below one width, which is where the rectangle's left edge is 0.
        assertEquals(width.toFloat(), clamp(0f))
        assertEquals(width.toFloat(), clamp(-50f))
        assertEquals(150f, clamp(150f))
    }

    /** With no offset the window is the plain one, and its low end is 0.0, never -0.0. */
    @Test
    fun anUnoffsetWidgetKeepsThePlainWindow() {
        val clamp = { proposed: Float ->
            constrainDragCoordinate(proposed, starting = 10f, extent = 40, canvasExtent = 402)
        }
        assertEquals(0f, clamp(-5f))
        assertEquals(362f, clamp(500f))
        // -0.0f == 0.0f is false to assertEquals, and the clamp used to return it because
        // the offset was negated as a Float rather than as an Int.
        assertEquals(0f.toRawBits(), clamp(-5f).toRawBits())
    }

    /**
     * Abutting widgets do not share their common edge. The hit test used to be inclusive at
     * both ends, which made every rectangle width+1 px wide and gave the right and bottom
     * edges to two widgets at once.
     */
    @Test
    fun twoAbuttingWidgetsDoNotShareTheirCommonEdge() {
        val left = guide(globalIndex = 1, x = 0, y = 0, width = 40, height = 40)
        val right = guide(globalIndex = 2, x = 40, y = 0, width = 40, height = 40)
        val hit = { x: Float ->
            hitWidget(
                widgets = listOf(left, right),
                point = Offset(x, 20f),
                canvasWidth = 256,
                canvasHeight = 402,
                faceWidth = 256,
                faceHeight = 402,
            )?.globalIndex
        }

        assertEquals(left.globalIndex, hit(39f))
        assertEquals("the shared column belongs to the widget that starts there", right.globalIndex, hit(40f))
        assertEquals(right.globalIndex, hit(79f))
        assertNull("and one past the last widget is nothing at all", hit(80f))
    }

    /**
     * Every rung is a fixed fraction of the extent the face shipped with, so a size is
     * reachable twice and a step is undone by the opposite step.
     *
     * The previous scaler multiplied the extent on screen by 1.125 or 0.875, which is
     * neither: 60×60 shrank to 52×52, grew back to 58×58, shrank to 50×50 — every round
     * trip a little smaller, and no size the user could return to.
     */
    @Test
    fun resizeStepsAreFixedFractionsOfTheOriginalExtent() {
        val original = sprite(width = 60, height = 60)
        assertEquals(SpriteSize(95, 57, 57), nextSpriteSize(original, grow = false))

        val shrunk = sprite(width = 54, height = 54, originalWidth = 60, originalHeight = 60)
        assertEquals(SpriteSize(95, 57, 57), nextSpriteSize(shrunk, grow = true))
        assertEquals(SpriteSize(85, 51, 51), nextSpriteSize(shrunk, grow = false))
        assertEquals(90, spriteSizePercent(shrunk))
    }

    /** Smaller, Larger, Smaller lands back where the first Smaller put it. */
    @Test
    fun aResizeRoundTripReturnsToTheSameSize() {
        var size = SpriteSize(100, 60, 60)
        val steps = listOf(false, true, false, true, false)
        val visited = steps.map { grow ->
            size = requireNotNull(
                nextSpriteSize(
                    sprite(size.width, size.height, originalWidth = 60, originalHeight = 60),
                    grow,
                ),
            )
            size.width to size.height
        }
        assertEquals(
            listOf(57 to 57, 60 to 60, 57 to 57, 60 to 60, 57 to 57),
            visited,
        )
    }

    /** A step is 5% of the original, which on a 60 px sprite is 3 px rather than 6. */
    @Test
    fun oneStepIsFivePercentOfTheOriginal() {
        assertEquals(5, SpriteResizeStepPercent)
        val ladder = spriteResizeLadder(60, 60)
        assertEquals((20..200 step 5).toList(), ladder.map { it.percentOfOriginal })
        assertEquals(3, 60 - requireNotNull(nextSpriteSize(sprite(60, 60), grow = false)).width)
    }

    /**
     * Aspect ratio is what the panel's label promises, and clamping each side at the
     * ceiling separately broke it: repeatedly growing a 57×68 sprite used to end at
     * 128×128, a square. Rungs past the ceiling are dropped instead, so both sides always
     * come from the same percentage.
     */
    @Test
    fun everyRungKeepsTheOriginalAspectRatio() {
        val ladder = spriteResizeLadder(57, 68)
        assertTrue(ladder.isNotEmpty())
        ladder.forEach { rung ->
            assertEquals(scaled(57, rung.percentOfOriginal), rung.width)
            assertEquals(scaled(68, rung.percentOfOriginal), rung.height)
            assertTrue("${rung.width}x${rung.height} exceeds the ceiling", rung.width <= 128)
            assertTrue("${rung.width}x${rung.height} exceeds the ceiling", rung.height <= 128)
        }
        // Strictly increasing, so a tap always changes the size.
        assertEquals(ladder.sortedBy { it.area }, ladder)
        assertEquals(ladder.distinctBy { it.width to it.height }, ladder)
    }

    /**
     * Face `00022` ships 114×136 hour digits, above the 128 px growth ceiling — and a
     * shrunk one has to be able to come back to exactly that, which is the size whose bytes
     * the store shipped. So the top rung of an oversized sprite's ladder is 100%, and there
     * is nothing above it.
     */
    @Test
    fun anOversizedWidgetCanBeTakenBackToWhatItShipped() {
        val shipped = sprite(width = 114, height = 136)
        assertEquals(100, spriteSizePercent(shipped))
        assertNull(nextSpriteSize(shipped, grow = true))
        assertEquals(SpriteSize(100, 114, 136), spriteResizeLadder(114, 136).last())

        val shrunk = sprite(width = 57, height = 68, originalWidth = 114, originalHeight = 136)
        assertEquals(50, spriteSizePercent(shrunk))
        assertEquals(SpriteSize(55, 63, 75), nextSpriteSize(shrunk, grow = true))
        // And the whole way back up, one rung at a time, ends on the shipped extent.
        var size = SpriteSize(50, 57, 68)
        while (true) {
            size = nextSpriteSize(
                sprite(size.width, size.height, originalWidth = 114, originalHeight = 136),
                grow = true,
            ) ?: break
        }
        assertEquals(SpriteSize(100, 114, 136), size)
    }

    /**
     * A project resized by an earlier build carries sizes that are on no ladder — 53×53 of
     * a 60×60 original. The next tap moves in the direction it was pressed and lands on a
     * rung, rather than snapping to the nearest one and appearing to go the wrong way.
     */
    @Test
    fun anExtentOffTheLadderSnapsOnInTheDirectionOfTheTap() {
        val drifted = sprite(width = 53, height = 53, originalWidth = 60, originalHeight = 60)
        assertEquals(SpriteSize(90, 54, 54), nextSpriteSize(drifted, grow = true))
        assertEquals(SpriteSize(85, 51, 51), nextSpriteSize(drifted, grow = false))
        assertNull(spriteSizePercent(drifted))
    }

    /** A tiny sprite's low rungs round to the same pixels; each size is offered once. */
    @Test
    fun aTinySpriteHasOneRungPerDistinctSize() {
        val ladder = spriteResizeLadder(4, 4)
        assertEquals(ladder.map { it.width to it.height }.distinct().size, ladder.size)
        assertTrue(ladder.all { it.width >= 1 && it.height >= 1 })
        assertEquals(8 to 8, ladder.last().let { it.width to it.height })
        // The shipped extent is always the rung that reads 100%, even where three
        // percentages round to it.
        assertEquals(SpriteSize(100, 4, 4), ladder.single { it.width == 4 })
    }

    /**
     * The background image's zoom moves on the same kind of grid: whole percentage points,
     * so the step does not grow with the zoom the way multiplying by 1.02 did (100 → 102 →
     * … → 110 → 113, with 111 and 112 unreachable), and a value a pinch left between two
     * steps snaps back onto them.
     */
    @Test
    fun zoomStepsAreWholePercentagePointsAndReversible() {
        assertEquals(1.02f, steppedZoom(1f, grow = true), 1e-6f)
        assertEquals(1f, steppedZoom(steppedZoom(1f, grow = true), grow = false), 1e-6f)
        // A pinch leaves the zoom between rungs; the next tap lands on one.
        assertEquals(1.38f, steppedZoom(1.374f, grow = true), 1e-6f)
        assertEquals(1.36f, steppedZoom(1.374f, grow = false), 1e-6f)
        // Both ends hold rather than wrapping or drifting past the placement's clamp.
        assertEquals(0.25f, steppedZoom(0.25f, grow = false), 1e-6f)
        assertEquals(8f, steppedZoom(8f, grow = true), 1e-6f)
    }

    private fun scaled(extent: Int, percent: Int) = (extent * percent + 50) / 100

    private fun sprite(
        width: Int,
        height: Int,
        originalWidth: Int = width,
        originalHeight: Int = height,
    ) = guide(globalIndex = 0, x = 0, y = 0, width = width, height = height).copy(
        originalWidth = originalWidth,
        originalHeight = originalHeight,
        canResize = true,
    )

    private fun guide(
        globalIndex: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) = WidgetGuide(
        ordinal = globalIndex,
        globalIndex = globalIndex,
        type = 3,
        sequenceId = globalIndex,
        x = x,
        y = y,
        width = width,
        height = height,
        recordSize = 0,
        isFinal = false,
        canEditPosition = true,
        colorArgb = null,
        supportMessage = "",
    )
}
