package dev.fitface.studio.core.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.format.ClockDigits
import dev.fitface.studio.core.format.FaceRecordParser
import dev.fitface.studio.core.format.Fit3Container
import dev.fitface.studio.core.format.IMAGE_RGB565
import dev.fitface.studio.core.model.ClockFontName
import dev.fitface.studio.core.model.ClockFontRequest
import dev.fitface.studio.core.model.ClockPart
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditKind
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.PhoneFont
import dev.fitface.studio.core.model.TextFont
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.WidgetResizeKind
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.widgetResizeLadder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The clock drawn again in a font: the same length, kept across a reopen and an archive, and
 * kept by every later resize, with Undo and Reset font bringing back the face's own digits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClockFontRepositoryTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private lateinit var context: Context
    private lateinit var database: FitFaceDatabase
    private lateinit var repository: WatchFaceRepositoryImpl

    @Before fun setup() {
        assumeTrue(Files.isDirectory(root.resolve("SM_R390")))
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FitFaceDatabase::class.java).allowMainThreadQueries().build()
        repository = repository()
    }

    @After fun cleanup() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) {
            File(context.filesDir, "projects").deleteRecursively()
            File(context.cacheDir, "transfers").deleteRecursively()
        }
    }

    private fun repository() = WatchFaceRepositoryImpl(context, database.projectDao(),
        AndroidImageSource(context.contentResolver), context.contentResolver, DiagnosticsLog())

    private fun face(id: String): FacePackage {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("no corpus face $id", Files.isRegularFile(path))
        return FacePackage(FacePackage.sourceKey("test-$id", 1, 0), "SM-R390_$id.apk", id, 0, 1,
            ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("assets/$name.bin")); zip.write(Files.readAllBytes(path)); zip.closeEntry()
                }
            }.toByteArray())
    }

    private suspend fun bytes() = repository.prepareDirectInstall().copyBytes()

    private suspend fun apply(s: EditorSnapshot, request: ClockFontRequest): EditorSnapshot {
        val preview = repository.previewClockFont(request, s.projectId, s.selectedVariant.basename)
        assertEquals(s.preview.width to s.preview.height, preview.preview.width to preview.preview.height)
        return repository.applyClockFont(preview.ticket)
    }

    /** Every ARGB of [s]'s face inside the clock's widgets. */
    private fun clockPixels(s: EditorSnapshot): List<Int> = s.widgets.filter { it.clockPart == ClockPart.TIME }.flatMap { widget ->
        (widget.drawTop until widget.drawTop + widget.height).flatMap { y ->
            (widget.drawLeft until widget.drawLeft + widget.width).map { x -> s.composedPreview.argb[y * s.composedPreview.width + x] }
        }
    }

    @Test fun theClockIsDrawnInAPhoneFontKeptByAReopenAResizeAndUndoneInOneStep() = runBlocking {
        var s = repository.openPackage(face("00106"))
        val pristine = bytes()
        val digits = s.widgets.filter { it.clockPart == ClockPart.TIME }
        assertEquals("hour and minute, tens and units, and the colon", listOf(1, 3, 3, 3, 3), digits.map { it.type }.sorted())
        assertTrue(digits.all { it.clockFont == null })
        val before = clockPixels(s)

        s = apply(s, ClockFontRequest(TextFont.Phone(PhoneFont.SERIF), setOf(ClockPart.TIME)))
        val drawn = bytes()
        assertEquals("no byte is added", pristine.size, drawn.size)
        assertFalse(drawn.contentEquals(pristine))
        assertEquals(EditKind.CLOCK_FONT, s.undoKind)
        assertTrue(s.widgets.filter { it.clockPart == ClockPart.TIME }.all { it.clockFont == ClockFontName(PhoneFont.SERIF) })
        assertTrue("the clock looks different", before != clockPixels(s))
        assertEquals("only the selected style", listOf("style0.bin"), s.editedVariantNames.toList())

        // Each frame is drawn in the digits' own colour, over their own black box: every pixel
        // is the box, that colour, or an edge between the two.
        val original = Fit3Container.parse(pristine).entryByBasename("style0.bin")
        val edited = Fit3Container.parse(drawn).entryByBasename("style0.bin")
        val set = ClockDigits.sets(edited).first { it.part == ClockPart.TIME && ClockDigits.COLON !in it.values.values }
        val images = FaceRecordParser.scanImages(original)
        assertEquals(IMAGE_RGB565, images[set.values.keys.first()].format)
        val colour = requireNotNull(ClockDigits.look(set.values.keys.map { FaceRecordParser.decodeImage(original, images[it]) })).colour
        val pixels = set.values.keys.flatMap { FaceRecordParser.decodeImage(edited, FaceRecordParser.scanImages(edited)[it]).argb.toList() }
        assertEquals(0xFF000000.toInt(), pixels.groupingBy { it }.eachCount().maxBy { it.value }.key)
        val ink = pixels.filter { it != 0xFF000000.toInt() }.groupingBy { it }.eachCount().maxBy { it.value }.key
        for (shift in listOf(16, 8, 0)) assertTrue(abs((ink ushr shift and 0xFF) - (colour ushr shift and 0xFF)) <= 8)
        pixels.forEach { pixel ->
            for (shift in listOf(16, 8, 0)) assertTrue((pixel ushr shift and 0xFF) <= (colour ushr shift and 0xFF) + 8)
        }

        // A resize draws from the new digits, so coming back to full size is the drawing again.
        val digit = s.widgets.first { it.clockPart == ClockPart.TIME }
        val rung = widgetResizeLadder(digit.originalWidth, digit.originalHeight, WidgetResizeKind.RASTER)
            .single { it.percentOfOriginal == 80 }
        s = repository.resizeWidget("style0.bin", digit.globalIndex, digit.type, digit.sequenceId, digit.x, digit.y,
            rung.width, rung.height, applyToAllStyles = false)
        val small = s.widgets.first { it.globalIndex == digit.globalIndex }
        s = repository.resizeWidget("style0.bin", small.globalIndex, small.type, small.sequenceId, small.x, small.y,
            digit.originalWidth, digit.originalHeight, applyToAllStyles = false)
        assertArrayEquals(drawn, bytes())

        // Kept by a reopen, in a checkpoint an older build refuses.
        repository = repository()
        s = repository.openProject(s.projectId)
        assertTrue(s.widgets.filter { it.clockPart == ClockPart.TIME }.all { it.clockFont == ClockFontName(PhoneFont.SERIF) })
        assertArrayEquals(drawn, bytes())

        // Reset font: the face's own digits, byte for byte.
        s = repository.resetClockFont("style0.bin", ClockPart.TIME, applyToAllStyles = false)
        assertArrayEquals(pristine, bytes())
        assertTrue(s.widgets.filter { it.clockPart == ClockPart.TIME }.all { it.clockFont == null })
        assertEquals(EditKind.CLOCK_FONT, s.undoKind)
        assertThrows(WatchFaceException::class.java) {
            runBlocking { repository.resetClockFont("style0.bin", ClockPart.TIME, applyToAllStyles = false) }
        }
        // Undo brings the font back, and one more Undo, from a fresh apply, takes it away.
        repository.undo()
        assertArrayEquals(drawn, bytes())
        s = repository.resetClockFont("style0.bin", ClockPart.TIME, applyToAllStyles = false)
        s = apply(s, ClockFontRequest(TextFont.Phone(PhoneFont.MONOSPACE), setOf(ClockPart.TIME)))
        repository.undo()
        assertArrayEquals(pristine, bytes())
    }

    @Test fun everyStyleTakesOneColourAndAnOpaqueClockKeepsItsBox() = runBlocking {
        // 00008's digits are opaque frames on a black box.
        var s = repository.openPackage(face("00008"))
        val pristine = bytes()
        val original = Fit3Container.parse(pristine)
        val set = ClockDigits.sets(original.entryByBasename("style0.bin")).first { it.part == ClockPart.TIME }
        assertEquals(IMAGE_RGB565, FaceRecordParser.scanImages(original.entryByBasename("style0.bin"))[set.values.keys.first()].format)

        s = apply(s, ClockFontRequest(TextFont.Phone(PhoneFont.SANS_BOLD), setOf(ClockPart.TIME),
            tint = 0xFFFF0000.toInt(), applyToAllStyles = true))
        val drawn = bytes()
        assertEquals(pristine.size, drawn.size)
        val styles = s.styleNames
        assertEquals(styles.toSet(), s.editedVariantNames)
        val edited = Fit3Container.parse(drawn)
        styles.forEach { style ->
            val entry = edited.entryByBasename(style)
            val before = original.entryByBasename(style)
            val images = FaceRecordParser.scanImages(entry)
            val sets = ClockDigits.sets(entry).filter { it.part == ClockPart.TIME }
            assertTrue("$style has its colon too", sets.any { ClockDigits.COLON in it.values.values })
            sets.forEach { clock ->
                clock.values.keys.forEach { index ->
                    val frame = FaceRecordParser.decodeImage(entry, images[index])
                    val shipped = FaceRecordParser.decodeImage(before, FaceRecordParser.scanImages(before)[index])
                    assertEquals("$style keeps its box, or stays clear", shipped.argb[0], frame.argb[0])
                    assertTrue("$style is drawn in red", frame.argb.any { it == 0xFFFF0000.toInt() })
                }
            }
            sets.filter { ClockDigits.COLON !in it.values.values }.flatMap { it.values.keys }.forEach { index ->
                assertTrue(FaceRecordParser.decodeImage(entry, images[index]).argb.all { it ushr 24 == 0xFF })
            }
        }
        assertEquals("the always-on display keeps its own", original.entryByBasename("aod.bin").data.toList(),
            edited.entryByBasename("aod.bin").data.toList())
    }

    @Test fun anArchiveCarriesTheFontAndAFontFileDrawsTheClock() = runBlocking {
        val font = Files.walk(Path.of(System.getProperty("java.home"))).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.lowercase() == "ttf" }.findFirst().orElse(null)
        }
        assumeTrue("no TTF in this Java runtime", font != null)
        val file = File(context.cacheDir, "clock.ttf").apply { writeBytes(Files.readAllBytes(font)) }
        val read = repository.readFont(Uri.fromFile(file).toString())

        var s = repository.openPackage(face("00106"))
        s = apply(s, ClockFontRequest(read, setOf(ClockPart.TIME)))
        assertTrue(s.widgets.filter { it.clockPart == ClockPart.TIME }.all { it.clockFont == ClockFontName(file = "clock.ttf") })
        val drawn = bytes()
        file.delete()

        val transfers = File(context.cacheDir, "transfers").apply { mkdirs() }
        val destination = Uri.fromFile(File(transfers, "clock.zip")).toString()
        repository.exportProject(s.projectId, destination)
        val imported = repository.importProject(destination)
        s = repository.openProject(imported.id)
        assertArrayEquals(drawn, bytes())
        assertTrue(s.widgets.filter { it.clockPart == ClockPart.TIME }.all { it.clockFont == ClockFontName(file = "clock.ttf") })
        // The font file is gone, and Reset still works: nothing but coverage was kept.
        s = repository.resetClockFont(s.selectedVariant.basename, ClockPart.TIME, applyToAllStyles = false)
        assertTrue(s.widgets.all { it.clockFont == null })
    }

    @Test fun aFaceWithoutClockPicturesSaysSo() = runBlocking {
        // 00046's clock is three hands.
        val s = repository.openPackage(face("00046"))
        assertTrue(s.widgets.none { it.clockPart != null })
        val refused = assertThrows(WatchFaceException::class.java) {
            runBlocking {
                repository.previewClockFont(ClockFontRequest(TextFont.Phone(PhoneFont.SERIF), ClockPart.entries.toSet()),
                    s.projectId, s.selectedVariant.basename)
            }
        }
        assertTrue(refused.userMessage, refused.userMessage.contains("font can redraw"))
        assertNull(s.widgets.firstOrNull { it.clockFont != null })
    }
}
