package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import java.io.ByteArrayOutputStream

/** One additive edit and the compact, immutable source for its future resizes. */
data class WidgetImportEdit(
    val edit: StructuralEdit,
    val globalIndex: Int,
    val baseline: ContainerEntry,
    val addedResources: Map<String, ByteArray>,
)

/** Firmware-informed, experimental import. Never substitutes or deletes target artwork. */
object WidgetImporter {
    val stockTypes: Set<Int> get() = WidgetSchema.IMPORTABLE_STOCK_TYPES
    // A u16 widget table can never give a constructed record this index: append refuses
    // at 65535 records. Unlike a small magic index this remains root after later edits.
    const val ROOT_TARGET = 0xFFFF

    fun unavailableReason(entry: ContainerEntry, index: Int): String? {
        val record = FaceRecordParser.scanWidgets(entry).singleOrNull { it.globalIndex == index }
            ?: return "This widget is no longer present."
        if (record.widgetType !in stockTypes) return "This widget type cannot be added safely yet."
        if (record.widgetType == 5 && record.sourceId == 116)
            return "The watch controls this time-zone widget, so it cannot be added safely."
        if (record.liveAlignment?.code?.let { it !in 0..3 } == true)
            return "This widget’s positioning is not supported."
        val guide = FaceRecordParser.widgetGuides(entry).single { it.globalIndex == index }
        if (guide.placement == WidgetPlacement.BACKGROUND)
            return "Full-face backgrounds belong on the Background page."
        if (record.widgetType == 5 || record.widgetType == 13) {
            val raw = entry.data.copyOfRange(record.recordOffset, record.recordOffset + record.recordSize)
            try { validateDynamicRanges(raw, record.widgetType, Int.MAX_VALUE) }
            catch (error: Fit3FormatException) { return error.message }
        }
        return null
    }

    /**
     * What widget [index] would cost the face it is added to, close enough to show on a row.
     *
     * The record plus each raster it names, counted once however many fields reach it —
     * the same closure [importWidget] copies, and the whole of it for every raster-backed
     * type. A Value or a Composite also carries a font binding and a slice of each locale
     * dictionary, about 1.7 KB this cannot see, so treat it as a **floor**: the exact
     * figure is the one [importWidget] reports once the edit has been built, and that is
     * what the review page shows.
     *
     * Null where the closure cannot be resolved at all, which is a widget no row should
     * quote a price for. Nothing decides an edit on this number — `rebuild` refuses growth
     * past the ceiling whatever a picker estimated.
     */
    fun addedBytesEstimate(entry: ContainerEntry, index: Int): Int? {
        val record = FaceRecordParser.scanWidgets(entry).singleOrNull { it.globalIndex == index }
            ?: return null
        val pointers = try {
            FaceRecordParser.imagePointerFields(record, FaceRecordParser.imagesByRelativeOffset(entry))
        } catch (unresolved: Fit3FormatException) {
            return null
        }
        val rasters = pointers.map { it.image }.distinctBy { it.index }
            .sumOf { it.pixelOffset + it.dataSize - it.recordOffset }
        return record.recordSize + rasters
    }

