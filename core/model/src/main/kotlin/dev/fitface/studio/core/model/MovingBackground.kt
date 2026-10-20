package dev.fitface.studio.core.model

/**
 * A GIF read to become a moving background: every frame composited to the GIF's own size,
 * as a viewer shows it, and how long each one stays up, as stored.
 */
class AnimationSource(
    val uri: String,
    val width: Int,
    val height: Int,
    val frames: List<PreviewFrame>,
    val delaysMs: List<Int>,
) {
    init {
        require(frames.isNotEmpty() && frames.size == delaysMs.size) { "every frame needs a delay" }
    }
}

/**
 * A moving background as the watch will play it: which of the GIF's frames are kept, the
 * period every table entry lasts, and the table itself, one kept frame per entry. A frame
 * that stays up for two periods is two entries naming the same raster, which costs four bytes
 * rather than another frame.
 */
data class AnimationPlan(val frames: List<Int>, val periodMs: Int, val table: List<Int>) {
    val loopMs: Int get() = periodMs * table.size
}

/**
 * How a GIF's frames and delays become a type-4 record's frame table and period.
 *
 * The watch plays one period per table entry (T1a: `+0x26` is milliseconds), loops for ever
 * whatever the repeat count says (T1b), and its table holds at most [MAX_ENTRIES]. A GIF's
 * delays vary frame to frame, so the period is their greatest common divisor and a longer
 * delay becomes repeated entries; when that would need more than [MAX_ENTRIES], every kept
 * frame gets one entry at the average delay instead, which keeps the loop's length.
 */
object AnimationTiming {
    /** The table's frame count is one byte. */
    const val MAX_ENTRIES = 255

    /** Delays below this are read as [SHORT_DELAY_MS], as browsers do: 0 and 10 ms are "as fast as you can". */
    const val MIN_DELAY_MS = 20
    const val SHORT_DELAY_MS = 100
    private const val MAX_PERIOD_MS = 0xFFFF

    fun normalised(delayMs: Int): Int = if (delayMs < MIN_DELAY_MS) SHORT_DELAY_MS else delayMs

    /**
     * Every [step]th frame from the first, each lasting its own delay plus those of the frames
     * skipped after it, so the loop takes as long as the GIF's.
     */
    fun plan(delaysMs: List<Int>, step: Int): AnimationPlan {
        require(delaysMs.isNotEmpty() && step >= 1) { "a plan needs frames and a step" }
        val delays = delaysMs.map(::normalised)
        val kept = delays.indices.filter { it % step == 0 }
        require(kept.size <= MAX_ENTRIES) { "at most $MAX_ENTRIES frames can be kept" }
        val held = kept.map { start -> (start until minOf(start + step, delays.size)).sumOf { delays[it] } }
        val period = held.reduce(::gcd)
        val entries = held.sumOf { it / period }
        return if (entries <= MAX_ENTRIES && period <= MAX_PERIOD_MS) {
            AnimationPlan(kept, period, held.flatMapIndexed { frame, ms -> List(ms / period) { frame } })
        } else {
            val average = (held.sum() / held.size).coerceIn(1, MAX_PERIOD_MS)
            AnimationPlan(kept, average, kept.indices.toList())
        }
    }

    /** The smallest step keeping at most [MAX_ENTRIES] frames: 1 for nearly every GIF. */
    fun smallestStep(frameCount: Int): Int = (frameCount + MAX_ENTRIES - 1) / MAX_ENTRIES

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

/**
 * What a moving background adds to its style: one Indexed8 raster a kept frame (12-byte
 * header, the 1 KiB palette, a byte a pixel, the 4-byte trailer) and the record, 40 bytes plus
 * four a table entry. Exactly the growth the insert produces.
 */
fun movingBackgroundAddedBytes(frames: Int, entries: Int, width: Int, height: Int): Int =
    frames * (12 + 1024 + width * height + 4) + 0x28 + entries * 4

/**
 * The smallest frame step whose plan fits in [availableBytes], or null when not even one frame
 * does. Most GIFs fit whole on a custom face; a long one keeps every 2nd or 3rd frame.
 */
fun movingBackgroundStepThatFits(delaysMs: List<Int>, availableBytes: Long, width: Int, height: Int): Int? =
    (AnimationTiming.smallestStep(delaysMs.size)..delaysMs.size).firstOrNull { step ->
        val plan = AnimationTiming.plan(delaysMs, step)
        movingBackgroundAddedBytes(plan.frames.size, plan.table.size, width, height) <= availableBytes
    }

/** A style's moving background, read back from its record: what Install and the Background page say about it. */
data class MovingBackground(
    val globalIndex: Int,
    val frames: Int,
    val entries: Int,
    val periodMs: Int,
) {
    val loopMs: Int get() = periodMs * entries
}
