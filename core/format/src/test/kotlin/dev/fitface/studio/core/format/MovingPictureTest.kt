package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.animationAddedBytes
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MovingPictureTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val style = "style0.bin"

    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    /** A [size]-px square of [color] with a clear border two pixels wide, and soft pixels on its edge. */
    private fun dot(size: Int, color: Int) = IntArray(size * size) { index ->
        val x = index % size
        val y = index / size
        when {
            x < 2 || y < 2 || x >= size - 2 || y >= size - 2 -> 0x00000000
            x == 2 -> (0x60 shl 24) or (color and 0xFFFFFF) // under half: clear
            x == 3 -> (0xA0 shl 24) or (color and 0xFFFFFF) // over half: drawn, opaque
            else -> color
        }
    }

    private fun removed(after: Fit3Container, before: Fit3Container, variant: String, index: Int): Fit3Container {
        val record = FaceRecordParser.scanWidgets(after.entryByBasename(variant)).single { it.globalIndex == index }
        return StructuralEditor.deleteWidget(after, variant, index, record.widgetType, record.sequenceId, record.x, record.y,
            shippedImageCount = FaceRecordParser.scanImages(before.entryByBasename(variant)).size).container
    }

    @Test
    fun itGoesOnTopWhereItWasPlacedWithClearPixelsAndComesOutExactly() {
        val source = face("00112")
        val before = source.entryByBasename(style)
        val records = FaceRecordParser.scanWidgets(before)
        val result = MovingPicture.insert(source, style, 24, 24, listOf(dot(24, red), dot(24, blue)),
            table = listOf(0, 0, 1), periodMs = 120, x = -6, y = 300)
        val after = result.edit.container.entryByBasename(style)

        assertEquals(records.size, result.globalIndex)
        assertEquals(animationAddedBytes(2, 3, 24, 24), result.edit.container.fileSize - source.fileSize)
        // Every record that was there is untouched, and the still background is still the one.
        val now = FaceRecordParser.scanWidgets(after)
        records.forEachIndexed { index, old ->
            assertArrayEquals(before.data.copyOfRange(old.recordOffset, old.recordOffset + old.recordSize),
                after.data.copyOfRange(now[index].recordOffset, now[index].recordOffset + now[index].recordSize))
        }
        assertEquals(FaceRecordParser.backgroundImage(before)!!.width, FaceRecordParser.backgroundImage(after)!!.width)
        val guide = FaceRecordParser.widgetGuides(after).last()
        assertEquals(listOf(-6, 300, 24, 24), listOf(guide.drawLeft, guide.drawTop, guide.width, guide.height))
        assertEquals(WidgetPlacement.CANVAS, guide.placement)

        val frames = FaceRecordParser.resourceImages(after, now.last())
        assertTrue(frames.all { it.format == IMAGE_INDEXED8 && it.width == 24 && it.height == 24 })
        val first = FaceRecordParser.decodeImage(after, frames[0]).argb
        assertEquals("the border is clear", 0, first[0] ushr 24)
        assertEquals("under half alpha is clear", 0, first[24 * 10 + 2] ushr 24)
        assertEquals("over half is drawn, opaque", red, first[24 * 10 + 3])
        assertEquals(red, first[24 * 10 + 12])
        assertEquals(blue, FaceRecordParser.decodeImage(after, frames[2]).argb[24 * 10 + 12])
        assertEquals("one raster a frame, the held one named twice", frames[0].recordOffset, frames[1].recordOffset)

        assertArrayEquals(source.toByteArray(), removed(result.edit.container, source, style, result.globalIndex).toByteArray())
    }

    @Test
    fun anOpaqueClipKeepsAllTwoHundredAndFiftySixColours() {
        val template = CustomFaceTemplate.strip(face(CustomFaceTemplate.FACE_ID))
        val gradient = IntArray(64 * 32) { (0xFF shl 24) or (it % 256 shl 16) or (it / 8 % 256) }
        val result = MovingPicture.insert(template, style, 64, 32, listOf(gradient, gradient.reversedArray()), listOf(0, 1), 100, 10, 10)
        val after = result.edit.container.entryByBasename(style)
        assertEquals(FaceRecordParser.scanWidgets(template.entryByBasename(style)).size, result.globalIndex)
        val image = FaceRecordParser.resourceImages(after, FaceRecordParser.scanWidgets(after).last())[0]
        assertTrue(FaceRecordParser.decodeImage(after, image).argb.all { it ushr 24 == 0xFF })
    }

    @Test
    fun itIsRefusedWhereItWouldBeABackgroundOrDoesNotFit() {
        val template = CustomFaceTemplate.strip(face(CustomFaceTemplate.FACE_ID))
        assertEquals("A moving picture must fit on the face without covering all of it.",
            assertThrows(Fit3FormatException::class.java) {
                MovingPicture.insert(template, style, 256, 402, listOf(IntArray(256 * 402)), listOf(0), 100, 0, 0)
            }.message)
        assertThrows(Fit3FormatException::class.java) {
            MovingPicture.insert(template, style, 257, 10, listOf(IntArray(2570)), listOf(0), 100, 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MovingPicture.insert(template, style, 10, 10, listOf(IntArray(10 * 10), IntArray(10 * 10)), listOf(0), 100, 0, 0)
        }
        val each = animationAddedBytes(1, 1, 255, 402)
        val tooMany = (dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING - template.fileSize) / each + 1
        assertThrows(Fit3CapacityException::class.java) {
            MovingPicture.insert(template, style, 255, 402, List(tooMany) { IntArray(255 * 402) { red } },
                List(tooMany) { it }, 100, 0, 0)
        }
        assertNull(MovingBackgroundEditor.read(template.entryByBasename(style)))
    }

    /** Every catalogue face takes one on its first style and gives it back byte for byte. */
    @Test
    fun everyCatalogueFaceTakesOneAndGivesItBackExactly() {
        val faces = root.resolve("SM_R390")
        assumeTrue("corpus is not available at $root", Files.isDirectory(faces))
        var added = 0
        var full = 0
        faces.listDirectoryEntries("SM-R390_*_256x402").forEach { directory ->
            val bin = directory.resolve("${directory.name}.bin")
            if (!Files.isRegularFile(bin)) return@forEach
            val source = Fit3Container.parse(Files.readAllBytes(bin))
            val variant = FaceResources.selectableStyles(source).first().basename
            val result = try {
                MovingPicture.insert(source, variant, 32, 32, listOf(dot(32, red), dot(32, blue)), listOf(0, 1), 100, 112, 185)
            } catch (error: Fit3CapacityException) {
                full++
                return@forEach
            }
            added++
            assertTrue(directory.name, result.edit.container.validate().isValid)
            assertTrue(directory.name, DeliveryChecks.check(result.edit.container, directory.name.substring(8, 13)).canSend)
            assertArrayEquals(directory.name, Files.readAllBytes(bin),
                removed(result.edit.container, source, variant, result.globalIndex).toByteArray())
        }
        assertTrue("expected the catalogue, added to $added, full $full", added + full >= 99 && added >= 95)
    }
}
