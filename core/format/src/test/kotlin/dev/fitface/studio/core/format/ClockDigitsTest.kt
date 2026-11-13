package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.ClockPart
import dev.fitface.studio.core.model.PhoneFont
import dev.fitface.studio.core.model.WidgetResizeKind
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.widgetResizeLadder
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The clock drawn again in a font: the drawing replaces the originals, the face is drawn from
 * them at the size and turn it has, and the vendor's frames draw it back byte for byte.
 */
class ClockDigitsTest {
    private val style = "style0.bin"
    private val w = 20
    private val h = 30

    private fun container(records: List<ByteArray>, images: List<ByteArray>): Fit3Container {
        val widgetBytes = records.sumOf { it.size }
        val imageBytes = images.sumOf { it.size }
        val payload = ByteArray(STYLE_HEADER_SIZE + widgetBytes + imageBytes).apply {
            putU32(0, STYLE_MAGIC); putU32(4, records.size.toLong()); putU32(8, widgetBytes.toLong())
            putU32(12, imageBytes.toLong()); putU32(20, (STYLE_HEADER_SIZE + widgetBytes).toLong())
            this[0x11] = 1
            var at = STYLE_HEADER_SIZE
            (records + images).forEach { it.copyInto(this, at); at += it.size }
        }
        val offset = CONTAINER_HEADER_SIZE + DIRECTORY_ENTRY_SIZE
        val bytes = ByteArray(offset + payload.size)
        "oppo".encodeToByteArray().copyInto(bytes)
        bytes.putU32(4, 1); bytes.putU32(8, (bytes.size - CONTAINER_HEADER_SIZE).toLong())
        bytes.putU32(12, 1)
        "./SM-R390_00000_256x402/$style".encodeToByteArray().copyInto(bytes, CONTAINER_HEADER_SIZE)
        bytes.putU32(CONTAINER_HEADER_SIZE + 64, offset.toLong())
        bytes.putU32(CONTAINER_HEADER_SIZE + 68, payload.size.toLong())
        bytes.putU16(CONTAINER_HEADER_SIZE + 72, Crc16.ccittFalse(payload))
        payload.copyInto(bytes, offset)
        bytes.putU16(16, Crc16.ccittFalse(bytes, CONTAINER_HEADER_SIZE, bytes.size))
        return Fit3Container.parse(bytes)
    }

    /** A digit frame: a block of ink, wider for a larger [digit], on clear or on a black box. */
    private fun frame(digit: Int, format: Int = IMAGE_RGB565_ALPHA): ByteArray {
        val bpp = if (format == IMAGE_RGB565_ALPHA) 3 else 2
        return ByteArray(IMAGE_HEADER_SIZE + w * h * bpp + 4).apply {
            putU16(0, w); putU16(2, h); putU16(4, format); putU32(8, (w * h * bpp + 4).toLong())
            for (y in 5 until 25) for (x in 6 until 9 + digit) {
                val at = IMAGE_HEADER_SIZE + (y * w + x) * bpp
                putU16(at, 0x07E0) // green
                if (bpp == 3) this[at + 2] = 0xFF.toByte()
            }
        }
    }

    private fun sprite(index: Int, x: Int, source: Int, pointers: List<Int>) =
        ByteArray(0x24 + pointers.size * 4).apply {
            putU32(0, 3); putU32(4, source.toLong()); putU32(0x0C, ((index shl 16) or size).toLong())
            putU16(0x18, x); putU16(0x1A, 50); this[0x20] = pointers.size.toByte()
            pointers.forEachIndexed { i, p -> putU32(0x24 + i * 4, p.toLong()) }
        }

    /** Hour tens (frames 0–2) and units (0–9) sharing one set of ten digits, as most faces do. */
    private fun clock(format: Int = IMAGE_RGB565_ALPHA): Fit3Container {
        val size = IMAGE_HEADER_SIZE + w * h * (if (format == IMAGE_RGB565_ALPHA) 3 else 2) + 4
        val pointers = (0 until 10).map { it * size }
        return container(listOf(sprite(0, 40, 2, pointers.take(3)), sprite(1, 70, 3, pointers)),
            (0 until 10).map { frame(it, format) })
    }

    private fun entry(c: Fit3Container) = c.entryByBasename(style)
    private fun pristine(c: Fit3Container, font: ArtworkFont? = null) =
        WidgetPristine(ClockDigits.overlay(entry(c), listOfNotNull(font)), mapOf(0 to 0, 1 to 1))

