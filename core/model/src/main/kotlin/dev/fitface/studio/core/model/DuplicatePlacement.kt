package dev.fitface.studio.core.model

import kotlin.math.hypot

/** How far a new copy lands from the widget it copies, per axis, in pixels. */
const val DuplicateOffset = 8

/**
 * The rounded corner the face is drawn with, in panel pixels — what the editor's canvas
 * clips to at its usual size. An approximation for placing copies, not a measured watch
 * value: inside it a widget is visible, outside it a widget is drawn but cut off.
 */
const val PanelCornerRadius = 40

/**
 * Where a new copy of [widget] is moved from its original, as `dx to dy`.
 *
 * A copy used to land exactly on top of its original, so an accidental Duplicate tap left
 * nothing to see and a second widget nobody knew about. Moving it [DuplicateOffset] shows
 * there are two: right and down if that keeps it inside the visible face, otherwise the
 * first other diagonal that does, then a single axis.
 *
 * "Inside" is the rounded face, not its rectangle. Duplicating the copy again and again
 * walks a chain of copies; checked against the rectangle alone, the chain marched down into
 * a bottom corner the face does not show. Checked against the rounded face, it turns back
 * while every copy is still visible. A widget already outside the rounded face — a vendor's
 * corner ornament — keeps to the plain rectangle, and one with no room either way stays put.
 *
 * [occupied] holds the top-left corners of other widgets the copy's size. A spot one of them
 * already covers is passed over while another visible one is free: at an edge the chain
 * otherwise bounced between two places, and every other copy sat exactly on an earlier one
 * — the invisible duplicate this offset exists to prevent.
 *
 * Only for a widget drawn with an outline on the canvas: a hand turns about a pivot, so an
 * offset copy would sweep a different circle, and a full-panel background has nowhere to go.
 */
fun duplicateOffset(
    widget: WidgetGuide,
    panelWidth: Int,
    panelHeight: Int,
    occupied: Set<Pair<Int, Int>> = emptySet(),
): Pair<Int, Int> {
    if (widget.placement != WidgetPlacement.CANVAS || !widget.canEditPosition) return 0 to 0
    val d = DuplicateOffset
    val moves = listOf(d to d, -d to d, d to -d, -d to -d, d to 0, -d to 0, 0 to d, 0 to -d)
    val visible = moves.filter { (dx, dy) ->
        insideRoundedPanel(widget.drawLeft + dx, widget.drawTop + dy, widget.width, widget.height,
            panelWidth, panelHeight)
    }
    (visible.firstOrNull { (dx, dy) -> (widget.drawLeft + dx to widget.drawTop + dy) !in occupied }
        ?: visible.firstOrNull())?.let { return it }
    fun axis(start: Int, size: Int, panel: Int) = when {
        start + size + d <= panel -> d
        start - d >= 0 -> -d
        else -> 0
    }
    return axis(widget.drawLeft, widget.width, panelWidth) to axis(widget.drawTop, widget.height, panelHeight)
}

/** Whether every corner of the rectangle lies inside the panel with [PanelCornerRadius] corners. */
internal fun insideRoundedPanel(left: Int, top: Int, width: Int, height: Int, panelWidth: Int, panelHeight: Int): Boolean {
    if (left < 0 || top < 0 || left + width > panelWidth || top + height > panelHeight) return false
    val r = minOf(PanelCornerRadius, panelWidth / 2, panelHeight / 2).toDouble()
    fun visible(x: Int, y: Int): Boolean {
        val cx = x.toDouble().coerceIn(r, panelWidth - r)
        val cy = y.toDouble().coerceIn(r, panelHeight - r)
        return hypot(x - cx, y - cy) <= r
    }
    return visible(left, top) && visible(left + width, top) &&
        visible(left, top + height) && visible(left + width, top + height)
}
