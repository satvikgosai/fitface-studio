package dev.fitface.studio.core.model

/**
 * The part of a GIF or video that becomes a moving background or picture.
 *
 * A part is a [VideoClip] on the source's own timeline, whichever it is, cut from frames already
 * read: all of a GIF, a frame belonging to it when it starts inside it; or a video's window, all
 * one read takes from its start, ten frames a second. A source opens at the longest part from its start that fits keeping every
 * frame, and keeps fitting the longest part whenever what a frame costs or how many are kept
 * changes; Shorter and Longer then trim it by hand, Longer no further than fits.
 */
object AnimationParts {
    /** A GIF shorter than this is kept whole: a part would be most of it anyway. */
    const val MIN_GIF_MS = 1000

    /** Whether [read] is a GIF long enough to be cut. A video is always cut: it is read by part. */
    fun cuttable(read: AnimationSource): Boolean =
        read.clip == null && read.frames.size >= 3 && read.durationMs >= MIN_GIF_MS

    /** The whole of a GIF [read] as a part, for one that can be cut. */
    fun whole(read: AnimationSource): VideoClip = VideoClip(read.durationMs, 0, read.durationMs)

    /**
     * Frames of [read] inside [part]: a GIF's that start in it, at least one; a video's read
     * for it, or null when [read] does not hold all of them and the part has to be read.
     */
    fun cut(read: AnimationSource, part: VideoClip): AnimationSource? {
        val clip = read.clip
        if (clip == null) {
            val starts = read.startsMs
            val from = starts.indexOfFirst { it >= part.startMs }.takeIf { it >= 0 } ?: return null
            val until = starts.indexOfFirst { it >= part.startMs + part.lengthMs }.takeIf { it >= 0 } ?: starts.size
            return read.slice(from, maxOf(until, from + 1), clip = null)
        }
        if (part == clip) return read
        // A frame the retriever could not decode was skipped, so positions no longer say times.
        if (read.frames.size != clip.frames || part.durationMs != clip.durationMs) return null
        val offset = part.startMs - clip.startMs
        if (offset < 0 || offset % VideoClip.FRAME_MS != 0 || part.startMs + part.lengthMs > clip.startMs + clip.lengthMs) {
            return null
        }
        val from = offset / VideoClip.FRAME_MS
        return read.slice(from, from + part.frames, clip = part)
    }

    /**
     * The longest part of GIF [read] from [startMs] that fits in [availableBytes] at [width] ×
     * [height], keeping every [step]th frame and no more than the table holds; null when not
     * even its first frame does. Exact, repeats counted once. A part starts on a frame, the
     * one nearest [startMs] (the earlier of two as near), so half-second steps either way land
     * on the frames nearest the half seconds.
     */
    fun longestGifPart(read: AnimationSource, startMs: Int, step: Int, availableBytes: Long, width: Int, height: Int): VideoClip? {
        require(read.clip == null) { "a GIF's part" }
        val starts = read.startsMs
        val from = starts.indices.minBy { kotlin.math.abs(starts[it] - startMs) }
        fun fits(until: Int): Boolean {
            if ((until - from + step - 1) / step > AnimationTiming.MAX_ENTRIES) return false
            val plan = read.slice(from, until, clip = null).plan(step)
            return animationAddedBytes(plan.frames.size, plan.table.size, width, height) <= availableBytes
        }
        val until = longest(from + 1, starts.size, ::fits) ?: return null
        val end = if (until == starts.size) read.durationMs else starts[until]
        return VideoClip(read.durationMs, starts[from], end - starts[from])
    }

    /**
     * How many of [read]'s frames, from its first, fit in [availableBytes] at [width] ×
     * [height] keeping every [step]th frame and no more than the table holds; null when not
     * even the first does. Exact, repeats counted once: what a video part already read is cut
     * to: all one read takes ([VideoClip.window]) is read, and the part is as much of it as fits.
     */
    fun fittingFrames(read: AnimationSource, step: Int, availableBytes: Long, width: Int, height: Int): Int? {
        fun fits(until: Int): Boolean {
            if ((until + step - 1) / step > AnimationTiming.MAX_ENTRIES) return false
            val plan = read.slice(0, until).plan(step)
            return animationAddedBytes(plan.frames.size, plan.table.size, width, height) <= availableBytes
        }
        return longest(1, read.frames.size, ::fits)
    }

    /**
     * The largest n in [low]..[high] for which [fits] holds, or null when [low] does not. A
     * part's cost grows with its length but for its table's four bytes an entry, which can fall
     * by a few when a longer part's period changes, so the answer is searched for and then
     * walked on while longer still fits.
     */
    private fun longest(low: Int, high: Int, fits: (Int) -> Boolean): Int? {
        if (!fits(low)) return null
        var good = low
        var bad = high + 1
        while (bad - good > 1) {
            val middle = (good + bad) ushr 1
            if (fits(middle)) good = middle else bad = middle
        }
        while (good < high && fits(good + 1)) good++
        return good
    }
}
