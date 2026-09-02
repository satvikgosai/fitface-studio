package dev.fitface.studio.core.format

import java.nio.charset.StandardCharsets

/**
 * The container's non-style resources, and the joins between them.
 *
 * A face is not only its widget streams. `setting.bin` says how many selectable styles
 * there are, `preview.bin` carries one fixed-size picture per style, each style declares
 * how many numbered font bindings it expects, and the locale files hold the strings a
 * live text widget indexes. Those counts have to agree with each other, and until now
 * nothing in the app checked that they did — a container could lose a style's preview or
 * name a font binding that was not there and still pass validation.
 */

/** `setting.bin` — always exactly 256 bytes. */
data class SettingRecord(
    val faceId: String,
    val version: Int,
    val styleCount: Int,
    val defaultStyle: Int,
    /** Two bytes the watch copies into its own record and never reads. `0xFF` in every
     * catalogue package; preserved verbatim rather than normalised. */
    val managerProperties: Pair<Int, Int>,
) {
    companion object {
        const val SIZE = 256

        fun parse(entry: ContainerEntry): SettingRecord {
            val data = entry.data
            if (data.size != SIZE) {
                throw Fit3FormatException("setting.bin is ${data.size} bytes, expected $SIZE")
            }
            val id = data.copyOfRange(0x10, 0x20)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(StandardCharsets.US_ASCII)
            return SettingRecord(
                faceId = id,
                // Read signed: the field is formatted as a signed decimal, and one
                // catalogue package stores ASCII "1000" here, which reads as 808464433.
                version = data.u32(0x30).toInt(),
                styleCount = data[0x34].toInt() and 0xFF,
                defaultStyle = data[0x35].toInt() and 0xFF,
                managerProperties = (data[0x36].toInt() and 0xFF) to (data[0x37].toInt() and 0xFF),
            )
        }
    }
}

/** The 24-byte header every `styleN.bin` and `aod.bin` opens with. */
data class StyleHeader(
    val widgetCount: Int,
    val widgetBytes: Int,
    val imageBytes: Int,
    /**
     * How many `font_N.bin` bindings the style expects, from the single byte at `+0x11`.
     *
     * The whole word reads as `count << 8`, which is why it looked like an opaque
     * `0x100`, `0x200`, … for so long. Valid counts are 1 to 10, and a style always
     * expects at least one even when every widget in it is a raster.
     */
    val fontBindingCount: Int,
    val storedImageOffset: Int,
) {
    /** Where the image section starts, which is what a raster pointer is relative to. */
    val computedImageOffset: Int get() = STYLE_HEADER_SIZE + widgetBytes

    companion object {
        const val MAX_FONT_BINDINGS = 10

        fun parse(entry: ContainerEntry): StyleHeader {
            val data = entry.data
            if (data.size < STYLE_HEADER_SIZE) {
                throw Fit3FormatException("${entry.basename}: shorter than its 24-byte header")
            }
            return StyleHeader(
                widgetCount = data.u32(0x04).checkedInt("widget count"),
                widgetBytes = data.u32(0x08).checkedInt("widget bytes"),
                imageBytes = data.u32(0x0C).checkedInt("image bytes"),
                fontBindingCount = data[0x11].toInt() and 0xFF,
                storedImageOffset = data.u32(0x14).checkedInt("image offset"),
            )
        }
    }
}

/**
 * `preview.bin` — the pictures the watch's own face picker shows.
 *
 * Every record is the same size, and that is load-bearing rather than incidental: the
 * picker seeks to `styleIndex * stride` for the header and then computes the payload
 * position from the size the header declares. Both calculations agree only while a
 * record is exactly [RECORD_STRIDE] bytes, so a preview of a different size would leave
 * the picker reading a header from one record and pixels from somewhere else.
 */
object PreviewStream {
    const val WIDTH = 178
    const val HEIGHT = 280
    const val PAYLOAD_SIZE = WIDTH * HEIGHT * 2 + 4
    const val RECORD_STRIDE = PAYLOAD_SIZE + IMAGE_HEADER_SIZE

