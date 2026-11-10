package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationPartsTest {
    /** One frame a shade, each lasting the delay given with it: equal shades are identical frames. */
    private fun gif(shades: List<Int>, delays: List<Int> = List(shades.size) { 100 }) = AnimationSource("gif", 4, 4,
        shades.map { shade -> PreviewFrame(4, 4, IntArray(16) { shade }) }, delays)

    private val frame = animationFrameBytes(4, 4)
    private fun room(frames: Int, entries: Int) = animationAddedBytes(frames, entries, 4, 4).toLong()

    @Test
    fun theLongestGifPartIsTheMostWholeFramesThatFit() {
        val source = gif(List(30) { it })
        assertEquals(VideoClip(3000, 0, 1200), AnimationParts.longestGifPart(source, 0, 1, room(12, 12), 4, 4))
        assertEquals("a byte short is a frame fewer",
            VideoClip(3000, 0, 1100), AnimationParts.longestGifPart(source, 0, 1, room(12, 12) - 1, 4, 4))
        assertEquals(VideoClip(3000, 500, 1200), AnimationParts.longestGifPart(source, 500, 1, room(12, 12), 4, 4))
        assertEquals("all of it", VideoClip(3000, 0, 3000), AnimationParts.longestGifPart(source, 0, 1, room(30, 30), 4, 4))
        assertNull("not one frame", AnimationParts.longestGifPart(source, 0, 1, frame.toLong(), 4, 4))
    }

    /** Repeats are stored once, so a GIF that repeats itself fits for longer in the same room. */
    @Test
    fun repeatsMakeTheFittingPartLonger() {
        val repeating = gif(List(30) { it % 3 })
        assertEquals(VideoClip(3000, 0, 3000), AnimationParts.longestGifPart(repeating, 0, 1, room(3, 30), 4, 4))
    }

    /** Fewer frames kept leave room for a longer part. */
    @Test
    fun aLargerStepFitsALongerPart() {
        val source = gif(List(30) { it })
        val every = requireNotNull(AnimationParts.longestGifPart(source, 0, 1, room(6, 6), 4, 4))
        val half = requireNotNull(AnimationParts.longestGifPart(source, 0, 2, room(6, 12), 4, 4))
        assertEquals(600, every.lengthMs)
        assertEquals(1200, half.lengthMs)
    }

    /** No part keeps more frames than the table's 255 entries hold, however much room there is. */
    @Test
    fun aPartKeepsNoMoreThanTheTableHolds() {
        val source = gif(List(600) { it }, List(600) { 40 })
        val part = requireNotNull(AnimationParts.longestGifPart(source, 0, 1, Long.MAX_VALUE, 4, 4))
        assertEquals(255 * 40, part.lengthMs)
        assertEquals(255, requireNotNull(AnimationParts.cut(source, part)).frames.size)
    }

    /** A GIF's frames last as long as they say, so a part starts and ends on a frame's start. */
    @Test
    fun aGifIsCutOnWholeFrames() {
        val source = gif(listOf(0, 1, 2, 3), listOf(100, 700, 100, 300))
        assertEquals(listOf(0, 100, 800, 900), source.startsMs)
        assertEquals(1200, source.durationMs)
        val cut = requireNotNull(AnimationParts.cut(source, VideoClip(1200, 50, 800)))
        assertEquals("the frames starting inside it", listOf(1, 2), cut.frames.map { it.argb[0] })
        assertEquals(listOf(700, 100), cut.delaysMs)
        assertEquals(VideoClip(1200, 100, 800), AnimationParts.longestGifPart(source, 90, 1, room(2, 8), 4, 4))
        assertEquals("the earlier of two as near", 0, AnimationParts.longestGifPart(source, 50, 1, room(4, 12), 4, 4)?.startMs)
    }

    /** Half-second marks land on the nearest frame, so stepping either way is even: 120 ms frames start 0, 0.48, 0.96, 1.44 s. */
    @Test
    fun aGifPartStartsOnTheFrameNearestTheMark() {
        val source = gif(List(40) { it }, List(40) { 120 })
        val room = Long.MAX_VALUE
        assertEquals(listOf(0, 480, 960, 1440, 2040), listOf(0, 500, 1000, 1500, 2000).map {
            requireNotNull(AnimationParts.longestGifPart(source, it, 1, room, 4, 4)).startMs
        })
    }

    @Test
    fun onlyAGifOfASecondOrMoreIsCut() {
        assertTrue(AnimationParts.cuttable(gif(List(10) { it })))
        assertFalse("half a second", AnimationParts.cuttable(gif(List(5) { it })))
        assertFalse("two frames", AnimationParts.cuttable(gif(listOf(0, 1), listOf(1000, 1000))))
    }

    /** A slice keeps storing a repeat once, naming the first copy inside it. */
    @Test
    fun aSliceKeepsItsRepeats() {
        val source = gif(listOf(0, 1, 0, 2, 1, 2))
        assertEquals(listOf(0, 1, 0, 3, 1, 3), source.firstOf)
        val slice = source.slice(1, 6)
        assertEquals(listOf(0, 1, 2, 0, 2), slice.firstOf)
        assertEquals(3, slice.plan(1).frames.size)
        assertSame(source, source.slice(0, 6))
    }

    /** What was read is measured exactly: repeats count once, so more of it fits than the guess. */
    @Test
    fun theFramesReadThatFitCountRepeatsOnce() {
        val still = AnimationSource("video", 4, 4, List(40) { PreviewFrame(4, 4, IntArray(16) { _ -> it / 10 }) },
            List(40) { 100 }, VideoClip(8000, 0, 4000))
        assertEquals("four pictures, each held ten frames", 40, AnimationParts.fittingFrames(still, 1, room(4, 40), 4, 4))
        assertEquals(30, AnimationParts.fittingFrames(still, 1, room(3, 30), 4, 4))
        assertNull(AnimationParts.fittingFrames(still, 1, frame.toLong(), 4, 4))
    }

    /** A video part inside what was read is cut from it; one that is not, or a read short of a frame, is read. */
    @Test
    fun aVideoPartIsCutOnlyFromAWholeRead() {
        val read = VideoClip(8000, 1000, 3000)
        val source = AnimationSource("video", 4, 4, List(30) { PreviewFrame(4, 4, IntArray(16) { _ -> it }) },
            List(30) { 100 }, read)
        val inside = requireNotNull(AnimationParts.cut(source, VideoClip(8000, 1500, 1000)))
        assertEquals(10, inside.frames.size)
        assertEquals(5, inside.frames.first().argb[0])
        assertEquals(VideoClip(8000, 1500, 1000), inside.clip)
        assertNull("starts before", AnimationParts.cut(source, VideoClip(8000, 500, 1000)))
        assertNull("ends after", AnimationParts.cut(source, VideoClip(8000, 3500, 1000)))
        val short = AnimationSource("video", 4, 4, source.frames.drop(1), source.delaysMs.drop(1), read)
        assertNull("a frame could not be read", AnimationParts.cut(short, VideoClip(8000, 1500, 1000)))
        assertSame("but what was read is still all of it", short, AnimationParts.cut(short, read))
    }
}
