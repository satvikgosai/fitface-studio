package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetResizeKind
import dev.fitface.studio.core.model.WidgetRotationKind
import dev.fitface.studio.core.model.artworkBounds
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.widgetResizeLadder
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Turning a Static's or Sprite's artwork: a redraw from the original, into the larger box a
 * turned image needs, about the widget's own centre — and back to the shipped bytes.
 */
class ArtworkTurnTest {
    private val style = "style0.bin"

    /** [names] styles of [records] over [images], each image a complete record with trailer. */
    private fun container(records: List<ByteArray>, images: List<ByteArray>,
        names: List<String> = listOf(style)): Fit3Container {
        val widgetBytes = records.sumOf { it.size }
        val imageBytes = images.sumOf { it.size }
        val payload = ByteArray(STYLE_HEADER_SIZE + widgetBytes + imageBytes).apply {
            putU32(0, STYLE_MAGIC); putU32(4, records.size.toLong()); putU32(8, widgetBytes.toLong())
            putU32(12, imageBytes.toLong()); putU32(20, (STYLE_HEADER_SIZE + widgetBytes).toLong())
            this[0x11] = 1
            var at = STYLE_HEADER_SIZE
            (records + images).forEach { it.copyInto(this, at); at += it.size }
        }
        val offset = CONTAINER_HEADER_SIZE + names.size * DIRECTORY_ENTRY_SIZE
        val bytes = ByteArray(offset + names.size * payload.size)
        "oppo".encodeToByteArray().copyInto(bytes)
        bytes.putU32(4, 1); bytes.putU32(8, (bytes.size - CONTAINER_HEADER_SIZE).toLong())
        bytes.putU32(12, names.size.toLong())
        names.forEachIndexed { i, name ->
            val dir = CONTAINER_HEADER_SIZE + i * DIRECTORY_ENTRY_SIZE
            "./SM-R390_00000_256x402/$name".encodeToByteArray().copyInto(bytes, dir)
            bytes.putU32(dir + 64, (offset + i * payload.size).toLong())
            bytes.putU32(dir + 68, payload.size.toLong())
            bytes.putU16(dir + 72, Crc16.ccittFalse(payload))
            payload.copyInto(bytes, offset + i * payload.size)
        }
        bytes.putU16(16, Crc16.ccittFalse(bytes, CONTAINER_HEADER_SIZE, bytes.size))
        return Fit3Container.parse(bytes)
    }

    /** A fully opaque `w×h` picture with a different colour in every pixel. */
    private fun image(w: Int, h: Int, format: Int = IMAGE_RGB565_ALPHA): ByteArray {
        val bpp = if (format == IMAGE_RGB565_ALPHA) 3 else 2
        return ByteArray(IMAGE_HEADER_SIZE + w * h * bpp + 4).apply {
            putU16(0, w); putU16(2, h); putU16(4, format); putU32(8, (w * h * bpp + 4).toLong())
            for (i in 0 until w * h) {
                putU16(IMAGE_HEADER_SIZE + i * bpp, (i * 37 + 11) and 0xFFFF)
                if (bpp == 3) this[IMAGE_HEADER_SIZE + i * bpp + 2] = 0xFF.toByte()
            }
        }
    }

    private fun static(index: Int, x: Int, y: Int, pointer: Int, code: Int = 0xFFFF, target: Int = 0xFFFF) =
        ByteArray(40).apply {
            putU32(0, 1); putU32(0x0C, ((index shl 16) or 40).toLong())
            putU16(0x18, x and 0xFFFF); putU16(0x1A, y and 0xFFFF)
            putU16(0x1C, code); putU16(0x1E, target); putU32(0x20, pointer.toLong())
        }

    private fun sprite(index: Int, x: Int, y: Int, pointers: List<Int>) =
        ByteArray(0x24 + pointers.size * 4).apply {
            putU32(0, 3); putU32(4, 2); putU32(0x0C, ((index shl 16) or size).toLong())
            putU16(0x18, x); putU16(0x1A, y); this[0x20] = pointers.size.toByte()
            pointers.forEachIndexed { i, p -> putU32(0x24 + i * 4, p.toLong()) }
        }

