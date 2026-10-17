package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.visualBounds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Containers that ask the watch one question each, packed as project archives.
 *
 * Several planned features rest on watch behaviour nobody has recorded: whether an appended
 * Animation plays and in what unit its period is, Indexed8 palette alpha, large overhangs, a
 * face ID outside the catalogue, a changed font binding, a swapped date order, a soft-edged
 * appended picture. Each probe here builds one of those and nothing else, so a watch session
 * can answer them in an afternoon.
 *
 * **Test code, never shipped.** None of these writers is offered in the app until its probe
 * has passed on a watch. They live beside the tests so the probes stay reproducible and
 * checked, and so the format rules they rely on are the module's own.
 *
 * **Delivered through Import.** A probe is a project archive whose pristine container is the
 * probe itself: no edit, no session. The Library's Import button takes it through the same
 * funnel as a download, and Install sends it through `validatedBytes()` like any project.
 * Nothing in the app had to change to carry them.
 */
internal object WatchProbes {
    const val STYLE = "style0.bin"
    const val AOD = "aod.bin"

    // ---------------------------------------------------------------------------------------
    // Rasters
    // ---------------------------------------------------------------------------------------

    fun rgb565(color: Int): Int =
        (((color ushr 16 and 0xFF) * 31 + 127) / 255 shl 11) or
            (((color ushr 8 and 0xFF) * 63 + 127) / 255 shl 5) or
            (((color and 0xFF) * 31 + 127) / 255)

    /** An opaque `0x0082` raster: header, two bytes a pixel, the four-byte trailer. */
    fun rgb565Raster(width: Int, height: Int, argb: IntArray): ByteArray {
        require(argb.size == width * height)
        val pixels = ByteArray(argb.size * 2)
        argb.forEachIndexed { index, color ->
            val value = rgb565(color)
            pixels[index * 2] = value.toByte()
            pixels[index * 2 + 1] = (value ushr 8).toByte()
        }
        return raster(width, height, IMAGE_RGB565, pixels)
    }

    /** A `0x0080` raster: the RGB565 half-word, then the alpha byte, per pixel. */
    fun rgb565AlphaRaster(width: Int, height: Int, argb: IntArray): ByteArray {
        require(argb.size == width * height)
        val pixels = ByteArray(argb.size * 3)
        argb.forEachIndexed { index, color ->
            val alpha = color ushr 24
            val value = if (alpha == 0) 0 else rgb565(color)
            pixels[index * 3] = value.toByte()
            pixels[index * 3 + 1] = (value ushr 8).toByte()
            pixels[index * 3 + 2] = alpha.toByte()
        }
        return raster(width, height, IMAGE_RGB565_ALPHA, pixels)
    }

    /**
     * A `0x0088` raster: a 256-entry palette stored blue, green, red, **alpha**, then one index
     * a pixel. The alpha byte is what T1d asks about: the decoder reads it, and no catalogue
     * face sets one below `0xFF`.
     */
    fun indexed8Raster(width: Int, height: Int, paletteArgb: IntArray, indices: ByteArray): ByteArray {
        require(paletteArgb.size == INDEXED_PALETTE_ENTRIES && indices.size == width * height)
        val payload = ByteArray(INDEXED_PALETTE_BYTES + indices.size)
        paletteArgb.forEachIndexed { entry, color ->
            payload[entry * 4] = (color and 0xFF).toByte()
            payload[entry * 4 + 1] = (color ushr 8 and 0xFF).toByte()
            payload[entry * 4 + 2] = (color ushr 16 and 0xFF).toByte()
            payload[entry * 4 + 3] = (color ushr 24 and 0xFF).toByte()
        }
        indices.copyInto(payload, INDEXED_PALETTE_BYTES)
        return raster(width, height, IMAGE_INDEXED8, payload)
    }

    private fun raster(width: Int, height: Int, format: Int, pixels: ByteArray): ByteArray {
        val record = ByteArray(IMAGE_HEADER_SIZE + pixels.size + OPAQUE_TRAILER_BYTES)
        record.putU16(0, width)
        record.putU16(2, height)
        record.putU16(4, format)
        record.putU32(8, pixels.size + OPAQUE_TRAILER_BYTES)
        pixels.copyInto(record, IMAGE_HEADER_SIZE)
        return record
    }

