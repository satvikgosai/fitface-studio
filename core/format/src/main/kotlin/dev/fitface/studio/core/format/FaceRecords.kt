package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.DataSourceLabels
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.WidgetCategory
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WidgetImageLayer
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.WidgetResizeKind
import kotlin.math.abs

const val STYLE_MAGIC = 0x12345678L

/** Two bytes per pixel, little-endian RGB565, no alpha channel. */
const val IMAGE_RGB565 = 0x0082

/** Three bytes per pixel: little-endian RGB565 followed by one alpha byte. */
const val IMAGE_RGB565_ALPHA = 0x0080

/**
 * 256-entry BGRA palette followed by one index byte per pixel. Rare — only one
 * raster in the whole 100-face live catalogue uses it (face `00002` style0
 * background) — but a face containing it cannot be opened at all without it.
 */
const val IMAGE_INDEXED8 = 0x0088

const val INDEXED_PALETTE_ENTRIES = 256
const val INDEXED_PALETTE_BYTES = INDEXED_PALETTE_ENTRIES * 4
const val IMAGE_HEADER_SIZE = 12
const val STYLE_HEADER_SIZE = 24

/**
 * The four bytes every raster record ends with, after its pixels.
 *
 * Zero in all 6,315 rasters of the catalogue. A resize copies them through verbatim and
 * refuses a raster that carries a different number of them, because a trailer of another
 * length is a record shape this app has never seen.
 */
const val OPAQUE_TRAILER_BYTES = 4

/**
 * Where [WidgetRecord.words] starts, which is `0x24`.
 *
 * Not a fixed header size, whatever the name suggests: a record's common prefix ends at
 * `0x18`, and everything from there is type-specific. This constant survives only
 * because `words` is a raw view over the tail for code that moves bytes; anything
 * reading meaning goes through [WidgetSchema].
 */
const val WIDGET_FIXED_SIZE = 36

const val WIDGET_STATIC = 1
const val WIDGET_ANIMATION = 4
const val WIDGET_VECTOR_ARC = 6
const val WIDGET_SOURCE_GROUP = 9
const val WIDGET_HAND = 2
const val WIDGET_SPRITE = 3
const val WIDGET_PAIR = 5
const val WIDGET_BADGE = 7
const val WIDGET_COMP = 13
const val WIDGET_ARC = 16
const val WIDGET_LINE_BAR = 17

data class ImageRecord(
    val index: Int,
    val recordOffset: Int,
    val pixelOffset: Int,
    val width: Int,
    val height: Int,
    val format: Int,
    val reserved: Int,
    val dataSize: Int,
    val pixelDataSize: Int,
    val opaqueTrailerSize: Int,
) {
    val bytesPerPixel: Int
        get() = when (format) {
            IMAGE_RGB565 -> 2
            IMAGE_RGB565_ALPHA -> 3
            IMAGE_INDEXED8 -> 1
            else -> throw Fit3FormatException("unsupported image format 0x${format.toString(16)}")
        }

    val isIndexed: Boolean get() = format == IMAGE_INDEXED8

    /** Palette bytes that precede the pixel bytes inside the record payload. */
    val paletteSize: Int get() = if (isIndexed) INDEXED_PALETTE_BYTES else 0

    /** Offset of the first pixel byte, skipping any palette. */
    val samplesOffset: Int get() = pixelOffset + paletteSize

    /**
     * Whether the watch blits this raster with per-pixel transparency. Plain
     * RGB565 has no alpha, so the watch always paints its full rectangle.
     */
    val hasAlphaChannel: Boolean get() = format != IMAGE_RGB565

    companion object {
        fun payloadSize(format: Int, width: Int, height: Int): Int {
            val samples = when (format) {
                IMAGE_RGB565 -> 2
                IMAGE_RGB565_ALPHA -> 3
                IMAGE_INDEXED8 -> 1
                else -> throw Fit3FormatException(
                    "unsupported image format 0x${format.toString(16)}",
                )
            }
            val palette = if (format == IMAGE_INDEXED8) INDEXED_PALETTE_BYTES else 0
            return try {
                Math.addExact(palette, Math.multiplyExact(Math.multiplyExact(width, height), samples))
            } catch (error: ArithmeticException) {
                throw Fit3FormatException("image size overflow", error)
            }
        }
    }
}

/**
 * A widget positioned against another widget's rectangle rather than the panel.
 *
 * Four types carry one of these — Static, Hand, Value and Composite — and it is live
 * unless [code] is [WidgetSchema.ALIGNMENT_DISABLED]. When it is live the record's
 * `x`/`y` are **offsets from the target's rectangle**, and [targetGlobalIndex] names the
 * widget they are measured from. A target that is not an earlier record in the same
 * style falls back to the panel, which is a real and common state, not an error: the
 * catalogue's own faces use target values that name no record at all.
 *
 * This is the field the format layer used to insist did not exist. Every Static, Hand,
 * Value and Composite record in the catalogue has one, and all of them are live.
 */
data class AlignmentRef(val code: Int, val targetGlobalIndex: Int) {
    val isLive: Boolean get() = code != WidgetSchema.ALIGNMENT_DISABLED
}

