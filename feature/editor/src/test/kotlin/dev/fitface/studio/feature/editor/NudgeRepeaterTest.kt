package dev.fitface.studio.feature.editor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An arrow's press, click and release, in the orders a phone delivers them. Whatever the
 * order, a gesture is one press, and a tap is one step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NudgeRepeaterTest {
    private val scope = TestScope()
    private var presses = 0
    private val steps = mutableListOf<Int>()
    private val repeater = NudgeRepeater(onPress = { presses++ }, onStep = { steps += it })

    @Test fun aTapIsOneStep() {
        repeater.pressed(scope)
        scope.runCurrent()
        repeater.clicked()
        repeater.released()
        scope.advanceUntilIdle()
        assertEquals(listOf(1), steps)
        assertEquals(1, presses)
    }

    /** The case that moved 2 px: the press is seen only after its click. */
    @Test fun aClickSeenBeforeItsPressIsStillOneStep() {
        repeater.clicked()
        repeater.pressed(scope)
        repeater.released()
        scope.advanceUntilIdle()
        assertEquals(listOf(1), steps)
        assertEquals(1, presses)
    }

    /** The release is late too: the click still stops the hold before it starts. */
    @Test fun aReleaseSeenLateStillLetsNoHoldStart() {
        repeater.pressed(scope)
        scope.advanceTimeBy(FirstRepeatDelayMillis - 1)
        repeater.clicked()
        scope.advanceTimeBy(FirstRepeatDelayMillis * 3)
        repeater.released()
        scope.advanceUntilIdle()
        assertEquals(listOf(1), steps)
        assertEquals(1, presses)
    }

    @Test fun aHoldStepsUntilItsClickAndTheClickAddsNothing() {
        repeater.pressed(scope)
        scope.advanceTimeBy(FirstRepeatDelayMillis + 1_000)
        val held = steps.size
        repeater.clicked()
        repeater.released()
        scope.advanceUntilIdle()
        assertTrue("a hold repeats: $steps", held >= 2)
        assertEquals(held, steps.size)
        assertEquals(1, steps.first())
        assertEquals(1, presses)
    }

    /** A hold dragged off the button ends with no click; the next tap is not mistaken for its release. */
    @Test fun aCancelledHoldDoesNotSwallowTheNextTap() {
        repeater.pressed(scope)
        scope.advanceTimeBy(FirstRepeatDelayMillis + 300)
        repeater.cancelled()
        val held = steps.size
        repeater.clicked()
        scope.advanceUntilIdle()
        assertEquals(held + 1, steps.size)
        assertEquals(2, presses)
    }

    /** TalkBack's click has no press at all. */
    @Test fun anAccessibilityClickIsOneStep() {
        repeater.clicked()
        scope.advanceUntilIdle()
        assertEquals(listOf(1), steps)
        assertEquals(1, presses)
    }
}
