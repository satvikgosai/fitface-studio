package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.PreviewFrame

data class ContainerEdit(
    val container: Fit3Container,
    val changedPayloadBytes: Int,
    val changedStyles: List<String>,
)

object FaceEditor {
    fun moveWidget(
        source: Fit3Container,
        entryBasename: String,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
    ): ContainerEdit = moveWidgetAcrossStyles(
        source = source,
        entryBasenames = listOf(entryBasename),
        globalIndex = globalIndex,
        widgetType = widgetType,
        sequenceId = sequenceId,
        x = x,
        y = y,
    )

    /**
     * Moves one widget to ([x], [y]) in the first entry of [entryBasenames], and in
     * every later entry that carries the same widget.
     *
     * A variant that does not carry it is left alone rather than failing the edit —
     * see [StyleWidgetMatch] for why styles legitimately differ.
     */
    fun moveWidgetAcrossStyles(
        source: Fit3Container,
        entryBasenames: List<String>,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        targetIndices: Map<String, Int>? = null,
    ): ContainerEdit {
        requireEditable(source)
        if (x !in Short.MIN_VALUE..Short.MAX_VALUE ||
            y !in Short.MIN_VALUE..Short.MAX_VALUE
        ) {
            throw Fit3FormatException("widget coordinates must fit signed 16-bit integers")
        }
        val targets = StyleWidgetMatch.resolve(source, entryBasenames, targetIndices) { _, records ->
            records.singleOrNull {
                it.globalIndex == globalIndex &&
                    it.widgetType == widgetType &&
                    it.sequenceId == sequenceId
            }
        }
        val badgeEndpoints = targets.associate { (entry, record) ->
            val endpoint = if (record.widgetType == WIDGET_BADGE) {
                val deltaX = x - record.x
                val deltaY = y - record.y
                val endpointX = record.raw1C.toShort().toInt() + deltaX
                val endpointY = record.raw1E.toShort().toInt() + deltaY
                if (endpointX !in Short.MIN_VALUE..Short.MAX_VALUE ||
                    endpointY !in Short.MIN_VALUE..Short.MAX_VALUE
                ) {
                    throw Fit3FormatException(
                        "${entry.basename}: moved Badge endpoints must fit signed 16-bit integers",
                    )
                }
                endpointX to endpointY
            } else {
                null
            }
            entry.index to endpoint
        }
        val output = source.toByteArray()
        val before = output.copyOf()
        targets.forEach { (entry, record) ->
            val base = entry.offset + record.recordOffset
            output.putU16(base + 0x18, x and 0xFFFF)
            output.putU16(base + 0x1A, y and 0xFFFF)
            badgeEndpoints.getValue(entry.index)?.let { (endpointX, endpointY) ->
                output.putU16(base + 0x1C, endpointX and 0xFFFF)
                output.putU16(base + 0x1E, endpointY and 0xFFFF)
            }
        }
        val changed = targets.sumOf { (entry, record) ->
            val base = entry.offset + record.recordOffset
            val coordinateEnd = if (record.widgetType == WIDGET_BADGE) {
                base + 0x20
            } else {
                base + 0x1C
            }
            (base + 0x18 until coordinateEnd).count { before[it] != output[it] }
        }
        if (changed == 0) {
            throw Fit3FormatException("widget movement would not change any bytes")
        }
        return finalize(source, output, targets.map { it.first }, changed)
    }