    fun importWidget(
        target: Fit3Container,
        targetVariant: String,
        donor: Fit3Container,
        donorVariant: String,
        donorIndex: Int,
    ): WidgetImportEdit {
        StructuralEditor.requireValidAndTight(target)
        StructuralEditor.requireValidAndTight(donor)
        val destination = StyleWidgetMatch.requireVariantEntry(target.entryByBasename(targetVariant))
        val source = StyleWidgetMatch.requireVariantEntry(donor.entryByBasename(donorVariant))
        unavailableReason(source, donorIndex)?.let { throw Fit3FormatException(it) }
        if (FaceRecordParser.panelSize(destination) != FaceRecordParser.panelSize(source))
            throw Fit3FormatException("The two faces have different panel dimensions.")
        val oldRecords = FaceRecordParser.scanWidgets(destination)
        if (oldRecords.map { it.globalIndex } != oldRecords.indices.toList() || oldRecords.size >= ROOT_TARGET)
            throw Fit3FormatException("The target widget index table cannot accept another record.")
        val record = FaceRecordParser.scanWidgets(source).single { it.globalIndex == donorIndex }
        val raw = source.data.copyOfRange(record.recordOffset, record.recordOffset + record.recordSize)
        val guide = FaceRecordParser.widgetGuides(source).single { it.globalIndex == donorIndex }
        record.liveAlignment?.let { alignment ->
            // Preserve the code: Value/Composite also use it for text justification.
            val panel = FaceRecordParser.panelSize(destination)
            val rootX = when (alignment.code) {
                2 -> (panel.width - guide.width) / 2
                3 -> panel.width - guide.width
                else -> 0
            }
            signed(raw, 0x18, guide.drawLeft - guide.drawOffsetX - rootX)
            signed(raw, 0x1A, guide.drawTop - guide.drawOffsetY)
            raw.putU16(WidgetSchema.spec(record.widgetType).alignment!!.targetOffset, ROOT_TARGET)
        }
        val fonts = mergeFonts(target, donor, raw, record.widgetType)
        val pointers = FaceRecordParser.imagePointerFields(record, FaceRecordParser.imagesByRelativeOffset(source))
        val copied = linkedMapOf<Int, Pair<Int, ByteArray>>()
        var rasterBytes = 0
        pointers.forEach { field ->
            if (field.image.index !in copied) {
                val image = field.image
                val data = source.data.copyOfRange(image.recordOffset, image.pixelOffset + image.dataSize)
                copied[image.index] = rasterBytes to data
                rasterBytes += data.size
            }
        }
        val originalHeader = StyleHeader.parse(destination)
        if (originalHeader.computedImageOffset != originalHeader.storedImageOffset ||
            originalHeader.storedImageOffset + originalHeader.imageBytes != destination.data.size)
            throw Fit3FormatException("The target style is not tightly packed.")
        raw.putU16(0x0E, oldRecords.size)
        pointers.forEach { field ->
            raw.putU32(field.offset - record.recordOffset,
                originalHeader.imageBytes + copied.getValue(field.image.index).first)
        }
        val imageSuffix = ByteArrayOutputStream().apply { copied.values.forEach { write(it.second) } }.toByteArray()
        val newWidgets = destination.data.copyOfRange(STYLE_HEADER_SIZE, originalHeader.storedImageOffset) + raw
        val oldImages = destination.data.copyOfRange(originalHeader.storedImageOffset, destination.data.size)
        val payload = styleBytes(destination.data, newWidgets, oldRecords.size + 1,
            oldImages + imageSuffix, fonts.bindingCount)
        val replacements = linkedMapOf(destination.index to payload)
        val additions = linkedMapOf<String, ByteArray>()
        fonts.resources.forEach { (name, data) ->
            val old = target.entries.singleOrNull { it.basename == name }
            if (old == null) additions[name] = data
            else if (!old.data.contentEquals(data)) replacements[old.index] = data
        }
        // Bindings belong to the package, not to one style. Only this declaration may
        // change outside the selected variant; their records and pixels stay untouched.
        FaceResources.variantEntries(target).forEach { entry ->
            if (StyleHeader.parse(entry).fontBindingCount != fonts.bindingCount) {
                replacements[entry.index] = (replacements[entry.index] ?: entry.data).copyOf().also {
                    it[0x11] = fonts.bindingCount.toByte()
                }
            }
        }
        val edit = StructuralEditor.rebuild(target, replacements, additions)
        val after = edit.container.entryByBasename(targetVariant)
        val afterRecords = FaceRecordParser.scanWidgets(after)
        oldRecords.forEachIndexed { index, old ->
            val now = afterRecords[index]
            check(destination.data.copyOfRange(old.recordOffset, old.recordOffset + old.recordSize)
                .contentEquals(after.data.copyOfRange(now.recordOffset, now.recordOffset + now.recordSize)))
        }
        val newStart = StyleHeader.parse(after).storedImageOffset
        check(oldImages.contentEquals(after.data.copyOfRange(newStart, newStart + oldImages.size)))
        val beforeIssues = crossResourceIssues(target).groupingBy { it.code to it.entryIndex }.eachCount()
        if (crossResourceIssues(edit.container).groupingBy { it.code to it.entryIndex }.eachCount()
                .any { (key, count) -> count > beforeIssues.getOrDefault(key, 0) })
            throw Fit3FormatException("The import introduced an invalid resource reference.")
        val baselineRaw = raw.copyOf().also { it.putU16(0x0E, 0) }
        pointers.forEach { field ->
            baselineRaw.putU32(field.offset - record.recordOffset, copied.getValue(field.image.index).first)
        }
        val baselineData = styleBytes(destination.data, baselineRaw, 1, imageSuffix, fonts.bindingCount)
        val baseline = destination.copy(data = baselineData, size = baselineData.size)
        val resultGuide = FaceRecordParser.widgetGuides(after).last()
        check(resultGuide.drawLeft == guide.drawLeft && resultGuide.drawTop == guide.drawTop)
        return WidgetImportEdit(edit, oldRecords.size, baseline, additions)
    }

