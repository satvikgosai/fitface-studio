package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.AddedWidgetKind
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.widgetSizeAt
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A picture added as a widget: one appended Static and its raster.
 *
 * What matters is that it is an import in every way the rest of the editor can see — the
 * append leaves every existing byte where it was, deleting it gives back the face it was,
 * and its baseline is a one-record entry its resizes are drawn from — and that the size it
 * arrives at is a rung those resizes reproduce byte for byte.
 */
class PictureWidgetTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val style = "style0.bin"

    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    /** A soft-edged disc: opaque in the middle, fading to fully transparent corners. */
    private fun disc(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val dx = (index % width - width / 2.0) / (width / 2.0)
        val dy = (index / width - height / 2.0) / (height / 2.0)
        val alpha = ((1.0 - kotlin.math.sqrt(dx * dx + dy * dy)) * 4 * 255).toInt().coerceIn(0, 255)
        (alpha shl 24) or 0x3366CC
    }

    private fun lastRecord(container: Fit3Container) =
        FaceRecordParser.scanWidgets(container.entryByBasename(style)).last()

    private fun rasterOf(container: Fit3Container, record: WidgetRecord): ByteArray {
        val entry = container.entryByBasename(style)
        val image = FaceRecordParser.imagePointerFields(record, FaceRecordParser.imagesByRelativeOffset(entry)).single().image
        return entry.data.copyOfRange(image.recordOffset, image.pixelOffset + image.dataSize)
    }

    @Test
    fun aPictureIsOneAppendedStaticAndItsRasterAndNothingElseMoves() {
        val original = face("00112")
        val before = FaceRecordParser.scanWidgets(original.entryByBasename(style))
        val result = PictureWidget.insert(original, style, 100, 60, disc(100, 60), 100)
        val edited = result.edit.container

        val records = FaceRecordParser.scanWidgets(edited.entryByBasename(style))
        assertEquals(before.size + 1, records.size)
        assertEquals(before.size, result.globalIndex)
        val added = records.last()
        assertEquals(WIDGET_STATIC, added.widgetType)
        assertEquals(0, added.liveAlignment?.code)
        assertEquals(WidgetImporter.ROOT_TARGET, added.liveAlignment?.targetGlobalIndex)
        assertEquals("no resource may be added beside a picture", original.entries.size, edited.entries.size)
        assertTrue(result.addedResources.isEmpty())

        val guide = FaceRecordParser.widgetGuides(edited.entryByBasename(style)).last()
        assertEquals((256 - 100) / 2, guide.drawLeft)
        assertEquals((402 - 60) / 2, guide.drawTop)
        assertEquals(100 to 60, guide.width to guide.height)
        assertEquals(WidgetPlacement.CANVAS, guide.placement)
        assertEquals("shown cost is actual cost",
            PictureWidget.addedBytes(100, 60, opaque = false), edited.fileSize - original.fileSize)
        assertEquals(edited.fileSize - original.fileSize, result.edit.sizeDelta)

        val baseline = FaceRecordParser.scanWidgets(result.baseline).single()
        assertEquals(0, baseline.globalIndex)
        assertArrayEquals("the baseline draws the same pixels at 100%",
            rasterOf(edited, added), result.baseline.data.copyOfRange(
                StyleHeader.parse(result.baseline).storedImageOffset, result.baseline.data.size))
    }

    @Test
    fun opaquePicturesAreStoredWithoutAlphaAndTransparentPixelsStoreBlack() {
        val opaque = PictureWidget.imageRecord(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()))
        assertEquals(IMAGE_RGB565, opaque.u16(4))
        assertEquals(IMAGE_HEADER_SIZE + 4 + OPAQUE_TRAILER_BYTES, opaque.size)
        assertEquals(0xF800, opaque.u16(IMAGE_HEADER_SIZE))
        assertEquals(0x07E0, opaque.u16(IMAGE_HEADER_SIZE + 2))

        val translucent = PictureWidget.imageRecord(2, 1, intArrayOf(0x00FFFFFF, 0x80FF0000.toInt()))
        assertEquals(IMAGE_RGB565_ALPHA, translucent.u16(4))
        assertEquals(IMAGE_HEADER_SIZE + 6 + OPAQUE_TRAILER_BYTES, translucent.size)
        assertEquals("a transparent pixel stores black", 0, translucent.u16(IMAGE_HEADER_SIZE))
        assertEquals(0, translucent[IMAGE_HEADER_SIZE + 2].toInt())
        assertEquals(0xF800, translucent.u16(IMAGE_HEADER_SIZE + 3))
        assertEquals(0x80, translucent[IMAGE_HEADER_SIZE + 5].toInt() and 0xFF)
    }

    /** The size a picture arrives at is a rung its resizes land on, sampled the same way. */
    @Test
    fun aSmallerRungIsTheBytesALaterResizeToItWouldWrite() {
        val original = face("00112")
        val pixels = disc(200, 120)
        val atHalf = PictureWidget.insert(original, style, 200, 120, pixels, 50)
        val whole = PictureWidget.insert(original, style, 200, 120, pixels, 100)
        val rung = requireNotNull(widgetSizeAt(200, 120, 50))
        val record = lastRecord(whole.edit.container)
        val resized = StructuralEditor.resizeWidget(whole.edit.container, listOf(style), record.globalIndex,
            record.widgetType, record.sequenceId, record.x, record.y, rung.width, rung.height,
            pristine = original,
            pristineWidgets = mapOf(style to WidgetPristine(whole.baseline, mapOf(record.globalIndex to 0))),
            targetIndices = mapOf(style to record.globalIndex)).container
        assertArrayEquals(rasterOf(resized, lastRecord(resized)), rasterOf(atHalf.edit.container, lastRecord(atHalf.edit.container)))
        assertArrayEquals("the baseline is the whole picture, whatever size was added",
            whole.baseline.data, atHalf.baseline.data)
    }

    @Test
    fun deletingAPictureGivesBackTheFaceItWas() {
        val original = face("00112")
        val edited = PictureWidget.insert(original, style, 80, 80, disc(80, 80), 100).edit.container
        val record = lastRecord(edited)
        val deleted = StructuralEditor.deleteWidget(edited, listOf(style), record.globalIndex, record.widgetType,
            record.sequenceId, record.x, record.y, targetIndices = mapOf(style to record.globalIndex)).container
        assertArrayEquals(original.toByteArray(), deleted.toByteArray())
    }

    /** Covering the whole panel both ways would make it the style's background layer. */
    @Test
    fun aPictureCoveringTheWholePanelOrOffItsLadderIsRefused() {
        val original = face("00112")
        assertThrows(Fit3FormatException::class.java) {
            PictureWidget.insert(original, style, 256, 402, IntArray(256 * 402) { -1 }, 100)
        }
        val tall = PictureWidget.insert(original, style, 256, 401, IntArray(256 * 401) { -1 }, 100)
        assertEquals(WidgetPlacement.CANVAS, FaceRecordParser.widgetGuides(tall.edit.container.entryByBasename(style)).last().placement)
        assertEquals("the style's background is the one it had",
            FaceRecordParser.backgroundImage(original.entryByBasename(style))?.index,
            FaceRecordParser.backgroundImage(tall.edit.container.entryByBasename(style))?.index)
        assertThrows(Fit3FormatException::class.java) {
            PictureWidget.insert(original, style, 100, 60, disc(100, 60), 300)
        }
    }

    /** `00022` is 76,640 bytes short of the 4 MiB the watch accepts. */
    @Test
    fun aPicturePastTheWatchCeilingIsRefused() {
        val near = face("00022")
        assertThrows(Fit3CapacityException::class.java) {
            PictureWidget.insert(near, style, 256, 200, IntArray(256 * 200) { -1 }, 100)
        }
        assertNotNull(PictureWidget.insert(near, style, 64, 64, IntArray(64 * 64) { -1 }, 100))
    }

    @Test
    fun aPictureOriginValidatesAndRefusesADonorFaceOrAnotherRecord() {
        val original = face("00112")
        val result = PictureWidget.insert(original, style, 90, 50, disc(90, 50), 100)
        val edited = result.edit.container
        fun origins(origin: WidgetImportOrigin) =
            WidgetImportOrigins(WidgetImportOrigins.digest(original.toByteArray()), listOf(origin))
        val picture = WidgetImportOrigin("p1", "", style,
            Base64.getEncoder().encodeToString(result.baseline.data), listOf(result.globalIndex), AddedWidgetKind.PICTURE)
        origins(picture).validate(original, edited)
        assertTrue(origins(picture).hasPictures)
        assertThrows(IllegalArgumentException::class.java) { origins(picture.copy(faceId = "00112")).validate(original, edited) }
        assertThrows(IllegalArgumentException::class.java) { origins(picture.copy(kind = AddedWidgetKind.DONOR)).validate(original, edited) }
        // A moving picture is recognised from its record and never saved here.
        assertThrows(IllegalArgumentException::class.java) {
            origins(picture.copy(kind = AddedWidgetKind.MOVING_PICTURE)).validate(original, edited)
        }

        // A Value imported from another face, claimed as a picture: wrong record, and a font.
        val value = WidgetImporter.importWidget(original, style, face("00028"), style, 13)
        val claimed = WidgetImportOrigin("p2", "", style,
            Base64.getEncoder().encodeToString(value.baseline.data), listOf(value.globalIndex), AddedWidgetKind.PICTURE)
        assertThrows(IllegalArgumentException::class.java) { origins(claimed).validate(original, value.edit.container) }
    }

    @Test
    fun aDonorOriginIsWrittenAsBeforeAndAPictureNamesItsKind() {
        val json = Json { ignoreUnknownKeys = true }
        val donor = WidgetImportOrigin("d", "00112", style, "AA==", listOf(3))
        assertFalse(json.encodeToString(donor).contains("kind"))
        val picture = donor.copy(faceId = "", kind = AddedWidgetKind.PICTURE)
        assertTrue(json.encodeToString(picture).contains("\"kind\":\"PICTURE\""))
        assertEquals(picture, json.decodeFromString<WidgetImportOrigin>(json.encodeToString(picture)))
    }

    /** Deleting the last picture takes the table with it, so the project needs schema 5 no longer. */
    @Test
    fun aTableOfDeletedPicturesIsPrunedAwayButADonorIsKept() {
        val picture = WidgetImportOrigin("p", "", style, "AAAA", emptyList(), AddedWidgetKind.PICTURE)
        val donor = WidgetImportOrigin("d", "00112", style, "AAAAAAAA", emptyList())
        assertNull(WidgetImportOrigins("x", listOf(picture)).pruned())
        assertEquals(listOf(donor), WidgetImportOrigins("x", listOf(picture, donor)).pruned()?.widgets)
        val live = picture.copy(indices = listOf(4))
        assertEquals(listOf(live), WidgetImportOrigins("x", listOf(live, donor)).pruned()?.widgets)
    }

    /** Placed where the canvas put it, partly off the face if it was left there. */
    @Test
    fun aPictureIsAddedWhereItWasPlaced() {
        val original = face("00112")
        val placed = PictureWidget.insert(original, style, 80, 60, disc(80, 60), 100, x = -20, y = 300)
        val guide = FaceRecordParser.widgetGuides(placed.edit.container.entryByBasename(style)).last()
        assertEquals(-20 to 300, guide.drawLeft to guide.drawTop)
        assertArrayEquals("the baseline is the same wherever it goes",
            PictureWidget.insert(original, style, 80, 60, disc(80, 60), 100).baseline.data, placed.baseline.data)
    }
}