    fun editPairWidget(
        source: Fit3Container,
        entryBasename: String,
        globalIndex: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        colorArgb: Int,
    ): ContainerEdit {
        requireEditable(source)
        if (x !in Short.MIN_VALUE..Short.MAX_VALUE ||
            y !in Short.MIN_VALUE..Short.MAX_VALUE
        ) {
            throw Fit3FormatException("widget coordinates must fit signed 16-bit integers")
        }
        if (colorArgb ushr 24 != 0xFF) {
            throw Fit3FormatException("Pair widget color must be opaque ARGB")
        }
        val entry = source.entryByBasename(entryBasename)
        val matches = FaceRecordParser.scanWidgets(entry).filter {
            it.globalIndex == globalIndex &&
                it.widgetType == 5 &&
                it.sequenceId == sequenceId
        }
        if (matches.size != 1) {
            throw Fit3FormatException(
                "$entryBasename: expected exactly one Pair widget with sequence " +
                    "$sequenceId, found ${matches.size}",
            )
        }
        val record = matches.single()
        val originalColor = record.words.firstOrNull()
            ?: throw Fit3FormatException("Pair widget has no type-specific color word")
        if (originalColor ushr 24 != 0xFFL) {
            throw Fit3FormatException(
                "Pair color word 0x${originalColor.toString(16)} is not opaque ARGB",
            )
        }
        val output = source.toByteArray()
        val base = entry.offset + record.recordOffset
        val before = output.copyOf()
        output.putU16(base + 0x18, x and 0xFFFF)
        output.putU16(base + 0x1A, y and 0xFFFF)
        output.putU32(base + WIDGET_FIXED_SIZE, colorArgb.toLong() and 0xFFFF_FFFFL)
        val changed = (base + 0x18 until base + 0x1C).count { before[it] != output[it] } +
            (base + WIDGET_FIXED_SIZE until base + WIDGET_FIXED_SIZE + 4)
                .count { before[it] != output[it] }
        if (changed == 0) {
            throw Fit3FormatException("Pair widget edit would not change any bytes")
        }
        return finalize(source, output, listOf(entry), changed)
    }

    fun recolorPairWidgetAcrossStyles(
        source: Fit3Container,
        entryBasenames: List<String>,
        globalIndex: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        colorArgb: Int,
        targetIndices: Map<String, Int>? = null,
    ): ContainerEdit {
        requireEditable(source)
        if (colorArgb ushr 24 != 0xFF) {
            throw Fit3FormatException("Pair widget color must be opaque ARGB")
        }
        val resolved = StyleWidgetMatch.resolve(source, entryBasenames, targetIndices) { _, records ->
            records.singleOrNull {
                it.globalIndex == globalIndex &&
                    it.widgetType == WIDGET_PAIR &&
                    it.sequenceId == sequenceId &&
                    it.x == x &&
                    it.y == y
            }
        }
        if (resolved.first().second.words.firstOrNull()?.ushr(24) != 0xFFL) {
            throw Fit3FormatException("Pair widget does not expose an opaque color word")
        }
        // A sibling whose colour word is not opaque ARGB is not the schema this
        // rewrite is proven against, so it keeps its own colour instead of being
        // corrupted — the same "best effort away from the selected style" rule.
        val targets = resolved.filter { (_, record) ->
            record.words.firstOrNull()?.ushr(24) == 0xFFL
        }
        val output = source.toByteArray()
        val before = output.copyOf()
        targets.forEach { (entry, record) ->
            output.putU32(
                entry.offset + record.recordOffset + WIDGET_FIXED_SIZE,
                colorArgb.toLong() and 0xFFFF_FFFFL,
            )
        }
        var changed = 0
        val changedEntries = targets.mapNotNull { (entry, record) ->
            val start = entry.offset + record.recordOffset + WIDGET_FIXED_SIZE
            val entryChanged = (start until start + 4).count { before[it] != output[it] }
            changed += entryChanged
            entry.takeIf { entryChanged > 0 }
        }
        if (changed == 0) {
            throw Fit3FormatException("Pair widget already uses that color")
        }
        return finalize(source, output, changedEntries, changed)
    }

