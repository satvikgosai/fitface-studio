package dev.fitface.studio.core.model

enum class WidgetArrangement { BACK, BACKWARD, FORWARD, FRONT }

/** Full-panel layers divide the table into fixed segments; a widget cannot cross one. */
fun arrangementTarget(widgets: List<WidgetGuide>, index: Int, action: WidgetArrangement): Int? {
    val ordered = widgets.sortedBy { it.ordinal }
    val selected = ordered.indexOfFirst { it.globalIndex == index }
    if (selected < 0 || ordered[selected].placement == WidgetPlacement.BACKGROUND) return null
    val first = (0 until selected).lastOrNull { ordered[it].placement == WidgetPlacement.BACKGROUND }?.plus(1) ?: 0
    val last = (selected + 1..ordered.lastIndex).firstOrNull { ordered[it].placement == WidgetPlacement.BACKGROUND }?.minus(1)
        ?: ordered.lastIndex
    val target = when (action) {
        WidgetArrangement.BACK -> first
        WidgetArrangement.BACKWARD -> (selected - 1).coerceAtLeast(first)
        WidgetArrangement.FORWARD -> (selected + 1).coerceAtMost(last)
        WidgetArrangement.FRONT -> last
    }
    return ordered[target].globalIndex.takeIf { target != selected }
}
