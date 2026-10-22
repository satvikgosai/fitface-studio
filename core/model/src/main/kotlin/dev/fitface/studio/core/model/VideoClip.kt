package dev.fitface.studio.core.model

import kotlin.math.roundToInt

/**
 * The part of a video read as a moving background or picture: [lengthMs] from [startMs],
 * sampled every [FRAME_MS], then treated exactly as a GIF's frames are.
 *
 * Ten frames a second is the rate the watch has been seen playing smoothly at full panel
 * (T1c, 100 ms), and the byte budget allows about three seconds of full-panel frames on a fresh
 * custom face, so a clip opens at the first three seconds and can be moved and stretched in
 * half-second steps up to six.
 */
data class VideoClip(val durationMs: Int, val startMs: Int, val lengthMs: Int) {
    init {
        require(durationMs >= MIN_DURATION_MS) { "the video is shorter than two frames" }
        require(startMs in 0 until durationMs && lengthMs in 1..MAX_LENGTH_MS && startMs + lengthMs <= durationMs) {
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
        val limit = minOf(MAX_LENGTH_MS, durationMs - startMs)
        return if (lengthMs >= limit) null else copy(lengthMs = minOf(limit, lengthMs + STEP_MS))
    }

    companion object {
        const val FRAME_MS = 100
        const val STEP_MS = 500
        const val MIN_LENGTH_MS = 500
        const val DEFAULT_LENGTH_MS = 3000
        const val MAX_LENGTH_MS = 6000
        /** Two frames: anything shorter is a still. */
        const val MIN_DURATION_MS = 2 * FRAME_MS
        /** What every frame read may take in memory together, the GIF decoder's own ceiling. */
        const val MAX_DECODED_BYTES = 64L * 1024 * 1024

        /** The first [DEFAULT_LENGTH_MS] of a [durationMs] video, or all of a shorter one. */
        fun opening(durationMs: Int): VideoClip = VideoClip(durationMs, 0, minOf(DEFAULT_LENGTH_MS, durationMs))

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

        /** How many [width] × [height] frames [MAX_DECODED_BYTES] holds, at most a full clip's worth. */
        fun maxFrames(width: Int, height: Int): Int =
            (MAX_DECODED_BYTES / (width.toLong() * height * 4)).toInt().coerceIn(1, MAX_LENGTH_MS / FRAME_MS)
    }
}