data class WidgetRecord(
    val ordinal: Int,
    val recordOffset: Int,
    val recordSize: Int,
    val globalIndex: Int,
    val widgetType: Int,
    /**
     * `+0x04` verbatim. It selects one of the watch's own live readings — see
     * [dev.fitface.studio.core.model.DataSourceLabels] — and a container cannot
     * introduce a new one. The name is historical; [sourceId] is the value that means
     * something. No catalogue record uses the high half.
     */
    val sequenceId: Int,
    val x: Int,
    val y: Int,
    /**
     * `+0x1C` and `+0x1E` verbatim, unsigned.
     *
     * Deliberately not called `width`/`height`: this pair is a signed extent only on the
     * five types that draw their own geometry ([storedWidth]/[storedHeight]). On a Static
     * or a Hand it is the [alignment] code and target index, and on a Rule it is the
     * second endpoint. Reading it as an extent everywhere is what let an alignment code
     * be reported as a widget's width.
     */
    val raw1C: Int,
    val raw1E: Int,
    val unknown20: Long,
    /**
     * The record tail as 32-bit words from `+0x24`, for code that moves bytes rather
     * than reading meaning. Prefer the named accessors and [WidgetSchema] for anything
     * semantic — a word index is not a field name, and the same word is a colour on one
     * type and a raster pointer on another.
     */
    val words: List<Long>,
    val alignment: AlignmentRef? = null,
    /** The type's own signed width, or null for a type that stores no extent. */
    val storedWidth: Int? = null,
    val storedHeight: Int? = null,
    /** Frames a Sprite or an Animation indexes, from its own count byte. */
    val frameCount: Int? = null,
) {
    /** The live reading this record follows. */
    val sourceId: Int get() = sequenceId and 0xFFFF

    /** The alignment reference, but only when it is actually in effect. */
    val liveAlignment: AlignmentRef? get() = alignment?.takeIf(AlignmentRef::isLive)

    /** A Value or Composite's stored text colour, `0xAARRGGBB`. */
    val textColorArgb: Long?
        get() = when (widgetType) {
            WIDGET_PAIR -> words.getOrNull(0)
            WIDGET_COMP -> words.getOrNull((0x58 - 0x24) / 4)
            else -> null
        }

    /** A Rule's stored line thickness. */
    val ruleThickness: Int?
        get() = if (widgetType == WIDGET_BADGE) {
            words.getOrNull((0x30 - 0x24) / 4)?.toInt()?.and(0xFF)
        } else {
            null
        }

    /** Byte offset of [field] inside the entry this record was read from. */
    fun fieldOffset(field: Int): Int = recordOffset + field

    /**
     * A byte or halfword of the type-specific tail, by its offset in the record.
     *
     * Both read out of [words], which is safe for either width because every field the
     * format defines sits at an even offset and no halfword straddles a word boundary.
     * Offsets below `+0x24` are the common prefix and have named properties instead.
     */
    private fun byteAt(offset: Int): Int? = wordFor(offset)?.let { (word, shift) ->
        ((word ushr shift) and 0xFF).toInt()
    }

    private fun u16At(offset: Int): Int? = wordFor(offset)?.let { (word, shift) ->
        ((word ushr shift) and 0xFFFF).toInt()
    }

    private fun wordFor(offset: Int): Pair<Long, Int>? {
        if (offset < WIDGET_FIXED_SIZE) return null
        val relative = offset - WIDGET_FIXED_SIZE
        return words.getOrNull(relative / 4)?.let { it to (relative % 4) * 8 }
    }

    /** Which `font_N.bin` a text widget draws with, or null for a type that has none. */
    val fontBindingIndex: Int?
        get() = when (widgetType) {
            WIDGET_PAIR -> byteAt(0x28)
            WIDGET_COMP -> byteAt(0x5E)
            else -> null
        }

    /**
     * Locale-dictionary items this record reads, as far as they can be known statically.
     *
     * A Value's base is added to its live reading, and a Composite's dynamic parts do the
     * same, so these are the lowest items each field can reach rather than the highest.
     * `0xFFFF` means the field is not a dictionary reference at all — a Value with that
     * base formats a number instead — and a Composite's ordering item is also disabled by
     * zero.
     */
    val dictionaryIndices: List<Int>
        get() = when (widgetType) {
            WIDGET_PAIR -> listOfNotNull(u16At(0x2C)?.takeIf { it != DISABLED_INDEX })
            WIDGET_COMP -> COMP_PART_OFFSETS.flatMap { part ->
                listOf(part + 0x02, part + 0x04, part + 0x06).mapNotNull { field ->
                    u16At(field)?.takeIf { it != DISABLED_INDEX }
                }
            } + listOfNotNull(
                u16At(0x62)?.takeIf { it != DISABLED_INDEX && it != 0 },
            )
            else -> emptyList()
        }

    /** The live reading each enabled part of a Composite follows. */
    val compositePartSources: List<Int>
        get() = if (widgetType == WIDGET_COMP) {
            COMP_PART_OFFSETS.mapNotNull { u16At(it)?.takeIf { source -> source != DISABLED_INDEX } }
        } else {
            emptyList()
        }

    private companion object {
        const val DISABLED_INDEX = 0xFFFF
        val COMP_PART_OFFSETS = listOf(0x24, 0x30, 0x3C, 0x48)
    }
}

object FaceRecordParser {
    private val SUPPORTED_IMAGE_FORMATS =
        setOf(IMAGE_RGB565, IMAGE_RGB565_ALPHA, IMAGE_INDEXED8)

    fun scanImages(entry: ContainerEntry): List<ImageRecord> {
        val (sectionStart, sectionEnd) = imageSection(entry)
        var cursor = sectionStart
        val records = mutableListOf<ImageRecord>()
        while (cursor < sectionEnd) {
            if (cursor + IMAGE_HEADER_SIZE > sectionEnd) {
                throw Fit3FormatException("${entry.basename}: truncated image header")
            }
            val width = entry.data.u16(cursor)
            val height = entry.data.u16(cursor + 2)
            val format = entry.data.u16(cursor + 4)
            val reserved = entry.data.u16(cursor + 6)
            val dataSize = entry.data.u32(cursor + 8).checkedInt("image data size")
            if (format !in SUPPORTED_IMAGE_FORMATS) {
                throw Fit3FormatException(
                    "${entry.basename}: unsupported image format 0x${format.toString(16)}",
                )
            }
            if (width == 0 || height == 0) {
                throw Fit3FormatException("${entry.basename}: zero-sized image")
            }
            val expected = ImageRecord.payloadSize(format, width, height)
            val trailer = dataSize - expected
            if (trailer !in 0..16) {
                throw Fit3FormatException(
                    "${entry.basename}: image ${records.size} has $trailer opaque bytes",
                )
            }
            val pixelOffset = cursor + IMAGE_HEADER_SIZE
            if (pixelOffset.toLong() + dataSize > sectionEnd) {
                throw Fit3FormatException("${entry.basename}: image exceeds its section")
            }
            records += ImageRecord(
                index = records.size,
                recordOffset = cursor,
                pixelOffset = pixelOffset,
                width = width,
                height = height,
                format = format,
                reserved = reserved,
                dataSize = dataSize,
                pixelDataSize = expected,
                opaqueTrailerSize = trailer,
            )
            cursor = pixelOffset + dataSize
        }
        if (cursor != sectionEnd) {
            throw Fit3FormatException("${entry.basename}: image scan did not end exactly")
        }
        return records
    }