    /** How many previews [entry] carries, or null when it is not a canonical stream. */
    fun recordCount(entry: ContainerEntry): Int? =
        if (entry.data.size % RECORD_STRIDE == 0) entry.data.size / RECORD_STRIDE else null

    fun isCanonical(entry: ContainerEntry): Boolean {
        val count = recordCount(entry) ?: return false
        if (count == 0) return false
        return (0 until count).all { index ->
            val base = index * RECORD_STRIDE
            entry.data.u16(base) == WIDTH &&
                entry.data.u16(base + 2) == HEIGHT &&
                entry.data.u16(base + 4) == IMAGE_RGB565 &&
                entry.data.u32(base + 8) == PAYLOAD_SIZE.toLong()
        }
    }
}

/**
 * `font_N.bin` — a 92-byte record naming a font the watch already has.
 *
 * It carries no glyphs. That is why no amount of editing can put a new typeface on the
 * watch: the record selects one of the firmware's own families at a requested pixel size,
 * and an unsupported combination falls back rather than failing.
 */
data class FontBinding(
    /**
     * One family selector per watch language.
     *
     * Byte 1 is the one that matters in practice: the watch checks it first and uses it
     * immediately when it is nonzero, and every catalogue binding that populates any
     * language slot also populates that one.
     */
    val familyByLanguage: ByteArray,
    val roleName: String,
    val pixelSize: Int,
) {
    val family: Int get() = familyByLanguage[1].toInt() and 0xFF

    companion object {
        const val SIZE = 92
        const val LANGUAGE_SLOTS = 72

        fun parse(entry: ContainerEntry): FontBinding {
            val data = entry.data
            if (data.size != SIZE) {
                throw Fit3FormatException(
                    "${entry.basename} is ${data.size} bytes, expected $SIZE",
                )
            }
            return FontBinding(
                familyByLanguage = data.copyOfRange(0, LANGUAGE_SLOTS),
                roleName = data.copyOfRange(0x48, 0x58)
                    .takeWhile { it != 0.toByte() }
                    .toByteArray()
                    .toString(StandardCharsets.US_ASCII),
                pixelSize = data.u32(0x58).checkedInt("font pixel size"),
            )
        }
    }
}

/**
 * `font_<locale>.bin` — the strings a live text widget indexes, not a font.
 *
 * A Value widget picks item `base + reading`, and a Composite picks fixed items plus one
 * per part, so the indices are part of the design of the widgets that use them. An item
 * has to be 1 to 64 bytes of UTF-8 to be usable.
 */
data class LocaleDictionary(
    val locale: String,
    val items: List<String>,
) {
    companion object {
        const val HEADER_SIZE = 24
        const val MAX_ITEM_BYTES = 64

        fun parse(entry: ContainerEntry): LocaleDictionary {
            val data = entry.data
            if (data.size < HEADER_SIZE) {
                throw Fit3FormatException("${entry.basename}: shorter than its header")
            }
            val count = data.u32(0x08).checkedInt("dictionary item count")
            val firstPayload = HEADER_SIZE + count * 8
            if (count <= 0 || firstPayload > data.size) {
                throw Fit3FormatException(
                    "${entry.basename}: declares $count items, which do not fit",
                )
            }
            var expected = firstPayload
            val items = (0 until count).map { index ->
                val length = data.u32(HEADER_SIZE + index * 8).checkedInt("item length")
                val offset = data.u32(HEADER_SIZE + index * 8 + 4).checkedInt("item offset")
                if (offset != expected) {
                    throw Fit3FormatException(
                        "${entry.basename}: item $index starts at $offset, expected $expected",
                    )
                }
                if (length !in 1..MAX_ITEM_BYTES || offset + length > data.size) {
                    throw Fit3FormatException(
                        "${entry.basename}: item $index is $length bytes at $offset",
                    )
                }
                expected = offset + length
                data.copyOfRange(offset, offset + length).toString(StandardCharsets.UTF_8)
            }
            if (expected != data.size) {
                throw Fit3FormatException(
                    "${entry.basename}: ${data.size - expected} bytes after the last item",
                )
            }
            return LocaleDictionary(
                locale = entry.basename.removePrefix("font_").removeSuffix(".bin"),
                items = items,
            )
        }
    }
}

