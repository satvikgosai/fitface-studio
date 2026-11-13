package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.ClockFontName
import dev.fitface.studio.core.model.ClockPart
import dev.fitface.studio.core.model.PhoneFont
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * One set of clock pictures drawn again in a font, as [SessionLineage.artworkFonts] keeps it.
 *
 * What is kept is each glyph's **coverage**, one byte a pixel at the original frame's size, and
 * the one colour it is drawn in: everything needed to draw the frame again in its own format,
 * and nothing that depends on the phone or the font file, which is never kept. The frames are
 * keyed by *original* image index — of the vendor's variant, or of an import's baseline.
 */
@Serializable
data class ArtworkFont(
    val phoneFont: PhoneFont? = null,
    val fontFile: String? = null,
    /** Opaque `0xFFRRGGBB`. */
    val colour: Int,
    val frames: Map<Int, String>,
) {
    val name: ClockFontName get() = ClockFontName(phoneFont, fontFile)
}

/**
 * The clock a face draws as pictures of characters — Sprites whose frame *n* shows the digit
 * *n*, or the *n*th weekday — and how a font draws those pictures again (Stage 9b).
 *
 * A font replaces a set's **originals**, never just what is on the face: every later resize and
 * turn samples the originals, so drawing only the current frames would last until the next tap.
 * Each original frame is drawn at its own size and in its own format, which makes the drawing a
 * pure replacement of samples. An RGB565 set's frames are opaque boxes, and the new glyph is
 * blended over the box's own colour rather than made transparent, so the face looks as it did
 * apart from the font and the frames keep their length. [StructuralEditor.redrawArtwork] then
 * draws the frames on the face from them at the size and turn they have now.
 *
 * Census (99 faces, every style and always-on display, 2026-10): hours and minutes are Sprites
 * on 66 faces, seconds, a second time zone and the weekday on one each; no face draws its date,
 * month or AM/PM this way, so those are not offered. No Sprite is Indexed8. Every opaque frame's
 * four corners agree on one colour but one always-on display's. The colon is a Static of its
 * own, recognised by place and shape ([sets]), and goes with hours and minutes.
 */
object ClockDigits {
    /** The part a Sprite on [source] shows by frame position, or null. */
    fun partOf(source: Int): ClockPart? = when (source) {
        2, 3, 10, 11 -> ClockPart.TIME
        14, 15 -> ClockPart.SECONDS
        106, 107, 109, 110 -> ClockPart.SECOND_ZONE
        17 -> ClockPart.WEEKDAY
        else -> null
    }

    /**
     * One raster pool every widget of which shows a clock part by frame position.
     *
     * [values] is what each current image shows: a digit, or a weekday counted from Monday.
     * Pools shared by hours and seconds take the earlier part, since their frames are the
     * same digits.
     */
    data class DigitSet(
        val part: ClockPart,
        val values: Map<Int, Int>,
        val widgets: Set<Int>,
        val width: Int,
        val height: Int,
    )

    /** What a colon's one frame shows; a digit is 0–9 and a weekday 0–6. */
    const val COLON = -1

    /**
     * [entry]'s sets, one per pool, in record order, then the colon between hours and minutes
     * when there is one ([colon]). Pools that cannot be redrawn are left out.
     */
    fun sets(entry: ContainerEntry): List<DigitSet> {
        val records = FaceRecordParser.scanWidgets(entry)
        if (records.none { it.widgetType == WIDGET_SPRITE && partOf(it.sourceId) != null }) return emptyList()
        val relative = FaceRecordParser.imagesByRelativeOffset(entry)
        val images = FaceRecordParser.scanImages(entry)
        val background = FaceRecordParser.backgroundImage(entry)?.index
        val sets = digitSets(records, relative, images, background)
        return sets + listOfNotNull(colon(entry, records, relative, images, background, sets))
    }

