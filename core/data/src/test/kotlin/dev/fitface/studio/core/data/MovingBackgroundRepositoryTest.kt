package dev.fitface.studio.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.format.CustomFaceTemplate
import dev.fitface.studio.core.format.FaceRecordParser
import dev.fitface.studio.core.format.Fit3CapacityException
import dev.fitface.studio.core.format.Fit3Container
import dev.fitface.studio.core.format.StructuralEditor
import dev.fitface.studio.core.model.AnimationParts
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditKind
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.MovingBackground
import dev.fitface.studio.core.model.PixelBounds
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.animationAddedBytes
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.animationStepThatFits
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
import org.junit.Assert.assertFalse
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

        assertTrue("the page counts the still one it replaces as free", s.stillBackgroundBytes > 0)
        val added = repository.addMovingBackground(source, ImagePlacement(), frameStep = 1)
        assertEquals(animationAddedBytes(3, 4, 256, 402) - s.stillBackgroundBytes, added.containerBytes - s.containerBytes)
        // 00112's style 0 had its still background at index 0, deleted with it, so this goes first.
        assertEquals(MovingBackground(globalIndex = 0, frames = 3, entries = 4, periodMs = 100), added.movingBackground)
        assertEquals(added.movingBackground, added.activeMovingBackground)
        assertFalse("it replaced the still one, which is deleted", "style0.bin" in added.backgroundStyles)
        assertFalse(hasStill("style0.bin"))
        assertEquals(0, added.stillBackgroundBytes)
        assertTrue(added.selectedBackgroundShown)
        assertEquals(EditKind.MOVING_BACKGROUND, added.undoKind)
        // Nothing new to remember: an added widget with no original, as an added background is.
        assertTrue(checkpoint(s.projectId).startsWith("{\"schema\":3,"))

        repository = repository()
        val reopened = repository.openProject(s.projectId)
        assertEquals(added.movingBackground, reopened.movingBackground)
        assertEquals(added.containerBytes, reopened.containerBytes)
    }

    /**
     * Deleted with every frame: the face is left as Remove background leaves it — no
     * background, the still one it replaced deleted too — and Undo brings it back.
     */
    @Test fun removingItTakesEveryFrameAndUndoBringsItBack() = runBlocking {
        val expected = noBackground("00112")
        repository.openPackage(face("00112"))
        val added = repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        val removed = repository.removeMovingBackground()
        assertNull(removed.movingBackground)
        assertFalse("no background is shown, not the one the moving one replaced", removed.selectedBackgroundShown)
        assertArrayEquals(expected, bytes())
        val undone = repository.undo()
        assertEquals(added.movingBackground, undone.movingBackground)
        assertEquals(added.containerBytes, undone.containerBytes)
    }

    /** Remove in the widget list does the same as the Background page's. */
    @Test fun removingItFromTheWidgetListDeletesItToo() = runBlocking {
        val expected = noBackground("00112")
        repository.openPackage(face("00112"))
        val added = repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        val widget = added.widgets.single { it.globalIndex == added.movingBackground!!.globalIndex }
        val removed = repository.removeWidget("style0.bin", widget.globalIndex, widget.type, widget.sequenceId,
            widget.x, widget.y, false, false, null)
        assertNull(removed.movingBackground)
        assertArrayEquals(expected, bytes())
    }

    /**
     * The Background page's one Remove takes a moving background and the still one under it in
     * one step, leaving none; one Undo brings both back, and with nothing left it is refused.
     */
    @Test fun removeTakesTheMovingAndTheStillBackgroundTogether() = runBlocking {
        val expected = noBackground("00112")
        repository.openPackage(face("00112"))
        val added = repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        val removed = repository.removeBackground(thisStyleOnly = true)
        assertNull(removed.movingBackground)
        assertFalse(removed.selectedBackgroundShown)
        assertEquals(EditKind.BACKGROUND, removed.undoKind)
        assertArrayEquals(expected, bytes())
        val nothing = runCatching { repository.removeBackground(thisStyleOnly = true) }.exceptionOrNull()
        assertEquals("There is no background here to remove.", (nothing as WatchFaceException).userMessage)
        assertEquals(added.movingBackground, repository.undo().movingBackground)
    }

    /** A background from another face replaces a moving one too, as if it had never been added. */
    @Test fun aBackgroundFromAnotherFaceReplacesTheMovingOne() = runBlocking {
        val donor = face("00076")
        fun importInto(opened: Long) = runBlocking {
            val handle = repository.inspectWidgetDonor(donor).handle
            val preview = repository.previewBackgroundImport(handle, "style0.bin", opened, "style0.bin", thisStyleOnly = true)
            repository.importBackground(preview.ticket)
        }
        val expected = importInto(repository.openPackage(face("00112")).projectId)

        val opened = repository.openPackage(face("00112"))
        repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        val imported = importInto(opened.projectId)
        assertNull(imported.movingBackground)
        assertTrue(imported.selectedBackgroundShown)
        // Its still one went with the moving one, so the donor's is added rather than written over it.
        assertArrayEquals(expected.composedPreview.argb, imported.composedPreview.argb)
        assertTrue(imported.validationErrors.isEmpty())
        assertNotNull("one undo step", repository.undo().movingBackground)
    }

    /**
     * At NATIVE the 32 × 50 GIF is stored at its own rectangle, centred, and every edit a
     * full-panel one takes treats it the same: it is still the background layer, it survives
     * Undo, Redo and a reopen, Remove leaves what removing a full-panel one leaves, and a
     * full-panel moving or a still background replaces it as either replaces a full-panel one.
     */
    @Test fun aSmallerOneTakesEveryEditAFullPanelOneDoes() = runBlocking {
        val expectedNone = noBackground("00112")
        val opened = repository.openPackage(face("00112"))
        val before = bytes()
        val three = repository.readAnimation(gif("three.gif"))

        val added = repository.addMovingBackground(three, ImagePlacement(fit = ImageFit.NATIVE), 1)
        val bounds = PixelBounds((256 - 32) / 2, (402 - 50) / 2, 32, 50)
        assertEquals(MovingBackground(globalIndex = 0, frames = 3, entries = 4, periodMs = 100, bounds = bounds),
            added.movingBackground)
        assertEquals(animationAddedBytes(3, 4, 32, 50) - opened.stillBackgroundBytes, added.containerBytes - opened.containerBytes)
        assertTrue(added.validationErrors.isEmpty())
        assertEquals(WidgetPlacement.BACKGROUND, added.widgets.single { it.globalIndex == 0 }.placement)
        assertFalse("the still background is deleted", hasStill("style0.bin"))
        val withIt = bytes()

        assertNull(repository.undo().movingBackground)
        assertArrayEquals(before, bytes())
        assertEquals(added.movingBackground, repository.redo().movingBackground)
        assertArrayEquals(withIt, bytes())

        // History lasts while the project is open; what reopens is the face as it was left.
        repository = repository()
        val reopened = repository.openProject(opened.projectId)
        assertEquals(added.movingBackground, reopened.movingBackground)
        assertArrayEquals(withIt, bytes())

        assertNull(repository.removeMovingBackground().movingBackground)
        assertArrayEquals(expectedNone, bytes())
        assertEquals(added.movingBackground, repository.undo().movingBackground)

        val full = repository.addMovingBackground(three, ImagePlacement(), 1)
        assertEquals(MovingBackground(globalIndex = 0, frames = 3, entries = 4, periodMs = 100), full.movingBackground)
        assertEquals(animationAddedBytes(3, 4, 256, 402) - opened.stillBackgroundBytes, full.containerBytes - opened.containerBytes)
        assertEquals(added.movingBackground, repository.undo().movingBackground)

        val still = repository.replaceBackground(png(), ImagePlacement())
        assertNull(still.movingBackground)
        assertTrue(still.validationErrors.isEmpty())
        assertTrue(still.selectedBackgroundShown)
        assertEquals(added.movingBackground, repository.undo().movingBackground)
    }

    /**
     * A face with no still background, filled by a moving one so no other raster fits beside
     * it, still offers a background: one replaces the moving one in the same commit, its room
     * counted free, so the page must not say there is no room for one.
     */
    @Test fun aFaceFullOfAMovingBackgroundStillOffersOneInItsPlace() = runBlocking {
        // The custom face: a clock on black, with room for a few full-panel frames.
        val custom = CustomFaceTemplate.strip(Fit3Container.parse(originalBytes("00006"))).toByteArray()
        val name = "SM-R390_00006_256x402"
        val fresh = repository.openPackage(FacePackage(FacePackage.sourceKey("test-custom", 1, 0), "SM-R390_00006.apk",
            "00006", 0, 1, ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("assets/$name.bin")); zip.write(custom); zip.closeEntry()
                }
            }.toByteArray()))
        assumeTrue("the custom face draws onto black", fresh.backgroundStyles.isEmpty())
        // Every frame of the longest part that fits, as the editor opens it: less than a frame is left.
        val forty = repository.readAnimation(gif("forty.gif"))
        val part = requireNotNull(AnimationParts.longestGifPart(forty, 0, 1,
            (WATCH_CONTAINER_BYTE_CEILING - fresh.containerBytes).toLong(), 256, 402))
        val full = repository.addMovingBackground(requireNotNull(AnimationParts.cut(forty, part)), ImagePlacement(), 1)
        assertTrue("no room for a still background beside it",
            WATCH_CONTAINER_BYTE_CEILING - full.containerBytes < StructuralEditor.addedBackgroundBytes(256, 402))
        assertTrue(full.canAddBackground)
        assertFalse(full.backgroundWouldNotFit)
        assertTrue(full.canPlaceBackground(thisStyleOnly = true))

        // Given one, as the page's add does on a face with none: the moving one goes in the same commit.
        val still = repository.addBackground(png(), ImagePlacement())
        assertNull(still.movingBackground)
        assertTrue(still.validationErrors.isEmpty())
        assertTrue(still.selectedBackgroundShown)
        assertTrue(still.containerBytes <= WATCH_CONTAINER_BYTE_CEILING)
    }

    /** 00112 with the selected style's background removed: what a removed moving one leaves. */
    private suspend fun noBackground(id: String): ByteArray {
        repository.openPackage(face(id))
        repository.removeBackground(thisStyleOnly = true)
        return bytes()
    }

    private suspend fun hasStill(variant: String): Boolean =
        FaceRecordParser.backgroundImage(Fit3Container.parse(bytes()).entryByBasename(variant)) != null

    /**
     * On a style with no still background it goes under every widget and the style still reads
     * as having no background. A still one added there replaces it — its frames are opaque, so
     * nothing under them would show — leaving exactly the face that adding it alone would, and
     * one Undo brings the moving one back.
     */
    @Test fun aStillBackgroundOnABareStyleReplacesTheMovingOne() = runBlocking {
        repository.openPackage(face("00108"))
        val alone = repository.addBackground(png(), ImagePlacement(), thisStyleOnly = true)
        val expected = bytes()

        repository.openPackage(face("00108"))
        val added = repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        assertEquals(0, added.movingBackground!!.globalIndex)
        assertTrue("style0.bin" !in added.backgroundStyles)
        val still = repository.addBackground(png(), ImagePlacement(), thisStyleOnly = true)
        assertNull(still.movingBackground)
        assertTrue("style0.bin" in still.backgroundStyles)
        assertEquals(alone.backgroundStyles, still.backgroundStyles)
        assertArrayEquals(expected, bytes())
        assertEquals(EditKind.BACKGROUND, still.undoKind)

        val undone = repository.undo()
        assertEquals(added.movingBackground, undone.movingBackground)
        assertTrue("style0.bin" !in undone.backgroundStyles)
        assertEquals(added.containerBytes, undone.containerBytes)
    }

    /**
     * The layer drawn over a background being placed leaves the moving one out, as it leaves
     * the still one out: the new background replaces it, so its first frame must not cover it.
     */
    @Test fun aBackgroundBeingPlacedIsNotCoveredByTheMovingOne() = runBlocking {
        val opened = repository.openPackage(face("00112"))
        val added = repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        assertArrayEquals(opened.widgetOverlay.argb, added.widgetOverlay.argb)
        assertTrue("the face itself still shows it", !opened.composedPreview.argb.contentEquals(added.composedPreview.argb))
    }

    /**
     * A whole-face replacement over a moving background removes it too, in one undo step, and
     * gives its style — whose still one went with it — the picture again: it draws what replacing
     * them all on a fresh face draws, and the other styles come out byte for byte the same.
     */
    @Test fun aStillReplacementTakesTheMovingBackgroundsPlace() = runBlocking {
        repository.openPackage(face("00112"))
        val fresh = repository.replaceBackground(png(), ImagePlacement())
        val expected = Fit3Container.parse(bytes())

        repository.openPackage(face("00112"))
        val added = repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        val replaced = repository.replaceBackground(png(), ImagePlacement())
        assertNull(replaced.movingBackground)
        assertEquals(fresh.backgroundStyles.toSet(), replaced.backgroundStyles.toSet())
        assertArrayEquals(fresh.composedPreview.argb, replaced.composedPreview.argb)
        val actual = Fit3Container.parse(bytes())
        expected.entries.filter { it.basename != "style0.bin" }.forEach { entry ->
            assertArrayEquals(entry.basename, entry.data, actual.entryByBasename(entry.basename).data)
        }

        val undone = repository.undo()
        assertEquals(added.movingBackground, undone.movingBackground)
        assertEquals(added.containerBytes, undone.containerBytes)
        assertNull("one step: the next undo is the moving background's own", repository.undo().movingBackground)
        assertArrayEquals(originalBytes("00112"), bytes())
    }

    /**
     * A second moving background takes the first one's place and its room: a face with no room
     * left for another still takes one, it comes out as adding it alone would, and one Undo
     * puts the first one back.
     */
    @Test fun aSecondOneReplacesTheFirstAndHasItsRoom() = runBlocking {
        repository.openPackage(face("00112"))
        repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        val expected = bytes()

        val fresh = repository.openPackage(face("00112"))
        val forty = repository.readAnimation(gif("forty.gif"))
        val step = requireNotNull(animationStepThatFits(forty,
            (WATCH_CONTAINER_BYTE_CEILING - fresh.containerBytes).toLong(), 256, 402))
        val first = repository.addMovingBackground(forty, ImagePlacement(), step)
        val plan = forty.plan(step)
        assertTrue("no room for the same again beside it", first.containerBytes +
            animationAddedBytes(plan.frames.size, plan.table.size, 256, 402) > WATCH_CONTAINER_BYTE_CEILING)
        val again = repository.addMovingBackground(forty, ImagePlacement(), step)
        assertEquals(first.movingBackground, again.movingBackground)
        assertEquals(first.containerBytes, again.containerBytes)

        val second = repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        assertEquals(MovingBackground(globalIndex = 0, frames = 3, entries = 4, periodMs = 100), second.movingBackground)
        assertArrayEquals(expected, bytes())
        assertEquals(EditKind.MOVING_BACKGROUND, second.undoKind)
        assertEquals(first.movingBackground, repository.undo().movingBackground)
    }

    /**
     * Remove background takes one of the rasters the face shipped with, and nothing after it is
     * mistaken for one: a moving picture added and deleted again leaves no frame behind, and
     * the widgets that were positioned against the background stay where they were.
     */
    @Test fun aRemovedBackgroundLeavesNothingStrandedOrMisplaced() = runBlocking {
        val opened = repository.openPackage(face("00112"))
        val bare = repository.removeBackground(thisStyleOnly = true)
        val before = bytes()
        assertEquals(
            opened.widgets.drop(1).map { it.drawLeft to it.drawTop },
            bare.widgets.map { it.drawLeft to it.drawTop },
        )

        val withPicture = repository.addMovingPicture(repository.readAnimation(gif("three.gif")), 1, 32, 50, 20, 20)
        val picture = withPicture.widgets.last()
        repository.removeWidget("style0.bin", picture.globalIndex, picture.type, picture.sequenceId,
            picture.x, picture.y, false, false, null)
        assertArrayEquals("every frame went with it", before, bytes())
        assertTrue(repository.currentSnapshot().validationErrors.isEmpty())
    }

    /**
     * A stock widget deleted first frees its artwork for the frames, and stays deleted through
     * moving backgrounds and a still replacement.
     */
    @Test fun aDeletedStockWidgetFreesRoomAndStaysDeleted() = runBlocking {
        val opened = repository.openPackage(face("00112"))
        val widget = opened.widgets.last { it.canEditPosition }
        val removed = repository.removeWidget("style0.bin", widget.globalIndex, widget.type, widget.sequenceId,
            widget.x, widget.y, false, false, null)
        assertTrue("its artwork went with it", removed.containerBytes < opened.containerBytes)
        repository.addMovingBackground(repository.readAnimation(gif("forty.gif")), ImagePlacement(), 20)
        repository.addMovingBackground(repository.readAnimation(gif("three.gif")), ImagePlacement(), 1)
        val replaced = repository.replaceBackground(png(), ImagePlacement())
        assertNull(replaced.movingBackground)
        assertEquals(opened.widgets.size - 1, replaced.widgets.size)
        assertTrue(replaced.validationErrors.isEmpty())
    }

    @Test fun oneTooLargeOrSomethingElseIsRefused() = runBlocking {
        val fresh = repository.openPackage(face("00112"))
        // Forty full-panel frames are about 4.2 MB, past the watch's ceiling on any face.
        val forty = repository.readAnimation(gif("forty.gif"))
        assertEquals(40, forty.frames.size)
        assertTrue(runCatching { repository.addMovingBackground(forty, ImagePlacement(), 1) }.exceptionOrNull()
            is Fit3CapacityException)
        // The step the page offers is the smallest that fits, and it does.
        val step = requireNotNull(animationStepThatFits(forty,
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
