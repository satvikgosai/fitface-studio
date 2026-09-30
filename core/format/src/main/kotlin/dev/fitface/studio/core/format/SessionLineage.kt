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
    /** Added font/dictionary closure survives deletion of its last widget-owning style. */
    val sharedResources: Map<String, String>? = null,
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
        val originalStyles = FaceResources.selectableStyles(original).map { it.basename }
        val styles = FaceResources.selectableStyles(current).map { it.basename }
        require(styles.isNotEmpty() && styles == styles.indices.map { "style$it.bin" } &&
            variants.keys == names && variants.values.distinct().size == variants.size &&
            styles.map { variants[it] } == originalStyles.filter { it in variants.values } &&
            (names - styles.toSet()) == FaceResources.variantEntries(original).map { it.basename }.toSet() - originalStyles.toSet() &&
            (names - styles.toSet()).all { variants[it] == it }) { "Invalid original variant identities." }
        require(widgets.keys == names) { "Missing widget identities." }
        val inverse = variants.entries.associate { it.value to it.key }
        val expectedPaths = original.entries.mapNotNull { entry ->
            if (entry.basename in originalStyles) inverse[entry.basename]?.let {
                entry.path.substringBeforeLast('/') + "/" + it
            } else entry.path
        }
        require(current.entries.take(expectedPaths.size).map { it.path } == expectedPaths) {
            "The edited entry paths do not belong to this face."
        }
        if (sharedResources == null) {
            // Schema-3 checkpoints written before style deletion still require origins.
            require(styles == originalStyles && variants.all { it.key == it.value }) {
                "Style deletion requires its shared resource metadata."
            }
            if (imports == null) require(current.entries.size == expectedPaths.size) { "Unexpected added resources." }
        } else {
            require(sharedResources == resourceClosure(original, current)) { "Shared imported resources are missing or inconsistent." }
        }
        WidgetImportOrigins.validateResources(original, current, expectedPaths)
        imports?.validate(original, current, expectedPaths)
        if (styles.size != originalStyles.size) {
            val setting = current.entryByBasename("setting.bin")
            require(SettingRecord.parse(setting).styleCount == styles.size &&
                (setting.data[0x35].toInt() and 255) in styles.indices &&
                PreviewStream.isCanonical(current.entryByBasename("preview.bin")) &&
                PreviewStream.recordCount(current.entryByBasename("preview.bin")) == styles.size) {
                "The saved style picker does not match its surviving styles."
            }
        }
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

    fun retainVariants(mapping: Map<String, String>): SessionLineage = copy(
        variants = variants.mapNotNull { (old, original) -> mapping[old]?.let { it to original } }.toMap(),
        widgets = widgets.mapNotNull { (old, origins) -> mapping[old]?.let { it to origins } }.toMap(),
    )

    fun withResources(original: Fit3Container, current: Fit3Container) =
        copy(sharedResources = resourceClosure(original, current))

    companion object {
        private fun resourceClosure(original: Fit3Container, current: Fit3Container): Map<String, String> =
            current.entries.filter { entry ->
                entry.basename.matches(Regex("font_[A-Za-z0-9_]+\\.bin")) &&
                    original.entries.singleOrNull { it.path == entry.path }?.data?.contentEquals(entry.data) != true
            }.associate { it.basename to WidgetImportOrigins.digest(it.data) }

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