    private fun digitSets(
        records: List<WidgetRecord>,
        relative: Map<Long, ImageRecord>,
        images: List<ImageRecord>,
        background: Int?,
    ): List<DigitSet> {
        val seen = mutableSetOf<Set<Int>>()
        return records.filter { it.widgetType == WIDGET_SPRITE && partOf(it.sourceId) != null }.mapNotNull { target ->
            val pool = FaceRecordParser.rasterPool(target, records, relative)
            if (pool.unreadable || pool.images.isEmpty() || !seen.add(pool.images)) return@mapNotNull null
            if (background != null && background in pool.images) return@mapNotNull null
            val parts = pool.widgets.map { if (it.widgetType == WIDGET_SPRITE) partOf(it.sourceId) else null }
            if (null in parts) return@mapNotNull null
            val part = parts.filterNotNull().minBy { it.ordinal }
            if ((part == ClockPart.WEEKDAY) != parts.all { it == ClockPart.WEEKDAY }) return@mapNotNull null
            val values = mutableMapOf<Int, Int>()
            pool.widgets.forEach { widget ->
                val frames = runCatching { FaceRecordParser.imagePointerFields(widget, relative) }.getOrNull()
                    ?: return@mapNotNull null
                if (if (part == ClockPart.WEEKDAY) frames.size != 7 else frames.size !in 1..10) return@mapNotNull null
                frames.forEachIndexed { position, field ->
                    if (values.put(field.image.index, position)?.let { it != position } == true) return@mapNotNull null
                }
            }
            val frames = pool.images.map(images::get)
            val signature = frames.map { Triple(it.width, it.height, it.format) }.toSet().singleOrNull()
                ?: return@mapNotNull null
            if (values.keys != pool.images || signature.third !in DRAWN_FORMATS ||
                frames.any { it.reserved != 0 || it.opaqueTrailerSize != OPAQUE_TRAILER_BYTES }) return@mapNotNull null
            DigitSet(part, values, pool.widgets.map { it.globalIndex }.toSet(), signature.first, signature.second)
        }
    }

    /**
     * The Static drawing the colon between hours and minutes, as a set of one, or null.
     *
     * The colon is not a reading, so nothing in its record says what it is; it is recognised
     * by place and shape. Its centre lies between the hour's units and the minute's tens and
     * level with them; its picture is drawn by Statics alone; and it is inked as exactly two
     * dots, one above the other. A shape test rather than place alone, because a decoration
     * can sit between the digits too, and drawing ":" over it would be wrong. A face that
     * draws a second colon, before the seconds or in a second time zone, often draws it from
     * the same picture, and the set then has both. Census (99 faces, first style): 26 colons
     * and nothing else between the digits but full-face backgrounds. One is tilted (`00105`),
     * one wider than a digit (`00009`) and one a box stored with alpha (`00010`).
     */
    private fun colon(
        entry: ContainerEntry,
        records: List<WidgetRecord>,
        relative: Map<Long, ImageRecord>,
        images: List<ImageRecord>,
        background: Int?,
        sets: List<DigitSet>,
    ): DigitSet? {
        val time = sets.filter { it.part == ClockPart.TIME }.flatMap { it.widgets }.toSet()
        if (time.isEmpty()) return null
        val sources = records.filter { it.globalIndex in time }.associate { it.globalIndex to it.sourceId }
        val guides = FaceRecordParser.widgetGuides(entry).associateBy { it.globalIndex }
        fun digit(vararg wanted: Int) = wanted.toList().firstNotNullOfOrNull { source ->
            sources.entries.firstOrNull { it.value == source }?.let { guides[it.key] }
        }
        val hour = digit(3, 2) ?: return null
        val minute = digit(10, 11) ?: return null
        val from = hour.drawLeft + hour.width / 2.0
        val to = minute.drawLeft + minute.width / 2.0
        if (to <= from) return null
        return records.filter { it.widgetType == WIDGET_STATIC }.mapNotNull { record ->
            val guide = guides[record.globalIndex] ?: return@mapNotNull null
            val x = guide.drawLeft + guide.width / 2.0
            val y = guide.drawTop + guide.height / 2.0
            if (x <= from || x >= to || y < hour.drawTop || y > hour.drawTop + hour.height || guide.width > 2 * hour.width) {
                return@mapNotNull null
            }
            val pool = FaceRecordParser.rasterPool(record, records, relative)
            val image = pool.images.singleOrNull()?.let(images::get) ?: return@mapNotNull null
            if (pool.unreadable || pool.widgets.any { it.widgetType != WIDGET_STATIC } || image.index == background ||
                image.format !in DRAWN_FORMATS || image.reserved != 0 || image.opaqueTrailerSize != OPAQUE_TRAILER_BYTES ||
                !twoDots(FaceRecordParser.decodeImage(entry, image))) return@mapNotNull null
            DigitSet(ClockPart.TIME, mapOf(image.index to COLON), pool.widgets.map { it.globalIndex }.toSet(),
                image.width, image.height)
        }.singleOrNull()
    }