    // ---------------------------------------------------------------------------------------
    // Records
    // ---------------------------------------------------------------------------------------

    /**
     * A type-4 Animation: position, mode 0, frame count, repeat count, period, then one
     * pointer a frame, which [append] fills. The layout is the firmware reading in
     * `docs/bin-format.md` §7 and R390 Studio's `widgetTimed`; no catalogue face carries one.
     * Repeat must be nonzero, and `0xFF` is a finite 255.
     */
    fun animationRecord(x: Int, y: Int, frames: Int, repeat: Int, periodMs: Int): ByteArray {
        require(frames in 1..255 && repeat in 1..255 && periodMs in 1..0xFFFF)
        val record = ByteArray(0x28 + frames * 4)
        record.putU32(0x00, WIDGET_ANIMATION.toLong())
        record.putU16(0x18, x and 0xFFFF)
        record.putU16(0x1A, y and 0xFFFF)
        record[0x24] = frames.toByte()
        record[0x25] = repeat.toByte()
        record.putU16(0x26, periodMs)
        return record
    }

    /** Pointer fields of an [animationRecord] with [frames] frames, in frame order. */
    fun animationPointers(frames: Int): List<Int> = (0 until frames).map { 0x28 + it * 4 }

    /**
     * A 40-byte Static at panel coordinates: alignment code 0 with target `0xFFFF`, which
     * names no record and so resolves to the panel. That is what an imported widget is
     * rebased to, the hardware-proven form, rather than target 0, which would measure from
     * whatever record happens to be first.
     */
    fun staticRecord(x: Int, y: Int): ByteArray = ByteArray(40).also {
        it.putU32(0x00, WIDGET_STATIC.toLong())
        it.putU16(0x18, x and 0xFFFF)
        it.putU16(0x1A, y and 0xFFFF)
        it.putU16(0x1E, WidgetImporter.ROOT_TARGET)
    }

    /**
     * [record] appended to [variant]'s widget table and [rasters] to the end of its image
     * section, each pointer field in [pointerFields] given its raster's relative offset in
     * turn. Nothing already in the style moves, which is the rule every append here follows.
     */
    fun append(
        container: Fit3Container,
        variant: String,
        record: ByteArray,
        rasters: List<ByteArray>,
        pointerFields: List<Int>,
    ): Fit3Container {
        require(rasters.size == pointerFields.size)
        val entry = container.entryByBasename(variant)
        val header = StyleHeader.parse(entry)
        check(header.storedImageOffset + header.imageBytes == entry.data.size) { "$variant is not tightly packed" }
        val records = FaceRecordParser.scanWidgets(entry)
        val raw = record.copyOf()
        raw.putU32(0x0C, (records.size.toLong() shl 16) or raw.size.toLong())
        var cursor = header.imageBytes
        rasters.zip(pointerFields).forEach { (raster, field) ->
            raw.putU32(field, cursor.toLong())
            cursor += raster.size
        }
        val widgets = entry.data.copyOfRange(STYLE_HEADER_SIZE, header.storedImageOffset) + raw
        val images = entry.data.copyOfRange(header.storedImageOffset, entry.data.size) +
            rasters.fold(ByteArray(0)) { all, raster -> all + raster }
        val data = WidgetImporter.styleBytes(entry.data, widgets, records.size + 1, images, header.fontBindingCount)
        return StructuralEditor.rebuild(container, mapOf(entry.index to data)).container
    }

    // ---------------------------------------------------------------------------------------
    // Whole-container rewrites
    // ---------------------------------------------------------------------------------------

