package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetResizeKind
import dev.fitface.studio.core.model.normalizedRotation
import dev.fitface.studio.core.model.widgetSizeAtMost
import dev.fitface.studio.core.model.widgetSizePercentOf
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A Rule's line: what its extent is, which way it points, and how a resize or a turn
 * rewrites it — in one place, because the editor's ladder, the resize and the rotation
 * have to agree on every pixel or a button re-offers the size already in force.
 *
 * **Both edits are measured against the pristine line turned to the current direction.**
 * A resize scales the pristine span rather than the last result, so a rung always lands on
 * the same geometry; a turn would otherwise be undone by the next resize. Turning the
 * pristine span first keeps both: its length is the shipped one, its direction the user's.
 */
internal object RuleGeometry {
    /** Below this, a Rule's stored thickness is not the number its extent came from. */
    const val MINIMUM_THICKNESS = 2

    /** Fallback thickness for a Rule that stores an implausible one — as `drawnExtents`. */
    const val FALLBACK_THICKNESS = 8

    data class Span(val x: Int, val y: Int) {
        val length: Double get() = hypot(x.toDouble(), y.toDouble())
    }

    data class Line(val span: Span, val thickness: Int?)

    fun span(record: WidgetRecord) =
        Span(record.raw1C.toShort().toInt() - record.x, record.raw1E.toShort().toInt() - record.y)

    /** The stored thickness, or null where it is below the floor and was substituted. */
    fun thickness(record: WidgetRecord): Int? =
        record.ruleThickness?.takeIf { it >= MINIMUM_THICKNESS }

    /** The extent a Rule reports: its span or its thickness, whichever is larger, per axis. */
    fun extent(span: Span, thickness: Int?): Pair<Int, Int> {
        val t = thickness ?: FALLBACK_THICKNESS
        return maxOf(abs(span.x), t) to maxOf(abs(span.y), t)
    }

    /**
     * Clockwise from +x on the panel (y runs down), in tenths of whole degrees, or null
     * for a line with no length. Whole degrees because integer endpoints cannot hold a
     * finer angle on any line short enough to fit the panel.
     */
    fun direction(span: Span): Int? {
        if (span.x == 0 && span.y == 0) return null
        val degrees = Math.toDegrees(atan2(span.y.toDouble(), span.x.toDouble())).roundToInt()
        return normalizedRotation(degrees * 10)
    }

    /**
     * Whether [current] points where [original] does, to within what rounding its
     * endpoints to whole pixels can explain. A shrunk diagonal is a few pixels long, so its
     * integer direction wanders by degrees without anyone having turned it.
     */
    fun sameDirection(original: Span, current: Span): Boolean {
        if (original.length == 0.0 || current.length == 0.0) return true
        val a = atan2(original.y.toDouble(), original.x.toDouble())
        val b = atan2(current.y.toDouble(), current.x.toDouble())
        var difference = abs(a - b) % (2 * Math.PI)
        if (difference > Math.PI) difference = 2 * Math.PI - difference
        return difference <= atan2(0.75, min(original.length, current.length))
    }

    /** [span]'s length pointing at [directionTenths]; exact where it already points there. */
    fun turned(span: Span, directionTenths: Int): Span {
        if (direction(span) == normalizedRotation(directionTenths)) return span
        val radians = Math.toRadians(directionTenths / 10.0)
        return Span((span.length * cos(radians)).roundToInt(), (span.length * sin(radians)).roundToInt())
    }

    /** The pristine span turned to wherever the current one points, unless it has not turned. */
    fun anchor(pristine: Span, current: Span): Span =
        if (sameDirection(pristine, current)) pristine else turned(pristine, direction(current)!!)

    /** The direction a guide reports: the pristine one until the line has really turned. */
    fun effectiveDirection(pristine: Span, current: Span): Int? = direction(anchor(pristine, current))

    /**
     * [origin] resized to a `width × height` extent, sign for sign.
     *
     * Scaling the span keeps a zero axis at zero and a reversed one reversed, and the
     * thickness scales with it because one axis of the reported extent may *be* the
     * thickness — see `StructuralEditor.resizeEndpointEntry` for the cases that broke.
     */
    fun scaled(origin: Line, width: Int, height: Int): Line {
        val (reportedWidth, reportedHeight) = extent(origin.span, origin.thickness)
        val span = Span(
            scaledSpan(origin.span.x, width, reportedWidth),
            scaledSpan(origin.span.y, height, reportedHeight),
        )
        // Where an axis of the reported extent *is* the thickness, the requested value for
        // that axis is what the thickness has to become — not a second rounding of the
        // same number, which on face `00049` asked for 92×10 and came back 92×9.
        val thickness = origin.thickness?.let {
            val scaled = if (reportedHeight == it) height else scaledExtent(it, width, reportedWidth)
            scaled.coerceIn(MINIMUM_THICKNESS, 0xFF)
        }
        return Line(span, thickness)
    }

    /**
     * The line [current] becomes when turned to [directionTenths] at the same rung of the
     * resize ladder it is on now, or null when that rung does not exist at the new angle.
     *
     * The rung is a percentage of the pristine line's extent *at its current direction*,
     * so turning a line resized to 60% leaves it at 60% of its shipped length rather than
     * snapping it back, and the extent it lands on is one the ladder offers — or the largest
     * that fits at the new angle, so a lit rotate button is never refused.
     */
    fun turnedLine(pristine: Line, current: Line, directionTenths: Int): Line? {
        val (anchorWidth, anchorHeight) = extent(anchor(pristine.span, current.span), pristine.thickness)
        val (width, height) = extent(current.span, current.thickness)
        val percent = widgetSizePercentOf(anchorWidth, anchorHeight, width, height, WidgetResizeKind.FIELDS)
        val target = turned(pristine.span, directionTenths)
        val (targetWidth, targetHeight) = extent(target, pristine.thickness)
        val size = widgetSizeAtMost(targetWidth, targetHeight, percent, WidgetResizeKind.FIELDS) ?: return null
        return scaled(Line(target, pristine.thickness), size.width, size.height)
    }

    /**
     * Where a line of [span] starts so that its midpoint stays where [x],[y] + [current]
     * put it, to half a pixel. Twice the midpoint is an integer, so this rounds once.
     */
    fun startKeepingMidpoint(x: Int, y: Int, current: Span, span: Span): Pair<Int, Int> =
        Math.floorDiv(2 * x + current.x - span.x + 1, 2) to Math.floorDiv(2 * y + current.y - span.y + 1, 2)

    private fun scaledExtent(extent: Int, requested: Int, from: Int): Int =
        if (from <= 0) extent else ((extent.toLong() * requested + from / 2) / from).toInt().coerceAtLeast(1)

    private fun scaledSpan(span: Int, requested: Int, from: Int): Int {
        if (span == 0 || from <= 0) return span
        val scaled = ((abs(span).toLong() * requested + from / 2) / from).toInt()
        return if (span < 0) -scaled else scaled
    }
}
