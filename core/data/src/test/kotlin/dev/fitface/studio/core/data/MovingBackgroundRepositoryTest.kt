package dev.fitface.studio.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.format.Fit3CapacityException
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditKind
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.MovingBackground
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.movingBackgroundAddedBytes
import dev.fitface.studio.core.model.movingBackgroundStepThatFits
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A GIF from a picked file to a moving background that saves, reopens, undoes and goes: the
 * cost shown is what the face grows by, the still background stays the one a replacement
 * edits, removal takes every frame with it, and the checkpoint needs no new schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MovingBackgroundRepositoryTest {
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
            File(context.cacheDir, "gifs").deleteRecursively()
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

    private fun originalBytes(id: String): ByteArray =
        Files.readAllBytes(root.resolve("SM_R390/SM-R390_${id}_256x402/SM-R390_${id}_256x402.bin"))

    /** A test GIF copied to a file, as a picked document would be. */
    private fun gif(name: String): String = file(name, requireNotNull(javaClass.getResourceAsStream("/gif/$name")).readBytes())

    private fun file(name: String, bytes: ByteArray): String {
        val file = File(context.cacheDir, "gifs/$name").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        return Uri.fromFile(file).toString()
    }

    private suspend fun bytes() = repository.prepareDirectInstall().copyBytes()

    private suspend fun checkpoint(projectId: Long): String =
        File(requireNotNull(database.projectDao().findById(projectId)?.editedBinPath)).readText()

    /** Three frames shown 100, 200 and 100 ms: four 100 ms steps, the middle frame twice. */
    @Test fun aGifBecomesAMovingBackgroundAboveTheStillOneAndCostsWhatWasShown() = runBlocking {
        val s = repository.openPackage(face("00112"))
        val source = repository.readAnimation(gif("three.gif"))
        assertEquals(32 to 50, source.width to source.height)
        assertEquals(listOf(100, 200, 100), source.delaysMs)

        val added = repository.addMovingBackground(source, ImagePlacement(), frameStep = 1)
        assertEquals(movingBackgroundAddedBytes(3, 4, 256, 402), added.containerBytes - s.containerBytes)
        // 00112's style 0 has its still background at index 0, so this goes directly above it.
        assertEquals(MovingBackground(globalIndex = 1, frames = 3, entries = 4, periodMs = 100), added.movingBackground)
        assertEquals(added.movingBackground, added.activeMovingBackground)
        assertTrue("the still background is still the one a replacement edits", "style0.bin" in added.backgroundStyles)
        assertEquals(EditKind.MOVING_BACKGROUND, added.undoKind)
        // Nothing new to remember: an added widget with no original, as an added background is.
        assertTrue(checkpoint(s.projectId).startsWith("{\"schema\":3,"))

        repository = repository()
        val reopened = repository.openProject(s.projectId)
        assertEquals(added.movingBackground, reopened.movingBackground)
        assertEquals(added.containerBytes, reopened.containerBytes)
    }

    /** Deleted, not parked under Removed: the face goes back to its own bytes, and Undo brings it back. */
    @Test fun removingItTakesEveryFrameAndUndoBringsItBack() = runBlocking {
        repository.openPackage(face("00112"))
        val added = repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        val removed = repository.removeMovingBackground()
        assertNull(removed.movingBackground)
        assertTrue(removed.removedWidgets.isEmpty())
        assertArrayEquals(originalBytes("00112"), bytes())
        val undone = repository.undo()
        assertEquals(added.movingBackground, undone.movingBackground)
        assertEquals(added.containerBytes, undone.containerBytes)
    }

    /** Remove in the widget list does the same as the Background page's. */
    @Test fun removingItFromTheWidgetListDeletesItToo() = runBlocking {
        repository.openPackage(face("00112"))
        val added = repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        val widget = added.widgets.single { it.globalIndex == added.movingBackground!!.globalIndex }
        val removed = repository.removeWidget("style0.bin", widget.globalIndex, widget.type, widget.sequenceId,
            widget.x, widget.y, false, false, null)
        assertNull(removed.movingBackground)
        assertTrue(removed.removedWidgets.isEmpty())
        assertArrayEquals(originalBytes("00112"), bytes())
    }

    /**
     * On a style with no still background it goes under every widget, the style still reads as
     * having no background, and adding one puts the still picture underneath the moving one.
     */
    @Test fun aBareStyleStillTakesAStillBackgroundUnderneath() = runBlocking {
        repository.openPackage(face("00108"))
        val added = repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        assertEquals(0, added.movingBackground!!.globalIndex)
        assertTrue("style0.bin" !in added.backgroundStyles)
        val still = repository.addBackground(png(), ImagePlacement(), thisStyleOnly = true)
        assertTrue("style0.bin" in still.backgroundStyles)
        assertEquals(1, still.movingBackground!!.globalIndex)
        assertEquals(added.movingBackground!!.copy(globalIndex = 1), still.movingBackground)
    }

    @Test fun aSecondOneOneTooLargeOrSomethingElseIsRefused() = runBlocking {
        repository.openPackage(face("00112"))
        repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        val second = runCatching {
            repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        }.exceptionOrNull()
        assertEquals("This style already has a moving background. Remove it first.", (second as WatchFaceException).userMessage)

        val fresh = repository.openPackage(face("00112"))
        // Forty full-panel frames are about 4.2 MB, past the watch's ceiling on any face.
        val forty = repository.readAnimation(gif("forty.gif"))
        assertEquals(40, forty.frames.size)
        assertTrue(runCatching { repository.addMovingBackground(forty, ImagePlacement(), 1) }.exceptionOrNull()
            is Fit3CapacityException)
        // The step the page offers is the smallest that fits, and it does.
        val step = requireNotNull(movingBackgroundStepThatFits(forty.delaysMs,
            (WATCH_CONTAINER_BYTE_CEILING - fresh.containerBytes).toLong(), 256, 402))
        assertTrue("00112 has room for a few frames, not forty", step > 1)
        assertNotNull(repository.addMovingBackground(forty, ImagePlacement(), step).movingBackground)

        val notAGif = runCatching { repository.readAnimation(file("not.gif", "hello".toByteArray())) }.exceptionOrNull()
        assertEquals("This file is not a GIF.", (notAGif as WatchFaceException).userMessage)
    }

    private fun png(): String {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2255CC.toInt()) }
        val file = File(context.cacheDir, "gifs/still.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file).toString()
    }
}