    private fun guides(c: Fit3Container) = FaceRecordParser.widgetGuides(c.entryByBasename(style))
    private fun turn(c: Fit3Container, index: Int, angle: Int, from: Int, pristine: Fit3Container): Fit3Container {
        val g = guides(c).single { it.globalIndex == index }
        return StructuralEditor.turnArtwork(c, listOf(style), index, g.type, g.sequenceId, g.x, g.y, angle,
            turns = mapOf(style to from), pristine = pristine).container.also { assertTrue(it.validate().isValid) }
    }
    /** Twice the centre, so a half pixel is an integer. */
    private fun centre(c: Fit3Container, index: Int) = guides(c).single { it.globalIndex == index }
        .let { (2 * it.drawLeft + it.width) to (2 * it.drawTop + it.height) }
    private fun pixels(c: Fit3Container, image: Int = 0): ByteArray {
        val entry = c.entryByBasename(style)
        val record = FaceRecordParser.scanImages(entry)[image]
        return entry.data.copyOfRange(record.samplesOffset, record.pixelOffset + record.pixelDataSize)
    }

    @Test fun aQuarterTurnPermutesThePixelsKeepsTheCentreAndComesBackExactly() {
        val original = container(listOf(static(0, 100, 200, 0)), listOf(image(20, 10)))
        assertEquals(WidgetRotationKind.ARTWORK, guides(original).single().rotationKind)
        val turned = turn(original, 0, 900, 0, original)
        val guide = guides(turned).single()
        assertEquals(10 to 20, guide.width to guide.height)
        assertEquals(centre(original, 0), centre(turned, 0))
        // Clockwise: the original's bottom-left pixel is the turned image's top-left.
        val before = pixels(original); val after = pixels(turned)
        assertArrayEquals(before.copyOfRange(9 * 20 * 3, 9 * 20 * 3 + 3), after.copyOfRange(0, 3))
        assertArrayEquals(original.toByteArray(), turn(turned, 0, 0, 900, original).toByteArray())
    }

    @Test fun anyOtherAngleGrowsToTheTurnedBoundsWithTransparentCornersAndResetsExactly() {
        val original = container(listOf(static(0, 100, 200, 0)), listOf(image(40, 20)))
        val turned = turn(original, 0, 300, 0, original)
        val guide = guides(turned).single()
        assertEquals(artworkBounds(40, 20, 300), guide.width to guide.height)
        assertEquals(centre(original, 0).first.toDouble(), centre(turned, 0).first.toDouble(), 1.0)
        assertEquals(centre(original, 0).second.toDouble(), centre(turned, 0).second.toDouble(), 1.0)
        val samples = pixels(turned)
        assertEquals(0, samples[2].toInt())                              // top-left corner
        assertEquals(0xFF, samples[(guide.height / 2 * guide.width + guide.width / 2) * 3 + 2].toInt() and 0xFF)
        assertEquals(FaceRecordParser.scanImages(original.entryByBasename(style)).size,
            FaceRecordParser.scanImages(turned.entryByBasename(style)).size)
        // Turned again from the original, never from the last result; then reset.
        val again = turn(turned, 0, 600, 300, original)
        assertEquals(artworkBounds(40, 20, 600), guides(again).single().let { it.width to it.height })
        assertArrayEquals(original.toByteArray(), turn(again, 0, 0, 600, original).toByteArray())
        assertThrows(Fit3FormatException::class.java) { turn(turned, 0, 300, 300, original) }
        assertThrows(Fit3FormatException::class.java) { turn(original, 0, 305, 0, original) }
    }

    @Test fun aTurnedPictureResizesOnItsTurnedLadderAndComesBackToTheShippedBytes() {
        val original = container(listOf(static(0, 100, 200, 0)), listOf(image(40, 20)))
        var current = turn(original, 0, 300, 0, original)
        val (anchorWidth, anchorHeight) = artworkBounds(40, 20, 300)
        val rung = widgetResizeLadder(anchorWidth, anchorHeight, WidgetResizeKind.RASTER)
            .last { it.percentOfOriginal == 80 }
        fun resize(c: Fit3Container, w: Int, h: Int): Fit3Container {
            val g = guides(c).single()
            return StructuralEditor.resizeWidget(c, listOf(style), 0, g.type, g.sequenceId, g.x, g.y, w, h,
                pristine = original, turns = mapOf(style to 300)).container
        }
        current = resize(current, rung.width, rung.height)
        assertEquals(rung.width to rung.height, guides(current).single().let { it.width to it.height })
        // Turning at 80% keeps 80%, now of the new turn's bounds.
        val quarter = turn(current, 0, 900, 300, original)
        val expected = widgetResizeLadder(20, 40, WidgetResizeKind.RASTER).single { it.percentOfOriginal == 80 }
        assertEquals(expected.width to expected.height, guides(quarter).single().let { it.width to it.height })
        current = resize(current, anchorWidth, anchorHeight)
        assertArrayEquals(original.toByteArray(), turn(current, 0, 0, 300, original).toByteArray())
    }

