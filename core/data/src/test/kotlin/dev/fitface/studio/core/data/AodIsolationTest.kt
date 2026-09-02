package dev.fitface.studio.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.format.Fit3Apk
import dev.fitface.studio.core.format.Fit3Container
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.WatchFaceException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That AOD and the numbered styles cannot leak edits into one another.
 *
 * `moveWidget`'s apply-to-all path used to append `"aod.bin"` to its own target list
 * whenever the container carried one, so a style-wide move silently moved the matching
 * AOD widget too — the one case, before the isolation model existed, where AOD was
 * pulled into an edit meant for the numbered styles. This is the regression test for
 * that leak and for its mirror, and both are asserted on the **payload bytes** of the
 * container that was written to disk rather than on what a snapshot reports: a snapshot
 * is derived, and the guarantee is about what the watch receives.
 */
@RunWith(RobolectricTestRunner::class)
class AodIsolationTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val packagePath: Path get() = root.resolve("SM-R390_00046.apk")

    private lateinit var context: Context
    private lateinit var database: FitFaceDatabase
    private lateinit var repository: WatchFaceRepositoryImpl

    @Before
    fun setUp() {
        assumeTrue("no package at $packagePath", Files.isRegularFile(packagePath))
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FitFaceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WatchFaceRepositoryImpl(
            context = context,
            projectDao = database.projectDao(),
            imageSource = AndroidImageSource(context.contentResolver),
            diagnostics = DiagnosticsLog(),
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) File(context.filesDir, "projects").deleteRecursively()
    }

    /** The regression test for the `moveWidget` leak this class exists to guard. */
    @Test
    fun movingAStyleWidgetAcrossAllStylesLeavesAodBytesUntouched() = runBlocking {
        val opened = repository.openPackage(facePackage())
        assumeTrue("this fixture carries no aod.bin", opened.hasAod)

        val style = repository.currentSnapshot("style0.bin")
        val widget = style.widgets.first { it.canEditPosition && it.width > 0 && it.height > 0 }
        val moved = repository.moveWidget(
            styleName = "style0.bin",
            globalIndex = widget.globalIndex,
            widgetType = widget.type,
            sequenceId = widget.sequenceId,
            x = widget.x + 3,
            y = widget.y,
            applyToAllStyles = true,
        )

        // The edit has to have actually landed, or every assertion below passes for the
        // wrong reason.
        assertTrue("the move changed nothing", moved.isDirty)
        assertNotEquals(
            "the widget did not move",
            widget.x,
            moved.widgets.first { it.globalIndex == widget.globalIndex }.x,
        )
        assertArrayEquals(
            "a style-wide move rewrote aod.bin",
            pristineEntry("aod.bin"),
            committedEntry(opened.projectId, "aod.bin"),
        )
        // Which *styles* an apply-to-all move reaches is best effort — a style that does
        // not carry the widget is skipped — so the property asserted is the one this
        // class is about: the style that was edited changed, and AOD did not.
        assertTrue("style0.bin was not recorded as edited", "style0.bin" in moved.editedVariantNames)
        assertTrue("aod.bin was recorded as edited", "aod.bin" !in moved.editedVariantNames)
    }

    /** The mirror: an AOD edit must never reach a numbered style, apply-all or not. */
    @Test
    fun movingAnAodWidgetLeavesEveryStyleByteIdentical() = runBlocking {
        val opened = repository.openPackage(facePackage())
        val aod = repository.currentSnapshot("aod.bin")
        assumeTrue("this fixture carries no aod.bin", aod.hasAod)
        val widget = aod.widgets.firstOrNull { it.canEditPosition && it.width > 0 && it.height > 0 }
        assumeTrue("this fixture's AOD has no movable widget", widget != null)

        val moved = repository.moveWidget(
            styleName = "aod.bin",
            globalIndex = widget!!.globalIndex,
            widgetType = widget.type,
            sequenceId = widget.sequenceId,
            x = widget.x + 3,
            y = widget.y,
            // Even a stale `true` here must resolve to AOD alone: the isolation is the
            // repository's to enforce, not something the UI is trusted to hide.
            applyToAllStyles = true,
        )

        assertEquals(setOf("aod.bin"), moved.editedVariantNames)
        aod.styleNames.forEach { name ->
            assertArrayEquals(
                "an AOD move rewrote $name",
                pristineEntry(name),
                committedEntry(opened.projectId, name),
            )
        }
    }

    /** Looking at AOD must never change which style installs, or what it samples. */
    @Test
    fun selectingAodLeavesTheActiveInstallStyleUnchanged() = runBlocking {
        val opened = repository.openPackage(facePackage())
        val activeBefore = opened.activeStyleName
        val samplerBefore = repository.prepareDirectInstall().samplerId

        val aod = repository.currentSnapshot("aod.bin")

        assertEquals(activeBefore, aod.activeStyleName)
        assertTrue(aod.isAodSelected)
        assertEquals("aod.bin", aod.selectedVariant.basename)
        // The one that matters: what goes to the watch is chosen by the active style, so
        // it cannot move because the canvas is showing something else.
        assertEquals(samplerBefore, repository.prepareDirectInstall().samplerId)
        // And a null request — every commit takes one — resolves back to what is on the
        // canvas without disturbing the active style either.
        assertEquals(activeBefore, repository.currentSnapshot(null).activeStyleName)
    }

    /**
     * `preview.bin` holds one frame per numbered style and none for AOD, and the frame is
     * written from whatever the canvas composes — so refreshing it while AOD is selected
     * would paint the always-on render into a style's face-picker frame. The UI hides the
     * button; this is the refusal underneath it.
     */
    @Test
    fun theFacePickerThumbnailCannotBeRefreshedFromTheAodCanvas() = runBlocking {
        val opened = repository.openPackage(facePackage())
        assumeTrue("this fixture carries no aod.bin", opened.hasAod)
        val aod = repository.currentSnapshot("aod.bin")
        assertTrue("the button would still be offered", !aod.canRefreshThumbnail)

        assertThrows(WatchFaceException::class.java) {
            runBlocking { repository.refreshThumbnail() }
        }

        // Refused before anything was written: no container was committed, and the
        // session is still holding the package as it came.
        assertTrue(
            "a container was committed by a refused refresh",
            !File(context.filesDir, "projects/${opened.projectId}/edited.bin").isFile,
        )
        val after = repository.currentSnapshot("aod.bin")
        assertTrue("the refused refresh dirtied the project", !after.isDirty)
        assertTrue("the refused refresh marked the thumbnail current", !after.thumbnailRefreshed)
    }

    private val pristine by lazy {
        Fit3Container.parse(Fit3Apk.parse(Files.readAllBytes(packagePath)).binary)
    }

    /** The payload of [basename] in the package as downloaded. */
    private fun pristineEntry(basename: String): ByteArray =
        pristine.entryByBasename(basename).data

    /** The payload of [basename] in the container this project last committed to disk. */
    private fun committedEntry(projectId: Long, basename: String): ByteArray {
        val edited = File(context.filesDir, "projects/$projectId/edited.bin")
        assertTrue("no committed container at $edited", edited.isFile)
        return Fit3Container.parse(edited.readBytes()).entryByBasename(basename).data
    }

    private fun facePackage() = FacePackage(
        sourceKey = FacePackage.sourceKey(
            productId = "test-00046",
            versionCode = 1,
            styleId = 0,
        ),
        displayName = "SM-R390_00046.apk",
        expectedFaceId = "00046",
        selectedStyleId = 0,
        versionCode = 1,
        bytes = Files.readAllBytes(packagePath),
    )
}