    /**
     * [container] under another five-digit face ID, at the same length: every directory path,
     * `setting.bin +0x10`, and the two name slots only where they hold the canonical name.
     * Stage 8's send-time rewrite, written here first so T3 can test the idea.
     */
    fun reidentify(container: Fit3Container, faceId: String): Fit3Container {
        require(faceId.matches(Regex("\\d{5}")))
        val setting = FaceResources.settingOrNull(container) ?: error("no setting.bin")
        val old = SettingRecord.parse(setting).faceId
        val data = setting.data.copyOf()
        faceId.toByteArray(Charsets.US_ASCII).copyInto(data, 0x10)
        listOf(0x38, 0x78).forEach { slot ->
            val canonical = "SM-R390_${old}_256x402".toByteArray(Charsets.US_ASCII)
            if (data.copyOfRange(slot, slot + canonical.size).contentEquals(canonical)) {
                "SM-R390_${faceId}_256x402".toByteArray(Charsets.US_ASCII).copyInto(data, slot)
            }
        }
        val renamed = container.entries.associate { it.index to it.path.replace("SM-R390_${old}_", "SM-R390_${faceId}_") }
        return StructuralEditor.rebuild(container, mapOf(setting.index to data), renamedEntries = renamed).container
    }

    /** A 92-byte binding naming one family by selector byte 1, at [size] pixels. */
    fun binding(family: Int, size: Int, role: String): ByteArray = ByteArray(FontBinding.SIZE).also {
        it[1] = family.toByte()
        role.toByteArray(Charsets.US_ASCII).copyInto(it, 0x48)
        it.putU32(0x58, size.toLong())
    }

    /**
     * Each Value in [targets] (style-0 global index to its new binding) pointed at a binding
     * appended for it, and every variant's binding count raised to match. Native bindings are
     * not touched: they are shared by other widgets and styles.
     */
    fun rebindValues(container: Fit3Container, targets: Map<Int, ByteArray>): Fit3Container {
        val bindings = FaceResources.fontBindings(container).size
        require(bindings + targets.size <= StyleHeader.MAX_FONT_BINDINGS)
        val style = container.entryByBasename(STYLE)
        val records = FaceRecordParser.scanWidgets(style)
        val styleData = style.data.copyOf()
        val additions = linkedMapOf<String, ByteArray>()
        targets.entries.forEachIndexed { position, (index, data) ->
            val record = records.single { it.globalIndex == index }
            require(record.widgetType == WIDGET_PAIR) { "widget $index is not a Value" }
            styleData[record.recordOffset + 0x28] = (bindings + position).toByte()
            additions["font_${bindings + position}.bin"] = data
        }
        val count = bindings + targets.size
        val replacements = FaceResources.variantEntries(container).associate { entry ->
            entry.index to (if (entry.index == style.index) styleData else entry.data.copyOf()).also { it[0x11] = count.toByte() }
        }
        return StructuralEditor.rebuild(container, replacements, additions).container
    }

    /**
     * Every Composite part reading source [a] made to read [b] and the reverse, in every style
     * and the always-on display. The naive day/month swap T6 measures, locale tables and
     * permutation left as they are.
     */
    fun swapCompositeSources(container: Fit3Container, a: Int, b: Int): Fit3Container {
        val replacements = linkedMapOf<Int, ByteArray>()
        FaceResources.variantEntries(container).forEach { entry ->
            val data = entry.data.copyOf()
            var changed = false
            FaceRecordParser.scanWidgets(entry).filter { it.widgetType == WIDGET_COMP }.forEach { record ->
                listOf(0x24, 0x30, 0x3C, 0x48).forEach { program ->
                    val at = record.recordOffset + program
                    when (data.u16(at)) {
                        a -> { data.putU16(at, b); changed = true }
                        b -> { data.putU16(at, a); changed = true }
                    }
                }
            }
            if (changed) replacements[entry.index] = data
        }
        require(replacements.isNotEmpty()) { "no Composite reads source $a or $b" }
        return StructuralEditor.rebuild(container, replacements).container
    }

