package dev.fitface.studio.core.model

/**
 * The sizes a resize is allowed to land on, and how a Smaller or Larger tap steps between
 * them.
 *
 * This lives beside the format layer's own bound rather than in the editor because the two
 * **have to agree exactly**: a rung the format layer would refuse is a button that fails,
 * and that is not a hypothetical — a Rule's cross-axis is its thickness, so for as long as
 * the resize left the thickness alone the extent that came back was never the rung that was
 * asked for, `nextWidgetSize` re-offered the rung already in force, and the format layer
 * refused it. 56 of the catalogue's 84 Rules reached that within a few taps. Being in
 * `:core:model` is what lets a corpus test walk the real ladder through real containers.
 */

data class WidgetSize(val percentOfOriginal: Int, val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
}

/** How much of the original extent one Smaller or Larger tap is worth. */
const val WidgetResizeStepPercent = 5

/**
 * Percentages of the *original* extent a resize is allowed to land on, 20% to 200%.
 *
 * Scaling the current extent by a factor instead is what made resizing unpredictable:
 * ×0.875 then ×1.125 does not come back, so 60×60 went to 52×52, back up to 58×58, down
 * to 50×50 — every round trip a little smaller, and no size reachable twice. Each rung
 * here is a fixed fraction of the extent the face shipped with, and
 * [dev.fitface.studio.core.model.WatchFaceRepository.resizeWidget] resamples the pristine
 * artwork every time, so the same rung always produces the same pixels and Smaller then
 * Larger is exactly the size it started from.
 */
private val WidgetResizePercents: List<Int> =
    (20..200 step WidgetResizeStepPercent).toList()

/**
 * Every size the selected widget can be resized to, smallest first.
 *
 * The top is [widgetResizeLimit] per side, and it depends on [kind] because the two kinds
 * of resize cost different things. A raster-backed widget can always be taken back to the
 * extent its face shipped — `00022`'s digits are 114×136 — and 128 px is how far past that
 * it may grow, because those pixels count against the container's 4 MiB limit. A widget
 * that stores its own extent adds no bytes at all, so the only bound on it is what can be
 * a widget on the panel.
 *
 * Rungs over the limit are **dropped, not clamped**, because clamping one side of an
 * aspect-locked pair squashes the artwork: growing 57×68 repeatedly used to end at 128×128.
 * So a face with oversized artwork tops out at exactly 100%.
 */
fun widgetResizeLadder(
    originalWidth: Int,
    originalHeight: Int,
    kind: WidgetResizeKind = WidgetResizeKind.RASTER,
): List<WidgetSize> {
    if (originalWidth <= 0 || originalHeight <= 0 || kind == WidgetResizeKind.NONE) {
        return emptyList()
    }
    return WidgetResizePercents
        .mapNotNull { percent -> widgetSizeAt(originalWidth, originalHeight, percent, kind) }
        // A small widget's rungs round to the same pixel size — at 5% steps a 4×4 sprite is
        // 4×4 anywhere from 90% to 110%. Keep one rung per size so a tap always changes
        // something, and label it with the percentage nearest 100 so the extent the face
        // shipped with is always the one that reads "100%".
        .groupBy { it.width to it.height }
        .map { (_, rungs) -> rungs.minBy { kotlin.math.abs(it.percentOfOriginal - 100) } }
        .sortedBy { it.area }
}

/**
 * One rung of [widgetResizeLadder], or null where it is over [widgetResizeLimit].
 *
 * Public so a turned Rule can land on the same percentage of its new extent: the format
 * layer has to compute exactly the size the ladder would offer, or the editor re-offers it.
 */
fun widgetSizeAt(
    originalWidth: Int,
    originalHeight: Int,
    percent: Int,
    kind: WidgetResizeKind = WidgetResizeKind.RASTER,
): WidgetSize? {
    if (originalWidth <= 0 || originalHeight <= 0 || kind == WidgetResizeKind.NONE) return null
    return WidgetSize(
        percentOfOriginal = percent,
        width = scaledExtent(originalWidth, percent),
        height = scaledExtent(originalHeight, percent),
    ).takeIf {
        it.width <= widgetResizeLimit(originalWidth, kind) &&
            it.height <= widgetResizeLimit(originalHeight, kind)
    }
}

/**
 * The rung of a `originalWidth × originalHeight` ladder a [width] × [height] widget is on,
 * or the nearest by area for a size off the ladder, as a percentage.
 */
fun widgetSizePercentOf(
    originalWidth: Int,
    originalHeight: Int,
    width: Int,
    height: Int,
    kind: WidgetResizeKind = WidgetResizeKind.RASTER,
): Int {
    val ladder = widgetResizeLadder(originalWidth, originalHeight, kind)
    return ladder.firstOrNull { it.width == width && it.height == height }?.percentOfOriginal
        ?: ladder.minByOrNull { kotlin.math.abs(it.area - width.toLong() * height) }?.percentOfOriginal
        ?: 100
}

/**
 * [percent] of a `originalWidth × originalHeight` original, or the largest rung below it
 * that is within [widgetResizeLimit].
 *
 * What a turn lands on, and what another style is resized to: the same fraction of *its*
 * original. A turned box is larger, so a widget enlarged near its limit can have no rung at
 * that fraction at the new angle — and a button that is lit and then refused is the outcome
 * this ladder exists to prevent. 100% always fits, so this is null only without a ladder.
 */
fun widgetSizeAtMost(
    originalWidth: Int,
    originalHeight: Int,
    percent: Int,
    kind: WidgetResizeKind = WidgetResizeKind.RASTER,
): WidgetSize? = widgetSizeAt(originalWidth, originalHeight, percent, kind)
    ?: widgetResizeLadder(originalWidth, originalHeight, kind)
        .filter { it.percentOfOriginal <= percent }
        .maxByOrNull { it.percentOfOriginal }

private fun scaledExtent(extent: Int, percent: Int): Int =
    ((extent * percent + 50) / 100).coerceAtLeast(1)

/**
 * The rung a Smaller or Larger tap moves to, or null at the end of the ladder.
 *
 * Chosen by area rather than by index so an extent that is not on the ladder — a project
 * resized by an earlier build, whose sizes came from repeated multiplication — snaps onto
 * it in the direction of the tap instead of jumping.
 */
fun nextWidgetSize(widget: WidgetGuide, grow: Boolean): WidgetSize? {
    val ladder = widgetResizeLadder(widget.originalWidth, widget.originalHeight, widget.resizeKind)
    val area = widget.width.toLong() * widget.height
    return if (grow) {
        ladder.firstOrNull { it.area > area }
    } else {
        ladder.lastOrNull { it.area < area }
    }
}

/** Where the widget currently sits on its ladder, as a percentage of the original. */
fun widgetSizePercent(widget: WidgetGuide): Int? =
    widgetResizeLadder(widget.originalWidth, widget.originalHeight, widget.resizeKind)
        .firstOrNull { it.width == widget.width && it.height == widget.height }
        ?.percentOfOriginal
