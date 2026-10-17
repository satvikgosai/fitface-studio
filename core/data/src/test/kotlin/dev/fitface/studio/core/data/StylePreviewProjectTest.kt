package dev.fitface.studio.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.FacePackage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pictures the Styles page and the projects list draw, from opening a real package
 * to the paths handed to the UI.
 *
 * Neither screen may parse a container to show a face — the projects list would have to
 * open every project on the way in — so both read the package's own style previews out
 * of app-private storage. This checks the extraction actually happens, that the paths
 * point at real PNGs inside `filesDir` (invariant 5), and that a project's row follows
 * the style it was left on rather than always showing the first one.
 */
@RunWith(RobolectricTestRunner::class)
class StylePreviewProjectTest {
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
        repository = newRepository()
    }

    /** A new repository is a new process: nothing it reads is cached yet. */
    private fun newRepository() = WatchFaceRepositoryImpl(
        context = context,
        projectDao = database.projectDao(),
        imageSource = AndroidImageSource(context.contentResolver),
        contentResolver = context.contentResolver,
        diagnostics = DiagnosticsLog(),
    )

    // Both guards matter: with no corpus the assumption in `setUp` fires before either
    // field is assigned, and an uninitialised `lateinit` read here would turn every
    // skip into an error.
    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) File(context.filesDir, "projects").deleteRecursively()
    }

    @Test
    fun openingAPackagePutsOnePreviewOnDiskForEveryStyle() = runBlocking {
        val snapshot = repository.openPackage(facePackage(styleId = 0))

        assertEquals(snapshot.styleNames.toSet(), snapshot.stylePreviewPaths.keys)
        snapshot.stylePreviewPaths.forEach { (style, path) ->
            val file = File(path)
            assertTrue("$style preview is not a file: $path", file.isFile)
            assertTrue(
                "$style preview is outside app-private storage: $path",
                file.canonicalPath.startsWith(context.filesDir.canonicalPath),
            )
            assertTrue(
                "$style preview is not a PNG",
                file.readBytes().copyOf(PngSignature.size).contentEquals(PngSignature),
            )
        }
    }

    @Test
    fun theProjectRowFollowsTheStyleItWasLeftOn() = runBlocking {
        repository.openPackage(facePackage(styleId = 2))

        val summary = repository.observeProjects().first().single()
        val preview = requireNotNull(summary.previewImagePath) { "no preview for the project" }
        assertEquals("style2.png", File(preview).name)
    }

    @Test fun previewsFollowPristineIdentityAcrossRepeatedDeletionReopenCopyAndReset() = runBlocking {
        val original = repository.openPackage(facePackage(styleId = 2))
        val expected = original.stylePreviewPaths.getValue("style2.bin")
        var s = repository.deleteStyles(setOf("style0.bin"), repository.styleManagement().revision)
        assertEquals("style1.bin", s.activeStyleName)
        assertEquals(expected, s.stylePreviewPaths.getValue("style1.bin"))
        assertEquals(expected, repository.observeProjects().first().single().previewImagePath)
        s = repository.deleteStyles(setOf("style0.bin"), repository.styleManagement().revision)
        assertEquals("style0.bin", s.activeStyleName)
        assertEquals(expected, s.stylePreviewPaths.getValue("style0.bin"))
        s = repository.openProject(s.projectId)
        assertEquals(expected, s.stylePreviewPaths.getValue("style0.bin"))
        val copy = repository.duplicateProject(s.projectId)
        val copied = repository.openProject(copy.id)
        val copiedPath = copied.stylePreviewPaths.getValue("style0.bin")
        assertTrue(copiedPath != expected)
        org.junit.Assert.assertArrayEquals(File(expected).readBytes(), File(copiedPath).readBytes())
        val summary = repository.observeProjects().first().single { it.id == copy.id }
        assertEquals(copiedPath, summary.previewImagePath)
        s = repository.resetEdits()
        assertEquals("style2.bin", s.activeStyleName)
        assertEquals(original.styleNames.size, s.styleNames.size)
    }

    /**
     * The list reads only the style map at the head of a checkpoint. Decoding whole
     * checkpoints held the first list back 7–8 s on a cold start; here the saved container
     * is cut short, so a list that still decoded it would lose the map and show the wrong
     * style, the deleted style's neighbour.
     */
    @Test
    fun aColdListFollowsTheStyleMapWithoutDecodingTheSavedContainer() = runBlocking {
        val original = repository.openPackage(facePackage(styleId = 2))
        val expected = original.stylePreviewPaths.getValue("style2.bin")
        val s = repository.deleteStyles(setOf("style0.bin"), repository.styleManagement().revision)
        assertEquals("style1.bin", s.activeStyleName)
        val checkpoint = File(requireNotNull(database.projectDao().findById(s.projectId)?.editedBinPath))
        val text = checkpoint.readText()
        assertTrue("the lineage is not at the head of the file", text.startsWith("{\"schema\":3,\"lineage\":{"))
        val container = text.indexOf("\"editedContainer\":\"")
        assertEquals("the container is not written last", text.length - 2,
            text.indexOf('"', container + "\"editedContainer\":\"".length))
        checkpoint.writeText(text.substring(0, container + 40))

        repository = newRepository()
        assertEquals(expected, repository.observeProjects().first().single().previewImagePath)
    }

    /**
     * Checkpoints written before the lineage moved to the head keep removed records and
     * import baselines ahead of it, and stay that way until their next edit. The list must
     * still find the map behind them, and opening, which decodes the whole file, must not
     * care about the order at all.
     */
    @Test
    fun aCheckpointInTheEarlierFieldOrderStillPicksItsPreviewAndOpens() = runBlocking {
        val original = repository.openPackage(facePackage(styleId = 2))
        val expected = original.stylePreviewPaths.getValue("style2.bin")
        var s = repository.deleteStyles(setOf("style0.bin"), repository.styleManagement().revision)
        val widget = s.widgets.first { candidate ->
            candidate.width > 0 && candidate.height > 0 &&
                s.widgets.none { it.alignedToGlobalIndex == candidate.globalIndex }
        }
        s = repository.removeWidget(
            styleName = s.selectedVariant.basename,
            globalIndex = widget.globalIndex,
            widgetType = widget.type,
            sequenceId = widget.sequenceId,
            x = widget.x,
            y = widget.y,
            requireFinal = false,
            applyToAllStyles = false,
        )
        assertEquals(1, s.removedWidgets.size)
        val checkpoint = File(requireNotNull(database.projectDao().findById(s.projectId)?.editedBinPath))
        val fields = Json.parseToJsonElement(checkpoint.readText()).jsonObject
        val earlierOrder = listOf("schema", "thumbnailRefreshed", "removed", "importOrigins", "lineage", "editedContainer")
        assertTrue("a field the earlier order does not name: ${fields.keys}", earlierOrder.containsAll(fields.keys))
        checkpoint.writeText(JsonObject(earlierOrder.mapNotNull { key -> fields[key]?.let { key to it } }.toMap()).toString())
        assertTrue("the removed records are not ahead of the lineage", checkpoint.readText().startsWith("{\"schema\":3,\"removed\":["))

        repository = newRepository()
        assertEquals(expected, repository.observeProjects().first().single().previewImagePath)
        val reopened = repository.openProject(s.projectId)
        assertEquals("style1.bin", reopened.activeStyleName)
        assertEquals(1, reopened.removedWidgets.size)
        assertEquals(expected, reopened.stylePreviewPaths.getValue("style1.bin"))
    }

    @Test
    fun reopeningAProjectRewritesNothingAndStillResolves() = runBlocking {
        val first = repository.openPackage(facePackage(styleId = 1))
        val stamps = first.stylePreviewPaths.mapValues { (_, path) -> File(path).lastModified() }

        val reopened = repository.openProject(first.projectId)

        assertEquals(first.stylePreviewPaths, reopened.stylePreviewPaths)
        assertEquals(
            stamps,
            reopened.stylePreviewPaths.mapValues { (_, path) -> File(path).lastModified() },
        )
    }

    private fun facePackage(styleId: Int) = FacePackage(
        sourceKey = FacePackage.sourceKey(
            productId = "test-00046",
            versionCode = 1,
            styleId = styleId,
        ),
        displayName = "Minimalist.apk",
        expectedFaceId = "00046",
        selectedStyleId = styleId,
        versionCode = 1,
        bytes = Files.readAllBytes(packagePath),
    )

    private companion object {
        val PngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
