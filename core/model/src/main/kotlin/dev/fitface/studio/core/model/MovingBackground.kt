package dev.fitface.studio.core.model

/**
 * A GIF or a video clip read to become a moving background or picture: every frame at one
 * size — a GIF's own, composited as a viewer shows it, or a video's at [VideoClip.frameSize] —
 * and how long each one stays up.
 */
class AnimationSource(
    val uri: String,
    val width: Int,
    val height: Int,
    val frames: List<PreviewFrame>,
    val delaysMs: List<Int>,
    /** The part of a video these frames were read from; null for a GIF, which is read whole. */
    val clip: VideoClip? = null,
    /** [firstOf] when it is already known, as for a [slice]; found from the pixels otherwise. */
    firstOf: List<Int>? = null,
) {
    init {
        require(frames.isNotEmpty() && frames.size == delaysMs.size) { "every frame needs a delay" }
        require(firstOf == null || firstOf.size == frames.size) { "every frame needs its first identical frame" }
    }

    /**
     * Each frame's first identical frame: its own index unless an earlier one has exactly the
     * same pixels. Placing, tinting and cropping turn the same pixels into the same frame, so
     * a repeat is found here, before any of that, and stored once.
     */
    val firstOf: List<Int> = firstOf ?: HashMap<Int, MutableList<Int>>().let { seen ->
        frames.mapIndexed { index, frame ->
            val candidates = seen.getOrPut(frame.argb.contentHashCode()) { mutableListOf() }
            candidates.firstOrNull { frames[it].argb.contentEquals(frame.argb) }
                ?: index.also { candidates += it }
        }
    }

    /** When each frame starts, in ms, timed as the watch will play it ([AnimationTiming.normalised]). */
    val startsMs: List<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        delaysMs.runningFold(0) { at, delay -> at + AnimationTiming.normalised(delay) }.dropLast(1)
    }

    /** One loop of every frame, in ms. */
    val durationMs: Int get() = startsMs.last() + AnimationTiming.normalised(delaysMs.last())

    /** Every [step]th frame, timed as [AnimationTiming.plan] times it, with repeats stored once. */
    fun plan(step: Int): AnimationPlan = AnimationTiming.plan(delaysMs, step, firstOf)

    /**
     * Frames [from] until [until] as a source of their own, read from [clip]: a repeat inside
     * them is still stored once, now naming the first copy inside them.
     */
    fun slice(from: Int, until: Int, clip: VideoClip? = this.clip): AnimationSource {
        require(from in frames.indices && until in from + 1..frames.size) { "a slice needs a frame" }
        if (from == 0 && until == frames.size && clip == this.clip) return this
        val first = HashMap<Int, Int>()
        return AnimationSource(uri, width, height, frames.subList(from, until), delaysMs.subList(from, until), clip,
            (from until until).map { index -> first.getOrPut(firstOf[index]) { index - from } })
    }
}

/**
 * A moving background as the watch will play it: which of the GIF's frames are stored, the
 * period every table entry lasts, and the table itself, naming a stored frame per entry. A
 * frame that stays up for two periods is two entries naming the same raster, which costs four
 * bytes rather than another frame; so is a kept frame identical to an earlier one. [kept] is
 * how many of the GIF's frames play, repeats included; [frames] is what is stored.
 */
data class AnimationPlan(val frames: List<Int>, val periodMs: Int, val table: List<Int>, val kept: Int = frames.size) {
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
     * skipped after it, so the loop takes as long as the GIF's. [firstOf] gives each frame's
     * first identical frame ([AnimationSource.firstOf]); a kept frame identical to an earlier
     * kept one names its raster instead of storing another.
     */
    fun plan(delaysMs: List<Int>, step: Int, firstOf: List<Int>? = null): AnimationPlan {
        require(delaysMs.isNotEmpty() && step >= 1) { "a plan needs frames and a step" }
        require(firstOf == null || firstOf.size == delaysMs.size) { "every frame needs its first identical frame" }
        val delays = delaysMs.map(::normalised)
        val kept = delays.indices.filter { it % step == 0 }
        require(kept.size <= MAX_ENTRIES) { "at most $MAX_ENTRIES frames can be kept" }
        val held = kept.map { start -> (start until minOf(start + step, delays.size)).sumOf { delays[it] } }
        val identities = kept.map { firstOf?.get(it) ?: it }
        val stored = identities.distinct()
        val slotOf = stored.withIndex().associate { (slot, frame) -> frame to slot }
        val slots = identities.map { slotOf.getValue(it) }
        val period = held.reduce(::gcd)
        val entries = held.sumOf { it / period }
        return if (entries <= MAX_ENTRIES && period <= MAX_PERIOD_MS) {
            AnimationPlan(stored, period, held.flatMapIndexed { frame, ms -> List(ms / period) { slots[frame] } }, kept.size)
        } else {
            val average = (held.sum() / held.size).coerceIn(1, MAX_PERIOD_MS)
            AnimationPlan(stored, average, slots, kept.size)
        }
    }