    /**
     * Turns one widget to [angleTenths] in the first entry of [entryBasenames], and in
     * every later entry that carries it — a same-size field patch for each of the three
     * [WidgetSchema.RotationModel]s.
     *
     * - A Composite's native angle changes alone: text, layout and resources stay intact.
     * - A Rule's endpoints turn about their midpoint, at the resize rung it is on now,
     *   measured against its pristine line from [pristine]/[pristineWidgets] — see
     *   [RuleGeometry]. Its colour is untouched and its thickness stays on that rung.
     * - A vector arc's start becomes [angleTenths] and its end keeps the stored distance
     *   from it, so a reset writes back the vendor's exact pair.
     *
     * The angle is absolute, so siblings take the same angle, each keeping its own length,
     * range or text; a sibling that cannot take it is skipped, as other best-effort edits.
     */
    fun rotateWidget(source: Fit3Container, entryBasenames: List<String>, globalIndex: Int,
        sequenceId: Int, x: Int, y: Int, angleTenths: Int, targetIndices: Map<String, Int>? = null,
        pristine: Fit3Container? = null, pristineWidgets: Map<String, WidgetPristine> = emptyMap()): ContainerEdit {
        requireEditable(source)
        val angle = dev.fitface.studio.core.model.normalizedRotation(angleTenths)
        val resolved = StyleWidgetMatch.resolve(source, entryBasenames, targetIndices) { _, records ->
            records.singleOrNull { it.globalIndex == globalIndex && WidgetSchema.spec(it.widgetType).rotation != null &&
                it.sequenceId == sequenceId && it.x == x && it.y == y }
        }
        val output = source.toByteArray()
        val patches = resolved.map { (entry, record) ->
            val patch = runCatching {
                rotationPatch(entry, record, angle, pristineWidgets[entry.basename]?.let { it.entry to it.sources }
                    ?: pristine?.entries?.singleOrNull { it.basename == entry.basename }?.let { it to null })
            }
            // The selected style is strict; a sibling that cannot take the angle keeps its own.
            if (entry === resolved.first().first) patch.getOrThrow() to entry else patch.getOrNull()?.let { it to entry }
        }
        var changed = 0
        val entries = patches.mapNotNull { pair ->
            val (patch, entry) = pair ?: return@mapNotNull null
            val before = changed
            patch.forEach { (field, value) ->
                val offset = entry.offset + field
                val previous = output.copyOfRange(offset, offset + 2)
                output.putU16(offset, value and 0xFFFF)
                changed += (0..1).count { previous[it] != output[offset + it] }
            }
            entry.takeIf { changed > before }
        }
        if (changed == 0) throw Fit3FormatException("This widget already uses that rotation.")
        return finalize(source, output, entries, changed)
    }

    /** Halfword writes, as offsets from the start of the entry, that give [record] [angle]. */
    private fun rotationPatch(entry: ContainerEntry, record: WidgetRecord, angle: Int,
        pristine: Pair<ContainerEntry, Map<Int, Int>?>?): List<Pair<Int, Int>> {
        val base = record.recordOffset
        return when (val model = WidgetSchema.spec(record.widgetType).rotation) {
            null -> throw Fit3FormatException("A ${WidgetSchema.spec(record.widgetType).name} widget cannot be rotated.")
            is WidgetSchema.RotationModel.NativeAngle -> {
                if (angle != 0 && !dev.fitface.studio.core.model.canRotateText(record.storedWidth ?: 0, record.storedHeight ?: 0)) {
                    throw Fit3FormatException("This text box is too large to rotate safely. Its angle can still be reset to zero.")
                }
                listOf(base + model.offset to angle)
            }
            is WidgetSchema.RotationModel.AngleRange -> {
                if (angle % 10 != 0) throw Fit3FormatException("Arc angles are whole degrees.")
                val start = entry.data.u16(base + model.startOffset).toShort().toInt()
                val end = entry.data.u16(base + model.endOffset).toShort().toInt()
                if (end - start !in 0..360) throw Fit3FormatException("This arc's range cannot be turned safely.")
                listOf(base + model.startOffset to angle / 10, base + model.endOffset to angle / 10 + end - start)
            }
            WidgetSchema.RotationModel.Endpoints -> {
                if (angle % 10 != 0) throw Fit3FormatException("Line angles are whole degrees.")
                val origin = pristine?.let { (pristineEntry, sources) ->
                    StructuralEditor.pristineRecord(entry, pristineEntry, record, sources)
                }?.takeIf { RuleGeometry.span(it).length > 0 } ?: record
                val current = RuleGeometry.Line(RuleGeometry.span(record), RuleGeometry.thickness(record))
                if (current.span.length == 0.0) throw Fit3FormatException("A line with no length has no direction to turn.")
                val line = RuleGeometry.turnedLine(
                    RuleGeometry.Line(RuleGeometry.span(origin), RuleGeometry.thickness(origin)), current, angle,
                ) ?: throw Fit3FormatException("This line is too long to turn at its current size. Make it smaller first.")
                val (startX, startY) = RuleGeometry.startKeepingMidpoint(record.x, record.y, current.span, line.span)
                val fields = listOf(startX, startY, startX + line.span.x, startY + line.span.y)
                if (fields.any { it !in Short.MIN_VALUE..Short.MAX_VALUE }) {
                    throw Fit3FormatException("Turned line endpoints must fit signed 16-bit integers.")
                }
                buildList {
                    add(base + 0x18 to startX); add(base + 0x1A to startY)
                    add(base + 0x1C to fields[2]); add(base + 0x1E to fields[3])
                    // `+0x30` is a byte beside the `+0x31` diagnostic flag, so the halfword
                    // written back keeps that flag's byte as it is.
                    line.thickness?.let { add(base + 0x30 to ((entry.data[base + 0x31].toInt() and 0xFF) shl 8 or it)) }
                }
            }
        }
    }

