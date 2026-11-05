package dev.fitface.studio.core.data

import kotlin.math.roundToInt

/**
 * Which of a video's own frames a one-pass read decodes for each sample time: the frame
 * nearest the time at the video's average rate, in runs of at most a batch of consecutive
 * frames, so no more than a batch of full-size frames is held at once.
 *
 * A frame decoded in order costs one decode; a frame asked for on its own decodes everything
 * from the keyframe before it, which is what made reading a video slow. A run is one seek and
 * then frames in order.
 */
internal object VideoFramePlan {
    /** For each sample time, the index of the video's frame nearest it, of [frameCount] over [durationMs]. */
    fun frameIndexes(timesUs: List<Long>, durationMs: Int, frameCount: Int): List<Int> {
        require(durationMs > 0 && frameCount > 0)
        return timesUs.map { time ->
            (time / 1000.0 * frameCount / durationMs).roundToInt().coerceIn(0, frameCount - 1)
        }
    }

    /**
     * Runs of consecutive frames covering every one of [indexes] (in order, never decreasing),
     * none longer than [batch]; frames between two runs that no sample needs are not decoded.
     */
    fun runs(indexes: List<Int>, batch: Int): List<IntRange> {
        require(batch >= 1)
        val runs = mutableListOf<IntRange>()
        var position = 0
        while (position < indexes.size) {
            val first = indexes[position]
            var last = first
            while (position < indexes.size && indexes[position] < first + batch) {
                last = indexes[position]
                position++
            }
            runs += first..last
        }
        return runs
    }

    /** How many full-size [width] × [height] frames, two bytes a pixel, fit [budgetBytes] together; at least one. */
    fun batch(width: Int, height: Int, budgetBytes: Long, most: Int = 32): Int =
        (budgetBytes / (width.toLong() * height * 2)).coerceIn(1L, most.toLong()).toInt()
}