    /** The smallest step keeping at most [MAX_ENTRIES] frames: 1 for nearly every GIF. */
    fun smallestStep(frameCount: Int): Int = (frameCount + MAX_ENTRIES - 1) / MAX_ENTRIES

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

/**
 * What an animation of [width] × [height] frames adds to its style, a moving background or a
 * moving picture alike: one Indexed8 raster a kept frame (12-byte header, the 1 KiB palette, a
 * byte a pixel, the 4-byte trailer) and the record, 40 bytes plus four a table entry. Exactly
 * the growth either insert produces.
 */
fun animationAddedBytes(frames: Int, entries: Int, width: Int, height: Int): Int =
    frames * animationFrameBytes(width, height) + 0x28 + entries * 4

/** One kept frame's Indexed8 raster at [width] × [height]: what Fewer saves and More costs. */
fun animationFrameBytes(width: Int, height: Int): Int = 12 + 1024 + width * height + 4

/**
 * The smallest frame step whose plan fits in [availableBytes] at [width] × [height], or null
 * when not even one frame does. Most GIFs fit whole on a custom face; a long one keeps every
 * 2nd or 3rd frame as a background, and nearly any fits whole as a small picture.
 */
fun animationStepThatFits(source: AnimationSource, availableBytes: Long, width: Int, height: Int): Int? =
    (AnimationTiming.smallestStep(source.frames.size)..source.frames.size).firstOrNull { step ->
        val plan = source.plan(step)
        animationAddedBytes(plan.frames.size, plan.table.size, width, height) <= availableBytes
    }

/** A rectangle of the panel, in its pixels. */
data class PixelBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The panel pixels a [sourceWidth] × [sourceHeight] picture placed by [placement] covers on a
 * [panelWidth] × [panelHeight] panel: its [placedRect] rounded outward, so a filtered edge is
 * inside, and cut to the panel. Null when none of it is on the panel.
 *
 * What a moving background stores a frame at: the rest of the panel is black, the still
 * background deleted when one is added, so storing it would be storing black.
 */
fun movingBackgroundBounds(
    sourceWidth: Int,
    sourceHeight: Int,
    panelWidth: Int,
    panelHeight: Int,
    placement: ImagePlacement,
): PixelBounds? {
    val rect = placedRect(sourceWidth, sourceHeight, panelWidth.toFloat(), panelHeight.toFloat(), placement)
    val left = kotlin.math.floor(rect.left).toInt().coerceIn(0, panelWidth)
    val top = kotlin.math.floor(rect.top).toInt().coerceIn(0, panelHeight)
    val right = kotlin.math.ceil(rect.right).toInt().coerceIn(0, panelWidth)
    val bottom = kotlin.math.ceil(rect.bottom).toInt().coerceIn(0, panelHeight)
    return if (right <= left || bottom <= top) null else PixelBounds(left, top, right - left, bottom - top)
}

/**
 * A style's moving background, read back from its record: what Install and the Background
 * page say about it. [bounds] is where its frames draw when they cover less than the panel,
 * null for a full-panel one.
 */
data class MovingBackground(
    val globalIndex: Int,
    val frames: Int,
    val entries: Int,
    val periodMs: Int,
    val bounds: PixelBounds? = null,
) {
    val loopMs: Int get() = periodMs * entries
}
