package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoClipTest {
    @Test fun aVideoIsReadWholeAsFarAsOneReadGoes() {
        val long = VideoClip.opening(8000, 3000)
        assertEquals(VideoClip(8000, 0, 3000, 3000), long)
        assertEquals(30, long.frames)
        assertEquals(listOf(0L, 100_000L, 200_000L), long.sampleTimesUs.take(3))
        assertEquals(2_900_000L, long.sampleTimesUs.last())
        assertEquals(VideoClip(8000, 0, 8000), VideoClip.opening(8000))
        assertEquals(VideoClip(8000, 6000, 2000, 3000), VideoClip.window(8000, 6000, 3000))

        val short = VideoClip.opening(1250)
        assertEquals(1250, short.lengthMs)
        // 0, 100 … 1200: the last frame is read inside the video, not past it.
        assertEquals(13, short.frames)
        assertEquals(1_200_000L, short.sampleTimesUs.last())
    }

    @Test fun theWindowMovesAndStretchesInHalfSecondsInsideTheVideo() {
        val clip = VideoClip.opening(4200, 3000)
        assertNull("nothing before the start", clip.earlier())
        val later = requireNotNull(clip.later())
        assertEquals(VideoClip(4200, 500, 3000, 3000), later)
        // Moving on past where three seconds fit shortens the clip to what is left.
        val near = requireNotNull(requireNotNull(later.later()).later())
        assertEquals(VideoClip(4200, 1500, 2700, 3000), near)
        assertEquals(VideoClip(4200, 3500, 700, 3000), generateSequence(near) { it.later() }.last())
        assertEquals(VideoClip(4200, 1000, 2700, 3000), near.earlier())

        assertNull("no longer than the video allows", VideoClip(4200, 1500, 2700).longer())
        assertNull("no longer than one read", clip.longer())
        assertEquals(VideoClip(4200, 0, 3500), VideoClip(4200, 0, 3000).longer())
        // Nothing but the video and one read caps the length: a 20 s video stretches to all of it.
        assertEquals(VideoClip(20_000, 0, 20_000), generateSequence(VideoClip(20_000, 0, 3000)) { it.longer() }.last())
        // Where one read holds 31.1 s of cover-sized landscape frames, that is as long as it goes.
        val wide = VideoClip(40_000, 0, 3000, VideoClip.maxLengthMs(715, 402))
        assertEquals(VideoClip(40_000, 0, 31_100, 31_100), generateSequence(wide) { it.longer() }.last())
        assertEquals(VideoClip(20_000, 0, 1000, 1000), VideoClip.opening(20_000, 1000))
        assertEquals(VideoClip(9000, 0, 500), generateSequence(VideoClip.opening(9000)) { it.shorter() }.last())
    }

    @Test fun framesAreReadJustLargeEnoughToCoverThePanelAndNeverEnlarged() {
        // Landscape 16:9 covers the portrait panel by its height.
        assertEquals(715 to 402, VideoClip.frameSize(1920, 1080, 256, 402))
        assertEquals(256 to 455, VideoClip.frameSize(720, 1280, 256, 402))
        assertEquals(200 to 150, VideoClip.frameSize(200, 150, 256, 402))
        // 256 MiB holds 311 cover-sized landscape frames at three bytes a pixel, so 31.1 s; a
        // portrait one is held to the minute a read takes at most.
        assertEquals(311, VideoClip.maxFrames(715, 402))
        assertEquals(600, VideoClip.maxFrames(256, 455))
        assertEquals(60_000, VideoClip.maxLengthMs(256, 455))
        assertEquals(600, VideoClip.maxFrames(200, 150))
    }
}
