package dev.fitface.studio.core.model

import kotlin.math.*

/** Editor allocation policy, not a measured total watch RAM budget (RGB888 = 3 bytes/pixel). */
const val MAX_ROTATED_TEXT_PIXELS = 256 * 402
fun canRotateText(width: Int, height: Int): Boolean =
    width in 1..1024 && height in 1..1024 && width.toLong() * height <= MAX_ROTATED_TEXT_PIXELS

fun normalizedRotation(tenths: Int): Int = ((tenths % 3600) + 3600) % 3600

/**
 * What a widget's angle is, which decides what turning it rewrites and how it is drawn.
 *
 * Only Composite text stores an angle. A Rule and a vector arc store geometry that *has*
 * a direction, so turning them rewrites that geometry and the stored box follows. Every
 * other type has no angle at all: a tilted digit on a vendor face is tilted artwork.
 */
enum class WidgetRotationKind {
    /** Composite `+0x5C` in tenths; the watch rotates an opaque RGB888 canvas of the box. */
    TEXT,

    /** A Rule's endpoint direction in whole degrees; it turns about its midpoint. */
    LINE,

    /** A vector arc's start angle in whole degrees; its range turns with it. */
    ARC,
}

/** Whether [angleTenths] is an angle [widget] can be given. */
fun canRotateTo(widget: WidgetGuide, angleTenths: Int): Boolean = when (widget.rotationKind) {
    null -> false
    WidgetRotationKind.TEXT ->
        normalizedRotation(angleTenths) == 0 || canRotateText(widget.width, widget.height)
    // Integer endpoints and whole-degree arc fields: a tenth would be silently rounded.
    WidgetRotationKind.LINE, WidgetRotationKind.ARC -> angleTenths % 10 == 0
}

data class RotationBounds(val left: Int, val top: Int, val width: Int, val height: Int)

/** Same conservative raster bounds for rendering, hit testing, outlines and movement. */
fun rotationBounds(width: Int, height: Int, pivotX: Int, pivotY: Int, degrees: Double): RotationBounds {
    if (degrees % 360 == 0.0 || width <= 0 || height <= 0) return RotationBounds(0, 0, width, height)
    val c = cos(Math.toRadians(degrees)); val s = sin(Math.toRadians(degrees))
    val corners = listOf(0 to 0, width to 0, 0 to height, width to height)
    val xs = corners.map { (x, y) -> pivotX + (x - pivotX) * c - (y - pivotY) * s }
    val ys = corners.map { (x, y) -> pivotY + (x - pivotX) * s + (y - pivotY) * c }
    val left = floor(xs.min()).toInt(); val top = floor(ys.min()).toInt()
    return RotationBounds(left, top, ceil(xs.max()).toInt() - left + 1, ceil(ys.max()).toInt() - top + 1)
}

/**
 * Stored geometry is unchanged by a text rotation; these extents include the rendered
 * corners. A turned line or arc has already rewritten its own box, so it is its own bound.
 */
val WidgetGuide.visualBounds: RotationBounds get() = rotationBounds(width, height,
    width / 2, height / 2, if (rotationKind == WidgetRotationKind.TEXT) (rotationTenths ?: 0) / 10.0 else 0.0)
val WidgetGuide.visualOffsetX: Int get() = drawOffsetX + visualBounds.left
val WidgetGuide.visualOffsetY: Int get() = drawOffsetY + visualBounds.top
