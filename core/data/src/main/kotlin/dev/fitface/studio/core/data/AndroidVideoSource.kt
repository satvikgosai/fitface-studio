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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Frames of a picked video, read with the platform's retriever: no codec of our own, and the
 * file is read through its descriptor rather than copied.
 *
 * Each frame is the one nearest its time ([MediaMetadataRetriever.OPTION_CLOSEST]), upright
 * as the video's rotation says, at [VideoClip.frameSize]. Fewer frames are read rather than
 * more memory used: [VideoClip.maxFrames] stops the clip once its frames would pass the
 * GIF decoder's own 64 MiB.
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
     * [clip]'s frames, each [width] × [height]: what [VideoClip.frameSize] chose for the
     * panel. Stops at [VideoClip.maxFrames]; throws when not one frame could be decoded.
     */
    suspend fun frames(uri: String, video: Video, clip: VideoClip, width: Int, height: Int): List<PreviewFrame> =
        retriever(uri).useRetriever { retriever ->
            val frames = mutableListOf<PreviewFrame>()
            for (time in clip.sampleTimesUs.take(VideoClip.maxFrames(width, height))) {
                coroutineContext.ensureActive()
                val bitmap = retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
                    ?: continue
                frames += upright(bitmap, video, width, height)
            }
            if (frames.isEmpty()) {
                throw WatchFaceException("No frame of this video could be read.", "video: no frames decoded")
            }
            frames
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
}