    /** Coverage drawing a bar across row [row] of every frame. */
    private fun font(row: Int = 10, colour: Int = 0xFFFF0000.toInt()) = ArtworkFont(
        phoneFont = PhoneFont.SERIF,
        colour = colour,
        frames = (0 until 10).associateWith { digit ->
            ClockDigits.encodeCoverage(ByteArray(w * h) { i -> if (i / w == row && i % w <= digit + 5) 0xFF.toByte() else 0 })
        },
    )

    private fun redraw(c: Fit3Container, pristine: WidgetPristine, turn: Int = 0) =
        StructuralEditor.redrawArtwork(c, listOf(StructuralEditor.ArtworkRedraw(style, 1, pristine, turn))).container
            .also { assertTrue(it.validate().isValid) }

    private fun samples(c: Fit3Container, image: Int): ByteArray {
        val record = FaceRecordParser.scanImages(entry(c))[image]
        return entry(c).data.copyOfRange(record.samplesOffset, record.pixelOffset + record.pixelDataSize)
    }

    @Test fun theSharedDigitsAreOneSetAndEachFrameIsItsPosition() {
        val set = ClockDigits.sets(entry(clock())).single()
        assertEquals(ClockPart.TIME, set.part)
        assertEquals((0 until 10).associateWith { it }, set.values)
        assertEquals(setOf(0, 1), set.widgets)
        assertEquals(w to h, set.width to set.height)
    }

    @Test fun aDrawingReplacesTheOriginalsAndTheFaceKeepsItsLengthAndPositions() {
        val original = clock()
        val font = font()
        val drawn = redraw(original, pristine(original, font))
        assertEquals(original.fileSize, drawn.fileSize)
        assertEquals(FaceRecordParser.widgetGuides(entry(original)).map { it.drawLeft to it.drawTop },
            FaceRecordParser.widgetGuides(entry(drawn)).map { it.drawLeft to it.drawTop })
        val bar = samples(drawn, 4)
        // Row 10 is red at full alpha for x ≤ 9; everything else, the old ink included, is clear.
        val red = 0xF800
        assertEquals(red, ((bar[(10 * w + 9) * 3 + 1].toInt() and 0xFF) shl 8) or (bar[(10 * w + 9) * 3].toInt() and 0xFF))
        assertEquals(0xFF, bar[(10 * w + 9) * 3 + 2].toInt() and 0xFF)
        assertEquals(0, bar[(10 * w + 10) * 3 + 2].toInt())
        assertEquals(0, bar[(20 * w + 7) * 3 + 2].toInt())
        // Drawn again from the same originals, nothing changes.
        assertArrayEquals(drawn.toByteArray(), redraw(drawn, pristine(original, font)).toByteArray())
    }

    @Test fun theVendorFramesDrawTheShippedBytesBack() {
        val original = clock()
        val drawn = redraw(original, pristine(original, font()))
        assertFalse(original.toByteArray().contentEquals(drawn.toByteArray()))
        assertArrayEquals(original.toByteArray(), redraw(drawn, pristine(original)).toByteArray())
    }

    @Test fun aResizeAfterADrawingSamplesTheDrawingAndAResetKeepsTheRung() {
        val original = clock()
        val font = font()
        val rung = widgetResizeLadder(w, h, WidgetResizeKind.RASTER).single { it.percentOfOriginal == 80 }
        fun resize(c: Fit3Container, from: WidgetPristine): Fit3Container {
            val g = FaceRecordParser.widgetGuides(entry(c)).single { it.globalIndex == 1 }
            return StructuralEditor.resizeWidget(c, listOf(style), 1, g.type, g.sequenceId, g.x, g.y,
                rung.width, rung.height, pristineWidgets = mapOf(style to from)).container
        }
        // Drawn then resized, or resized then drawn: the same frames, both from the drawing.
        val drawnThenResized = resize(redraw(original, pristine(original, font)), pristine(original, font))
        val resizedThenDrawn = redraw(resize(original, pristine(original)), pristine(original, font))
        assertArrayEquals(drawnThenResized.toByteArray(), resizedThenDrawn.toByteArray())
        assertEquals(rung.width, FaceRecordParser.scanImages(entry(resizedThenDrawn))[0].width)
        // Reset font at 80% is the vendor's frames at 80%.
        assertArrayEquals(resize(original, pristine(original)).toByteArray(),
            redraw(resizedThenDrawn, pristine(original)).toByteArray())
    }

