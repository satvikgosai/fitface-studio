package dev.fitface.studio.core.model

/** Ordered old-to-new addresses; pristine identity belongs in session metadata. */
fun survivingStyleNames(styles: List<String>, removed: Set<String>): Map<String, String> {
    require(removed.isNotEmpty() && removed.all { it in styles }) { "Choose numbered styles to delete." }
    require(removed.size < styles.size) { "Keep at least one style." }
    return styles.filterNot { it in removed }.mapIndexed { index, name -> name to "style$index.bin" }.toMap()
}

fun survivingActiveStyle(styles: List<String>, mapping: Map<String, String>, active: String): String {
    mapping[active]?.let { return it }
    val position = styles.indexOf(active)
    val fallback = styles.drop(position.coerceAtLeast(0)).firstOrNull { it in mapping }
        ?: styles.last { it in mapping }
    return mapping.getValue(fallback)
}

/** Exact current renders and byte costs, tied to the container that was reviewed. */
data class StyleManagement(
    val snapshot: EditorSnapshot,
    val revision: String,
    val previews: Map<String, PreviewFrame>,
    val reclaimableBytes: Map<String, Int>,
)

/** A typed failure shared by framework-free writers and UI; never parse message text. */
data class ContainerCapacity(val currentBytes: Int, val proposedBytes: Int,
    val limit: Int = WATCH_CONTAINER_BYTE_CEILING) {
    val deficit: Int get() = (proposedBytes - limit).coerceAtLeast(0)
}
interface ContainerCapacityFailure { val capacity: ContainerCapacity }
fun Throwable.containerCapacity(): ContainerCapacity? =
    (this as? ContainerCapacityFailure)?.capacity ?: cause?.takeIf { it !== this }?.containerCapacity()
