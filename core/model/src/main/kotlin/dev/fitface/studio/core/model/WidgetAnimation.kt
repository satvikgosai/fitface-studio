package dev.fitface.studio.core.model

/**
 * An Animation as the canvas plays it: its distinct [frames] in the order they are first
 * shown, [table] naming one for each entry of the record's frame table, every entry shown for
 * [periodMs]. The watch loops it for ever whatever the record says (T1b), so this does too.
 */
data class WidgetAnimation(
    val globalIndex: Int,
    val frames: List<PreviewFrame>,
    val table: List<Int>,
    val periodMs: Int,
) {
    init {
        require(frames.isNotEmpty() && table.isNotEmpty() && table.all { it in frames.indices } && periodMs > 0) {
            "an animation names frames it has, each shown for some time"
        }
    }

    val loopMs: Int get() = periodMs * table.size

    /** Which of [frames] is showing [elapsedMs] after the loop began. */
    fun frameAt(elapsedMs: Long): Int = table[((elapsedMs.coerceAtLeast(0) / periodMs) % table.size).toInt()]
}