/** Which of the container's entries is which kind of resource. */
object FaceResources {
    fun settingOrNull(container: Fit3Container): ContainerEntry? =
        container.entries.singleOrNull { it.basename == "setting.bin" }

    fun previewOrNull(container: Fit3Container): ContainerEntry? =
        container.entries.singleOrNull { it.basename == "preview.bin" }

    /** `style0.bin`, `style1.bin`, … in numeric order. */
    fun selectableStyles(container: Fit3Container): List<ContainerEntry> =
        container.entries
            .filter { STYLE_NAME.matches(it.basename) }
            .sortedBy { styleNumber(it.basename) }

    fun aodOrNull(container: Fit3Container): ContainerEntry? =
        container.entries.singleOrNull { it.basename == "aod.bin" }

    /** Every entry with a widget stream: the selectable styles and the always-on face. */
    fun styleEntries(container: Fit3Container): List<ContainerEntry> =
        selectableStyles(container) + listOfNotNull(aodOrNull(container))

    fun fontBindings(container: Fit3Container): List<ContainerEntry> =
        container.entries
            .filter { BINDING_NAME.matches(it.basename) }
            .sortedBy { bindingNumber(it.basename) }

    fun dictionaries(container: Fit3Container): List<ContainerEntry> =
        container.entries.filter {
            it.basename.startsWith("font_") &&
                it.basename.endsWith(".bin") &&
                !BINDING_NAME.matches(it.basename)
        }

    fun styleNumber(basename: String): Int =
        STYLE_NAME.matchEntire(basename)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    fun bindingNumber(basename: String): Int =
        BINDING_NAME.matchEntire(basename)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    private val STYLE_NAME = Regex("""style(\d+)\.bin""")
    private val BINDING_NAME = Regex("""font_(\d+)\.bin""")
}

/**
 * Everything one resource says about another, checked.
 *
 * Reported as warnings, deliberately. Every one of these holds across the whole live
 * catalogue, so nothing here should ever fire on a face the app can open today — but
 * `validate()` gates delivery, and a container that is merely *unusual* must not become
 * uninstallable because a join this layer has only ever seen one shape of disagrees.
 * The app's own edits are held to their exact byte ranges by the structural invariants
 * instead, which is where an error belongs.
 */