    /** [container] with widget [globalIndex] of style 0 moved so its turned outline's left/top lands at [left]/[top]. */
    fun place(container: Fit3Container, globalIndex: Int, left: Int?, top: Int?): Fit3Container {
        val guide = FaceRecordParser.widgetGuides(container.entryByBasename(STYLE)).single { it.globalIndex == globalIndex }
        val bounds = guide.visualBounds
        val x = guide.x + (left?.let { it - (guide.drawLeft + bounds.left) } ?: 0)
        val y = guide.y + (top?.let { it - (guide.drawTop + bounds.top) } ?: 0)
        return FaceEditor.moveWidget(container, STYLE, guide.globalIndex, guide.type, guide.sequenceId, x, y).container
    }

    // ---------------------------------------------------------------------------------------
    // Pictures
    // ---------------------------------------------------------------------------------------

    private val digitRows = mapOf(
        '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to listOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
        '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    )

    /** [text] in a 5×7 block font, each cell [scale] pixels, centred on [width] × [height]. */
    fun drawDigits(pixels: IntArray, width: Int, height: Int, text: String, scale: Int, color: Int) {
        val textWidth = (text.length * 6 - 1) * scale
        val left = (width - textWidth) / 2
        val top = (height - 7 * scale) / 2
        text.forEachIndexed { position, character ->
            val rows = digitRows.getValue(character)
            rows.forEachIndexed { row, bits ->
                bits.forEachIndexed { column, bit ->
                    if (bit == '1') {
                        for (dy in 0 until scale) for (dx in 0 until scale) {
                            val x = left + (position * 6 + column) * scale + dx
                            val y = top + row * scale + dy
                            if (x in 0 until width && y in 0 until height) pixels[y * width + x] = color
                        }
                    }
                }
            }
        }
    }

    /** A filled square with a coloured border and [text] in its middle: one animation frame. */
    fun frame(size: Int, text: String, background: Int, border: Int, ink: Int, scale: Int): IntArray {
        val pixels = IntArray(size * size) { background }
        for (i in 0 until size) for (j in 0 until 3) {
            pixels[j * size + i] = border
            pixels[(size - 1 - j) * size + i] = border
            pixels[i * size + j] = border
            pixels[i * size + size - 1 - j] = border
        }
        drawDigits(pixels, size, size, text, scale, ink)
        return pixels
    }

    /** A disc whose edge fades from opaque to clear over its outer [fade] pixels. */
    fun softDisc(size: Int, color: Int, fade: Int): IntArray = IntArray(size * size) { index ->
        val x = index % size + 0.5
        val y = index / size + 0.5
        val radius = size / 2.0
        val distance = hypot(x - radius, y - radius)
        val alpha = ((radius - distance) / fade * 255).roundToInt().coerceIn(0, 255)
        (alpha shl 24) or (color and 0xFFFFFF)
    }

    // ---------------------------------------------------------------------------------------
    // Packing
    // ---------------------------------------------------------------------------------------

    /**
     * [container] as a project archive: the store package's face metadata, the container
     * under its own face ID's member name, and a schema-1 manifest naming the probe. No style
     * previews: they would picture the face the probe was built from.
     */
    fun archive(storePackage: ByteArray, container: Fit3Container, projectName: String): ByteArray {
        val faceId = SettingRecord.parse(requireNotNull(FaceResources.settingOrNull(container))).faceId
        val members = ByteArrayOutputStream()
        ZipOutputStream(members).use { out ->
            ZipInputStream(ByteArrayInputStream(storePackage)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.name == Fit3Apk.FACE_METADATA_MEMBER) {
                        out.putNextEntry(ZipEntry(entry.name))
                        input.copyTo(out)
                        out.closeEntry()
                    }
                    input.closeEntry()
                }
            }
            out.putNextEntry(ZipEntry("assets/SM-R390_${faceId}_256x402.bin"))
            out.write(container.toByteArray())
            out.closeEntry()
        }
        val manifest = ProjectManifest(
            schema = 1,
            projectName = projectName,
            faceId = faceId,
            displayName = "$projectName.zip",
            selectedStyle = STYLE,
        )
        return ByteArrayOutputStream().also {
            ProjectArchive.pack(ByteArrayInputStream(members.toByteArray()), it, manifest, null, null)
        }.toByteArray()
    }
}