    private data class Fonts(val resources: Map<String, ByteArray>, val bindingCount: Int)

    private fun mergeFonts(target: Fit3Container, donor: Fit3Container, raw: ByteArray, type: Int): Fonts {
        val bindings = FaceResources.fontBindings(target)
        if (bindings.map { FaceResources.bindingNumber(it.basename) } != bindings.indices.toList() ||
            bindings.size !in 0..StyleHeader.MAX_FONT_BINDINGS)
            throw Fit3FormatException("The target font table is not supported.")
        val field = when (type) { 5 -> 0x28; 13 -> 0x5E; else -> return Fonts(emptyMap(), bindings.size) }
        val selected = donor.entryByBasename("font_${raw[field].toInt() and 255}.bin")
        FontBinding.parse(selected)
        val resources = linkedMapOf<String, ByteArray>()
        val existing = bindings.indexOfFirst { it.data.contentEquals(selected.data) }
        val index = if (existing >= 0) existing else bindings.size.also {
            if (it >= StyleHeader.MAX_FONT_BINDINGS)
                throw Fit3FormatException("This import needs another font binding, but the face already uses all 10.")
            resources["font_$it.bin"] = selected.data.copyOf()
        }
        raw[field] = index.toByte()
        val fields = dictionaryFields(raw, type)
        if (fields.isNotEmpty()) {
            val current = dictionaries(target)
            val incoming = dictionaries(donor)
            if (incoming.isEmpty()) throw Fit3FormatException("This widget's text dictionary is missing.")
            val delta = current.values.firstOrNull()?.second?.items?.size ?: 0
            if (current.values.map { it.second.items.size }.distinct().size > 1 ||
                incoming.values.map { it.second.items.size }.distinct().size != 1)
                throw Fit3FormatException("Localized dictionaries do not have matching index tables.")
            val currentFallback = current["font_en.bin"]
            val donorFallback = incoming["font_en.bin"]
            (current.keys + incoming.keys).toSortedSet().forEach { name ->
                val left = current[name] ?: currentFallback
                val right = incoming[name] ?: donorFallback
                if (left == null && current.isNotEmpty() || right == null)
                    throw Fit3FormatException("A required locale has no English fallback dictionary.")
                val prefix = left?.second?.items.orEmpty()
                val suffix = right.second.items
                fields.forEach { f ->
                    if (raw.u16(f) >= suffix.size) throw Fit3FormatException("A donor text reference is out of range.")
                }
                validateDynamicRanges(raw, type, suffix.size)
                resources[name] = dictionaryBytes((left ?: right).first.data, prefix + suffix)
            }
            fields.forEach { f ->
                val shifted = raw.u16(f) + delta
                if (shifted >= 0xFFFF) throw Fit3FormatException("The dictionary index space is exhausted.")
                raw.putU16(f, shifted)
            }
        }
        return Fonts(resources, maxOf(bindings.size, index + 1))
    }

