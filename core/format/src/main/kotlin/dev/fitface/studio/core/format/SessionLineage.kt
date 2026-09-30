package dev.fitface.studio.core.format

import kotlinx.serialization.Serializable

/** An original index is an identity within the pristine variant, never a current address. */
@Serializable
data class NativeWidgetOrigin(val originalIndex: Int? = null, val duplicate: Boolean = false)

/** Persisted alongside the exact BIN. Operations supply index changes explicitly. */
@Serializable
data class SessionLineage(
    val originalSha256: String,
    val variants: Map<String, String>,
    val widgets: Map<String, Map<Int, NativeWidgetOrigin>>,
) {
    fun remap(variant: String, mapping: (Int) -> Int?) = copy(widgets = widgets +
        (variant to widgets.getValue(variant).mapNotNull { (index, origin) ->
            mapping(index)?.let { it to origin }
        }.toMap()))

    fun append(variant: String, index: Int, origin: NativeWidgetOrigin) = copy(widgets =
        widgets + (variant to (widgets.getValue(variant) + (index to origin))))

    fun originalEntry(original: Fit3Container, variant: String) = original.entryByBasename(variants.getValue(variant))

    fun validateRemoved(original: Fit3Container, current: Fit3Container, variant: String,
        raw: ByteArray, origin: NativeWidgetOrigin) {
        require(variant in variants) { "Removed widget names a missing variant." }
        val entry = current.entryByBasename(variant)
        val header = StyleHeader.parse(entry)
        val data = WidgetImporter.styleBytes(entry.data, raw, 1,
            entry.data.copyOfRange(header.storedImageOffset, entry.data.size), header.fontBindingCount)
        val saved = entry.copy(data = data, size = data.size)
        val record = FaceRecordParser.scanWidgets(saved).single()
        FaceRecordParser.imagePointerFields(record, FaceRecordParser.imagesByRelativeOffset(saved))
        origin.originalIndex?.let { index ->
            val pristine = FaceRecordParser.scanWidgets(originalEntry(original, variant)).singleOrNull { it.globalIndex == index }
            require(pristine != null && pristine.widgetType == record.widgetType &&
                pristine.sequenceId == record.sequenceId && pristine.recordSize == record.recordSize) {
                "A removed widget's original identity is inconsistent."
            }
        }
    }

    fun validate(original: Fit3Container, current: Fit3Container, imports: WidgetImportOrigins?) {
        require(originalSha256 == WidgetImportOrigins.digest(original.toByteArray())) { "Widget identities belong to another face." }
        val names = FaceResources.variantEntries(current).map { it.basename }.toSet()
        // Deleting/renaming variants is introduced separately; do not grant it implicitly.
        require(variants.keys == names && variants.values.toSet() == names && variants.all { it.key == it.value }) {
            "Invalid original variant identities."
        }
        require(widgets.keys == names) { "Missing widget identities." }
        require(current.entries.take(original.entries.size).map { it.path } == original.entries.map { it.path }) {
            "The edited entry paths do not belong to this face."
        }
        if (imports == null) require(current.entries.size == original.entries.size) { "Unexpected added resources." }
        imports?.validate(original, current)
        names.forEach { variant ->
            val before = FaceRecordParser.scanWidgets(originalEntry(original, variant)).associateBy { it.globalIndex }
            val records = FaceRecordParser.scanWidgets(current.entryByBasename(variant)).associateBy { it.globalIndex }
            val origins = widgets.getValue(variant)
            require(origins.keys == records.keys - imports?.indices(variant).orEmpty()) { "Incomplete widget identities." }
            val claimed = mutableSetOf<Int>()
            origins.forEach { (index, origin) ->
                origin.originalIndex?.let { source ->
                    val pristine = before[source]
                    val record = records.getValue(index)
                    require(pristine != null && pristine.widgetType == record.widgetType &&
                        pristine.sequenceId == record.sequenceId && pristine.recordSize == record.recordSize) {
                        "A widget's original artwork is missing or inconsistent."
                    }
                    require(origin.duplicate || claimed.add(source)) { "Two native widgets claim the same identity." }
                }
            }
        }
    }

    companion object {
        /** One-time migration of legacy edits; subsequent edits never re-infer identity. */
        fun capture(original: Fit3Container, current: Fit3Container, imports: WidgetImportOrigins?): SessionLineage {
            val variants = FaceResources.variantEntries(current).associate { it.basename to it.basename }
            return SessionLineage(WidgetImportOrigins.digest(original.toByteArray()), variants,
                variants.mapValues { (variant, _) ->
                    val entry = current.entryByBasename(variant)
                    val pristine = original.entryByBasename(variant)
                    val imported = imports?.indices(variant).orEmpty()
                    // An appended panel raster must not steal a legacy small Static's identity.
                    val generated = if (FaceRecordParser.backgroundImage(pristine) == null) {
                        val oldCount = FaceRecordParser.scanImages(pristine).size
                        val images = FaceRecordParser.imagesByRelativeOffset(entry)
                        val background = FaceRecordParser.backgroundImage(entry)
                        FaceRecordParser.scanWidgets(entry).filter { record ->
                            record.widgetType == 1 && FaceRecordParser.imagePointerFields(record, images).any {
                                it.image.index >= oldCount && it.image == background
                            }
                        }.map { it.globalIndex }.toSet()
                    } else emptySet()
                    val excluded = imported + generated
                    val sources = FaceRecordParser.originalWidgetSources(entry, pristine, excluded)
                    val duplicates = FaceRecordParser.duplicateSourceGlobalIndices(entry, pristine, excluded)
                    FaceRecordParser.scanWidgets(entry).filterNot { it.globalIndex in imported }.associate { record ->
                        val index = record.globalIndex
                        index to NativeWidgetOrigin(sources[index] ?: duplicates[index], index in duplicates)
                    }
                })
        }
    }
}