    /**
     * Whether [frame] inks exactly two blobs, one above the other — level enough that neither
     * reaches the other's rows, which a tilted colon still is. Specks under 3% of the ink are
     * ignored.
     */
    internal fun twoDots(frame: PreviewFrame): Boolean {
        val weight = weights(frame)
        val strongest = weight.maxOrNull() ?: return false
        if (strongest == 0) return false
        val ink = BooleanArray(weight.size) { weight[it] * 4 >= strongest }
        val seen = BooleanArray(ink.size)
        // Each blob as left, top, right, bottom and its pixel count.
        val blobs = mutableListOf<IntArray>()
        for (start in ink.indices) {
            if (!ink[start] || seen[start]) continue
            val bounds = intArrayOf(frame.width, frame.height, -1, -1, 0)
            val stack = ArrayDeque(listOf(start))
            seen[start] = true
            while (stack.isNotEmpty()) {
                val at = stack.removeLast()
                val x = at % frame.width
                val y = at / frame.width
                bounds[0] = minOf(bounds[0], x); bounds[1] = minOf(bounds[1], y)
                bounds[2] = maxOf(bounds[2], x); bounds[3] = maxOf(bounds[3], y); bounds[4]++
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until frame.width || ny !in 0 until frame.height) continue
                    val next = ny * frame.width + nx
                    if (ink[next] && !seen[next]) { seen[next] = true; stack.addLast(next) }
                }
            }
            blobs += bounds
        }
        val total = blobs.sumOf { it[4] }
        val dots = blobs.filter { it[4] * 100 >= total * 3 }.sortedBy { it[1] }
        if (dots.size != 2) return false
        val (upper, lower) = dots
        return upper[3] < lower[1]
    }

    /**
     * How strongly each pixel of [frame] is ink: its alpha on a clear frame, or its distance
     * from the box's colour on a frame whose corners are opaque — every RGB565 frame, and a
     * frame with alpha that still paints a box (`00010`'s colon).
     */
    private fun weights(frame: PreviewFrame): IntArray {
        val box = boxColour(frame)
        return if (box ushr 24 == 0xFF) IntArray(frame.argb.size) { distance(frame.argb[it], box) }
        else IntArray(frame.argb.size) { frame.argb[it] ushr 24 }
    }

    /**
     * Where a set's own glyphs sit in their frames — the box they ink, across every frame — and
     * the colour they are drawn in. Null when no frame inks anything.
     *
     * Ink is coverage of at least a quarter ([weights]). The colour is the mean of the most
     * solid ink, so the faint edge of an anti-aliased glyph does not darken it.
     */
    data class Look(val left: Int, val top: Int, val right: Int, val bottom: Int, val colour: Int)

    fun look(frames: List<PreviewFrame>): Look? {
        if (frames.isEmpty()) return null
        val weights = frames.map(::weights)
        val strongest = weights.maxOf { it.maxOrNull() ?: 0 }
        if (strongest == 0) return null
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = 0; var bottom = 0
        var red = 0L; var green = 0L; var blue = 0L; var count = 0L
        frames.forEachIndexed { index, frame ->
            val weight = weights[index]
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                val w = weight[y * frame.width + x]
                if (w * 4 >= strongest) {
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
                }
                if (w * 10 >= strongest * 9) {
                    val colour = frame.argb[y * frame.width + x]
                    red += colour ushr 16 and 0xFF; green += colour ushr 8 and 0xFF; blue += colour and 0xFF; count++
                }
            }
        }
        val colour = (0xFF shl 24) or ((red / count).toInt() shl 16) or ((green / count).toInt() shl 8) or (blue / count).toInt()
        return Look(left, top, right, bottom, colour)
    }

    /**
     * [original]'s samples drawn again: [colour] at [coverage], in the original's own format.
     * A frame that paints a box — its corners opaque — keeps it, in the colour of most of its
     * corners, under the glyph; any other is clear around it.
     */
    internal fun samples(original: ImageRecord, entry: ContainerEntry, coverage: ByteArray, colour: Int): ByteArray {
        val pixels = original.width * original.height
        require(coverage.size == pixels) { "a glyph's coverage must match its frame" }
        val ink = rgb565(colour)
        val box = boxColour(FaceRecordParser.decodeImage(entry, original))
        return when (original.format) {
            IMAGE_RGB565_ALPHA -> if (box ushr 24 == 0xFF) ByteArray(pixels * 3).also { out ->
                coverage.forEachIndexed { index, value ->
                    val sample = rgb565(blend(box, colour, value.toInt() and 0xFF))
                    out[index * 3] = sample.toByte()
                    out[index * 3 + 1] = (sample ushr 8).toByte()
                    out[index * 3 + 2] = 0xFF.toByte()
                }
            } else ByteArray(pixels * 3).also { out ->
                coverage.forEachIndexed { index, value ->
                    val alpha = value.toInt() and 0xFF
                    val sample = if (alpha == 0) 0 else ink
                    out[index * 3] = sample.toByte()
                    out[index * 3 + 1] = (sample ushr 8).toByte()
                    out[index * 3 + 2] = alpha.toByte()
                }
            }
            IMAGE_RGB565 -> ByteArray(pixels * 2).also { out ->
                coverage.forEachIndexed { index, value ->
                    val sample = rgb565(blend(box, colour, value.toInt() and 0xFF))
                    out[index * 2] = sample.toByte()
                    out[index * 2 + 1] = (sample ushr 8).toByte()
                }
            }
            else -> throw Fit3FormatException("This picture's colour format cannot be drawn in a font.")
        }
    }

    /**
     * [pristine] with every frame [fonts] drew replaced by its drawing. Offsets and lengths are
     * unchanged, so pointers, pairing and every resize rule read it as they read the original.
     */
    fun overlay(pristine: ContainerEntry, fonts: Collection<ArtworkFont>): ContainerEntry {
        if (fonts.isEmpty()) return pristine
        val images = FaceRecordParser.scanImages(pristine)
        val data = pristine.data.copyOf()
        fonts.forEach { font ->
            font.frames.forEach { (index, encoded) ->
                val image = images.getOrNull(index)
                    ?: throw Fit3FormatException("A saved clock font names a picture this face does not have.")
                samples(image, pristine, coverage(encoded, image.width * image.height), font.colour)
                    .copyInto(data, image.samplesOffset)
            }
        }
        return pristine.copy(data = data)
    }

    fun encodeCoverage(coverage: ByteArray): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val out = ByteArrayOutputStream()
        try {
            deflater.setInput(coverage)
            deflater.finish()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        } finally {
            deflater.end()
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Exactly [pixels] bytes of coverage, or refused: a saved drawing is read from a file. */
    fun coverage(encoded: String, pixels: Int): ByteArray {
        val packed = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw Fit3FormatException("A saved clock font is damaged.", error)
        }
        val inflater = Inflater()
        try {
            inflater.setInput(packed)
            val out = ByteArray(pixels)
            var filled = 0
            while (filled < pixels && !inflater.finished()) {
                val read = inflater.inflate(out, filled, pixels - filled)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                filled += read
            }
            if (filled != pixels || !inflater.finished() || inflater.remaining != 0) {
                throw Fit3FormatException("A saved clock font does not match its picture.")
            }
            return out
        } catch (error: DataFormatException) {
            throw Fit3FormatException("A saved clock font is damaged.", error)
        } finally {
            inflater.end()
        }
    }

    /** The colour, alpha included, most of [frame]'s corners have: its box when it is opaque. */
    internal fun boxColour(frame: PreviewFrame): Int {
        val corners = listOf(0, frame.width - 1, frame.argb.size - frame.width, frame.argb.size - 1)
            .map { frame.argb[it] }
        return corners.groupingBy { it }.eachCount().entries.maxWith(compareBy({ it.value }, { -corners.indexOf(it.key) })).key
    }

    private fun distance(a: Int, b: Int): Int =
        (abs((a ushr 16 and 0xFF) - (b ushr 16 and 0xFF)) + abs((a ushr 8 and 0xFF) - (b ushr 8 and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF)) + 2) / 3

    private fun blend(under: Int, over: Int, alpha: Int): Int {
        fun channel(shift: Int) = ((under ushr shift and 0xFF) * (255 - alpha) + (over ushr shift and 0xFF) * alpha + 127) / 255
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun rgb565(colour: Int): Int =
        (((colour ushr 16 and 0xFF) * 31 + 127) / 255 shl 11) or
            (((colour ushr 8 and 0xFF) * 63 + 127) / 255 shl 5) or
            (((colour and 0xFF) * 31 + 127) / 255)

    private val DRAWN_FORMATS = setOf(IMAGE_RGB565, IMAGE_RGB565_ALPHA)
}
