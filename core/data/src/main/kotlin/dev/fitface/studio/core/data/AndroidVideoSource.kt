package dev.fitface.studio.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.VideoClip
import dev.fitface.studio.core.model.WatchFaceException
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Frames of a picked video, read with the platform's retriever: no codec of our own, and the
 * file is read through its descriptor rather than copied.
 *
 * Read in one pass where the video says how many frames it has: runs of consecutive frames
 * ([MediaMetadataRetriever.getFramesAtIndex], [VideoFramePlan]), keeping the one nearest each
 * sample time. Otherwise, or if that fails, one frame at a time, the one nearest its time
 * ([MediaMetadataRetriever.OPTION_CLOSEST]), which decodes from a keyframe for every frame
 * and is several times slower. Either way each frame is upright as the video's rotation says,
 * at [VideoClip.frameSize], and written to a cache file ([CachedVideoFrames]); a clip is
 * never longer than [VideoClip.maxLengthMs], and [VideoClip.maxFrames] stops the read there.
 */
internal class AndroidVideoSource(private val context: Context) {
    /** What the video is, read before any frame: its upright size and its length. */
    class Video(val width: Int, val height: Int, val durationMs: Int, val rotation: Int)

    fun probe(uri: String): Video = retriever(uri).useRetriever { retriever ->
        if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
            throw WatchFaceException("This file has no video in it.", "video: no video track")
        }
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (duration == null || width == null || height == null || width <= 0 || height <= 0) {
            throw WatchFaceException("This video could not be read.", "video: metadata missing")
        }
        if (duration < VideoClip.MIN_DURATION_MS) {
            throw WatchFaceException("This video is too short to move. Choose a longer one.", "video: ${duration}ms")
        }
        val quarter = (rotation % 180 + 180) % 180 == 90
        Video(if (quarter) height else width, if (quarter) width else height,
            duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), rotation)
    }

    /**
     * [clip]'s frames, each [width] × [height] — what [VideoClip.frameSize] chose for the panel
     * — one for every sample time, cached in [cache]. A frame that cannot be decoded repeats
     * the one before it, so positions still say times. Stops at [VideoClip.maxFrames]; throws
     * when not one frame could be decoded.
     */
    suspend fun frames(uri: String, video: Video, clip: VideoClip, width: Int, height: Int, cache: File): List<PreviewFrame> =
        retriever(uri).useRetriever { retriever ->
            val times = clip.sampleTimesUs.take(VideoClip.maxFrames(width, height))
            inOnePass(retriever, video, times, width, height, cache)
                ?: oneAtATime(retriever, video, times, width, height, cache)
        }

    /** [times] read in runs of consecutive frames, or null where the video does not say its frame count or the read fails. */
    private suspend fun inOnePass(
        retriever: MediaMetadataRetriever,
        video: Video,
        times: List<Long>,
        width: Int,
        height: Int,
        cache: File,
    ): List<PreviewFrame>? {
        val count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            ?.takeIf { it > 1 } ?: return null
        val indexes = VideoFramePlan.frameIndexes(times, video.durationMs, count)
        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
        val writer = CachedVideoFrames.Writer(cache, width, height)
        try {
            var sample = 0
            for (run in VideoFramePlan.runs(indexes, VideoFramePlan.batch(video.width, video.height, BATCH_BYTES))) {
                coroutineContext.ensureActive()
                val bitmaps = retriever.getFramesAtIndex(run.first, run.last - run.first + 1, params)
                try {
                    if (bitmaps.size != run.last - run.first + 1) return null
                    bitmaps.forEachIndexed { offset, bitmap ->
                        val index = run.first + offset
                        if (sample >= indexes.size || indexes[sample] != index) return@forEachIndexed
                        val frame = upright(bitmap, video, width, height)
                        while (sample < indexes.size && indexes[sample] == index) {
                            writer.add(frame)
                            sample++
                        }
                    }
                } finally {
                    bitmaps.forEach { if (!it.isRecycled) it.recycle() }
                }
            }
            return if (sample == indexes.size) writer.finish() else null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Some devices and files cannot be read this way; one frame at a time still can.
            return null
        } finally {
            writer.close()
        }
    }

    /** [times] read one frame at a time, each from the keyframe before it. */
    private suspend fun oneAtATime(
        retriever: MediaMetadataRetriever,
        video: Video,
        times: List<Long>,
        width: Int,
        height: Int,
        cache: File,
    ): List<PreviewFrame> = CachedVideoFrames.Writer(cache, width, height).use { writer ->
        var missing = 0
        var last: PreviewFrame? = null
        for (time in times) {
            coroutineContext.ensureActive()
            val bitmap = retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
            val frame = bitmap?.let { upright(it, video, width, height) } ?: last
            if (frame == null) {
                missing++
                continue
            }
            // Frames that could not be read before the first that could take its picture.
            repeat(missing + 1) { writer.add(frame) }
            missing = 0
            last = frame
        }
        if (last == null) throw WatchFaceException("No frame of this video could be read.", "video: no frames decoded")
        writer.finish()
    }

    /**
     * [bitmap] turned upright and scaled to exactly [width] × [height], recycling it. The
     * platform already turns frames on most devices; one that comes back the other way round
     * from the video's upright shape is turned here.
     */
    private fun upright(bitmap: Bitmap, video: Video, width: Int, height: Int): PreviewFrame {
        var source = bitmap
        val wantLandscape = video.width > video.height
        val isLandscape = source.width > source.height
        if (video.width != video.height && source.width != source.height && wantLandscape != isLandscape) {
            val turned = Bitmap.createBitmap(source, 0, 0, source.width, source.height,
                Matrix().apply { setRotate(video.rotation.toFloat()) }, true)
            if (turned !== source) source.recycle()
            source = turned
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(output).drawBitmap(source, Rect(0, 0, source.width, source.height),
                RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            val pixels = IntArray(width * height)
            output.getPixels(pixels, 0, width, 0, 0, width, height)
            return PreviewFrame(width, height, pixels)
        } finally {
            output.recycle()
            source.recycle()
        }
    }

    private fun retriever(uri: String): MediaMetadataRetriever = MediaMetadataRetriever().also { retriever ->
        try {
            retriever.setDataSource(context, Uri.parse(uri))
        } catch (error: Exception) {
            retriever.release()
            throw WatchFaceException("This video could not be opened.", "video: ${error.javaClass.simpleName}", error)
        }
    }

    private inline fun <T> MediaMetadataRetriever.useRetriever(block: (MediaMetadataRetriever) -> T): T =
        try { block(this) } finally { release() }

    private companion object {
        /** Full-size frames one run may hold together while it is read. */
        const val BATCH_BYTES = 32L * 1024 * 1024
    }
}
