package dev.fitface.studio.core.model

import kotlin.math.roundToInt

/** A video's length and its frames' size as [VideoClip.frameSize] reads them, before any is read. */
data class VideoProbe(val durationMs: Int, val width: Int, val height: Int, val maxLengthMs: Int)

/**
 * The part of a video read as a moving background or picture: [lengthMs] from [startMs],
 * sampled every [FRAME_MS], then treated exactly as a GIF's frames are.
 *
 * Ten frames a second is the rate the watch has been seen playing smoothly at full panel
 * (T1c, 100 ms). A picked video is read once, from its start, as far as [maxLengthMs] allows
 * ([opening]); the part kept is then cut from those frames ([AnimationParts]) and moved and
 * stretched in half-second steps, read again only when it starts past what was read. The
 * same class is a GIF's part, on the GIF's own timeline.
 *
 * [maxLengthMs] is the longest read: [MAX_READ_MS], or less where the frames would pass
 * [MAX_CACHED_BYTES] at the size they are read at. They are kept in a cache file rather than
 * in memory. The 4 MiB container and the frame table's 255 entries then decide how many of
 * them are kept.
 */
data class VideoClip(
    val durationMs: Int,
    val startMs: Int,
    val lengthMs: Int,
    val maxLengthMs: Int = durationMs,
) {
    init {
        require(durationMs >= MIN_DURATION_MS) { "the video is shorter than two frames" }
        require(maxLengthMs >= FRAME_MS) { "at least one frame must be read" }
        require(startMs in 0 until durationMs && lengthMs in 1..maxLengthMs && startMs + lengthMs <= durationMs) {
            "the clip must lie inside the video"
        }
    }

    /** Frames read: one every [FRAME_MS] from the start, inside the clip. */
    val frames: Int get() = (lengthMs + FRAME_MS - 1) / FRAME_MS

    /** When each frame is read, in microseconds, as the platform's retriever takes them. */
    val sampleTimesUs: List<Long> get() = List(frames) { (startMs + it * FRAME_MS) * 1000L }

    fun earlier(): VideoClip? =
        if (startMs == 0) null else copy(startMs = maxOf(0, startMs - STEP_MS))

    /** Half a second later, shortened where the video ends; null once less than [MIN_LENGTH_MS] would be left. */
    fun later(): VideoClip? {
        val next = startMs + STEP_MS
        if (durationMs - next < minOf(MIN_LENGTH_MS, durationMs)) return null
        return copy(startMs = next, lengthMs = minOf(lengthMs, durationMs - next))
    }

    fun shorter(): VideoClip? {
        val floor = minOf(MIN_LENGTH_MS, durationMs - startMs)
        return if (lengthMs <= floor) null else copy(lengthMs = maxOf(floor, lengthMs - STEP_MS))
    }

    fun longer(): VideoClip? {
        val limit = minOf(maxLengthMs, durationMs - startMs)
        return if (lengthMs >= limit) null else copy(lengthMs = minOf(limit, lengthMs + STEP_MS))
    }

    companion object {
        const val FRAME_MS = 100
        const val STEP_MS = 500
        const val MIN_LENGTH_MS = 500
        /** Two frames: anything shorter is a still. */
        const val MIN_DURATION_MS = 2 * FRAME_MS

        /**
         * The longest read: a minute, past the 25.5 s that every frame of a full frame table
         * covers and the 51 s that every 2nd frame does.
         */
        const val MAX_READ_MS = 60_000

        /** What every frame read may take together in its cache file, three bytes a pixel. */
        const val MAX_CACHED_BYTES = 256L * 1024 * 1024

        /** What one read frame takes in the cache: three bytes a pixel, as frames are opaque. */
        const val CACHED_BYTES_PER_PIXEL = 3

        /** All of a [durationMs] video from its start that one read takes: [maxLengthMs] or less. */
        fun opening(durationMs: Int, maxLengthMs: Int = durationMs): VideoClip =
            VideoClip(durationMs, 0, minOf(durationMs, maxLengthMs), maxLengthMs)

        /** All of a [durationMs] video from [startMs] that one read takes. */
        fun window(durationMs: Int, startMs: Int, maxLengthMs: Int): VideoClip =
            VideoClip(durationMs, startMs, minOf(durationMs - startMs, maxLengthMs), maxLengthMs)

        /**
         * The size a [videoWidth] × [videoHeight] frame is read at: just large enough to cover
         * a [panelWidth] × [panelHeight] panel at zoom 1, the size the placing page starts at,
         * and never larger than the video itself.
         */
        fun frameSize(videoWidth: Int, videoHeight: Int, panelWidth: Int, panelHeight: Int): Pair<Int, Int> {
            require(videoWidth > 0 && videoHeight > 0 && panelWidth > 0 && panelHeight > 0)
            val scale = minOf(1.0, maxOf(panelWidth.toDouble() / videoWidth, panelHeight.toDouble() / videoHeight))
            return (videoWidth * scale).roundToInt().coerceAtLeast(1) to (videoHeight * scale).roundToInt().coerceAtLeast(1)
        }

        /** How many [width] × [height] frames one read takes: [MAX_READ_MS] of them, or what [MAX_CACHED_BYTES] holds. */
        fun maxFrames(width: Int, height: Int): Int =
            (MAX_CACHED_BYTES / (width.toLong() * height * CACHED_BYTES_PER_PIXEL))
                .coerceIn(1L, (MAX_READ_MS / FRAME_MS).toLong()).toInt()

        /** The longest clip [maxFrames] reads at [width] × [height]. */
        fun maxLengthMs(width: Int, height: Int): Int =
            (maxFrames(width, height).toLong() * FRAME_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