    fun scanWidgets(entry: ContainerEntry): List<WidgetRecord> {
        if (entry.basename == "preview.bin") return emptyList()
        val data = entry.data
        if (data.size < STYLE_HEADER_SIZE || data.u32(0) != STYLE_MAGIC) {
            throw Fit3FormatException("${entry.basename}: invalid style header")
        }
        val widgetCount = data.u32(4).checkedInt("widget count")
        val widgetBytes = data.u32(8).checkedInt("widget bytes")
        val imageBytes = data.u32(12).checkedInt("image bytes")
        val imageOffset = data.u32(20).checkedInt("image offset")
        if (imageOffset != STYLE_HEADER_SIZE + widgetBytes ||
            imageOffset.toLong() + imageBytes != data.size.toLong()
        ) {
            throw Fit3FormatException("${entry.basename}: inconsistent widget/image sections")
        }
        val records = mutableListOf<WidgetRecord>()
        var cursor = STYLE_HEADER_SIZE
        while (cursor < imageOffset) {
            if (cursor + WidgetSchema.MINIMUM_RECORD_SIZE > imageOffset) {
                throw Fit3FormatException("${entry.basename}: truncated widget")
            }
            val indexSize = data.u32(cursor + 0x0C)
            val recordSize = (indexSize and 0xFFFF).toInt()
            val globalIndex = (indexSize ushr 16).toInt()
            val widgetType = data.u32(cursor).checkedInt("widget type")
            // A record whose type is outside 1..17 is not a widget with an unfamiliar
            // layout, it is a misparse: the watch dispatches exactly those seventeen and
            // ignores everything else. Reading one as a generic record because its size
            // looked plausible is how a broken stream used to reach the canvas.
            val spec = WidgetSchema.specOrNull(widgetType) ?: throw Fit3FormatException(
                "${entry.basename}: widget ${records.size} has unsupported type $widgetType",
            )
            if (recordSize < spec.minimumSize) {
                throw Fit3FormatException(
                    "${entry.basename}: ${spec.name} widget ${records.size} is $recordSize " +
                        "bytes, which is below the ${spec.minimumSize} its fields need",
                )
            }
            if (cursor + recordSize > imageOffset) {
                throw Fit3FormatException("${entry.basename}: widget exceeds stream")
            }
            // Exact, not "at least": every one of the catalogue's 4,034 records is exactly
            // its type's size, a variable frame table included. An extra tail would mean
            // either a field this layer does not know about or a size that disagrees with
            // the frame count, and both are worth refusing rather than reading past.
            spec.expectedSize(data, cursor)?.let { expected ->
                if (recordSize != expected) {
                    throw Fit3FormatException(
                        "${entry.basename}: ${spec.name} widget ${records.size} declares " +
                            "$recordSize bytes, its fields need exactly $expected",
                    )
                }
            }
            val wordCount = (recordSize - WIDGET_FIXED_SIZE).coerceAtLeast(0) / 4
            val alignment = spec.alignment?.let {
                AlignmentRef(
                    code = data.u16(cursor + it.codeOffset),
                    targetGlobalIndex = data.u16(cursor + it.targetOffset),
                )
            }
            records += WidgetRecord(
                ordinal = records.size,
                recordOffset = cursor,
                recordSize = recordSize,
                globalIndex = globalIndex,
                widgetType = widgetType,
                sequenceId = data.u32(cursor + 4).checkedInt("sequence id"),
                // Inert constructors can be only 16 bytes; never borrow geometry from
                // the next record (or read past the stream when the inert one is last).
                x = if (recordSize >= 0x1A) data.i16(cursor + 0x18) else 0,
                y = if (recordSize >= 0x1C) data.i16(cursor + 0x1A) else 0,
                raw1C = if (recordSize >= 0x1E) data.u16(cursor + 0x1C) else 0,
                raw1E = if (recordSize >= 0x20) data.u16(cursor + 0x1E) else 0,
                unknown20 = if (recordSize >= 0x24) data.u32(cursor + 0x20) else 0,
                words = List(wordCount) { word ->
                    data.u32(cursor + WIDGET_FIXED_SIZE + word * 4)
                },
                alignment = alignment,
                storedWidth = if (spec.hasStoredExtent) data.i16(cursor + 0x1C) else null,
                storedHeight = if (spec.hasStoredExtent) data.i16(cursor + 0x1E) else null,
                frameCount = (spec.pointers as? WidgetSchema.PointerLayout.Table)?.let {
                    data[cursor + it.countOffset].toInt() and 0xFF
                },
            )
            cursor += recordSize
        }
        if (cursor != imageOffset || records.size != widgetCount) {
            throw Fit3FormatException(
                "${entry.basename}: declared $widgetCount widgets, parsed ${records.size}",
            )
        }
        return records
    }

