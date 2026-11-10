package dev.fitface.studio.core.data

import dev.fitface.studio.core.model.PreviewFrame
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameReadingTest {
    /** Ten samples a second from a 30 fps video are every 3rd frame, the nearest to each time. */
    @Test
    fun eachSampleIsTheNearestFrame() {
        val times = List(5) { it * 100_000L }
        assertEquals(listOf(0, 3, 6, 9, 12), VideoFramePlan.frameIndexes(times, durationMs = 10_000, frameCount = 300))
        assertEquals("a 24 fps video", listOf(0, 2, 5, 7, 10), VideoFramePlan.frameIndexes(times, 10_000, 240))
        assertEquals("a 5 fps video repeats frames", listOf(0, 1, 1, 2, 2), VideoFramePlan.frameIndexes(times, 10_000, 50))
        assertEquals("never past the last", listOf(4), VideoFramePlan.frameIndexes(listOf(9_900_000L), 1000, 5))
    }

    /** Runs cover every frame asked for, in order, none longer than a batch. */
    @Test
    fun runsCoverEveryFrameAskedForInBatches() {
        assertEquals(listOf(0..9, 12..21, 24..27), VideoFramePlan.runs(List(10) { it * 3 }, batch = 10))
        assertEquals("a run of one", listOf(0..0, 3..3, 6..6), VideoFramePlan.runs(listOf(0, 3, 6), batch = 1))
        assertEquals("a frame asked for twice is one", listOf(0..2), VideoFramePlan.runs(listOf(0, 1, 1, 2, 2), batch = 8))
        assertEquals("a gap no sample needs is skipped", listOf(0..3, 100..100), VideoFramePlan.runs(listOf(0, 3, 100), batch = 8))
    }

    /** As many full-size frames as 32 MB holds, between one and 32. */
    @Test
    fun aBatchIsWhatTheBudgetHolds() {
        assertEquals(8, VideoFramePlan.batch(1080, 1920, 32L * 1024 * 1024))
        assertEquals(2, VideoFramePlan.batch(2160, 3840, 32L * 1024 * 1024))
        assertEquals(1, VideoFramePlan.batch(8000, 8000, 32L * 1024 * 1024))
        assertEquals(32, VideoFramePlan.batch(64, 36, 32L * 1024 * 1024))
    }

    /** Frames written to the cache come back as they were, opaque, read when asked for. */
    @Test
    fun cachedFramesComeBackAsWritten() {
        val file = Files.createTempFile("frames", ".rgb").toFile()
        try {
            val frames = List(5) { index -> PreviewFrame(3, 2, IntArray(6) { (0xFF shl 24) or (index * 40 shl 16) or (it * 30) }) }
            val cached = CachedVideoFrames.Writer(file, 3, 2).use { writer ->
                frames.forEach(writer::add)
                writer.finish()
            }
            assertEquals(5, cached.size)
            assertEquals(5L * 3 * 2 * 3, file.length())
            frames.forEachIndexed { index, frame -> assertArrayEquals(frame.argb, cached[index].argb) }
            assertSame("the last few are held", cached[4], cached[4])
            assertEquals(listOf(2, 3), cached.subList(2, 4).map { it.argb[0] ushr 16 and 0xFF }.map { it / 40 })
        } finally {
            file.delete()
        }
    }

    /**
     * A read's file lives while its frames are placed: a new read deletes all but the latest
     * finished one (and what an earlier run left), a failed read deletes its own, a release
     * keeps only what is placed, and nothing being written is ever deleted.
     */
    @Test
    fun aReadsFileLivesWhileItsFramesArePlaced() {
        val directory = Files.createTempDirectory("video-frames").toFile()
        try {
            val stale = directory.resolve("left-over.rgb").apply { writeText("x") }
            val files = VideoFrameFiles(directory)
            val first = files.next()
            assertFalse("an earlier run's file goes on the first read", stale.exists())
            files.finished(first, succeeded = true)

            val second = files.next()
            assertTrue("the latest finished read stays while another is read", first.exists())
            files.releaseExcept(null)
            assertTrue("a file being written is never deleted", second.exists())
            assertFalse(first.exists())
            files.finished(second, succeeded = true)

            val failed = files.next()
            files.finished(failed, succeeded = false)
            assertFalse("a failed read deletes its own", failed.exists())
            assertTrue(second.exists())

            val third = files.next().also { files.finished(it, succeeded = true) }
            files.releaseExcept(second)
            assertTrue("what is placed stays", second.exists())
            assertTrue("and the latest read, which may be about to be placed", third.exists())
            val fourth = files.next().also { files.finished(it, succeeded = true) }
            assertFalse("no more than two reads are ever kept", second.exists())
            assertEquals(setOf(third, fourth), directory.listFiles()!!.toSet())

            files.releaseExcept(null)
            assertEquals("nothing placed, nothing kept", 0, directory.listFiles()!!.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