    @Test fun resizingEveryStyleGivesEachItsOwnTurnedRungRatherThanTheSelectedSize() {
        val original = container(listOf(static(0, 100, 200, 0)), listOf(image(40, 20)), listOf(style, "style1.bin"))
        // Turns are style-local: only style0 is a quarter turn, so the two boxes differ.
        val g = guides(original).single()
        val turned = StructuralEditor.turnArtwork(original, listOf(style), 0, g.type, g.sequenceId, g.x, g.y, 900,
            pristine = original).container
        val selected = guides(turned).single()
        assertEquals(20 to 40, selected.width to selected.height)
        val rung = widgetResizeLadder(20, 40, WidgetResizeKind.RASTER).single { it.percentOfOriginal == 90 }
        val resized = StructuralEditor.resizeWidget(turned, listOf(style, "style1.bin"), 0, selected.type,
            selected.sequenceId, selected.x, selected.y, rung.width, rung.height, pristine = original,
            turns = mapOf(style to 900)).container
        assertEquals(rung.width to rung.height, guides(resized).single().let { it.width to it.height })
        // 90% of style1's own 40×20, not style0's 18×36 squashed onto it.
        val sibling = FaceRecordParser.widgetGuides(resized.entryByBasename("style1.bin")).single()
        val expected = widgetResizeLadder(40, 20, WidgetResizeKind.RASTER).single { it.percentOfOriginal == 90 }
        assertEquals(expected.width to expected.height, sibling.width to sibling.height)
    }

    @Test fun anEnlargedPictureThatCannotKeepItsRungTurnsOntoTheLargestThatFits() {
        val original = container(listOf(static(0, 100, 200, 0)), listOf(image(64, 64)))
        val g = guides(original).single()
        // 200% of 64 is the 128 px limit; turned 15° the box is 79 px, and 200% of that is not.
        val grown = StructuralEditor.resizeWidget(original, listOf(style), 0, g.type, g.sequenceId, g.x, g.y,
            128, 128, pristine = original).container
        val turned = turn(grown, 0, 150, 0, original)
        val (anchorWidth, anchorHeight) = artworkBounds(64, 64, 150)
        val expected = requireNotNull(dev.fitface.studio.core.model.widgetSizeAtMost(anchorWidth, anchorHeight, 200))
        assertTrue(expected.percentOfOriginal in 101..199)
        assertEquals(expected.width to expected.height, guides(turned).single().let { it.width to it.height })
    }

    @Test fun everyWidgetSharingTheFramesTurnsAboutItsOwnCentre() {
        val frame = IMAGE_HEADER_SIZE + 30 * 30 * 3 + 4
        val original = container(
            listOf(sprite(0, 20, 40, listOf(0, frame)), sprite(1, 120, 40, listOf(0, frame))),
            listOf(image(30, 30), image(30, 30)),
        )
        assertEquals(1, guides(original).first().sharedArtworkWidgets)
        val turned = turn(original, 0, 450, 0, original)
        guides(turned).forEach { assertEquals(artworkBounds(30, 30, 450), it.width to it.height) }
        // 30 px grows by 13 at 45°, an odd number, so the centre holds to half a pixel.
        for (index in 0..1) {
            assertEquals(centre(original, index).first.toDouble(), centre(turned, index).first.toDouble(), 1.0)
            assertEquals(centre(original, index).second.toDouble(), centre(turned, index).second.toDouble(), 1.0)
        }
        assertArrayEquals(original.toByteArray(), turn(turned, 1, 0, 450, original).toByteArray())
    }

    @Test fun aWidgetPlacedFromItsOwnRightEdgeStillKeepsItsCentre() {
        // Code 3 measures from the target's right edge minus the widget's *own* width, so
        // a plain shift of the stored x would move it by the whole growth.
        val original = container(listOf(static(0, -30, 60, 0, code = 3, target = 7)), listOf(image(40, 20)))
        val turned = turn(original, 0, 900, 0, original)
        assertEquals(centre(original, 0), centre(turned, 0))
        assertArrayEquals(original.toByteArray(), turn(turned, 0, 0, 900, original).toByteArray())
    }

