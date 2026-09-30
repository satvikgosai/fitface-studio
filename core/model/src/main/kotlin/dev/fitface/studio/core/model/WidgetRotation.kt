package dev.fitface.studio.core.model

import kotlin.math.*

/** Editor allocation policy, not a measured total watch RAM budget (RGB888 = 3 bytes/pixel). */
const val MAX_ROTATED_TEXT_PIXELS = 256 * 402
fun canRotateText(width: Int, height: Int): Boolean =
    width in 1..1024 && height in 1..1024 && width.toLong() * height <= MAX_ROTATED_TEXT_PIXELS

fun normalizedRotation(tenths: Int): Int = ((tenths % 3600) + 3600) % 3600

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

/** Stored geometry is unchanged by rotation; these extents include the rendered corners. */
val WidgetGuide.visualBounds: RotationBounds get() = rotationBounds(width, height,
    width / 2, height / 2, (rotationTenths ?: 0) / 10.0)
val WidgetGuide.visualOffsetX: Int get() = drawOffsetX + visualBounds.left
val WidgetGuide.visualOffsetY: Int get() = drawOffsetY + visualBounds.top
