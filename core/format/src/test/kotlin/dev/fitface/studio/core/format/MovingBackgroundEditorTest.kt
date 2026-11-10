package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.MovingBackground
import dev.fitface.studio.core.model.PixelBounds
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.animationAddedBytes
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

class MovingBackgroundEditorTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val style = "style0.bin"

    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    private fun solid(color: Int) = IntArray(256 * 402) { color }
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    /** Deletes the moving background the way Remove will: record and frames, nothing else. */
    private fun removed(after: Fit3Container, before: Fit3Container, variant: String, index: Int): Fit3Container {
        val record = FaceRecordParser.scanWidgets(after.entryByBasename(variant)).single { it.globalIndex == index }
        return StructuralEditor.deleteWidget(after, listOf(variant), index, record.widgetType, record.sequenceId, record.x, record.y,
            targetIndices = mapOf(variant to index)).container
    }

    @Test
    fun onABareStyleItGoesUnderEverythingAndComesOutExactly() {
        val template = CustomFaceTemplate.strip(face(CustomFaceTemplate.FACE_ID))
        val before = template.entryByBasename(style)
        val result = MovingBackgroundEditor.insert(template, style, listOf(solid(red), solid(green), solid(blue)),
            table = listOf(0, 0, 1, 2), periodMs = 100)
        val after = result.edit.container.entryByBasename(style)

        assertEquals(0, result.globalIndex)
        assertEquals(MovingBackground(globalIndex = 0, frames = 3, entries = 4, periodMs = 100), MovingBackgroundEditor.read(after))
        assertEquals(animationAddedBytes(3, 4, 256, 402), result.edit.container.fileSize - template.fileSize)
        // A full-panel layer to the editor, but never "the" background a replacement would edit.
        val guides = FaceRecordParser.widgetGuides(after)
        assertEquals(WidgetPlacement.BACKGROUND, guides[0].placement)
        assertNull(FaceRecordParser.backgroundImage(after))
        assertEquals(FaceRecordParser.widgetGuides(before).map { it.placement }, guides.drop(1).map { it.placement })
        // The frames are what was given, in table order, and frame 0 is what the preview draws.
        val frames = FaceRecordParser.resourceImages(after, FaceRecordParser.scanWidgets(after)[0])
        assertEquals(listOf(red, red, green, blue), frames.map { FaceRecordParser.decodeImage(after, it).argb.toSet().single() })
        assertTrue(frames.all { it.format == IMAGE_INDEXED8 && it.width == 256 && it.height == 402 })

        assertArrayEquals(template.toByteArray(), removed(result.edit.container, template, style, 0).toByteArray())
    }

    /**
     * Placed smaller than the panel, it is stored at the rectangle it covers, drawn there,
     * tagged in the four bytes the firmware never reads, and still the background: under the
     * widgets, pinned, never a moving picture, and removed byte for byte.
     */
    @Test
    fun placedSmallerItIsStoredAtItsRectangleAndStaysTheBackground() {
        listOf(CustomFaceTemplate.strip(face(CustomFaceTemplate.FACE_ID)), face("00046")).forEach { source ->
            val bounds = PixelBounds(78, 163, 100, 75)
            fun small(color: Int) = IntArray(100 * 75) { color }
            val result = MovingBackgroundEditor.insert(source, style, listOf(small(red), small(green)),
                table = listOf(0, 1, 1), periodMs = 100, bounds = bounds)
            val after = result.edit.container.entryByBasename(style)
            val record = FaceRecordParser.scanWidgets(after)[result.globalIndex]

            assertEquals(MovingBackground(result.globalIndex, frames = 2, entries = 3, periodMs = 100, bounds = bounds),
                MovingBackgroundEditor.read(after))
            assertEquals(animationAddedBytes(2, 3, 100, 75), result.edit.container.fileSize - source.fileSize)
            assertEquals(78, after.data.u16(record.recordOffset + 0x18))
            assertEquals(163, after.data.u16(record.recordOffset + 0x1A))
            assertEquals("BKGD", String(after.data.copyOfRange(record.recordOffset + 0x1C, record.recordOffset + 0x20), Charsets.US_ASCII))
            assertEquals(WidgetPlacement.BACKGROUND, FaceRecordParser.widgetGuides(after)[result.globalIndex].placement)
            assertEquals(0, MovingPicture.countOnTop(after))
            assertTrue(FaceRecordParser.resourceImages(after, record).all { it.width == 100 && it.height == 75 })
            assertArrayEquals(source.toByteArray(),
                removed(result.edit.container, source, style, result.globalIndex).toByteArray())
        }
        val template = CustomFaceTemplate.strip(face(CustomFaceTemplate.FACE_ID))
        assertThrows(Fit3FormatException::class.java) {
            MovingBackgroundEditor.insert(template, style, listOf(IntArray(100 * 75)), listOf(0), 100,
                PixelBounds(200, 0, 100, 75))
        }
        // The whole panel given as bounds is the full-panel record, untagged, exactly as without.
        val full = MovingBackgroundEditor.insert(template, style, listOf(solid(red)), listOf(0), 100,
            PixelBounds(0, 0, 256, 402))
        assertArrayEquals(MovingBackgroundEditor.insert(template, style, listOf(solid(red)), listOf(0), 100)
            .edit.container.toByteArray(), full.edit.container.toByteArray())
    }

    /** Above every still background layer, which keeps being the one a replacement edits. */
    @Test
    fun onAStyleWithABackgroundItGoesAboveItAndTheStillOneStaysTheBackground() {
        listOf("00046", "00076").forEach { id ->
            val source = face(id)
            val entry = source.entryByBasename(style)
            val layers = FaceRecordParser.widgetGuides(entry).filter { it.placement == WidgetPlacement.BACKGROUND }
            val result = MovingBackgroundEditor.insert(source, style, listOf(solid(red), solid(blue)), listOf(0, 1), 250)
            val after = result.edit.container.entryByBasename(style)
            assertEquals(id, layers.maxOf { it.globalIndex } + 1, result.globalIndex)
            assertEquals(id, FaceRecordParser.backgroundImage(entry)!!.recordOffset - FaceRecordParser.scanImages(entry).first().recordOffset,
                FaceRecordParser.backgroundImage(after)!!.recordOffset - FaceRecordParser.scanImages(after).first().recordOffset)
            assertArrayEquals(id, source.toByteArray(), removed(result.edit.container, source, style, result.globalIndex).toByteArray())
        }
    }

    /**
     * Every catalogue face takes one in its first style and gives it back byte for byte: the
     * index shift and the alignment renumbering are checked against every layout there is.
     * A face too close to the ceiling refuses it; that is counted, not failed.
     */
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
                MovingBackgroundEditor.insert(source, variant, listOf(solid(red), solid(green)), listOf(0, 1), 100)
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
        assertTrue("expected the catalogue, added to $added, full $full", added + full >= 99 && added >= 90)
    }

    @Test
    fun theAlwaysOnDisplayTakesOneToo() {
        val source = face("00046")
        val result = MovingBackgroundEditor.insert(source, "aod.bin", listOf(solid(red), solid(blue)), listOf(0, 1), 300)
        assertEquals(300, MovingBackgroundEditor.read(result.edit.container.entryByBasename("aod.bin"))!!.periodMs)
        assertArrayEquals(source.entryByBasename(style).data, result.edit.container.entryByBasename(style).data)
    }

    @Test
    fun aSecondOneAWrongFrameOrTooManyFramesIsRefused() {
        val template = CustomFaceTemplate.strip(face(CustomFaceTemplate.FACE_ID))
        val once = MovingBackgroundEditor.insert(template, style, listOf(solid(red)), listOf(0), 100).edit.container
        assertEquals("This style already has a moving background. Remove it first.",
            assertThrows(Fit3FormatException::class.java) {
                MovingBackgroundEditor.insert(once, style, listOf(solid(blue)), listOf(0), 100)
            }.message)
        assertThrows(IllegalArgumentException::class.java) {
            MovingBackgroundEditor.insert(template, style, listOf(IntArray(10)), listOf(0), 100)
        }
        // A table must name every frame it is given, and only those.
        assertThrows(IllegalArgumentException::class.java) {
            MovingBackgroundEditor.insert(template, style, listOf(solid(red), solid(blue)), listOf(0, 0), 100)
        }
        val tooMany = (WATCH_CONTAINER_BYTE_CEILING - template.fileSize) / animationAddedBytes(1, 1, 256, 402) + 1
        assertThrows(Fit3CapacityException::class.java) {
            MovingBackgroundEditor.insert(template, style, List(tooMany) { solid(red) }, List(tooMany) { it }, 100)
        }
    }
}
