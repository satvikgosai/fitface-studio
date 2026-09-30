package dev.fitface.studio.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held nudge widens its step, and the curve is here rather than in the feel of it.
 *
 * The control promises one pixel a tap, so the fine end is not negotiable — and a hold that
 * never coarsens is what the complaint was about: 402 pixels of panel at one pixel a tick
 * is about half a minute of holding one button. `:feature:editor` cannot measure a
 * composable, so the decision lives in a pure function and is pinned here.
 */
class NudgeAccelerationTest {
    @Test
    fun aHoldBeginsAtTheSinglePixelTheLabelPromises() {
        assertEquals(1, nudgeStepPixels(0))
        assertEquals(1, nudgeStepPixels(9))
    }

    @Test
    fun itWidensTwiceAndThenStops() {
        assertEquals(2, nudgeStepPixels(10))
        assertEquals(2, nudgeStepPixels(23))
        assertEquals(5, nudgeStepPixels(24))
        assertEquals(5, nudgeStepPixels(10_000))
    }

    @Test
    fun itNeverNarrowsWhileTheFingerIsDown() {
        // A step that shrank mid-hold would move the widget backwards relative to the
        // finger's expectation, which is the one thing acceleration must not do.
        (0..200).map(::nudgeStepPixels).zipWithNext().forEach { (earlier, later) ->
            assertTrue("$earlier then $later", later >= earlier)
        }
    }

    @Test
    fun crossingThePanelIsSecondsRatherThanMinutes() {
        // 402px is the tallest a widget can travel. At the old flat one pixel a tick this
        // was 402 ticks; the assertion is on the count, not on any wall clock.
        var travelled = 0
        var ticks = 0
        while (travelled < 402) {
            travelled += nudgeStepPixels(ticks)
            ticks++
        }
        assertTrue("$ticks ticks to cross the panel", ticks < 120)
    }
}