    private fun dictionaries(container: Fit3Container) = FaceResources.dictionaries(container)
        .associate { entry ->
            val dictionary = LocaleDictionary.parse(entry)
            // The normal reader tolerates malformed UTF-8 for inspection. An import
            // re-emits strings, so accepting replacement characters would lose bytes.
            dictionary.items.forEachIndexed { index, text ->
                val offset = entry.data.u32(28 + index * 8).toInt()
                val length = entry.data.u32(24 + index * 8).toInt()
                if (!text.encodeToByteArray().contentEquals(entry.data.copyOfRange(offset, offset + length)))
                    throw Fit3FormatException("A donor or target dictionary is not valid UTF-8.")
            }
            entry.basename to (entry to dictionary)
        }

    /** Numeric Composite bases are presence flags, not dictionary references. */
    private fun dictionaryFields(raw: ByteArray, type: Int): List<Int> = buildList {
        fun live(offset: Int) { if (raw.u16(offset) != 0xFFFF) add(offset) }
        if (type == 5) live(0x2C)
        if (type == 13) {
            for (part in listOf(0x24, 0x30, 0x3C, 0x48)) {
                live(part + 2); live(part + 4)
                if (raw[part + 8] == 0.toByte()) live(part + 6)
            }
            if (raw.u16(0x62) !in listOf(0, 0xFFFF)) add(0x62)
        }
    }

    private fun validateDynamicRanges(raw: ByteArray, type: Int, count: Int) {
        fun check(source: Int, base: Int) {
            if (base == 0xFFFF) return
            val maximum = when (source) {
                0 -> 0; 5, 125 -> 1; 17 -> 6; 21 -> 11
                // These bounds are not a finite word table; do not guess from a sample.
                else -> throw Fit3FormatException("Dictionary indexing for data source $source is not supported for import.")
            }
            if (base + maximum >= count) throw Fit3FormatException("The donor's dynamic text table is incomplete.")
        }
        if (type == 5) check(raw.u32(4).toInt(), raw.u16(0x2C))
        if (type == 13) {
            for (part in listOf(0x24, 0x30, 0x3C, 0x48))
                if (raw[part + 8] == 0.toByte() && raw.u16(part) != 0xFFFF)
                    check(raw.u16(part), raw.u16(part + 6))
        }
    }

    private fun dictionaryBytes(template: ByteArray, strings: List<String>): ByteArray {
        val encoded = strings.map { it.toByteArray(Charsets.UTF_8) }
        if (encoded.any { it.size !in 1..LocaleDictionary.MAX_ITEM_BYTES })
            throw Fit3FormatException("A dictionary item exceeds the firmware byte limit.")
        val prefix = template.copyOfRange(0, 24) + ByteArray(strings.size * 8)
        prefix.putU32(8, strings.size)
        var cursor = prefix.size
        encoded.forEachIndexed { i, bytes ->
            prefix.putU32(24 + i * 8, bytes.size); prefix.putU32(28 + i * 8, cursor)
            cursor += bytes.size
        }
        return ByteArrayOutputStream().apply { write(prefix); encoded.forEach(::write) }.toByteArray()
    }

    internal fun styleBytes(template: ByteArray, records: ByteArray, count: Int,
        images: ByteArray, fonts: Int): ByteArray =
        (template.copyOfRange(0, STYLE_HEADER_SIZE) + records + images).also {
            it.putU32(4, count); it.putU32(8, records.size); it.putU32(12, images.size)
            it[0x11] = fonts.toByte(); it.putU32(20, STYLE_HEADER_SIZE + records.size)
        }

    private fun signed(raw: ByteArray, field: Int, value: Int) {
        if (value !in Short.MIN_VALUE..Short.MAX_VALUE)
            throw Fit3FormatException("Imported placement exceeds the coordinate range.")
        raw.putU16(field, value and 0xFFFF)
    }
}
