package dev.fitface.studio.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.format.Fit3Container
import dev.fitface.studio.core.format.MovingPicture
import dev.fitface.studio.core.model.AddedWidgetKind
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditKind
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.VideoClip
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.animationAddedBytes
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/**
 * A GIF or a video clip to a moving picture on top of the face that plays on the canvas,
 * saves, reopens, duplicates and goes — and the video reader's frames: ten a second, upright,
 * at the size that covers the panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MovingPictureRepositoryTest {
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
        ShadowMediaMetadataRetriever.reset()
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) {
            File(context.filesDir, "projects").deleteRecursively()
            File(context.cacheDir, "moving").deleteRecursively()
        }
    }

    private fun repository() = WatchFaceRepositoryImpl(context, database.projectDao(),
        AndroidImageSource(context.contentResolver), context.contentResolver, DiagnosticsLog())

    private fun face(id: String, bin: ByteArray? = null): FacePackage {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("no corpus face $id", Files.isRegularFile(path))
        return FacePackage(FacePackage.sourceKey("test-$id", 1, 0), "SM-R390_$id.apk", id, 0, 1,
            ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("assets/$name.bin")); zip.write(bin ?: Files.readAllBytes(path)); zip.closeEntry()
                }
            }.toByteArray())
    }

    private fun originalBytes(id: String): ByteArray =
        Files.readAllBytes(root.resolve("SM_R390/SM-R390_${id}_256x402/SM-R390_${id}_256x402.bin"))

    private fun file(name: String, bytes: ByteArray): String {
        val file = File(context.cacheDir, "moving/$name").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        return Uri.fromFile(file).toString()
    }

    private fun gif(name: String): String = file(name, requireNotNull(javaClass.getResourceAsStream("/gif/$name")).readBytes())

    private suspend fun bytes() = repository.prepareDirectInstall().copyBytes()

    private suspend fun checkpoint(projectId: Long): String =
        File(requireNotNull(database.projectDao().findById(projectId)?.editedBinPath)).readText()

    private suspend fun remove(widget: WidgetGuide) = repository.removeWidget("style0.bin", widget.globalIndex,
        widget.type, widget.sequenceId, widget.x, widget.y, requireFinal = false, applyToAllStyles = false)

    /** Three frames shown 100, 200 and 100 ms, put on top of 00112 at 40, 60. */
    @Test fun aGifBecomesAMovingPictureOnTopThatPlaysAndCostsWhatWasShown() = runBlocking {
        val opened = repository.openPackage(face("00112"))
        val source = repository.readAnimation(gif("three.gif"))
        val added = repository.addMovingPicture(source, frameStep = 1, width = 32, height = 50, x = 40, y = 60)

        assertEquals(animationAddedBytes(3, 4, 32, 50), added.containerBytes - opened.containerBytes)
        val widget = added.widgets.last()
        assertEquals(opened.widgets.size, widget.globalIndex)
        assertEquals(AddedWidgetKind.MOVING_PICTURE, widget.addedKind)
        assertEquals(listOf(40, 60, 32, 50), listOf(widget.drawLeft, widget.drawTop, widget.width, widget.height))
        assertEquals("not resizable once added", false, widget.canResize)
        assertEquals(1, added.activeMovingPictures)
        assertNull("not a moving background", added.movingBackground)
        assertEquals(opened.backgroundStyles, added.backgroundStyles)
        // The canvas gets every frame in table order, the held one named twice.
        val playing = added.widgetAnimations.single { it.globalIndex == widget.globalIndex }
        assertEquals(listOf(0, 1, 1, 2), playing.table)
        assertEquals(100, playing.periodMs)
        assertEquals(3, playing.frames.size)
        assertEquals(listOf(32, 50), listOf(playing.frames[0].width, playing.frames[0].height))
        assertEquals(EditKind.MOVING_PICTURE, added.undoKind)
        // Nothing new to remember: an added widget with no original.
        assertTrue(checkpoint(opened.projectId).startsWith("{\"schema\":3,"))

        repository = repository()
        val reopened = repository.openProject(opened.projectId)
        assertEquals(AddedWidgetKind.MOVING_PICTURE, reopened.widgets.last().addedKind)
        assertEquals(1, reopened.activeMovingPictures)
        assertEquals(added.containerBytes, reopened.containerBytes)
    }

    /** Deleted with its frames, except the frames a duplicate of it still draws; Undo brings each back. */
    @Test fun removingItDeletesItsFramesUnlessADuplicateStillDrawsThem() = runBlocking {
        repository.openPackage(face("00112"))
        val added = repository.addMovingPicture(repository.readAnimation(gif("three.gif")), 1, 32, 50, 40, 60, tint = 0xFF00FF00.toInt())
        val original = added.widgets.last()
        val twice = repository.duplicateWidget("style0.bin", original.globalIndex, original.type, original.sequenceId,
            original.x, original.y, applyToAllStyles = false)
        assertEquals(added.containerBytes + 0x28 + 4 * 4, twice.containerBytes)
        assertEquals(2, twice.activeMovingPictures)

        val one = remove(twice.widgets.single { it.globalIndex == original.globalIndex })
        assertTrue("the duplicate keeps drawing them", one.removedWidgets.isEmpty())
        assertEquals(1, one.activeMovingPictures)
        assertEquals(3, one.widgetAnimations.single().frames.size)
        val none = remove(one.widgets.last())
        assertEquals(0, none.activeMovingPictures)
        assertTrue(none.removedWidgets.isEmpty())
        assertArrayEquals(originalBytes("00112"), bytes())

        assertEquals(1, repository.undo().activeMovingPictures)
        assertEquals(2, repository.undo().activeMovingPictures)
    }

    /**
     * A face that arrives with a moving picture — a probe, say — plays it and says so on
     * Install like one added here, but it is part of that face: removing it keeps it restorable.
     */
    @Test fun aMovingPictureTheFaceArrivedWithPlaysAndIsCountedButStaysTheFaces() = runBlocking {
        val stock = Fit3Container.parse(originalBytes("00112"))
        val dot = IntArray(24 * 24) { if (it % 24 in 6..17 && it / 24 in 6..17) 0xFFFF6F00.toInt() else 0 }
        val shipped = MovingPicture.insert(stock, "style0.bin", 24, 24, listOf(dot, dot.reversedArray()), listOf(0, 1), 80, 30, 150)
        val opened = repository.openPackage(face("00112", shipped.edit.container.toByteArray()))
        assertEquals(1, opened.activeMovingPictures)
        assertEquals(listOf(0, 1), opened.widgetAnimations.single().table)
        val widget = opened.widgets.single { it.globalIndex == shipped.globalIndex }
        assertNull("the face's own, not added here", widget.addedKind)
        val removed = remove(widget)
        assertEquals("parked under Removed, frames and all", 1, removed.removedWidgets.size)
        assertEquals(0, removed.activeMovingPictures)
    }

    private fun bitmap(width: Int, height: Int, left: Int, right: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = left })
                drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = right })
            }
        }

    private fun video(name: String, durationMs: Int, width: Int, height: Int, rotation: Int): Pair<String, DataSource> {
        val uri = file(name, ByteArray(32))
        val source = DataSource.toDataSource(context, Uri.parse(uri))
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes")
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, "$durationMs")
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "$width")
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "$height")
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "$rotation")
        return uri to source
    }

    /** A 1.25 s landscape clip: thirteen frames, 100 ms each, read just large enough to cover the panel. */
    @Test fun aVideoIsReadTenFramesASecondCoveringThePanel() = runBlocking {
        val opened = repository.openPackage(face("00112"))
        val (uri, source) = video("landscape.mp4", 1250, 1280, 720, 0)
        repeat(13) { ShadowMediaMetadataRetriever.addScaledFrame(source, it * 100_000L, 715, 402,
            bitmap(715, 402, 0xFFFF0000.toInt(), 0xFF0000FF.toInt())) }
        val read = repository.readAnimation(uri, VideoClip.opening(1250))
        assertEquals(715 to 402, read.width to read.height)
        assertEquals(13, read.frames.size)
        assertEquals(List(13) { 100 }, read.delaysMs)
        assertEquals(VideoClip(1250, 0, 1250), read.clip)
        assertEquals(0xFFFF0000.toInt(), read.frames[0].argb[200 * 715 + 100])

        // As a moving picture it costs what a GIF of the same frames would: every 2nd frame
        // kept is seven frames, each shown for two 100 ms entries but the last.
        val added = repository.addMovingPicture(read, frameStep = 2, width = 128, height = 72, x = 64, y = 160)
        assertEquals(animationAddedBytes(7, 13, 128, 72), added.containerBytes - opened.containerBytes)
    }

    /** A phone held upright stores its video sideways with a rotation; the frames come out upright. */
    @Test fun aRotatedVideoIsTurnedUpright() = runBlocking {
        repository.openPackage(face("00112"))
        val (uri, source) = video("rotated.mp4", 300, 1280, 720, 90)
        // Upright it is 720 × 1280, which covers the panel at 256 × 455; a frame handed back
        // still sideways (left red, right blue) is turned a quarter clockwise: red on top.
        repeat(3) { ShadowMediaMetadataRetriever.addScaledFrame(source, it * 100_000L, 256, 455,
            bitmap(455, 256, 0xFFFF0000.toInt(), 0xFF0000FF.toInt())) }
        val read = repository.readAnimation(uri, VideoClip.opening(300))
        assertEquals(256 to 455, read.width to read.height)
        assertEquals(0xFFFF0000.toInt(), read.frames[0].argb[50 * 256 + 128])
        assertEquals(0xFF0000FF.toInt(), read.frames[0].argb[400 * 256 + 128])
    }

    @Test fun aFileWithNoVideoOrTooShortIsRefusedInWords() = runBlocking {
        repository.openPackage(face("00112"))
        val silent = file("audio.m4a", ByteArray(32))
        val noVideo = runCatching { repository.readAnimation(silent, VideoClip.opening(1000)) }.exceptionOrNull()
        assertEquals("This file has no video in it.", (noVideo as WatchFaceException).userMessage)
        val (short, _) = video("short.mp4", 150, 640, 360, 0)
        val tooShort = runCatching { repository.readAnimation(short, VideoClip.opening(1000)) }.exceptionOrNull()
        assertEquals("This video is too short to move. Choose a longer one.", (tooShort as WatchFaceException).userMessage)
    }
}
