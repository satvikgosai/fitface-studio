package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoClipTest {
    @Test fun aLongVideoOpensAtItsFirstThreeSecondsAndAShortOneWhole() {
        val long = VideoClip.opening(8000)
        assertEquals(VideoClip(8000, 0, 3000), long)
        assertEquals(30, long.frames)
        assertEquals(listOf(0L, 100_000L, 200_000L), long.sampleTimesUs.take(3))
        assertEquals(2_900_000L, long.sampleTimesUs.last())

        val short = VideoClip.opening(1250)
        assertEquals(1250, short.lengthMs)
        // 0, 100 … 1200: the last frame is read inside the video, not past it.
        assertEquals(13, short.frames)
        assertEquals(1_200_000L, short.sampleTimesUs.last())
    }

    @Test fun theWindowMovesAndStretchesInHalfSecondsInsideTheVideo() {
        val clip = VideoClip.opening(4200)
        assertNull("nothing before the start", clip.earlier())
        val later = requireNotNull(clip.later())
        assertEquals(VideoClip(4200, 500, 3000), later)
        // Moving on past where three seconds fit shortens the clip to what is left.
        val near = requireNotNull(requireNotNull(later.later()).later())
        assertEquals(VideoClip(4200, 1500, 2700), near)
        assertEquals(VideoClip(4200, 3500, 700), generateSequence(near) { it.later() }.last())
        assertEquals(VideoClip(4200, 1000, 2700), near.earlier())

        assertNull("no longer than the video allows", VideoClip(4200, 1500, 2700).longer())
        assertEquals(VideoClip(4200, 0, 3500), clip.longer())
        assertEquals(VideoClip(9000, 0, 6000), generateSequence(VideoClip.opening(9000)) { it.longer() }.last())
        assertEquals(VideoClip(9000, 0, 500), generateSequence(VideoClip.opening(9000)) { it.shorter() }.last())
    }

    @Test fun framesAreReadJustLargeEnoughToCoverThePanelAndNeverEnlarged() {
        // Landscape 16:9 covers the portrait panel by its height.
        assertEquals(715 to 402, VideoClip.frameSize(1920, 1080, 256, 402))
        assertEquals(256 to 455, VideoClip.frameSize(720, 1280, 256, 402))
        assertEquals(200 to 150, VideoClip.frameSize(200, 150, 256, 402))
        // About 58 cover-sized landscape frames fit 64 MiB; a small frame stops at six seconds.
        assertEquals(58, VideoClip.maxFrames(715, 402))
        assertEquals(60, VideoClip.maxFrames(200, 150))
    }
}
