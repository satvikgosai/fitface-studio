package dev.fitface.studio.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.model.AddedWidgetKind
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditKind
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.PictureSizePreset
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.pictureAddedBytes
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures added as widgets, from a picked file to a saved, reopened, exported project: the
 * cost shown is what the face grows by, it lands where it was placed in the colour chosen,
 * the size added is a rung it resizes back to, and schema 5 is written exactly while one
 * exists. The background shares the colour rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PictureWidgetRepositoryTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private lateinit var context: Context
    private lateinit var database: FitFaceDatabase
    private lateinit var repository: WatchFaceRepositoryImpl
    private val red = 0xFFFF0000.toInt()

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
            File(context.cacheDir, "pictures").deleteRecursively()
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

    /** A PNG on disk, [color] all over, with its left quarter fully transparent when [clearQuarter]. */
    private fun png(width: Int, height: Int, color: Int = 0xFF2255CC.toInt(), clearQuarter: Boolean = true): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        if (clearQuarter) for (y in 0 until height) for (x in 0 until width / 4) bitmap.setPixel(x, y, 0)
        val file = File(context.cacheDir, "pictures/p-${System.nanoTime()}.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file).toString()
    }

    private suspend fun bytes() = repository.prepareDirectInstall().copyBytes()

    /** The checkpoint the project row names; undo history keeps others beside it. */
    private suspend fun checkpoint(projectId: Long): String =
        File(requireNotNull(database.projectDao().findById(projectId)?.editedBinPath)).readText()

    private suspend fun add(s: EditorSnapshot, uri: String, preset: PictureSizePreset = PictureSizePreset.MEDIUM,
        x: Int? = null, y: Int? = null, tint: Int? = null): EditorSnapshot {
        val prepared = repository.preparePicture(uri, s.projectId, s.selectedVariant.basename)
        val size = prepared.sizes.single { it.preset == preset }
        return repository.addPicture(prepared.handle, s.projectId, s.selectedVariant.basename, size.percent,
            x ?: (256 - size.width) / 2, y ?: (402 - size.height) / 2, tint)
    }

    @Test fun aPictureArrivesWhereItWasPlacedAndCostsWhatWasShown() = runBlocking {
        val s = repository.openPackage(face("00112"))
        val before = s.containerBytes
        val prepared = repository.preparePicture(png(600, 400), s.projectId, s.selectedVariant.basename)
        assertEquals("fitted to the panel width", 256 to 171, prepared.width to prepared.height)
        assertEquals(256 to 171, prepared.preview.width to prepared.preview.height)
        assertEquals("a transparent quarter needs alpha", false, prepared.opaque)
        val medium = requireNotNull(prepared.defaultSize)
        assertEquals(PictureSizePreset.MEDIUM, medium.preset)

        val added = repository.addPicture(prepared.handle, s.projectId, s.selectedVariant.basename,
            medium.percent, 10, 20)
        assertEquals("shown cost is actual cost",
            pictureAddedBytes(medium.width, medium.height, prepared.opaque), added.containerBytes - before)
        val widget = added.widgets.last()
        assertEquals(AddedWidgetKind.PICTURE, widget.addedKind)
        assertNull(widget.importedFromFaceId)
        assertEquals(medium.width to medium.height, widget.width to widget.height)
        assertEquals(10 to 20, widget.drawLeft to widget.drawTop)
        assertEquals("resizes are measured from the whole picture", 256 to 171, widget.originalWidth to widget.originalHeight)
        assertTrue(widget.canResize)
        assertEquals(EditKind.PICTURE, added.undoKind)
        assertTrue(checkpoint(s.projectId).startsWith("{\"schema\":5,\"lineage\":{"))
    }

    @Test fun aPictureReopensAndResizesFromItsBaseline() = runBlocking {
        var s = repository.openPackage(face("00112"))
        s = add(s, png(512, 256, red, clearQuarter = false), PictureSizePreset.SMALL)
        assertEquals(64 to 32, s.widgets.last().width to s.widgets.last().height)

        repository = repository()
        s = repository.openProject(s.projectId)
        val picture = s.widgets.last()
        assertEquals(AddedWidgetKind.PICTURE, picture.addedKind)
        assertEquals(256 to 128, picture.originalWidth to picture.originalHeight)
        s = repository.resizeWidget(s.selectedVariant.basename, picture.globalIndex, picture.type, picture.sequenceId,
            picture.x, picture.y, 256, 128, false)
        val full = s.widgets.single { it.globalIndex == picture.globalIndex }
        assertEquals(256 to 128, full.width to full.height)
        assertEquals("a picture as wide as the face is still a widget", WidgetPlacement.CANVAS, full.placement)
    }

    /** The colour is part of the picture: drawn over what was there, and kept by a resize. */
    @Test fun aColouredPictureDrawsOnTopInItsColourAndKeepsItWhenResized() = runBlocking {
        var s = repository.openPackage(face("00112"))
        s = add(s, png(300, 300, 0xFFFFFFFF.toInt(), clearQuarter = false), PictureSizePreset.LARGE, tint = red)
        fun centre(snapshot: EditorSnapshot): Int {
            val picture = snapshot.widgets.last()
            val frame = snapshot.composedPreview
            return frame.argb[(picture.drawTop + picture.height / 2) * frame.width + picture.drawLeft + picture.width / 2]
        }
        assertEquals(red, centre(s))
        val picture = s.widgets.last()
        s = repository.resizeWidget(s.selectedVariant.basename, picture.globalIndex, picture.type, picture.sequenceId,
            picture.x, picture.y, picture.originalWidth / 2, picture.originalHeight / 2, false)
        assertEquals("the baseline was coloured, not just the first size", red, centre(s))
    }

    /** One colour rule on both pages: a background picked white and coloured blue is blue. */
    @Test fun aColouredBackgroundIsRecolouredByBrightness() = runBlocking {
        repository.openPackage(face("00112"))
        val white = png(256, 402, 0xFFFFFFFF.toInt(), clearQuarter = false)
        val plain = repository.replaceBackground(white, ImagePlacement(fit = ImageFit.STRETCH))
        val coloured = repository.replaceBackground(white, ImagePlacement(fit = ImageFit.STRETCH, tint = 0xFF0000FF.toInt()))
        val corner = 3 * plain.preview.width + 3
        assertEquals(0xFFFFFFFF.toInt(), plain.composedPreview.argb[corner])
        assertEquals(0xFF0000FF.toInt(), coloured.composedPreview.argb[corner])
    }

    @Test fun deletingTheLastPictureGivesBackTheFaceAndAnOlderSchema() = runBlocking {
        var s = repository.openPackage(face("00112"))
        val pristine = bytes()
        s = add(s, png(120, 120))
        val picture = s.widgets.last()
        s = repository.removeWidget(s.selectedVariant.basename, picture.globalIndex, picture.type, picture.sequenceId,
            picture.x, picture.y, requireFinal = false, applyToAllStyles = false)
        assertArrayEquals(pristine, bytes())
        assertTrue("deleted, not parked under Removed", s.removedWidgets.isEmpty())
        val saved = checkpoint(s.projectId)
        assertTrue(saved.startsWith("{\"schema\":3,"))
        assertTrue("no picture table is left behind", !saved.contains("importOrigins"))
    }

    @Test fun undoAndRedoTakeAPictureAwayAndBack() = runBlocking {
        var s = repository.openPackage(face("00112"))
        val before = bytes()
        s = add(s, png(200, 50))
        val after = bytes()
        s = repository.undo()
        assertArrayEquals(before, bytes())
        assertEquals(EditKind.PICTURE, s.redoKind)
        s = repository.redo()
        assertArrayEquals(after, bytes())
        assertEquals(AddedWidgetKind.PICTURE, s.widgets.last().addedKind)
    }

    @Test fun aDuplicateProjectAndAnArchiveCarryThePicture() = runBlocking {
        var s = repository.openPackage(face("00112"))
        val prepared = repository.preparePicture(png(120, 90), s.projectId, s.selectedVariant.basename)
        assertEquals("small enough to keep whole", PictureSizePreset.ORIGINAL, prepared.defaultSize?.preset)
        s = repository.addPicture(prepared.handle, s.projectId, s.selectedVariant.basename, 100, 60, 150)
        val edited = bytes()

        val copy = repository.duplicateProject(s.projectId)
        assertEquals(AddedWidgetKind.PICTURE, repository.openProject(copy.id).widgets.last().addedKind)
        assertArrayEquals(edited, bytes())

        val transfers = File(context.cacheDir, "transfers").apply { mkdirs() }
        val archive = Uri.fromFile(File(transfers, "picture.zip")).toString()
        repository.exportProject(s.projectId, archive)
        val imported = repository.importProject(archive)
        assertEquals(AddedWidgetKind.PICTURE, repository.openProject(imported.id).widgets.last().addedKind)
        assertArrayEquals(edited, bytes())

        // The same archive claiming schema 4, which predates pictures, is refused: a build
        // that reads 4 would keep the widget and lose what it is.
        val older = File(transfers, "older.zip")
        older.writeBytes(rewritten(File(transfers, "picture.zip").readBytes()) { _, text ->
            text.replace("\"schema\":5", "\"schema\":4")
        })
        try {
            repository.importProject(Uri.fromFile(older).toString())
            fail("a picture under schema 4 was accepted")
        } catch (expected: Exception) {
            // Refused, and no half-made project is left behind.
        }
        assertEquals(3, repository.observeProjects().first().size)
    }

    /** A turned picture is still a widget, however large: never taken for the background. */
    @Test fun aLargePictureTurnedStaysAWidget() = runBlocking {
        // A small face: a 200 × 400 picture turned to 45° needs room 00112 does not have.
        var s = repository.openPackage(face("00006"))
        // Kept whole, so its own size is also the largest: offered once, as Original.
        s = add(s, png(200, 400, red, clearQuarter = false), PictureSizePreset.ORIGINAL)
        val tall = s.widgets.last()
        assertEquals(200 to 400, tall.width to tall.height)
        s = repository.rotateWidget(s.selectedVariant.basename, tall.globalIndex, tall.sequenceId,
            tall.x, tall.y, 450, false)
        val turned = s.widgets.single { it.globalIndex == tall.globalIndex }
        assertEquals(WidgetPlacement.CANVAS, turned.placement)
        assertTrue(turned.canResize)
    }

    private fun rewritten(archive: ByteArray, edit: (String, String) -> String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { out ->
            ZipInputStream(archive.inputStream()).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    val data = input.readBytes()
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(if (entry.name.endsWith(".json")) edit(entry.name, data.decodeToString()).encodeToByteArray() else data)
                    out.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}