    fun replaceBackgrounds(
        source: Fit3Container,
        entryBasenames: List<String>,
        width: Int,
        height: Int,
        argb: IntArray,
    ): ContainerEdit {
        requireEditable(source)
        if (width <= 0 || height <= 0 || argb.size != width * height) {
            throw Fit3FormatException("replacement pixel dimensions are inconsistent")
        }
        val output = source.toByteArray()
        var changedBytes = 0
        val changedEntries = mutableListOf<ContainerEntry>()
        // Computed once so every style keeps a byte-identical background.
        val indexedPayload by lazy(LazyThreadSafetyMode.NONE) { IndexedImage.quantize(argb) }
        backgroundRasters(source, entryBasenames).forEach { (entry, image) ->
            if (image.width != width || image.height != height) {
                throw Fit3FormatException(
                    "${entry.basename}: background is ${image.width}x${image.height}, " +
                        "replacement is ${width}x$height",
                )
            }
            val start = entry.offset + image.pixelOffset
            if (image.isIndexed) {
                // Palette and index plane are both rewritten, so the record keeps
                // its exact byte length and no relocation is needed.
                indexedPayload.forEachIndexed { offset, byte ->
                    if (output[start + offset] != byte) changedBytes++
                    output[start + offset] = byte
                }
            } else {
                repeat(argb.size) { index ->
                    val color = argb[index]
                    val rgb565 = encodeRgb565(
                        color ushr 16 and 0xFF,
                        color ushr 8 and 0xFF,
                        color and 0xFF,
                    )
                    val absolute = entry.offset + image.samplesOffset +
                        index * image.bytesPerPixel
                    val low = rgb565.toByte()
                    val high = (rgb565 ushr 8).toByte()
                    if (output[absolute] != low) changedBytes++
                    if (output[absolute + 1] != high) changedBytes++
                    output[absolute] = low
                    output[absolute + 1] = high
                }
            }
            changedEntries += entry
        }
        if (changedBytes == 0) {
            throw Fit3FormatException("background replacement would not change any pixels")
        }
        return finalize(source, output, changedEntries, changedBytes)
    }

    fun tintBackgrounds(
        source: Fit3Container,
        entryBasenames: List<String>,
        red: Int,
        green: Int,
        blue: Int,
        threshold: Int = 16,
    ): ContainerEdit {
        requireEditable(source)
        listOf(red, green, blue, threshold).forEach {
            if (it !in 0..255) throw Fit3FormatException("color channels must be 0..255")
        }
        val output = source.toByteArray()
        var changedBytes = 0
        val changedEntries = mutableListOf<ContainerEntry>()
        backgroundRasters(source, entryBasenames).forEach { (entry, image) ->
            if (image.isIndexed) {
                // Only the 256-entry palette needs recolouring; the index plane
                // already describes the picture.
                repeat(INDEXED_PALETTE_ENTRIES) { entryIndex ->
                    val base = entry.offset + image.pixelOffset + entryIndex * 4
                    val oldBlue = output[base].toInt() and 0xFF
                    val oldGreen = output[base + 1].toInt() and 0xFF
                    val oldRed = output[base + 2].toInt() and 0xFF
                    val luminance = (77 * oldRed + 150 * oldGreen + 29 * oldBlue + 128) ushr 8
                    if (luminance <= threshold) return@repeat
                    val channels = intArrayOf(
                        (blue * luminance + 127) / 255,
                        (green * luminance + 127) / 255,
                        (red * luminance + 127) / 255,
                    )
                    channels.forEachIndexed { offset, value ->
                        val byte = value.coerceIn(0, 255).toByte()
                        if (output[base + offset] != byte) changedBytes++
                        output[base + offset] = byte
                    }
                }
                changedEntries += entry
                return@forEach
            }
            repeat(image.width * image.height) { index ->
                val absolute = entry.offset + image.samplesOffset + index * image.bytesPerPixel
                val existing = output.u16(absolute)
                val oldRed = (((existing ushr 11) and 0x1F) * 255 + 15) / 31
                val oldGreen = (((existing ushr 5) and 0x3F) * 255 + 31) / 63
                val oldBlue = ((existing and 0x1F) * 255 + 15) / 31
                val luminance = (77 * oldRed + 150 * oldGreen + 29 * oldBlue + 128) ushr 8
                if (luminance <= threshold) return@repeat
                val replacement = encodeRgb565(
                    (red * luminance + 127) / 255,
                    (green * luminance + 127) / 255,
                    (blue * luminance + 127) / 255,
                )
                val low = replacement.toByte()
                val high = (replacement ushr 8).toByte()
                if (output[absolute] != low) changedBytes++
                if (output[absolute + 1] != high) changedBytes++
                output[absolute] = low
                output[absolute + 1] = high
            }
            changedEntries += entry
        }
        if (changedBytes == 0) {
            throw Fit3FormatException("tint would not change any pixels")
        }
        return finalize(source, output, changedEntries, changedBytes)
    }

