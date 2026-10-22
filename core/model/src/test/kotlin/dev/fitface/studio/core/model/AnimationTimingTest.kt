package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimationTimingTest {
    @Test
    fun evenDelaysGiveOneEntryAFrame() {
        val plan = AnimationTiming.plan(listOf(50, 50, 50), step = 1)
        assertEquals(AnimationPlan(listOf(0, 1, 2), 50, listOf(0, 1, 2)), plan)
        assertEquals(150, plan.loopMs)
    }

    /** A frame shown twice as long is two entries naming the same frame, not a second raster. */
    @Test
    fun aLongerDelayRepeatsItsFrame() {
        val plan = AnimationTiming.plan(listOf(100, 200, 100), step = 1)
        assertEquals(100, plan.periodMs)
        assertEquals(listOf(0, 1, 1, 2), plan.table)
        assertEquals(400, plan.loopMs)
    }

    /** Browsers read 0 and 10 ms as 100 ms; so does this. */
    @Test
    fun veryShortDelaysAreReadAsATenthOfASecond() {
        val plan = AnimationTiming.plan(listOf(0, 10, 20, 100), step = 1)
        assertEquals(listOf(100, 100, 20, 100).reduce { a, b -> gcd(a, b) }, plan.periodMs)
        assertEquals(320, plan.loopMs)
    }

    /** A dropped frame's time goes to the frame kept before it, so the loop keeps its length. */
    @Test
    fun aStepKeepsEveryNthFrameAndTheLoopsLength() {
        val plan = AnimationTiming.plan(List(5) { 50 }, step = 2)
        assertEquals(listOf(0, 2, 4), plan.frames)
        assertEquals(50, plan.periodMs)
        assertEquals(listOf(0, 0, 1, 1, 2), plan.table)
        assertEquals(250, plan.loopMs)
    }

    /** Past 255 entries the table falls back to one entry a frame at the average delay. */
    @Test
    fun aTableThatWouldOverflowUsesTheAverageDelay() {
        val delays = List(200) { if (it % 2 == 0) 30 else 70 }
        val plan = AnimationTiming.plan(delays, step = 1)
        assertEquals((0 until 200).toList(), plan.table)
        assertEquals(50, plan.periodMs)
        assertEquals(10_000, plan.loopMs)
    }

    @Test
    fun longGifsNeedAStepToKeepAtMost255Frames() {
        assertEquals(1, AnimationTiming.smallestStep(255))
        assertEquals(2, AnimationTiming.smallestStep(256))
        assertEquals(255, AnimationTiming.plan(List(510) { 40 }, step = 2).frames.size)
    }

    @Test
    fun theCostIsFramesTimesAnIndexedRasterPlusTheRecord() {
        assertEquals(20 * (12 + 1024 + 256 * 402 + 4) + 40 + 20 * 4, animationAddedBytes(20, 20, 256, 402))
    }

    /** The smallest step that fits: whole when there is room, every 2nd when half is, none when one frame is too many. */
    @Test
    fun theStepThatFitsIsTheSmallest() {
        val delays = List(10) { 100 }
        val all = animationAddedBytes(10, 10, 256, 402).toLong()
        assertEquals(1, animationStepThatFits(delays, all, 256, 402))
        assertEquals(2, animationStepThatFits(delays, all - 1, 256, 402))
        assertEquals(10, animationStepThatFits(delays, animationAddedBytes(1, 10, 256, 402).toLong(), 256, 402))
        assertNull(animationStepThatFits(delays, 50_000, 256, 402))
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