internal fun crossResourceIssues(container: Fit3Container): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    fun warn(code: String, message: String, entryIndex: Int? = null) {
        issues += ValidationIssue(ValidationIssue.Severity.WARNING, code, message, entryIndex)
    }

    val styles = FaceResources.selectableStyles(container)
    val bindings = FaceResources.fontBindings(container)
    val setting = FaceResources.settingOrNull(container)
    val preview = FaceResources.previewOrNull(container)

    val declaredStyles = setting?.let { entry ->
        runCatching { SettingRecord.parse(entry) }
            .onFailure { warn("setting_unreadable", it.message ?: "unreadable", entry.index) }
            .getOrNull()
    }?.let { record ->
        if (record.styleCount != styles.size) {
            warn(
                "style_count_mismatch",
                "setting.bin declares ${record.styleCount} styles, the package carries " +
                    "${styles.size}",
                setting.index,
            )
        }
        if (styles.isNotEmpty() && record.defaultStyle >= styles.size) {
            warn(
                "default_style_out_of_range",
                "setting.bin selects style ${record.defaultStyle} of ${styles.size}",
                setting.index,
            )
        }
        record.styleCount
    }

    if (styles.map { FaceResources.styleNumber(it.basename) } != styles.indices.toList()) {
        warn(
            "style_numbering",
            "selectable styles are not numbered consecutively from zero: " +
                styles.joinToString { it.basename },
        )
    }
    if (bindings.map { FaceResources.bindingNumber(it.basename) } != bindings.indices.toList()) {
        warn(
            "font_numbering",
            "numbered fonts are not consecutive from zero: " +
                bindings.joinToString { it.basename },
        )
    }

    preview?.let { entry ->
        if (!PreviewStream.isCanonical(entry)) {
            warn(
                "preview_not_canonical",
                "preview.bin is not a stream of ${PreviewStream.RECORD_STRIDE}-byte " +
                    "${PreviewStream.WIDTH}x${PreviewStream.HEIGHT} records",
                entry.index,
            )
        } else {
            val count = PreviewStream.recordCount(entry)
            val expected = declaredStyles ?: styles.size
            if (count != expected) {
                warn(
                    "preview_count_mismatch",
                    "preview.bin holds $count pictures for $expected selectable styles",
                    entry.index,
                )
            }
        }
    }

    bindings.forEach { entry ->
        runCatching { FontBinding.parse(entry) }
            .onFailure { warn("font_binding_unreadable", it.message ?: "unreadable", entry.index) }
    }
    FaceResources.dictionaries(container).forEach { entry ->
        runCatching { LocaleDictionary.parse(entry) }
            .onFailure { warn("dictionary_unreadable", it.message ?: "unreadable", entry.index) }
    }
    val smallestDictionary = FaceResources.dictionaries(container)
        .mapNotNull { runCatching { LocaleDictionary.parse(it).items.size }.getOrNull() }
        .minOrNull()

    FaceResources.styleEntries(container).forEach { entry ->
        val header = runCatching { StyleHeader.parse(entry) }
            .onFailure { warn("style_header_unreadable", it.message ?: "unreadable", entry.index) }
            .getOrNull() ?: return@forEach
        if (header.fontBindingCount !in 1..StyleHeader.MAX_FONT_BINDINGS) {
            warn(
                "font_count_out_of_range",
                "${entry.basename} expects ${header.fontBindingCount} font bindings",
                entry.index,
            )
        } else if (header.fontBindingCount != bindings.size) {
            warn(
                "font_count_mismatch",
                "${entry.basename} expects ${header.fontBindingCount} font bindings, the " +
                    "package carries ${bindings.size}",
                entry.index,
            )
        }
        if (header.storedImageOffset != header.computedImageOffset) {
            warn(
                "image_offset_mismatch",
                "${entry.basename} stores image offset ${header.storedImageOffset}, its " +
                    "widget stream ends at ${header.computedImageOffset}",
                entry.index,
            )
        }
        val records = runCatching { FaceRecordParser.scanWidgets(entry) }
            .onFailure { warn("widgets_unreadable", it.message ?: "unreadable", entry.index) }
            .getOrNull() ?: return@forEach
        if (records.map(WidgetRecord::globalIndex) != records.indices.toList()) {
            warn(
                "widget_indices",
                "${entry.basename} does not number its widgets consecutively from zero",
                entry.index,
            )
        }
        val images = runCatching { FaceRecordParser.scanImages(entry) }
            .onFailure { warn("images_unreadable", it.message ?: "unreadable", entry.index) }
            .getOrNull() ?: return@forEach
        if (images.any { it.opaqueTrailerSize != 4 }) {
            warn(
                "image_trailer",
                "${entry.basename} has a raster whose trailer is not the canonical 4 bytes",
                entry.index,
            )
        }
        val relative = FaceRecordParser.imagesByRelativeOffset(entry)
        records.forEach { record ->
            val spec = WidgetSchema.spec(record.widgetType)
            if (spec.pointers !is WidgetSchema.PointerLayout.None) {
                runCatching { FaceRecordParser.imagePointerFields(record, relative) }
                    .onFailure {
                        warn(
                            "unresolved_image_pointer",
                            "${entry.basename} widget ${record.globalIndex}: " +
                                (it.message ?: "pointer does not resolve"),
                            entry.index,
                        )
                    }
            }
            record.fontBindingIndex?.let { index ->
                if (index >= bindings.size) {
                    warn(
                        "font_index_out_of_range",
                        "${entry.basename} widget ${record.globalIndex} selects font $index " +
                            "of ${bindings.size}",
                        entry.index,
                    )
                }
            }
            if (smallestDictionary != null) {
                record.dictionaryIndices.filter { it >= smallestDictionary }.forEach { index ->
                    warn(
                        "dictionary_index_out_of_range",
                        "${entry.basename} widget ${record.globalIndex} reads string $index, " +
                            "and one locale carries only $smallestDictionary",
                        entry.index,
                    )
                }
            }
        }
    }
    return issues
}