    /**
     * Rewrites the `preview.bin` raster the watch's face picker and the companion
     * app show for [styleIndex], using [composed] (any size; box-filtered down to
     * the raster's own dimensions).
     *
     * Same-size patch only: `preview.bin` rasters are plain RGB565 with a fixed
     * four-byte trailer, so the record length never changes and no pointer in the
     * container has to move.
     *
     * Returns null when the stored raster already encodes [composed]; that is a
     * no-op, not a failure, and callers re-render on every validation pass.
     */
    fun replacePreviewThumbnail(
        source: Fit3Container,
        styleIndex: Int,
        composed: PreviewFrame,
    ): ContainerEdit? {
        requireEditable(source)
        val entry = source.entries.singleOrNull { it.basename == "preview.bin" }
            ?: throw Fit3FormatException("container has no preview.bin thumbnail entry")
        val rasters = FaceRecordParser.scanImages(entry)
        val raster = rasters.getOrNull(styleIndex) ?: throw Fit3FormatException(
            "preview.bin has ${rasters.size} rasters; style $styleIndex has none",
        )
        if (raster.format != IMAGE_RGB565) {
            throw Fit3FormatException(
                "preview.bin raster $styleIndex is format 0x${raster.format.toString(16)}, " +
                    "which this app does not re-encode",
            )
        }
        val scaled = boxFilter(composed, raster.width, raster.height)
        val output = source.toByteArray()
        var changed = 0
        repeat(scaled.size) { index ->
            val color = scaled[index]
            val rgb565 = encodeRgb565(
                color ushr 16 and 0xFF,
                color ushr 8 and 0xFF,
                color and 0xFF,
            )
            val absolute = entry.offset + raster.samplesOffset + index * 2
            val low = rgb565.toByte()
            val high = (rgb565 ushr 8).toByte()
            if (output[absolute] != low) changed++
            if (output[absolute + 1] != high) changed++
            output[absolute] = low
            output[absolute + 1] = high
        }
        if (changed == 0) return null
        return finalize(source, output, listOf(entry), changed)
    }

    /**
     * The picture the watch's face picker holds for [styleIndex] now, or null where there is
     * none this app can read — no `preview.bin`, no raster at that index, or one that is not
     * plain RGB565, which is the only kind [replacePreviewThumbnail] writes.
     */
    fun previewThumbnail(source: Fit3Container, styleIndex: Int): PreviewFrame? {
        val entry = source.entries.singleOrNull { it.basename == "preview.bin" } ?: return null
        val raster = FaceRecordParser.scanImages(entry).getOrNull(styleIndex) ?: return null
        if (raster.format != IMAGE_RGB565) return null
        return FaceRecordParser.decodeImage(entry, raster)
    }

    /**
     * Exactly the picture [replacePreviewThumbnail] would write for [composed], as the face
     * picker will then show it: box-filtered to the raster's own size and quantised to
     * RGB565 — so what the Styles page shows as "after" is the stored pixels, not the
     * canvas scaled down by the display. Null where [previewThumbnail] is null.
     */
    fun renderedThumbnail(source: Fit3Container, styleIndex: Int, composed: PreviewFrame): PreviewFrame? {
        val entry = source.entries.singleOrNull { it.basename == "preview.bin" } ?: return null
        val raster = FaceRecordParser.scanImages(entry).getOrNull(styleIndex) ?: return null
        if (raster.format != IMAGE_RGB565) return null
        val scaled = boxFilter(composed, raster.width, raster.height)
        return PreviewFrame(raster.width, raster.height, IntArray(scaled.size) { index ->
            val color = scaled[index]
            val rgb565 = encodeRgb565(color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF)
            // The decoder's own expansion, so this is what reading the raster back gives.
            val red = (((rgb565 ushr 11) and 0x1F) * 255 + 15) / 31
            val green = (((rgb565 ushr 5) and 0x3F) * 255 + 31) / 63
            val blue = ((rgb565 and 0x1F) * 255 + 15) / 31
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        })
    }