    @Test fun aTurnedSetIsDrawnAtItsTurn() {
        val original = clock()
        val font = font()
        val g = FaceRecordParser.widgetGuides(entry(original)).single { it.globalIndex == 1 }
        val turned = StructuralEditor.turnArtwork(original, listOf(style), 1, g.type, g.sequenceId, g.x, g.y, 300,
            pristineWidgets = mapOf(style to pristine(original))).container
        val drawn = redraw(turned, pristine(original, font), turn = 300)
        assertEquals(turned.fileSize, drawn.fileSize)
        // The same as turning a face whose own frames were the drawing.
        val turnedDrawing = StructuralEditor.turnArtwork(redraw(original, pristine(original, font)), listOf(style), 1,
            g.type, g.sequenceId, g.x, g.y, 300, pristineWidgets = mapOf(style to pristine(original, font))).container
        assertArrayEquals(turnedDrawing.toByteArray(), drawn.toByteArray())
        assertArrayEquals(turned.toByteArray(), redraw(drawn, pristine(original), turn = 300).toByteArray())
    }

    @Test fun anOpaqueSetKeepsItsBoxItsFormatAndItsLength() {
        val original = clock(IMAGE_RGB565)
        val drawn = redraw(original, pristine(original, font(colour = 0xFFFFFFFF.toInt())))
        assertEquals(original.fileSize, drawn.fileSize)
        assertEquals(setOf(IMAGE_RGB565), FaceRecordParser.scanImages(entry(drawn)).map { it.format }.toSet())
        val bar = samples(drawn, 4)
        assertEquals(0xFFFF, ((bar[(10 * w + 9) * 2 + 1].toInt() and 0xFF) shl 8) or (bar[(10 * w + 9) * 2].toInt() and 0xFF))
        // The box is black, and the old green ink is gone.
        assertEquals(0, bar[(20 * w + 7) * 2].toInt() or bar[(20 * w + 7) * 2 + 1].toInt())
        assertArrayEquals(original.toByteArray(), redraw(drawn, pristine(original)).toByteArray())
    }

    @Test fun theLookIsTheInkedBoxAndTheSolidColour() {
        val original = clock()
        val images = FaceRecordParser.scanImages(entry(original))
        val look = requireNotNull(ClockDigits.look(images.map { FaceRecordParser.decodeImage(entry(original), it) }))
        assertEquals(ClockDigits.Look(6, 5, 18, 25, 0xFF00FF00.toInt()), look)
        val opaque = clock(IMAGE_RGB565)
        val boxed = requireNotNull(ClockDigits.look(FaceRecordParser.scanImages(entry(opaque))
            .map { FaceRecordParser.decodeImage(entry(opaque), it) }))
        assertEquals(look, boxed)
    }

    @Test fun onlySetsEveryWidgetOfWhichShowsTheClockByPosition() {
        val size = IMAGE_HEADER_SIZE + w * h * 3 + 4
        val pointers = (0 until 10).map { it * size }
        val frames = (0 until 10).map { frame(it) }
        // Steps (29) is not a clock part; neither is a set it shares.
        assertEquals(emptyList<ClockDigits.DigitSet>(),
            ClockDigits.sets(entry(container(listOf(sprite(0, 40, 29, pointers)), frames))))
        assertEquals(emptyList<ClockDigits.DigitSet>(), ClockDigits.sets(entry(container(
            listOf(sprite(0, 40, 3, pointers), sprite(1, 70, 29, pointers)), frames))))
        // A weekday needs seven pictures, and digits ten at most.
        assertEquals(emptyList<ClockDigits.DigitSet>(),
            ClockDigits.sets(entry(container(listOf(sprite(0, 40, 17, pointers.take(6))), frames))))
        assertEquals(ClockPart.WEEKDAY, ClockDigits.sets(entry(container(listOf(sprite(0, 40, 17, pointers.take(7))), frames)))
            .single().part)
        // Two widgets disagreeing about what a frame shows.
        assertEquals(emptyList<ClockDigits.DigitSet>(), ClockDigits.sets(entry(container(
            listOf(sprite(0, 40, 3, pointers), sprite(1, 70, 2, pointers.drop(1).take(3))), frames))))
    }

