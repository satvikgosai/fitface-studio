package dev.fitface.studio.core.format

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable

/** Immutable imported artwork, independent of both donor cache and the vendor original. */
@Serializable
data class WidgetImportOrigin(
    val id: String,
    val faceId: String,
    val variant: String,
    val baseline: String,
    val indices: List<Int>,
) {
    fun entry(template: ContainerEntry): ContainerEntry {
        val bytes = Base64.getDecoder().decode(baseline)
        return template.copy(data = bytes, size = bytes.size)
    }
}

/** Index changes are supplied by the operation that caused them, never inferred as identity. */
@Serializable
data class WidgetImportOrigins(
    val originalSha256: String,
    val widgets: List<WidgetImportOrigin>,
) {
    fun indices(variant: String) = widgets.filter { it.variant == variant }.flatMap { it.indices }.toSet()
    fun find(variant: String, index: Int) = widgets.singleOrNull { it.variant == variant && index in it.indices }
    fun renumber(variant: String, mapping: (Int) -> Int?) = copy(widgets = widgets.map { origin ->
        if (origin.variant != variant) origin else origin.copy(indices = origin.indices.mapNotNull(mapping))
    })
    fun append(id: String, index: Int) = copy(widgets = widgets.map {
        if (it.id == id) it.copy(indices = it.indices + index) else it
    })

    /**
     * Drops the artwork no live widget and no saved removal ([referenced]) still needs —
     * what is left once an imported widget, and every duplicate of it, has been deleted.
     *
     * One is always kept. A table is what lets a container carry what an import added
     * beside the widget — its font resources, its dictionary entries — and an empty one is
     * refused: without it, reopening the project would find entries the original face
     * never had and refuse the edit.
     */
    fun pruned(referenced: Set<String>): WidgetImportOrigins {
        val needed = widgets.filter { it.indices.isNotEmpty() || it.id in referenced }
        return copy(widgets = needed.ifEmpty { listOfNotNull(widgets.minByOrNull { it.baseline.length }) })
    }

    /** Fail closed before opening an archive or committing provenance-dependent bytes. */
    fun validate(original: Fit3Container, current: Fit3Container) {
        require(originalSha256 == digest(original.toByteArray())) { "Imported artwork belongs to a different original face." }
        require(widgets.isNotEmpty() && widgets.size <= 256) { "Invalid imported artwork table." }
        require(widgets.map { it.id }.distinct().size == widgets.size) { "Duplicate imported artwork identity." }
        // Keeping removed artwork is intentional: Restore and resize need the same original.
        require(widgets.sumOf { it.baseline.length.toLong() } <= MAX_BASE64_BYTES) {
            "This project has too much saved imported artwork. Start a new project to add more."
        }
        val oldPaths = original.entries.map { it.path }
        require(current.entries.take(oldPaths.size).map { it.path } == oldPaths) { "The edited entry paths do not belong to this face." }
        val prefix = oldPaths.first().substringBeforeLast('/') + "/"
        current.entries.drop(oldPaths.size).forEach {
            require(it.path == prefix + it.basename && it.basename.matches(Regex("font_[A-Za-z0-9_]+\\.bin"))) {
                "Unexpected resource added to the face."
            }
        }
        // Imports may extend dictionaries, never rewrite the strings native widgets name.
        FaceResources.fontBindings(original).forEach { binding ->
            require(binding.data.contentEquals(current.entryByBasename(binding.basename).data)) {
                "A native font binding was replaced."
            }
        }
        FaceResources.dictionaries(original).forEach { dictionary ->
            val before = LocaleDictionary.parse(dictionary).items
            val after = LocaleDictionary.parse(current.entryByBasename(dictionary.basename)).items
            require(after.take(before.size) == before) { "A native text dictionary was replaced." }
        }
        val claimed = mutableSetOf<Pair<String, Int>>()
        widgets.forEach { origin ->
            require(origin.id.length in 1..64 && origin.faceId.matches(Regex("\\d{5}"))) { "Invalid donor identity." }
            val variant = StyleWidgetMatch.requireVariantEntry(current.entryByBasename(origin.variant))
            val baseline = origin.entry(variant)
            val header = StyleHeader.parse(baseline)
            require(header.computedImageOffset == header.storedImageOffset &&
                header.storedImageOffset.toLong() + header.imageBytes == baseline.size.toLong()) { "Invalid imported artwork size." }
            val record = FaceRecordParser.scanWidgets(baseline).single()
            require(record.globalIndex == 0 && record.widgetType in WidgetImporter.stockTypes) { "Invalid imported widget type." }
            require(record.liveAlignment?.targetGlobalIndex?.let { it != WidgetImporter.ROOT_TARGET } != true) {
                "Imported placement is not independent of the donor."
            }
            FaceRecordParser.imagePointerFields(record, FaceRecordParser.imagesByRelativeOffset(baseline))
            val currentRecords = FaceRecordParser.scanWidgets(variant).associateBy { it.globalIndex }
            origin.indices.forEach { index ->
                require(claimed.add(origin.variant to index)) { "Two origins claim the same widget." }
                val live = currentRecords[index]
                require(live != null && live.widgetType == record.widgetType && live.sequenceId == record.sequenceId &&
                    live.recordSize == record.recordSize) { "Imported widget identity is missing or inconsistent." }
            }
        }
    }

    companion object {
        // Leaves room for two 4 MiB containers and previews inside the 16 MiB archive cap.
        const val MAX_BASE64_BYTES = 6L * 1024 * 1024
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