    /** Area-averaging downscale so thumbnails keep thin glyphs legible. */
    internal fun boxFilter(source: PreviewFrame, width: Int, height: Int): IntArray {
        if (width <= 0 || height <= 0) {
            throw Fit3FormatException("thumbnail dimensions must be positive")
        }
        val output = IntArray(width * height)
        for (y in 0 until height) {
            val startY = y * source.height / height
            val endY = (((y + 1) * source.height + height - 1) / height).coerceAtMost(source.height)
            for (x in 0 until width) {
                val startX = x * source.width / width
                val endX = (((x + 1) * source.width + width - 1) / width)
                    .coerceAtMost(source.width)
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (sourceY in startY until maxOf(endY, startY + 1)) {
                    for (sourceX in startX until maxOf(endX, startX + 1)) {
                        val pixel = source.argb[sourceY * source.width + sourceX]
                        red += pixel ushr 16 and 0xFF
                        green += pixel ushr 8 and 0xFF
                        blue += pixel and 0xFF
                        count++
                    }
                }
                if (count == 0) count = 1
                output[y * width + x] = (0xFF shl 24) or
                    ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
            }
        }
        return output
    }

    private fun requireEditable(source: Fit3Container) {
        val report = source.validate()
        if (!report.isValid) {
            throw Fit3FormatException(
                "refusing to edit invalid container: ${report.errors.joinToString { it.code }}",
            )
        }
    }

    /**
     * Every style that carries a full-panel background raster, paired with it.
     *
     * Not simply raster 0: face `00022` opens every style with a 37×28 icon and
     * `00108` styles 0–3 with a 204×204 dial, and repainting those as if they were
     * the background silently overwrote artwork.
     *
     * A style with no such raster is skipped rather than failing the whole edit — the
     * same "strict on what carries the thing, best effort elsewhere" rule the widget
     * edits follow, and for the same reason: `00011` style0 and `00108` styles 0–3
     * paint straight onto black while their sibling styles do carry a background, so
     * refusing meant those faces could never have one replaced at all. Giving a bare
     * style a background is a different edit — [StructuralEditor.addBackgrounds], which
     * grows the container and is bounded by the watch's size ceiling — and not this
     * one's business.
     */
    private fun backgroundRasters(
        source: Fit3Container,
        entryBasenames: List<String>,
    ): List<Pair<ContainerEntry, ImageRecord>> =
        entryBasenames.map(source::entryByBasename).mapNotNull { entry ->
            FaceRecordParser.backgroundImage(entry)?.let { entry to it }
        }.ifEmpty {
            throw Fit3FormatException(
                "no style in this container has a full-panel background raster; this face " +
                    "draws straight onto the watch's black panel",
            )
        }

    private fun finalize(
        original: Fit3Container,
        output: ByteArray,
        entries: List<ContainerEntry>,
        changedPayloadBytes: Int,
    ): ContainerEdit {
        entries.forEach { entry ->
            val crc = Crc16.ccittFalse(output, entry.offset, entry.end)
            val checksumOffset =
                CONTAINER_HEADER_SIZE + entry.index * DIRECTORY_ENTRY_SIZE + 72
            output.putU16(checksumOffset, crc)
        }
        output.putU16(
            16,
            Crc16.ccittFalse(output, CONTAINER_HEADER_SIZE, output.size),
        )
        val edited = Fit3Container.parse(output)
        val report = edited.validate()
        if (!report.isValid) {
            throw Fit3FormatException(
                "edited container failed validation: ${report.errors.joinToString { it.code }}",
            )
        }
        if (edited.fileSize != original.fileSize) {
            throw Fit3FormatException("same-dimension edit unexpectedly changed file size")
        }
        return ContainerEdit(
            container = edited,
            changedPayloadBytes = changedPayloadBytes,
            changedStyles = entries.map { it.basename },
        )
    }

    private fun encodeRgb565(red: Int, green: Int, blue: Int): Int {
        val red5 = (red * 31 + 127) / 255
        val green6 = (green * 63 + 127) / 255
        val blue5 = (blue * 31 + 127) / 255
        return (red5 shl 11) or (green6 shl 5) or blue5
    }
}
