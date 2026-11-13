package dev.fitface.studio.core.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.model.AddedWidgetKind
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditKind
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.PhoneFont
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.TextFont
import dev.fitface.studio.core.model.TextPicture
import dev.fitface.studio.core.model.TextProblem
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.pictureAddedBytes
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Text drawn as a picture: white on clear, cropped to its ink, refused with a reason when it
 * cannot be drawn, added as an ordinary picture, and able to replace a face's label in one
 * Undo step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextPictureRepositoryTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private lateinit var context: Context
    private lateinit var database: FitFaceDatabase
    private lateinit var repository: WatchFaceRepositoryImpl

    @Before fun setup() {
        assumeTrue(Files.isDirectory(root.resolve("SM_R390")))
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FitFaceDatabase::class.java).allowMainThreadQueries().build()
        repository = WatchFaceRepositoryImpl(context, database.projectDao(),
            AndroidImageSource(context.contentResolver), context.contentResolver, DiagnosticsLog())
    }

    @After fun cleanup() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) {
            File(context.filesDir, "projects").deleteRecursively()
            File(context.cacheDir, "fonts").deleteRecursively()
        }
    }

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

    private suspend fun draw(s: EditorSnapshot, text: TextPicture) =
        repository.prepareText(text, s.projectId, s.selectedVariant.basename)

    private fun PreviewFrame.inkIn(column: Int? = null, row: Int? = null): Boolean =
        (0 until if (column != null) height else width).any { i ->
            argb[(row ?: i) * width + (column ?: i)] ushr 24 != 0
        }

    @Test fun textIsDrawnWhiteOnClearCroppedToItsInkAndAddedAsAPicture() = runBlocking {
        val s = repository.openPackage(face("00112"))
        val prepared = draw(s, TextPicture("Hello", sizePx = 40))
        val picture = requireNotNull(prepared.picture)
        assertNull(prepared.problem)
        val frame = picture.preview
        assertEquals(picture.width to picture.height, frame.width to frame.height)
        assertFalse("a text keeps its clear background", picture.opaque)
        assertTrue("drawn white, so a tint lands exactly",
            frame.argb.filter { it ushr 24 != 0 }.all { it and 0xFFFFFF == 0xFFFFFF })
        assertTrue(frame.argb.any { it == 0xFFFFFFFF.toInt() })
        assertTrue("cropped to its ink on every side", frame.inkIn(column = 0) && frame.inkIn(column = frame.width - 1) &&
            frame.inkIn(row = 0) && frame.inkIn(row = frame.height - 1))
        assertTrue("about as tall as the capitals at 40 px", frame.height in 25..45)

        val larger = requireNotNull(draw(s, TextPicture("Hello", sizePx = 80)).picture)
        assertTrue(larger.width > picture.width * 3 / 2 && larger.height > picture.height * 3 / 2)
        val twoLines = requireNotNull(draw(s, TextPicture("Hello\nHi", sizePx = 40)).picture)
        assertEquals("the widest line sets the width", larger.width / 2.0, twoLines.width.toDouble(), 3.0)
        assertTrue(twoLines.height > picture.height * 3 / 2)

        val again = draw(s, TextPicture("Hello", sizePx = 40))
        val added = repository.addPicture(requireNotNull(again.picture).handle, s.projectId, s.selectedVariant.basename,
            100, 20, 30, tint = 0xFFFF0000.toInt())
        assertEquals("costs what a picture its size costs",
            pictureAddedBytes(picture.width, picture.height, opaque = false), added.containerBytes - s.containerBytes)
        val widget = added.widgets.last()
        assertEquals(AddedWidgetKind.PICTURE, widget.addedKind)
        assertEquals(listOf(20, 30, picture.width, picture.height), listOf(widget.drawLeft, widget.drawTop, widget.width, widget.height))
        assertTrue("resizes like a picture", widget.canResize)
        assertEquals(EditKind.TEXT_PICTURE, added.undoKind)
        val centre = added.composedPreview.argb.let { argb ->
            (widget.drawTop until widget.drawTop + widget.height).flatMap { y ->
                (widget.drawLeft until widget.drawLeft + widget.width).map { x -> argb[y * added.composedPreview.width + x] }
            }
        }
        assertTrue("drawn in its colour", centre.any { it == 0xFFFF0000.toInt() })
    }

    @Test fun textThatCannotBeDrawnSaysWhy() = runBlocking {
        val s = repository.openPackage(face("00112"))
        assertEquals(TextProblem.EMPTY, draw(s, TextPicture("   ")).problem)
        assertEquals(TextProblem.TOO_LONG, draw(s, TextPicture("a".repeat(TextPicture.MAX_LENGTH + 1))).problem)
        assertEquals(TextProblem.TOO_MANY_LINES, draw(s, TextPicture("a\nb\nc\nd\ne")).problem)
        assertEquals("wider than the face", TextProblem.TOO_BIG, draw(s, TextPicture("WWWWWWWW", sizePx = 120)).problem)
        assertEquals("taller than the face", TextProblem.TOO_BIG, draw(s, TextPicture("I\nI\nI", sizePx = 200)).problem)
        val prepared = draw(s, TextPicture("I", sizePx = 200))
        assertNotNull("one tall letter fits", prepared.picture)
        assertTrue(requireNotNull(prepared.picture).height <= s.preview.height - 1)
    }

    @Test fun phoneFontsAndAFontFileDrawDifferentlyAndANonFontIsRefused() = runBlocking {
        val s = repository.openPackage(face("00112"))
        val plain = requireNotNull(draw(s, TextPicture("Hello", TextFont.Phone(PhoneFont.SANS), 40)).picture)
        val mono = requireNotNull(draw(s, TextPicture("Hello", TextFont.Phone(PhoneFont.MONOSPACE), 40)).picture)
        assertTrue("a monospace font draws other pixels",
            plain.width != mono.width || !plain.preview.argb.contentEquals(mono.preview.argb))

        val junk = File(context.cacheDir, "junk.ttf").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        val refused = assertThrows(WatchFaceException::class.java) { runBlocking { repository.readFont(Uri.fromFile(junk).toString()) } }
        assertTrue(refused.userMessage, refused.userMessage.contains("not a font"))
        assertTrue("its scratch copy is gone", context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("font-") })

        // A real font file, from the test's own runtime where it ships one.
        val font = Files.walk(Path.of(System.getProperty("java.home"))).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.lowercase() == "ttf" }.findFirst().orElse(null)
        }
        assumeTrue("no TTF in this Java runtime", font != null)
        val file = File(context.cacheDir, "picked.ttf").apply { writeBytes(Files.readAllBytes(font)) }
        val read = repository.readFont(Uri.fromFile(file).toString())
        assertEquals("picked.ttf", read.name)
        val drawn = requireNotNull(draw(s, TextPicture("Hello", read, 40)).picture)
        assertTrue(drawn.width != plain.width || !drawn.preview.argb.contentEquals(plain.preview.argb))
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("font-") })
    }

    @Test fun aLabelIsOfferedAndReplacedByItsTextInOneUndoStep() = runBlocking {
        var s = repository.openPackage(face("00106"))
        val pristine = repository.prepareDirectInstall().copyBytes()
        val labels = s.widgets.mapNotNull { widget -> widget.label?.let { widget to it } }
        assertEquals(listOf("steps", "bpm", "kcal", "floors"), labels.map { it.second.text })
        assertTrue(labels.all { it.second.sizePx > 0 })
        assertTrue("readings are not labels", s.widgets.filter { it.followsReading && it.sourceLabel != "constant zero" }
            .all { it.label == null })

        val (label, word) = labels.first()
        val prepared = requireNotNull(draw(s, TextPicture(word.text, sizePx = word.sizePx)).picture)
        val group = 7_001L
        s = repository.addPicture(prepared.handle, s.projectId, s.selectedVariant.basename, 100,
            label.drawLeft, label.drawTop, group = group)
        s = repository.removeWidget(s.selectedVariant.basename, label.globalIndex, label.type, label.sequenceId,
            label.x, label.y, requireFinal = false, applyToAllStyles = false, group = group)
        assertTrue("the label is deleted", s.widgets.none { it.label == word })
        assertEquals(labels.size - 1, s.widgets.count { it.label != null })
        assertEquals(AddedWidgetKind.PICTURE, s.widgets.last().addedKind)

        s = repository.undo()
        assertArrayEquals("one Undo takes both back", pristine, repository.prepareDirectInstall().copyBytes())
        assertEquals(labels.map { it.second }, s.widgets.mapNotNull { it.label })
    }
}