    /** A `w×h` RGB565+A picture inking the given rectangles, as left, top, right, bottom. */
    private fun picture(w: Int, h: Int, vararg blobs: IntArray): ByteArray =
        ByteArray(IMAGE_HEADER_SIZE + w * h * 3 + 4).apply {
            putU16(0, w); putU16(2, h); putU16(4, IMAGE_RGB565_ALPHA); putU32(8, (w * h * 3 + 4).toLong())
            blobs.forEach { (left, top, right, bottom) ->
                for (y in top until bottom) for (x in left until right) {
                    putU16(IMAGE_HEADER_SIZE + (y * w + x) * 3, 0xFFFF)
                    this[IMAGE_HEADER_SIZE + (y * w + x) * 3 + 2] = 0xFF.toByte()
                }
            }
        }

    private fun static(index: Int, x: Int, pointer: Int) = ByteArray(40).apply {
        putU32(0, 1); putU32(0x0C, ((index shl 16) or 40).toLong())
        putU16(0x18, x); putU16(0x1A, 55); putU16(0x1C, 0); putU16(0x1E, 0xFFFF); putU32(0x20, pointer.toLong())
    }

    @Test fun theColonBetweenHoursAndMinutesIsRecognisedByPlaceAndShape() {
        val size = IMAGE_HEADER_SIZE + w * h * 3 + 4
        val digits = (0 until 10).map { it * size }
        val clock = listOf(sprite(0, 40, 3, digits), sprite(1, 100, 10, digits.take(6)))
        val frames = (0 until 10).map { frame(it) }
        val after = 10 * size
        fun sets(colon: ByteArray, x: Int = 72) = ClockDigits.sets(entry(container(clock + static(2, x, after), frames + colon)))
        val dots = picture(8, 30, intArrayOf(2, 6, 6, 10), intArrayOf(2, 20, 6, 24))
        val colon = sets(dots).last()
        assertEquals(ClockPart.TIME, colon.part)
        assertEquals(mapOf(10 to ClockDigits.COLON), colon.values)
        assertEquals(setOf(2), colon.widgets)
        // One blob, two side by side, or out of place: not a colon.
        assertEquals(1, sets(picture(8, 30, intArrayOf(2, 6, 6, 24))).size)
        assertEquals(1, sets(picture(8, 30, intArrayOf(0, 6, 3, 10), intArrayOf(5, 6, 8, 10))).size)
        assertEquals(1, sets(dots, x = 20).size)
        // Drawn again like any set: the face keeps its length and the colon draws back.
        val original = container(clock + static(2, 72, after), frames + dots)
        val coverage = ClockDigits.encodeCoverage(ByteArray(8 * 30) { if (it / 8 in 12..14) 0xFF.toByte() else 0 })
        val font = ArtworkFont(phoneFont = PhoneFont.SANS, colour = 0xFFFF0000.toInt(), frames = mapOf(10 to coverage))
        val sources = mapOf(0 to 0, 1 to 1, 2 to 2)
        fun redraw(c: Fit3Container, f: ArtworkFont?) = StructuralEditor.redrawArtwork(c, listOf(StructuralEditor.ArtworkRedraw(
            style, 2, WidgetPristine(ClockDigits.overlay(entry(original), listOfNotNull(f)), sources)))).container
        val drawn = redraw(original, font)
        assertEquals(original.fileSize, drawn.fileSize)
        assertFalse(drawn.toByteArray().contentEquals(original.toByteArray()))
        assertArrayEquals(original.toByteArray(), redraw(drawn, null).toByteArray())
    }

    @Test fun savedCoverageComesBackExactlyAndDamageIsRefused() {
        val coverage = ByteArray(w * h) { (it * 7).toByte() }
        assertArrayEquals(coverage, ClockDigits.coverage(ClockDigits.encodeCoverage(coverage), w * h))
        assertThrows(Fit3FormatException::class.java) { ClockDigits.coverage(ClockDigits.encodeCoverage(coverage), w * h - 1) }
        assertThrows(Fit3FormatException::class.java) { ClockDigits.coverage(ClockDigits.encodeCoverage(coverage), w * h + 1) }
        assertThrows(Fit3FormatException::class.java) { ClockDigits.coverage("not base64!", w * h) }
        assertThrows(Fit3FormatException::class.java) { ClockDigits.coverage("AAAA", w * h) }
    }