    @Test fun anOpaquePictureStaysOpaqueAtQuarterTurnsAndGainsClearCornersOtherwise() {
        val original = container(listOf(static(0, 100, 200, 0)), listOf(image(20, 10, IMAGE_RGB565)))
        guides(original).single().let {
            assertEquals(WidgetRotationKind.ARTWORK, it.rotationKind)
            assertTrue(it.opaqueArtwork)
        }
        fun format(c: Fit3Container) = FaceRecordParser.scanImages(c.entryByBasename(style)).single().format
        // A quarter turn is a permutation: still RGB565, still the same number of bytes.
        val quarter = turn(original, 0, 900, 0, original)
        assertEquals(IMAGE_RGB565, format(quarter))
        assertEquals(original.fileSize, quarter.fileSize)
        assertArrayEquals(pixels(original).copyOfRange(9 * 20 * 2, 9 * 20 * 2 + 2), pixels(quarter).copyOfRange(0, 2))
        assertArrayEquals(original.toByteArray(), turn(quarter, 0, 0, 900, original).toByteArray())
        // Any other angle stores alpha: the picture's own rectangle stays opaque, only the
        // corners it uncovers are clear.
        val tilted = turn(original, 0, 300, 0, original)
        assertEquals(IMAGE_RGB565_ALPHA, format(tilted))
        val guide = guides(tilted).single()
        assertFalse(guide.opaqueArtwork) // The frames have alpha now; the repository keeps the flag.
        val samples = pixels(tilted)
        assertEquals(0, samples[2].toInt())
        assertEquals(0xFF, samples[(guide.height / 2 * guide.width + guide.width / 2) * 3 + 2].toInt() and 0xFF)
        // Turned back, it is the shipped RGB565 frame again, byte for byte.
        assertArrayEquals(original.toByteArray(), turn(tilted, 0, 0, 300, original).toByteArray())
    }

    @Test fun artworkWithoutItsOriginalIsRefused() {
        val alpha = container(listOf(static(0, 100, 200, 0)), listOf(image(20, 10)))
        assertThrows(Fit3FormatException::class.java) {
            StructuralEditor.turnArtwork(alpha, listOf(style), 0, 1, 0, 100, 200, 900)
        }
    }

    /**
     * Every turnable picture in each catalogue face's first style, turned and reset to the
     * shipped bytes. Recorded on the 99-face corpus.
     */
    @Test fun everyCataloguePictureTurnsAndResets() {
        val directory = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot"))).resolve("SM_R390")
        assumeTrue("no corpus at $directory", Files.isDirectory(directory))
        val failures = mutableListOf<String>()
        var turned = 0
        Files.list(directory).use { it.toList() }.sorted().forEach { folder ->
            val path = folder.resolve("${folder.fileName}.bin")
            if (!Files.isRegularFile(path)) return@forEach
            val original = Fit3Container.parse(Files.readAllBytes(path))
            val entry = original.entries.firstOrNull { it.basename == style } ?: return@forEach
            val seen = mutableSetOf<Set<Int>>()
            for (guide in FaceRecordParser.widgetGuides(entry).filter { it.rotationKind == WidgetRotationKind.ARTWORK }) {
                val record = FaceRecordParser.scanWidgets(entry).single { it.globalIndex == guide.globalIndex }
                val pool = FaceRecordParser.rasterPool(record, FaceRecordParser.scanWidgets(entry),
                    FaceRecordParser.imagesByRelativeOffset(entry)).images
                if (!seen.add(pool)) continue
                val label = "${folder.fileName} #${guide.globalIndex}"
                try {
                    val out = turn(turn(original, guide.globalIndex, 150, 0, original), guide.globalIndex, 0, 150, original)
                    if (!out.toByteArray().contentEquals(original.toByteArray())) failures += "$label: did not reset"
                    turned++
                } catch (error: Fit3FormatException) {
                    if (error is Fit3CapacityException) continue
                    failures += "$label: ${error.message}"
                }
            }
        }
        assumeTrue("corpus holds no turnable picture", turned > 0)
        assertEquals("$turned pools turned", emptyList<String>(), failures.take(10))
    }
}
