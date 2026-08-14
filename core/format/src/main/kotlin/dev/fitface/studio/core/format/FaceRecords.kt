package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.WidgetCategory
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WidgetImageLayer
import dev.fitface.studio.core.model.WidgetPlacement
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
const val WIDGET_FIXED_SIZE = 36

const val WIDGET_STATIC = 1
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

data class WidgetRecord(
    val ordinal: Int,
    val recordOffset: Int,
    val recordSize: Int,
    val globalIndex: Int,
    val widgetType: Int,
    val sequenceId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val unknown20: Long,
    val words: List<Long>,
)

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
            if (cursor + WIDGET_FIXED_SIZE > imageOffset) {
                throw Fit3FormatException("${entry.basename}: truncated widget")
            }
            val indexSize = data.u32(cursor + 0x0C)
            val recordSize = (indexSize and 0xFFFF).toInt()
            val globalIndex = (indexSize ushr 16).toInt()
            if (recordSize < WIDGET_FIXED_SIZE || recordSize > 600 || recordSize % 2 != 0) {
                throw Fit3FormatException(
                    "${entry.basename}: invalid widget size $recordSize",
                )
            }
            if (cursor + recordSize > imageOffset) {
                throw Fit3FormatException("${entry.basename}: widget exceeds stream")
            }
            val wordCount = (recordSize - WIDGET_FIXED_SIZE) / 4
            records += WidgetRecord(
                ordinal = records.size,
                recordOffset = cursor,
                recordSize = recordSize,
                globalIndex = globalIndex,
                widgetType = data.u32(cursor).checkedInt("widget type"),
                sequenceId = data.u32(cursor + 4).checkedInt("sequence id"),
                x = data.i16(cursor + 0x18),
                y = data.i16(cursor + 0x1A),
                width = data.u16(cursor + 0x1C),
                height = data.u16(cursor + 0x1E),
                unknown20 = data.u32(cursor + 0x20),
                words = List(wordCount) { word ->
                    data.u32(cursor + WIDGET_FIXED_SIZE + word * 4)
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
     * Every `aod.bin` in the corpus lacks one, as do all of face `00022`'s styles
     * and `00108` styles 0–3. Where a style does carry one it is raster 0 in every
     * observed container, so this keeps the previous behaviour for those faces.
     */
    fun backgroundImage(entry: ContainerEntry): ImageRecord? {
        val panel = panelSize(entry)
        if (panel.width <= 0 || panel.height <= 0) return null
        return scanImages(entry).firstOrNull {
            it.width == panel.width && it.height == panel.height
        }
    }

    /** `+0x20` of a Sprite is its frame count, followed by that many pointers. */
    private fun spriteFrameCount(record: WidgetRecord): Int =
        (record.unknown20 and 0xFF_FFFFL)
            .coerceIn(0L, record.words.size.toLong())
            .toInt()

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
     * What a resize will actually do, which is not always "resize this widget". The
     * frames are shared records, so the whole glyph pool moves together and the message
     * has to say how many widgets that is before the user taps.
     */
    private fun resizeMessage(
        pool: Set<Int>,
        records: List<WidgetRecord>,
        imagesByRelativeOffset: Map<Long, ImageRecord>,
        target: WidgetRecord,
    ): String {
        val sharing = records.count { other ->
            other.ordinal != target.ordinal &&
                referencedImages(other, imagesByRelativeOffset).any { it.index in pool }
        }
        return if (sharing == 0) {
            "Drag to move; resize every referenced Sprite frame together"
        } else {
            "Drag to move. Resizing rewrites the ${pool.size} shared frames behind this " +
                "widget, so the $sharing other widget${if (sharing == 1) "" else "s"} " +
                "drawing from the same set resize with it."
        }
    }

    internal fun sharedFrameClosure(
        target: WidgetRecord,
        records: List<WidgetRecord>,
        imagesByRelativeOffset: Map<Long, ImageRecord>,
    ): Set<Int> {
        val framesOf = records.map { referencedImages(it, imagesByRelativeOffset).map(ImageRecord::index) }
        val closure = referencedImages(target, imagesByRelativeOffset)
            .mapTo(mutableSetOf(), ImageRecord::index)
        while (true) {
            val reached = framesOf.filter { frames -> frames.any { it in closure } }.flatten()
            if (!closure.addAll(reached)) return closure
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
        fun word(index: Int): ImagePointerField {
            val value = record.words.getOrNull(index) ?: throw Fit3FormatException(
                "widget ${record.ordinal} type ${record.widgetType} has no word $index",
            )
            val image = imagesByRelativeOffset[value] ?: throw Fit3FormatException(
                "widget ${record.ordinal} type ${record.widgetType} word $index " +
                    "does not point at a raster",
            )
            return ImagePointerField(
                offset = record.recordOffset + WIDGET_FIXED_SIZE + index * 4,
                value = value,
                image = image,
            )
        }
        return when (record.widgetType) {
            WIDGET_STATIC -> {
                val image = imagesByRelativeOffset[record.unknown20]
                    ?: throw Fit3FormatException(
                        "Static widget ${record.ordinal} does not point at a raster",
                    )
                listOf(
                    ImagePointerField(
                        offset = record.recordOffset + 0x20,
                        value = record.unknown20,
                        image = image,
                    ),
                )
            }
            WIDGET_SPRITE -> {
                val frames = spriteFrameCount(record)
                if (frames <= 0) {
                    throw Fit3FormatException(
                        "Sprite widget ${record.ordinal} declares $frames frames",
                    )
                }
                (0 until frames).map(::word)
            }
            WIDGET_HAND -> listOf(word(1))
            WIDGET_ARC -> listOf(word(4))
            WIDGET_LINE_BAR -> listOf(word(2))
            else -> emptyList()
        }
    }

    /** Widget types [imagePointerFields] knows the pointer schema of. */
    internal val POINTER_BEARING_TYPES = setOf(
        WIDGET_STATIC,
        WIDGET_SPRITE,
        WIDGET_HAND,
        WIDGET_ARC,
        WIDGET_LINE_BAR,
    )

    fun widgetGuides(entry: ContainerEntry): List<WidgetGuide> {
        val records = scanWidgets(entry)
        val images = scanImages(entry)
        val firstImageOffset = images.firstOrNull()?.recordOffset ?: 0
        val imagesByRelativeOffset = images.associateBy {
            (it.recordOffset - firstImageOffset).toLong()
        }
        val panel = panelSize(entry)
        val background = backgroundImage(entry)
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
            // The whole glyph pool this Sprite reaches, because that is what the edit
            // rewrites — every condition below has to hold for the pool, not just for
            // the frames this record happens to name, or the UI enables a control whose
            // commit is guaranteed to fail.
            val resizePool = if (it.widgetType == WIDGET_SPRITE) {
                sharedFrameClosure(it, records, imagesByRelativeOffset)
            } else {
                emptySet()
            }
            val poolImages = resizePool.sorted().mapNotNull(images::getOrNull)
            val poolSignatures = poolImages.map { image ->
                listOf(
                    image.width,
                    image.height,
                    image.format,
                    image.reserved,
                    image.opaqueTrailerSize,
                )
            }.toSet()
            val canResizeSprite = it.widgetType == WIDGET_SPRITE &&
                records.count { candidate ->
                    candidate.widgetType == WIDGET_SPRITE &&
                        candidate.sequenceId == it.sequenceId
                } == 1 &&
                referencedImages.isNotEmpty() &&
                !paintsBackground &&
                // StructuralEditor.resizeSprite relocates the whole frame table and
                // refuses a record holding any word that is not an image pointer, so
                // only offer resize when every word is one.
                referencedImages.size == it.words.size &&
                poolImages.size == resizePool.size &&
                background?.index !in resizePool &&
                // Only Sprites may reach into the pool: a Static or a Hand sharing a
                // digit frame would mean the pool is not what this edit assumes.
                records.none { other ->
                    other.widgetType != WIDGET_SPRITE &&
                        referencedImages(other, imagesByRelativeOffset)
                            .any { image -> image.index in resizePool }
                } &&
                poolSignatures.size == 1 &&
                poolImages.first().let { image ->
                    image.format == IMAGE_RGB565_ALPHA &&
                        image.reserved == 0 &&
                        image.opaqueTrailerSize == 4
                }
            val badgeThickness = it.words.getOrNull(3)?.toInt()
                ?.and(0xFF)
                ?.takeIf { thickness -> thickness >= 2 }
                ?: 8
            // A raster-backed widget is exactly as big as the raster the watch
            // blits, and faces do leave the stored extent at a placeholder: 00079
            // stores width 1 for digit sprites whose frames are 52 px wide, and
            // 00022 stores height 20 for frames that are 136 px tall. Trusting the
            // stored value there drew a 1-pixel sliver instead of the widget.
            val rasterWidth = referencedImages.maxOfOrNull(ImageRecord::width)
            val rasterHeight = referencedImages.maxOfOrNull(ImageRecord::height)
            // A Badge's 0x1C/0x1E are the second endpoint, not an extent, and the
            // stored endpoint is the *larger* one in 52 of the corpus's 84 Badges. So
            // the span is the absolute difference, and the rectangle starts a whole
            // span earlier whenever the stored coordinate is the far end.
            val badgeEndX = it.width.toShort().toInt()
            val badgeEndY = it.height.toShort().toInt()
            val visualWidth = when {
                it.widgetType == WIDGET_BADGE ->
                    abs(badgeEndX - it.x).coerceAtLeast(badgeThickness)
                rasterWidth != null -> rasterWidth
                else -> it.width
            }
            val visualHeight = when {
                it.widgetType == WIDGET_BADGE ->
                    abs(badgeEndY - it.y).coerceAtLeast(badgeThickness)
                rasterHeight != null -> rasterHeight
                else -> it.height
            }
            val drawOffsetX = if (it.widgetType == WIDGET_BADGE && badgeEndX < it.x) {
                -visualWidth
            } else {
                0
            }
            val drawOffsetY = if (it.widgetType == WIDGET_BADGE && badgeEndY < it.y) {
                -visualHeight
            } else {
                0
            }
            val placement = when {
                paintsBackground -> WidgetPlacement.BACKGROUND
                // The watch rotates a Hand about the pivot in its `+0x20`, so its
                // artwork bounds are not where it appears. Reporting the size is
                // useful; outlining a rectangle there would be a lie.
                it.widgetType == WIDGET_HAND -> WidgetPlacement.HIDDEN
                visualWidth <= 0 || visualHeight <= 0 -> WidgetPlacement.HIDDEN
                panel.width > 0 && visualWidth >= panel.width &&
                    visualHeight >= panel.height -> WidgetPlacement.BACKGROUND
                else -> WidgetPlacement.CANVAS
            }
            // Plain RGB565 frames carry no alpha, so the watch paints the whole
            // rectangle including whatever sits behind the glyphs.
            val opaqueBackdrop = referencedImages.isNotEmpty() &&
                referencedImages.none(ImageRecord::hasAlphaChannel)
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
                canEditPosition = true,
                canResize = canResizeSprite,
                placement = placement,
                drawOffsetX = drawOffsetX,
                drawOffsetY = drawOffsetY,
                category = WidgetCategory.forWidgetType(it.widgetType),
                frameCount = spriteFrameCount(it).takeIf { count ->
                    it.widgetType == WIDGET_SPRITE && count > 0
                },
                hasOpaqueBackdrop = opaqueBackdrop && placement == WidgetPlacement.CANVAS,
                colorArgb = pairColor.takeIf { canEditPair },
                supportMessage = when {
                    placement == WidgetPlacement.BACKGROUND ->
                        "Covers the whole face. Replace it from Background instead of dragging it."
                    it.widgetType == WIDGET_HAND ->
                        "A clock hand: the watch rotates its ${visualWidth}×$visualHeight " +
                            "artwork about a pivot, so there is no fixed rectangle to outline. " +
                            "Nudging still rewrites its stored coordinates."
                    placement == WidgetPlacement.HIDDEN ->
                        "This record has no drawn rectangle, so the editor cannot preview it. " +
                            "Nudging still rewrites its stored coordinates."
                    canEditPair -> "Drag to move; choose an opaque Pair color below"
                    canResizeSprite -> resizeMessage(resizePool, records, imagesByRelativeOffset, it)
                    it.widgetType == WIDGET_SPRITE ->
                        "Drag to move; this Sprite does not match the proven resize schema"
                    it.widgetType == WIDGET_PAIR -> "Drag to move; Pair color schema is opaque"
                    else -> "Drag to move; ${WidgetCategory.forWidgetType(it.widgetType).label
                        .lowercase()} internals are preserved verbatim"
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
    ): Map<Int, Int> {
        val originals = scanWidgets(originalEntry)
        val current = scanWidgets(entry).sortedBy(WidgetRecord::globalIndex)
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
        record.words.forEach { word ->
            add(images[word]?.let { "img${it.index}" } ?: "raw$word")
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
    ): Map<Int, Int> {
        val sources = originalWidgetSources(entry, originalEntry)
        val originals = scanWidgets(originalEntry)
        val originalImages = imagesByRelativeOffset(originalEntry)
        val currentImages = imagesByRelativeOffset(entry)
        return scanWidgets(entry)
            .asSequence()
            .filterNot { it.globalIndex in sources }
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

    fun widgetImageLayers(
        entry: ContainerEntry,
        originalEntry: ContainerEntry,
        reference: PreviewFrame,
    ): List<WidgetImageLayer> {
        val currentRecords = scanWidgets(entry)
        val originalRecords = scanWidgets(originalEntry).associateBy(WidgetRecord::globalIndex)
        // Never `originalRecords[current.globalIndex]`: a removal renumbers the table,
        // and resolving a restored sprite's frames against whatever record now holds
        // its old index returns null and drops the widget off the canvas entirely.
        val originalSources = originalWidgetSources(entry, originalEntry)
        val duplicateSources = duplicateSourceGlobalIndices(entry, originalEntry)
        val currentImages = imagesByRelativeOffset(entry)
        val originalImages = imagesByRelativeOffset(originalEntry)
        val panel = panelSize(originalEntry)
        // A style without a full-panel raster paints onto the watch's black panel,
        // so that is what an embedded frame has to be differenced against. Without
        // this, every widget on face 00022 and on any aod.bin lost its layer.
        val originalBackground = backgroundImage(originalEntry)
            ?.let { decodeImage(originalEntry, it) }
            ?: blackPanel(panel)
            ?: return emptyList()

        return currentRecords.mapNotNull { current ->
            val original = originalSources[current.globalIndex]?.let(originalRecords::get)
                ?: duplicateSources[current.globalIndex]?.let(originalRecords::get)
                ?: return@mapNotNull null
            val image = when (current.widgetType) {
                WIDGET_STATIC -> {
                    val candidate = referencedImages(current, currentImages).singleOrNull()
                        ?: return@mapNotNull null
                    // The background layer is already the base image of the preview.
                    if (candidate.width >= originalBackground.width &&
                        candidate.height >= originalBackground.height
                    ) {
                        return@mapNotNull null
                    }
                    candidate
                }

                WIDGET_SPRITE -> {
                    val frameCount = spriteFrameCount(original)
                    if (frameCount <= 0) return@mapNotNull null
                    val originalCandidates = original.words.take(frameCount).mapIndexedNotNull {
                            index,
                            pointer,
                        ->
                        originalImages[pointer]?.let { index to it }
                    }
                    val selectedIndex = if (frameCount == 24) {
                        originalCandidates.firstOrNull()?.first
                    } else {
                        originalCandidates.minByOrNull { (_, candidate) ->
                            frameDifference(
                                entry = originalEntry,
                                image = candidate,
                                widget = original,
                                background = originalBackground,
                                reference = reference,
                            )
                        }?.first
                    } ?: return@mapNotNull null
                    current.words.getOrNull(selectedIndex)
                        ?.let(currentImages::get)
                        ?: return@mapNotNull null
                }

                else -> return@mapNotNull null
            }
            val decoded = decodeImage(entry, image)
            // Masking guesses which frame pixels are "background" so a moved
            // widget looks cut out. That guess is only legitimate when the
            // watch itself honours per-pixel alpha; an RGB565 frame is blitted
            // as a solid rectangle, and pretending otherwise is exactly how the
            // editor used to show transparent digits that install with a black
            // box behind them.
            val opaque = !image.hasAlphaChannel
            WidgetImageLayer(
                globalIndex = current.globalIndex,
                frame = if (opaque) {
                    decoded
                } else {
                    maskEmbeddedFrameBackground(
                        frame = decoded,
                        widget = original,
                        background = originalBackground,
                    )
                },
                isOpaque = opaque,
            )
        }
    }

    private fun clonePayloadMatches(first: WidgetRecord, second: WidgetRecord): Boolean =
        first.recordSize == second.recordSize &&
            first.widgetType == second.widgetType &&
            first.sequenceId == second.sequenceId &&
            first.unknown20 == second.unknown20 &&
            first.words == second.words

    private fun maskEmbeddedFrameBackground(
        frame: PreviewFrame,
        widget: WidgetRecord,
        background: PreviewFrame,
    ): PreviewFrame {
        val extentWidth = widget.width.takeIf { it > 0 } ?: frame.width
        val extentHeight = widget.height.takeIf { it > 0 } ?: frame.height
        val left = anchoredCoordinate(widget.x, extentWidth, background.width)
        val top = anchoredCoordinate(widget.y, extentHeight, background.height)
        val pixels = frame.argb.copyOf()
        for (localY in 0 until frame.height) {
            for (localX in 0 until frame.width) {
                val index = localY * frame.width + localX
                val framePixel = pixels[index]
                if (framePixel ushr 24 == 0) continue
                val x = left + localX
                val y = top + localY
                if (x !in 0 until background.width || y !in 0 until background.height) continue
                val backgroundPixel = background.argb[y * background.width + x]
                if (colorDifference(blend(backgroundPixel, framePixel), backgroundPixel) < 18) {
                    pixels[index] = 0
                }
            }
        }
        return PreviewFrame(frame.width, frame.height, pixels)
    }

    private fun imagesByRelativeOffset(entry: ContainerEntry): Map<Long, ImageRecord> {
        val images = scanImages(entry)
        val firstOffset = images.firstOrNull()?.recordOffset ?: return emptyMap()
        return images.associateBy { (it.recordOffset - firstOffset).toLong() }
    }

    /** The unlit panel a style with no background raster is drawn onto. */
    private fun blackPanel(panel: PanelSize): PreviewFrame? {
        if (panel.width <= 0 || panel.height <= 0) return null
        val pixels = IntArray(panel.width * panel.height) { 0xFF00_0000.toInt() }
        return PreviewFrame(panel.width, panel.height, pixels)
    }

    private fun frameDifference(
        entry: ContainerEntry,
        image: ImageRecord,
        widget: WidgetRecord,
        background: PreviewFrame,
        reference: PreviewFrame,
    ): Long {
        val frame = decodeImage(entry, image)
        val extentWidth = widget.width.takeIf { it > 0 } ?: frame.width
        val extentHeight = widget.height.takeIf { it > 0 } ?: frame.height
        val left = anchoredCoordinate(widget.x, extentWidth, background.width)
        val top = anchoredCoordinate(widget.y, extentHeight, background.height)
        var difference = 0L
        var compared = 0
        for (localY in 0 until frame.height) {
            for (localX in 0 until frame.width) {
                val x = left + localX
                val y = top + localY
                if (x !in 0 until background.width || y !in 0 until background.height) continue
                val framePixel = frame.argb[localY * frame.width + localX]
                val backgroundPixel = background.argb[y * background.width + x]
                val expected = blend(backgroundPixel, framePixel)
                val referenceX = x * reference.width / background.width
                val referenceY = y * reference.height / background.height
                val actual = reference.argb[referenceY * reference.width + referenceX]
                val expectedForeground = colorDifference(expected, backgroundPixel) >= 18
                val actualForeground = colorDifference(actual, backgroundPixel) >= 18
                if (!expectedForeground && !actualForeground) continue
                difference += colorDifferenceSquared(expected, actual)
                compared++
            }
        }
        return if (compared == 0) Long.MAX_VALUE else difference / compared
    }

    private fun blend(background: Int, foreground: Int): Int {
        val alpha = foreground ushr 24 and 0xFF
        if (alpha == 0xFF) return foreground
        if (alpha == 0) return background
        val inverse = 0xFF - alpha
        val red = ((foreground ushr 16 and 0xFF) * alpha +
            (background ushr 16 and 0xFF) * inverse) / 0xFF
        val green = ((foreground ushr 8 and 0xFF) * alpha +
            (background ushr 8 and 0xFF) * inverse) / 0xFF
        val blue = ((foreground and 0xFF) * alpha +
            (background and 0xFF) * inverse) / 0xFF
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun colorDifference(first: Int, second: Int): Int =
        abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)) +
            abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)) +
            abs((first and 0xFF) - (second and 0xFF))

    private fun colorDifferenceSquared(first: Int, second: Int): Long {
        val red = (first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)
        val green = (first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)
        val blue = (first and 0xFF) - (second and 0xFF)
        return red.toLong() * red + green.toLong() * green + blue.toLong() * blue
    }

    private fun anchoredCoordinate(value: Int, extent: Int, canvasExtent: Int): Int =
        if (value < 0) canvasExtent + value - extent else value

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