    @Test fun theLineageKeepsADrawingByItsArtworkAndChecksIt() {
        val original = clock()
        val lineage = SessionLineage.capture(original, original, null)
        val key = SessionLineage.nativeArtworkKey(style, 0)
        val saved = lineage.withFont(key, font())
        saved.validate(original, original, null)
        assertEquals(font(), saved.font(key))
        assertEquals(null, saved.withFont(key, null).artworkFonts)
        assertThrows(IllegalArgumentException::class.java) {
            lineage.withFont(SessionLineage.nativeArtworkKey(style, 10), font()).validate(original, original, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            lineage.withFont(key, font().copy(colour = 0x00FF0000)).validate(original, original, null)
        }
        assertThrows(Fit3FormatException::class.java) {
            lineage.withFont(key, font().copy(frames = mapOf(0 to ClockDigits.encodeCoverage(ByteArray(3)))))
                .validate(original, original, null)
        }
        assertEquals(saved.artworkFonts, saved.retainVariants(mapOf(style to style)).artworkFonts)
        assertEquals(null, saved.retainVariants(emptyMap()).artworkFonts)
    }

    /**
     * Every set of clock pictures in each catalogue face's first style and always-on display,
     * drawn again over its own originals and drawn back: the same length, and then the shipped
     * bytes. Recorded on the 99-face corpus.
     */
    @Test fun everyCatalogueClockIsDrawnAndDrawnBack() {
        val directory = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot"))).resolve("SM_R390")
        assumeTrue("no corpus at $directory", Files.isDirectory(directory))
        val failures = mutableListOf<String>()
        var drawn = 0
        val parts = mutableMapOf<ClockPart, Int>()
        var colons = 0
        Files.list(directory).use { it.toList() }.sorted().forEach { folder ->
            val path = folder.resolve("${folder.fileName}.bin")
            if (!Files.isRegularFile(path)) return@forEach
            val original = Fit3Container.parse(Files.readAllBytes(path))
            for (variant in listOf("style0.bin", "aod.bin")) {
                val entry = original.entries.firstOrNull { it.basename == variant } ?: continue
                val images = FaceRecordParser.scanImages(entry)
                val sources = FaceRecordParser.scanWidgets(entry).associate { it.globalIndex to it.globalIndex }
                for (set in ClockDigits.sets(entry)) {
                    val label = "${folder.fileName} $variant #${set.widgets.min()}"
                    try {
                        val frames = set.values.keys.associateWith { index ->
                            val image = images[index]
                            ClockDigits.encodeCoverage(ByteArray(image.width * image.height) { i ->
                                if ((i % image.width + i / image.width) % 5 == 0) 0xC0.toByte() else 0
                            })
                        }
                        val font = ArtworkFont(fontFile = "test.ttf", colour = 0xFF3366CC.toInt(), frames = frames)
                        val redraw = { c: Fit3Container, f: ArtworkFont? ->
                            StructuralEditor.redrawArtwork(c, listOf(StructuralEditor.ArtworkRedraw(variant, set.widgets.min(),
                                WidgetPristine(ClockDigits.overlay(entry, listOfNotNull(f)), sources)))).container
                        }
                        val out = redraw(original, font)
                        if (!out.validate().isValid) failures += "$label: invalid"
                        if (out.fileSize != original.fileSize) failures += "$label: length changed"
                        if (out.toByteArray().contentEquals(original.toByteArray())) failures += "$label: nothing drawn"
                        if (!redraw(out, null).toByteArray().contentEquals(original.toByteArray())) failures += "$label: not drawn back"
                        val look = ClockDigits.look(set.values.keys.map { FaceRecordParser.decodeImage(entry, images[it]) })
                        if (look == null || look.right <= look.left || look.bottom <= look.top) failures += "$label: no ink"
                        parts.merge(set.part, 1, Int::plus)
                        if (ClockDigits.COLON in set.values.values) colons++
                        drawn++
                    } catch (error: Fit3FormatException) {
                        failures += "$label: ${error.message}"
                    }
                }
            }
        }
        assumeTrue("corpus holds no clock pictures", drawn > 0)
        assertEquals("$drawn sets drawn $parts", emptyList<String>(), failures.take(10))
        println("ClockDigitsTest: $drawn sets drawn and drawn back, $parts, colons $colons")
    }
}