    fun decodeImage(entry: ContainerEntry, image: ImageRecord): PreviewFrame {
        val pixels = IntArray(image.width * image.height)
        if (image.isIndexed) {
            val palette = decodePalette(entry.data, image.pixelOffset)
            repeat(pixels.size) { index ->
                pixels[index] = palette[entry.data[image.samplesOffset + index].toInt() and 0xFF]
            }
            return PreviewFrame(image.width, image.height, pixels)
        }
        repeat(pixels.size) { index ->
            val offset = image.samplesOffset + index * image.bytesPerPixel
            val rgb565 = entry.data.u16(offset)
            val red = (((rgb565 ushr 11) and 0x1F) * 255 + 15) / 31
            val green = (((rgb565 ushr 5) and 0x3F) * 255 + 31) / 63
            val blue = ((rgb565 and 0x1F) * 255 + 15) / 31
            val alpha = if (image.format == IMAGE_RGB565_ALPHA) {
                entry.data[offset + 2].toInt() and 0xFF
            } else {
                0xFF
            }
            pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return PreviewFrame(image.width, image.height, pixels)
    }

    /** Palette entries are stored blue, green, red, alpha. */
    private fun decodePalette(data: ByteArray, offset: Int): IntArray =
        IntArray(INDEXED_PALETTE_ENTRIES) { entry ->
            val base = offset + entry * 4
            val blue = data[base].toInt() and 0xFF
            val green = data[base + 1].toInt() and 0xFF
            val red = data[base + 2].toInt() and 0xFF
            val alpha = data[base + 3].toInt() and 0xFF
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }

    /**
     * Coordinate space the watch renders [entry] in.
     *
     * Read from the container's own declared geometry rather than from raster 0,
     * because a style is not obliged to carry a full-panel background raster.
     * Falls back to the largest raster only for an entry whose path carries no
     * geometry at all.
     */
    fun panelSize(entry: ContainerEntry): PanelSize =
        entry.declaredPanelSize
            ?: scanImages(entry)
                .maxByOrNull { it.width.toLong() * it.height }
                ?.let { PanelSize(it.width, it.height) }
            ?: PanelSize(0, 0)

    /**
     * The full-panel background raster of [entry], or null when the style paints
     * straight onto the watch's black panel.
     *
     * 32 of the corpus's 99 `aod.bin` entries carry one (26 RGB565, 6 RGB565 with
     * alpha) and the other 67 compose over black, same as all of face `00022`'s
     * styles and `00108` styles 0–3. Where an entry does carry one it is raster 0 in
     * every observed container, so this keeps the previous behaviour for those faces.
     */
    fun backgroundImage(entry: ContainerEntry): ImageRecord? {
        val panel = panelSize(entry)
        if (panel.width <= 0 || panel.height <= 0) return null
        return scanImages(entry).firstOrNull {
            it.width == panel.width && it.height == panel.height
        }
    }

    /**
     * How many frames a Sprite indexes.
     *
     * The count is the single byte at `+0x20`; `+0x21..+0x23` are not part of it. This
     * used to read the low 24 bits and then clamp the result to the number of words
     * available, which cannot be wrong on a catalogue face — the scan now requires the
     * record's size to equal `0x24 + 4 × count` exactly — but hid a disagreement between
     * the count and the table instead of refusing it.
     */
    /**
     * The reading a widget follows, named, or null where naming it would be a guess.
     *
     * Only offered for a type that actually follows the common source word, so a
     * Static's registered-but-never-updated source and a Composite's unused common word
     * are not dressed up as a live value the widget shows.
     */
    private fun sourceLabelOf(record: WidgetRecord): String? =
        if (WidgetSchema.spec(record.widgetType).followsCommonSource) {
            DataSourceLabels.labelOrNull(record.sourceId)
        } else {
            null
        }

    private fun spriteFrameCount(record: WidgetRecord): Int =
        if (record.widgetType == WIDGET_SPRITE) record.frameCount ?: 0 else 0

    /**
     * Rasters a widget record addresses, in record order.
     *
     * Only Static and Sprite records address rasters, and each does so a documented
     * way: a Static keeps its single pointer in `+0x20`; a Sprite keeps its frame
     * count in `+0x20` followed by exactly that many pointers. Scanning every
     * type-word instead resolves any word that merely happens to equal a valid
     * section offset — and `words[0]` of a Static is `0x0` in every corpus record,
     * which is the *background* raster's own relative offset.
     */
    /**
     * Every frame a Sprite resize has to rewrite: the record's own, plus every frame
     * reached by a widget sharing one of them, closed over until nothing new appears.
     *
     * A face keeps one glyph pool and points several widgets into it — face `00022`
     * gives the hour's tens digit frames 2–4 and its units digit frames 2–11. They are
     * the same *records*, so there is no resizing one widget's copy; rewriting only the
     * frames the selected sprite names left the neighbour drawing three small glyphs
     * and seven large ones, with its box still reporting the largest.
     */
    /**
     * Every raster a resize of one widget has to rewrite, and every widget that shares
     * them.
     *
     * Rasters are shared *records*: a face keeps one glyph pool and points several widgets
     * into it — face `00022` gives the hour's tens digit frames 2–4 and its units digit
     * frames 2–11 — so there is no resizing one widget's copy. Rewriting only the frames
     * the selected sprite named left the neighbour drawing three small glyphs and seven
     * large ones, with its box still reporting the largest. 740 of the corpus's resizable
     * sprites overlap like this, most often four deep.
     *
     * **The closure is over [imagePointerFields], not [referencedImages].** The narrow map
     * is the one that decides a *drawn extent*, and it deliberately omits an image Arc's
     * and a LineBar's rasters because those are not the rectangle the watch draws. Closing
     * over it therefore returned an empty pool for exactly the two types whose rasters the
     * relocation does move — the mirror image of the bug that left those pointers stale.
     * A record whose pointers do not resolve contributes nothing rather than throwing:
     * this runs over every widget in the entry, including ones no resize will touch.
     */
    internal data class RasterPool(
        /** Image record indices the pool covers. */
        val images: Set<Int>,
        /** Every widget reaching into it, the target included. */
        val widgets: List<WidgetRecord>,
        /**
         * Whether any pointer-bearing record in the entry could not be read.
         *
         * The distinction matters because a record whose pointers do not resolve is
         * invisible to the closure — it contributes neither its rasters nor itself — so a
         * pool that looks exclusive might not be. `relocatePointers` refuses such an entry
         * outright, and this is what lets the capability gate refuse it too, instead of
         * lighting a control whose commit is certain to fail.
         */
        val unreadable: Boolean,
    )

    internal fun rasterPool(
        target: WidgetRecord,
        records: List<WidgetRecord>,
        imagesByRelativeOffset: Map<Long, ImageRecord>,
    ): RasterPool {
        fun rastersOf(record: WidgetRecord): List<Int> =
            runCatching { imagePointerFields(record, imagesByRelativeOffset) }
                .getOrDefault(emptyList())
                .map { it.image.index }
        val reachedBy = records.associateWith(::rastersOf)
        val unreadable = records.any {
            it.widgetType in POINTER_BEARING_TYPES && reachedBy.getValue(it).isEmpty()
        }
        val images = rastersOf(target).toMutableSet()
        while (true) {
            val reached = reachedBy.filterValues { rasters -> rasters.any { it in images } }
                .flatMap { it.value }
            if (!images.addAll(reached)) {
                return RasterPool(
                    images = images,
                    widgets = records.filter { record ->
                        reachedBy.getValue(record).any { it in images }
                    },
                    unreadable = unreadable,
                )
            }
        }
    }

    /** Pixel formats [StructuralEditor] can resample, so the ones a resize may touch. */
    private val RESAMPLED_FORMATS = setOf(IMAGE_RGB565, IMAGE_RGB565_ALPHA, IMAGE_INDEXED8)

    /**
     * Whether every raster a resize of [target] would rewrite matches the proven shape.
     *
     * One list, read by the capability gate and asserted again by the edit itself. All of
     * it is about the *pool*: the new dimensions are written to every raster in it, so
     * rasters that disagree about their format or their trailer cannot all be rewritten
     * from one pair of numbers.
     *
     * The format condition used to be `IMAGE_RGB565_ALPHA` alone, which was never about
     * safety — it was the one format the resampler could read. 620 Sprites, 41% of the
     * catalogue's, were refused for it.
     */
    private fun poolIsResizable(
        pool: RasterPool,
        images: List<ImageRecord>,
        background: ImageRecord?,
        target: WidgetRecord,
    ): Boolean {
        if (pool.images.isEmpty()) return false
        // `relocatePointers` refuses the whole entry when any pointer it is defined to
        // relocate does not resolve, so the gate has to refuse it too. No catalogue face
        // has one — all 6,315 rasters and every pointer to them resolve — but a gate that
        // says yes where the commit says no is the one outcome this pairing exists to
        // prevent.
        if (pool.unreadable) return false
        val poolImages = pool.images.sorted().mapNotNull(images::getOrNull)
        if (poolImages.size != pool.images.size) return false
        // A panel-sized layer belongs to the Background page, and it is reached here
        // through the *wide* pointer map rather than through the drawn extent, so an Arc
        // sharing the background raster would be caught even though its extent is its box.
        if (background != null && background.index in pool.images) return false
        // Only one type may reach into the pool. No pool in the catalogue spans two, and
        // one that did would mean the pool is not the thing this edit takes it for — a
        // Static sharing a digit frame with a Sprite, say.
        if (pool.widgets.any { it.widgetType != target.widgetType }) return false
        val signatures = poolImages.map {
            listOf(it.width, it.height, it.format, it.reserved, it.opaqueTrailerSize)
        }.toSet()
        if (signatures.size != 1) return false
        val sample = poolImages.first()
        return sample.width > 0 &&
            sample.height > 0 &&
            sample.format in RESAMPLED_FORMATS &&
            sample.reserved == 0 &&
            sample.opaqueTrailerSize == OPAQUE_TRAILER_BYTES
    }

    /**
     * The sentence a shared pool earns, or nothing when the widget owns its artwork.
     *
     * Separate from [resizeMessage] because a Hand needs it appended to a message of its
     * own: it is `HIDDEN`, so its arm of the `when` used to swallow the pool warning
     * entirely — for the one type whose result the canvas cannot show, and 18 of the
     * catalogue's 469 share their artwork with another hand.
     */
    private fun sharedPoolSuffix(pool: RasterPool?, target: WidgetRecord): String {
        val sharing = pool?.widgets?.count { it.ordinal != target.ordinal } ?: 0
        if (sharing == 0) return ""
        return " Resizing also resizes $sharing other " +
            "widget${if (sharing == 1) " that shares" else "s that share"} this artwork."
    }

    /**
     * What a resize will actually do, which is not always "resize this widget": the whole
     * pool moves together, so the message has to say how many widgets that is before the
     * user taps.
     */
    private fun resizeMessage(pool: RasterPool, target: WidgetRecord): String {
        val sharing = pool.widgets.count { it.ordinal != target.ordinal }
        return if (sharing == 0) {
            "Move or resize this widget."
        } else {
            "Drag to move." + sharedPoolSuffix(pool, target)
        }
    }

    /**
     * The rasters that decide a widget's **drawn extent**.
     *
     * Deliberately narrower than [imagePointerFields]: an Arc's raster is 310×310 on a
     * 256-wide panel and a LineBar's is a 138×14 strip, so neither describes the
     * rectangle the watch draws — those types keep the extent in `0x1C`/`0x1E`.
     * Measuring them by their raster would report an Arc as larger than the panel,
     * which is the test for "this is the background layer", and the widget would stop
     * being selectable. Use this for geometry; use [imagePointerFields] to move bytes.
     */
    internal fun referencedImages(
        record: WidgetRecord,
        imagesByRelativeOffset: Map<Long, ImageRecord>,
    ): List<ImageRecord> = when (record.widgetType) {
        WIDGET_STATIC -> listOfNotNull(imagesByRelativeOffset[record.unknown20])
        WIDGET_SPRITE -> record.words
            .take(spriteFrameCount(record))
            .mapNotNull(imagesByRelativeOffset::get)
        WIDGET_ANIMATION -> record.words.drop(1)
            .take(record.frameCount ?: 0)
            .mapNotNull(imagesByRelativeOffset::get)
        // A Hand keeps its sweep constant in words[0] and its sprite in words[1] —
        // the only word that resolves to a raster in all 469 corpus Hand records.
        // Resolving it gives the record a real artwork size to report; it does not
        // make the hand drawable, because the watch rotates it about its pivot.
        WIDGET_HAND -> listOfNotNull(
            record.words.getOrNull(1)?.let(imagesByRelativeOffset::get),
        )
        else -> emptyList()
    }

    /** One field inside a widget record that holds an image-section offset. */
    internal data class ImagePointerField(
        /** Byte offset of the field inside the *entry*, ready to patch. */
        val offset: Int,
        val value: Long,
        val image: ImageRecord,
    )

    /**
     * Every field of [record] that holds an image-section offset, so a relocation can
     * rewrite exactly those and nothing else.
     *
     * This is the authoritative pointer map, and it is wider than [referencedImages]:
     * **Arc (`words[4]`) and LineBar (`words[2]`) address rasters too** — 30 and 16
     * records across the corpus, every one resolving and none of them zero. That was
     * missed for a long time because the app never needed their artwork size, and it
     * mattered the moment an edit moved the image section under them: an unrelocated
     * pointer does not fail validation, it just draws nothing.
     *
     * Words that merely *look* like offsets are left alone. `0x0` is image 0's own
     * relative offset, so 681 Static `words[0]`, 734 Pair colour words and every zeroed
     * Comp field "resolve" by coincidence; the faces that ship with a background carry
     * those same zeros beside a real background at offset 0 and render correctly, which
     * is the proof they are not pointers. Rewriting them would corrupt a colour or a
     * glyph binding.
     *
     * Throws when a type that must carry a pointer does not, which is the schema check
     * every structural edit wants before it moves anything.
     */
    internal fun imagePointerFields(
        record: WidgetRecord,
        imagesByRelativeOffset: Map<Long, ImageRecord>,
    ): List<ImagePointerField> {
        val spec = WidgetSchema.spec(record.widgetType)
        fun pointer(fieldOffset: Int, label: String): ImagePointerField {
            val value = record.words.getOrNull((fieldOffset - WIDGET_FIXED_SIZE) / 4)
                ?: throw Fit3FormatException(
                    "${spec.name} widget ${record.ordinal} is too short for its $label pointer",
                )
            val image = imagesByRelativeOffset[value] ?: throw Fit3FormatException(
                "${spec.name} widget ${record.ordinal} $label does not point at a raster",
            )
            return ImagePointerField(
                offset = record.fieldOffset(fieldOffset),
                value = value,
                image = image,
            )
        }
        return when (val layout = spec.pointers) {
            is WidgetSchema.PointerLayout.None -> emptyList()

            is WidgetSchema.PointerLayout.Single -> {
                // A Static keeps its pointer at +0x20, which is the one field this map
                // cannot read out of `words`.
                if (layout.offset == 0x20) {
                    val image = imagesByRelativeOffset[record.unknown20]
                        ?: throw Fit3FormatException(
                            "${spec.name} widget ${record.ordinal} does not point at a raster",
                        )
                    listOf(
                        ImagePointerField(
                            offset = record.fieldOffset(0x20),
                            value = record.unknown20,
                            image = image,
                        ),
                    )
                } else {
                    listOf(pointer(layout.offset, "image"))
                }
            }

            is WidgetSchema.PointerLayout.Table -> {
                val frames = record.frameCount ?: 0
                if (frames <= 0) {
                    throw Fit3FormatException(
                        "${spec.name} widget ${record.ordinal} declares $frames frames",
                    )
                }
                (0 until frames).map {
                    pointer(layout.firstOffset + it * 4, "frame $it")
                }
            }
        }
    }

    /** All artwork referenced by this record, in frame-table order, using the writer's schema. */
    fun resourceImages(entry: ContainerEntry, record: WidgetRecord): List<ImageRecord> =
        imagePointerFields(record, imagesByRelativeOffset(entry)).map { it.image }

    /** Widget types [imagePointerFields] knows the pointer schema of. */
    internal val POINTER_BEARING_TYPES: Set<Int> get() = WidgetSchema.pointerBearingTypes

    /**
     * The rectangle each record covers, keyed by ordinal.
     *
     * Three different things decide it, which is why the old single `width`/`height`
     * pair could not express it: a raster-backed widget is as big as the artwork the
     * watch blits, a Value, Composite, arc or bar carries its own signed extent, and a
     * Rule stores a second endpoint whose span may run backwards.
     */
    private fun drawnExtents(
        records: List<WidgetRecord>,
        imagesByRelativeOffset: Map<Long, ImageRecord>,
    ): Map<Int, DrawnExtent> = records.associate { record ->
        val referenced = referencedImages(record, imagesByRelativeOffset)
        // A raster-backed widget is exactly as big as the raster the watch blits, and
        // faces do leave the stored extent at a placeholder: 00079 stores width 1 for
        // digit sprites whose frames are 52 px wide, and 00022 stores height 20 for
        // frames that are 136 px tall. Trusting the stored value there drew a 1-pixel
        // sliver instead of the widget.
        val rasterWidth = referenced.maxOfOrNull(ImageRecord::width)
        val rasterHeight = referenced.maxOfOrNull(ImageRecord::height)
        record.ordinal to if (record.widgetType == WIDGET_BADGE) {
            // A Rule's +0x1C/+0x1E is its second endpoint, not an extent, and the stored
            // endpoint is the *larger* one in 52 of the catalogue's 84 Rules. So the span
            // is the absolute difference, and the rectangle starts a whole span earlier
            // whenever the stored coordinate is the far end.
            val thickness = record.ruleThickness?.takeIf { it >= 2 } ?: 8
            val endX = record.raw1C.toShort().toInt()
            val endY = record.raw1E.toShort().toInt()
            val width = abs(endX - record.x).coerceAtLeast(thickness)
            val height = abs(endY - record.y).coerceAtLeast(thickness)
            DrawnExtent(
                width = width,
                height = height,
                offsetX = if (endX < record.x) -width else 0,
                offsetY = if (endY < record.y) -height else 0,
            )
        } else {
            DrawnExtent(
                width = rasterWidth ?: record.storedWidth ?: 0,
                height = rasterHeight ?: record.storedHeight ?: 0,
            )
        }
    }

    /**
     * Where each record's stored coordinates are measured from, in this entry.
     *
     * Anything that turns a stored coordinate into a position on the face has to go
     * through this — the canvas, the preview composer and the editor's write-back — or
     * they disagree about where a widget is.
     */
    internal fun placements(entry: ContainerEntry): Map<Int, ResolvedPlacement> {
        val records = scanWidgets(entry)
        val images = imagesByRelativeOffset(entry)
        return WidgetLayout.resolve(records, drawnExtents(records, images), panelSize(entry))
    }

    fun widgetGuides(entry: ContainerEntry): List<WidgetGuide> {
        val records = scanWidgets(entry)
        val images = scanImages(entry)
        val firstImageOffset = images.firstOrNull()?.recordOffset ?: 0
        val imagesByRelativeOffset = images.associateBy {
            (it.recordOffset - firstImageOffset).toLong()
        }
        val panel = panelSize(entry)
        val background = backgroundImage(entry)
        val extents = drawnExtents(records, imagesByRelativeOffset)
        val placements = WidgetLayout.resolve(records, extents, panel)
        return records.map {
            val pairMatches = records.count { candidate ->
                candidate.widgetType == WIDGET_PAIR && candidate.sequenceId == it.sequenceId
            }
            val pairColor = it.words.firstOrNull()?.takeIf { word ->
                word ushr 24 == 0xFFL
            }?.toInt()
            val canEditPair = it.widgetType == WIDGET_PAIR && pairMatches == 1 &&
                pairColor != null
            val referencedImages = referencedImages(it, imagesByRelativeOffset)
            val paintsBackground = background != null &&
                referencedImages.any { image -> image.recordOffset == background.recordOffset }
            val extent = extents.getValue(it.ordinal)
            val place = placements.getValue(it.ordinal)
            val visualWidth = extent.width
            val visualHeight = extent.height
            val drawOffsetX = extent.offsetX
            val drawOffsetY = extent.offsetY
            val placement = when {
                paintsBackground -> WidgetPlacement.BACKGROUND
                // The watch rotates a Hand about the pivot in its `+0x20`, so its
                // artwork bounds are not where it appears. Reporting the size is
                // useful; outlining a rectangle there would be a lie.
                it.widgetType == WIDGET_HAND -> WidgetPlacement.HIDDEN
                visualWidth <= 0 || visualHeight <= 0 -> WidgetPlacement.HIDDEN
                // Panel-sized *artwork* is a background layer — faces 00076 and 00089 each
                // stack two, one Static apiece, which is why this size test exists beside
                // `paintsBackground` at all. A widget that draws no raster is not one
                // however large its stored box is, and requiring the raster closes a
                // one-way door: the resize ladder tops out at 512 px a side, so growing a
                // 400x400 vector arc used to relabel it as the background, after which it
                // could be neither selected nor resized back.
                referencedImages.isNotEmpty() && panel.width > 0 &&
                    visualWidth >= panel.width &&
                    visualHeight >= panel.height -> WidgetPlacement.BACKGROUND
                else -> WidgetPlacement.CANVAS
            }
            // Plain RGB565 frames carry no alpha, so the watch paints the whole
            // rectangle including whatever sits behind the glyphs.
            val opaqueBackdrop = referencedImages.isNotEmpty() &&
                referencedImages.none(ImageRecord::hasAlphaChannel)
            // What resizing this record would rewrite, and whether every part of it
            // matches a shape this app has proven safe to rewrite.
            //
            // The type's own answer comes from [WidgetSchema.ResizeModel] rather than
            // from a `when` here, because this gate and `StructuralEditor.resizeWidget`
            // have to agree exactly: a control the UI lights and the commit refuses is
            // the failure mode this whole path is arranged to avoid, and it used to be
            // guarded by the two places testing for `WIDGET_SPRITE` independently.
            //
            // Every condition below holds for the whole raster *pool*, not for the
            // record's own artwork: several widgets point into one glyph pool and they
            // are the same records, so the edit moves all of them or none.
            val resizeModel = WidgetSchema.spec(it.widgetType).resize
            val resizePool = when (resizeModel) {
                null, is WidgetSchema.ResizeModel.Box, is WidgetSchema.ResizeModel.Endpoint ->
                    null
                else -> rasterPool(it, records, imagesByRelativeOffset)
            }
            val resizeKind = when {
                // A panel-sized layer is replaced from the Background page, which is
                // where an image of the right shape can be chosen for it.
                placement == WidgetPlacement.BACKGROUND -> WidgetResizeKind.NONE
                // Nothing to scale, and nothing to scale it from.
                visualWidth <= 0 || visualHeight <= 0 -> WidgetResizeKind.NONE
                resizeModel == null -> WidgetResizeKind.NONE
                resizeModel is WidgetSchema.ResizeModel.Box -> WidgetResizeKind.FIELDS
                resizeModel is WidgetSchema.ResizeModel.Endpoint ->
                    // A Rule is a line: scaling its endpoint vector needs a span to
                    // scale, and a record whose two endpoints coincide has none.
                    if (it.raw1C.toShort().toInt() != it.x || it.raw1E.toShort().toInt() != it.y) {
                        WidgetResizeKind.FIELDS
                    } else {
                        WidgetResizeKind.NONE
                    }
                resizePool != null && poolIsResizable(resizePool, images, background, it) ->
                    WidgetResizeKind.RASTER
                else -> WidgetResizeKind.NONE
            }
            WidgetGuide(
                ordinal = it.ordinal,
                globalIndex = it.globalIndex,
                type = it.widgetType,
                sequenceId = it.sequenceId,
                x = it.x,
                y = it.y,
                width = visualWidth,
                height = visualHeight,
                recordSize = it.recordSize,
                isFinal = it.ordinal == records.lastIndex,
                // An unresolved alignment code is the one case where the editor cannot
                // say what a stored coordinate means, so it does not offer to change it.
                // No catalogue face contains one.
                canEditPosition = place.isMovable,
                resizeKind = resizeKind,
                placement = placement,
                originX = place.originX,
                originY = place.originY,
                alignedToGlobalIndex = place.targetGlobalIndex,
                drawOffsetX = drawOffsetX,
                drawOffsetY = drawOffsetY,
                category = WidgetCategory.forWidgetType(it.widgetType),
                sourceLabel = sourceLabelOf(it),
                followsReading = WidgetSchema.spec(it.widgetType).followsCommonSource,
                frameCount = spriteFrameCount(it).takeIf { count ->
                    it.widgetType == WIDGET_SPRITE && count > 0
                },
                hasOpaqueBackdrop = opaqueBackdrop && placement == WidgetPlacement.CANVAS,
                colorArgb = pairColor.takeIf { canEditPair },
                rotationTenths = WidgetSchema.spec(it.widgetType).rotation?.let { field ->
                    entry.data.u16(it.recordOffset + field.offset)
                },
                supportMessage = when {
                    !place.isMovable ->
                        "This widget’s position cannot be measured safely, so moving is disabled. " +
                            "Its other properties stay unchanged."
                    placement == WidgetPlacement.BACKGROUND ->
                        "Covers the whole face. Replace it from Background instead of dragging it."
                    // One sentence: the editor prints this under the controls *and*, for a
                    // widget with no outline, in the banner above them, so a paragraph was
                    // the same paragraph twice on one screen.
                    it.widgetType == WIDGET_HAND ->
                        "This hand rotates, so use the arrows to move it." +
                            (if (resizeKind == WidgetResizeKind.RASTER) {
                                " Resizing scales its artwork and pivot together."
                            } else "") +
                            // A Hand is HIDDEN, so this arm used to swallow the pool
                            // warning for the one type that cannot show the result: 18 of
                            // the catalogue's 469 Hands share their artwork with another
                            // hand, which resizes with them. The note follows the *offer*,
                            // not the pool: every corpus Hand is resizable today, but one
                            // whose pool failed `poolIsResizable` would get no size
                            // controls and a sentence about what resizing it does.
                            sharedPoolSuffix(
                                resizePool.takeIf { resizeKind == WidgetResizeKind.RASTER },
                                it,
                            )
                    placement == WidgetPlacement.HIDDEN ->
                        "No selectable outline. Use the arrows to move it."
                    canEditPair -> "Drag to move; choose a solid colour below."
                    resizeKind == WidgetResizeKind.RASTER && resizePool != null ->
                        resizeMessage(resizePool, it)
                    resizeKind == WidgetResizeKind.FIELDS ->
                        "Move or resize this widget without increasing the file size."
                    resizeModel != null ->
                        "You can move this widget, but its artwork cannot be resized safely."
                    it.widgetType == WIDGET_PAIR -> "Drag to move; Value color schema is opaque"
                    else -> "Drag to move. Other properties stay unchanged."
                },
            )
        }
    }

    /**
     * Pairs each widget in [entry] with the record it came from in [originalEntry], as
     * a map of current global index to original global index.
     *
     * **A global index is not an identity across a structural edit.** Removing a widget
     * renumbers every record after it, and restoring one appends it at the end with the
     * next free number, so after a remove-and-restore on face `00022` the seq-10 hour
     * sprite sits at index 10 — where the original container keeps the seq-37 battery.
     * Reading the original by raw index therefore handed every consumer a different
     * widget: the composer cleared the battery's rectangle, the image layer resolved
     * against the battery's 11-frame table and returned null, and the restored sprite
     * vanished from the canvas leaving only its outline.
     *
     * So the pairing is resolved from most specific to least, and an original is
     * claimed at most once. An ambiguous step is skipped rather than guessed, which
     * leaves the widget unpaired — the same state as a genuinely new record.
     */
    fun originalWidgetSources(
        entry: ContainerEntry,
        originalEntry: ContainerEntry,
        excludedIndices: Set<Int> = emptySet(),
    ): Map<Int, Int> {
        val originals = scanWidgets(originalEntry)
        val current = scanWidgets(entry).filterNot { it.globalIndex in excludedIndices }
            .sortedBy(WidgetRecord::globalIndex)
        val originalImages = imagesByRelativeOffset(originalEntry)
        val currentImages = imagesByRelativeOffset(entry)
        val claimed = mutableSetOf<Int>()
        val sources = mutableMapOf<Int, Int>()

        fun samePayload(original: WidgetRecord, record: WidgetRecord) =
            payloadKey(original, originalImages) == payloadKey(record, currentImages)

        fun pass(match: (WidgetRecord, WidgetRecord) -> Boolean) {
            current.filterNot { it.globalIndex in sources }.forEach { record ->
                originals
                    .filter { it.globalIndex !in claimed && match(it, record) }
                    .singleOrNull()
                    ?.let {
                        sources[record.globalIndex] = it.globalIndex
                        claimed += it.globalIndex
                    }
            }
        }

        // Untouched and still at its own index — the overwhelmingly common case, and
        // the one that has to stay exact when a face carries two records with the same
        // type and sequence (00022 has two Statics at seq 0 and two Comps at seq 0).
        pass { original, record ->
            original.globalIndex == record.globalIndex &&
                samePayload(original, record) &&
                original.x == record.x && original.y == record.y
        }
        // Untouched but renumbered by a removal somewhere earlier in the table.
        pass { original, record ->
            samePayload(original, record) &&
                original.x == record.x && original.y == record.y
        }
        // Edited in place: a move or a resize keeps the index and the identity.
        pass { original, record ->
            original.globalIndex == record.globalIndex &&
                original.widgetType == record.widgetType &&
                original.sequenceId == record.sequenceId
        }
        // Renumbered but still where it was drawn. Several faces carry a row of
        // identical Statics — 00003 has nine at sequence 0 — so position is the only
        // thing separating them once their indices have shifted.
        pass { original, record ->
            original.widgetType == record.widgetType &&
                original.sequenceId == record.sequenceId &&
                original.x == record.x && original.y == record.y
        }
        // Renumbered and moved.
        pass(::samePayload)
        // Renumbered and edited — a widget removed, restored and then resized.
        pass { original, record ->
            original.widgetType == record.widgetType &&
                original.sequenceId == record.sequenceId
        }
        // Byte-identical twins that have been moved *and* renumbered are genuinely
        // indistinguishable — face 00003 carries nine Statics differing only in where
        // they sit. Nothing recoverable says which is which, so pair with any unclaimed
        // twin: they draw the same artwork, and leaving the widget with no original is
        // what makes it vanish from the canvas altogether.
        current.filterNot { it.globalIndex in sources }.forEach { record ->
            originals.firstOrNull { it.globalIndex !in claimed && samePayload(it, record) }
                ?.let {
                    sources[record.globalIndex] = it.globalIndex
                    claimed += it.globalIndex
                }
        }
        return sources
    }

    /**
     * A widget's payload with its image pointers resolved to record indices.
     *
     * The raw pointers are byte offsets into the image section, so relocating that
     * section rewrites them without changing what the widget refers to — comparing them
     * across an edit compares addresses, not identity. A Static keeps its pointer in
     * `+0x20`, which is exactly the field a resize relocates, so raw comparison made
     * every Static on a resized face look like a different widget and faces carrying
     * several identical ones stopped resolving at all.
     */
    private fun payloadKey(
        record: WidgetRecord,
        images: Map<Long, ImageRecord>,
    ): List<String> = buildList {
        add("size=${record.recordSize}")
        add("type=${record.widgetType}")
        add("seq=${record.sequenceId}")
        add(images[record.unknown20]?.let { "u20=img${it.index}" } ?: "u20=${record.unknown20}")
        record.words.forEachIndexed { index, word ->
            val rotation = WidgetSchema.spec(record.widgetType).rotation
            val stableWord = if (rotation?.offset == WIDGET_FIXED_SIZE + index * 4) word and 0xFFFF0000L else word
            add(images[stableWord]?.let { "img${it.index}" } ?: "raw$stableWord")
        }
    }

    /**
     * The records in [entry] that are copies of a widget still present in
     * [originalEntry], as current global index to the original they were copied from.
     *
     * A duplicate is what is left over once [originalWidgetSources] has paired every
     * record that *is* an original: it carries a widget's payload, but that original
     * already belongs to another record.
     */
    fun duplicateSourceGlobalIndices(
        entry: ContainerEntry,
        originalEntry: ContainerEntry,
        excludedIndices: Set<Int> = emptySet(),
    ): Map<Int, Int> {
        val sources = originalWidgetSources(entry, originalEntry, excludedIndices)
        val originals = scanWidgets(originalEntry)
        val originalImages = imagesByRelativeOffset(originalEntry)
        val currentImages = imagesByRelativeOffset(entry)
        return scanWidgets(entry)
            .asSequence()
            .filterNot { it.globalIndex in sources || it.globalIndex in excludedIndices }
            .mapNotNull { duplicate ->
                val key = payloadKey(duplicate, currentImages)
                val matches = originals.filter { payloadKey(it, originalImages) == key }
                // A copy starts life on top of the widget it was copied from, so
                // position separates a row of otherwise identical records. Once the
                // copy has also been moved, nothing does — and a face can carry nine
                // identical Statics — so fall back to any twin rather than none. They
                // draw the same artwork; reporting no source stops the copy drawing.
                val source = matches.firstOrNull { it.x == duplicate.x && it.y == duplicate.y }
                    ?: matches.firstOrNull()
                source?.let { duplicate.globalIndex to it.globalIndex }
            }
            .toMap()
    }

    /** Isolated native raster layers. No original-container pairing or preview matching. */
    fun widgetImageLayers(entry: ContainerEntry): List<WidgetImageLayer> {
        val guides = widgetGuides(entry).associateBy { it.globalIndex }
        return scanWidgets(entry).filter {
            it.widgetType in setOf(WIDGET_STATIC, WIDGET_SPRITE, WIDGET_ANIMATION) &&
                guides[it.globalIndex]?.placement != WidgetPlacement.BACKGROUND
        }.mapNotNull { record ->
            val images = resourceImages(entry, record)
            val index = if (record.widgetType == WIDGET_SPRITE)
                WidgetPreviewSample.spriteFrame(record.sourceId, images.size) else 0
            val image = images.getOrNull(index) ?: return@mapNotNull null
            WidgetImageLayer(record.globalIndex, decodeImage(entry, image), !image.hasAlphaChannel)
        }
    }

    internal fun imagesByRelativeOffset(entry: ContainerEntry): Map<Long, ImageRecord> {
        val images = scanImages(entry)
        val firstOffset = images.firstOrNull()?.recordOffset ?: return emptyMap()
        return images.associateBy { (it.recordOffset - firstOffset).toLong() }
    }

    private fun imageSection(entry: ContainerEntry): Pair<Int, Int> {
        if (entry.basename == "preview.bin") return 0 to entry.data.size
        if (entry.data.size < STYLE_HEADER_SIZE || entry.data.u32(0) != STYLE_MAGIC) {
            throw Fit3FormatException("${entry.basename}: invalid style header")
        }
        val widgetBytes = entry.data.u32(8).checkedInt("widget bytes")
        val imageBytes = entry.data.u32(12).checkedInt("image bytes")
        val imageOffset = entry.data.u32(20).checkedInt("image offset")
        if (imageOffset != STYLE_HEADER_SIZE + widgetBytes) {
            throw Fit3FormatException("${entry.basename}: inconsistent image offset")
        }
        if (imageOffset.toLong() + imageBytes != entry.data.size.toLong()) {
            throw Fit3FormatException("${entry.basename}: image section does not reach entry end")
        }
        return imageOffset to entry.data.size
    }
}
