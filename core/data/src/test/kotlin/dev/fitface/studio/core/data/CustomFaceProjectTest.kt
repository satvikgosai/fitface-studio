package dev.fitface.studio.core.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.data.db.ProjectEntity
import dev.fitface.studio.core.format.CustomFaceTemplate
import dev.fitface.studio.core.format.FaceEditor
import dev.fitface.studio.core.format.Fit3Apk
import dev.fitface.studio.core.format.Fit3Container
import dev.fitface.studio.core.format.pack
import dev.fitface.studio.core.model.CUSTOM_FACE_NUMBERS_FULL
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.isOutdated
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A custom face, from the store's real Info_4 package to an ordinary project.
 *
 * The template is built on the phone rather than shipped, so everything a project needs has
 * to come out of that build right: the row's provenance, the pictures, and a package every
 * other path accepts without knowing where it came from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomFaceProjectTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private fun storePackage(name: String): Path = root.resolve("packages").resolve(name)
    private val info4 = "com.samsung.fit3watchface.sm_r390_0006@40001.apk"

    private lateinit var context: Context
    private lateinit var database: FitFaceDatabase
    private lateinit var repository: WatchFaceRepositoryImpl

    @Before
    fun setUp() {
        assumeTrue("no Info_4 package in the corpus", Files.isRegularFile(storePackage(info4)))
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FitFaceDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = repository()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) {
            File(context.filesDir, "projects").deleteRecursively()
            File(context.cacheDir, "custom-face-tests").deleteRecursively()
        }
    }

    private fun repository() = WatchFaceRepositoryImpl(context, database.projectDao(),
        AndroidImageSource(context.contentResolver), context.contentResolver, DiagnosticsLog())

    private fun download(file: String = info4, faceId: String = "00006") = FacePackage(
        sourceKey = FacePackage.sourceKey("test-$faceId", 40001, 0),
        displayName = "SM-R390_$faceId.apk",
        expectedFaceId = faceId,
        selectedStyleId = 0,
        versionCode = 40001,
        bytes = Files.readAllBytes(storePackage(file)),
    )

    @Test
    fun aCustomFaceIsTheClockOnAnEmptyPanelInAProjectOfItsOwn() = runBlocking {
        val snapshot = repository.openTemplate(download(), "Custom face")
        // A face number of its own, so it installs beside Info_4 rather than over it.
        assertEquals("00201", snapshot.faceId)
        assertEquals("Custom face", snapshot.projectName)
        // The template is the project's starting point, not an edit of Info_4: reset lands
        // here, and nothing is under Removed.
        assertFalse(snapshot.isDirty)
        assertTrue(snapshot.removedWidgets.isEmpty())
        assertEquals(5, snapshot.widgets.size)
        // One style: the four were copies of each other once their readings were gone.
        assertEquals(listOf("style0.bin"), snapshot.styleNames)
        assertTrue(snapshot.hasAod)
        val row = requireNotNull(database.projectDao().findById(snapshot.projectId))
        assertTrue(row.sourceUri, row.sourceUri.startsWith("fit3-template://00006/v3/"))
        assertNull(row.productId)
        assertNull(row.packageVersionCode)
        assertNull(row.styleId)
        // The package's metadata is Info_4's, carried over; the Install card names the number.
        assertEquals("Info_4", row.faceName)
        // The container and its preview are filed under the number, which is where the parser
        // reads a project's face ID from.
        val apk = Fit3Apk.parse(File(row.localApkPath!!).readBytes())
        assertEquals("00201", apk.faceId)
        assertEquals("assets/SM-R390_00201_256x402.bin", apk.binaryMember)
        assertEquals(setOf(0), apk.stylePreviews.keys)
        // Never badged "update available", whatever the store does to Info_4.
        val summary = repository.observeProjects().first().single()
        assertFalse(summary.isOutdated(Long.MAX_VALUE))
    }

    /**
     * The store's pictures show heart rate and steps; the template has neither. Both kinds of
     * picture are redrawn from what the face now draws: the style previews the app shows,
     * and the frames the watch's own face picker shows.
     */
    @Test
    fun itsPicturesAreOfWhatItNowIs() = runBlocking {
        val snapshot = repository.openTemplate(download(), "Custom face")
        // Only the style it has: a preview of a style that is gone is a picture of nothing.
        assertEquals(setOf("style0.bin"), snapshot.stylePreviewPaths.keys)
        snapshot.styleNames.forEach { style ->
            val shown = repository.currentSnapshot(style)
            val path = requireNotNull(shown.stylePreviewPaths[style]) { "$style has no preview" }
            val bitmap = requireNotNull(BitmapFactory.decodeFile(path))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertArrayEquals("$style preview", shown.composedPreview.argb, pixels)
        }
        val saved = File(requireNotNull(database.projectDao().findById(snapshot.projectId)).localApkPath!!)
        val container = Fit3Container.parse(Fit3Apk.parse(saved.readBytes()).binary)
        snapshot.styleNames.indices.forEach { index ->
            val style = repository.currentSnapshot(snapshot.styleNames[index])
            assertArrayEquals(
                "picker frame $index",
                FaceEditor.renderedThumbnail(container, index, style.composedPreview)!!.argb,
                FaceEditor.previewThumbnail(container, index)!!.argb,
            )
        }
    }

    /** Each takes the next number, and they are named against each other, not their numbers. */
    @Test
    fun aSecondCustomFaceIsASecondProjectWithTheNextNumber() = runBlocking {
        val first = repository.openTemplate(download(), "Custom face")
        val second = repository.openTemplate(download(), "Custom face")
        assertNotEquals(first.projectId, second.projectId)
        assertEquals("00201", first.faceId)
        assertEquals("00202", second.faceId)
        assertEquals("Custom face 2", second.projectName)
        assertEquals(2, repository.observeProjects().first().size)
    }

    /** Deleting a custom face frees its number, and the next one takes the lowest free. */
    @Test
    fun aDeletedCustomFaceFreesItsNumber() = runBlocking {
        val first = repository.openTemplate(download(), "Custom face")
        repository.openTemplate(download(), "Custom face")
        repository.deleteProject(first.projectId)
        assertEquals("00201", repository.openTemplate(download(), "Custom face").faceId)
        assertEquals("00203", repository.openTemplate(download(), "Custom face").faceId)
    }

    /** A number the catalogue lists is never given out, in case the store grows into the range. */
    @Test
    fun aNumberInTheCatalogueIsSkipped() = runBlocking {
        val snapshot = repository.openTemplate(download(), "Custom face", setOf("00106", "00201", "00202"))
        assertEquals("00203", snapshot.faceId)
    }

    /** With all fifty in use the start is refused, and nothing is written. */
    @Test
    fun withEveryNumberInUseNothingIsSaved() = runBlocking {
        val dao = database.projectDao()
        (201..249).forEach { number ->
            dao.insert(ProjectEntity(displayName = "x", sourceUri = "fit3-template://00006/v3/$number",
                faceId = number.toString().padStart(5, '0'), importedAtEpochMillis = 0))
        }
        assertEquals("00250", repository.openTemplate(download(), "Custom face").faceId)
        val error = runCatching { repository.openTemplate(download(), "Custom face") }.exceptionOrNull()
        assertTrue("got $error", error is WatchFaceException)
        assertEquals(CUSTOM_FACE_NUMBERS_FULL, (error as WatchFaceException).userMessage)
        // No row and no files for the refused one: only 250's directory exists.
        assertEquals(50, dao.faceIds().size)
        assertEquals(1, File(context.filesDir, "projects").listFiles().orEmpty().size)
    }

    /**
     * A custom face made before numbers keeps Info_4's, still installs over it, and is counted
     * among the custom faces a new one is named against.
     */
    @Test
    fun anOlderCustomFaceKeepsInfo4sNumber() = runBlocking {
        val store = Files.readAllBytes(storePackage(info4))
        val stripped = CustomFaceTemplate.strip(Fit3Container.parse(Fit3Apk.parse(store).binary))
        val apk = File(context.filesDir, "projects/older/source.apk").apply { parentFile!!.mkdirs() }
        apk.writeBytes(CustomFaceTemplate.pack(store, stripped.toByteArray(), emptyMap()))
        val older = database.projectDao().insert(ProjectEntity(displayName = "SM-R390_00006.apk",
            sourceUri = "fit3-template://00006/v2/older", faceId = "00006", importedAtEpochMillis = 0,
            localApkPath = apk.path, selectedStyle = "style0.bin", projectName = "Custom face"))
        assertEquals("00006", repository.openProject(older).faceId)
        assertEquals(6, repository.prepareDirectInstall().faceId)
        val numbered = repository.openTemplate(download(), "Custom face")
        assertEquals("00201", numbered.faceId)
        assertEquals("Custom face 2", numbered.projectName)
    }

    /** Once it exists it is an ordinary project: it reopens, copies, and reaches the watch. */
    @Test
    fun itReopensDuplicatesAndPreparesAnInstall() = runBlocking {
        val snapshot = repository.openTemplate(download(), "Custom face")
        repository = repository()
        val reopened = repository.openProject(snapshot.projectId)
        assertEquals(snapshot.containerBytes, reopened.containerBytes)
        val payload = repository.prepareDirectInstall()
        assertEquals("SM-R390_00201_256x402.bin", payload.fileName)
        assertEquals(201, payload.faceId)
        // A copy keeps the number, as every copy keeps its face's, and is named among custom faces.
        val copy = repository.duplicateProject(snapshot.projectId)
        val reopenedCopy = repository.openProject(copy.id)
        assertEquals(5, reopenedCopy.widgets.size)
        assertEquals("00201", reopenedCopy.faceId)
        assertEquals("Custom face 2", reopenedCopy.projectName)
    }

    /** Reset goes back to the renumbered template, not to Info_4. */
    @Test
    fun resetKeepsTheNumber() = runBlocking {
        val template = repository.openTemplate(download(), "Custom face")
        val widget = template.widgets.last()
        val edited = repository.removeWidget("style0.bin", widget.globalIndex, widget.type, widget.sequenceId,
            widget.x, widget.y, false, false)
        assertTrue(edited.isDirty)
        val reset = repository.resetEdits()
        assertEquals("00201", reset.faceId)
        assertEquals(template.containerBytes, reset.containerBytes)
        assertEquals(201, repository.prepareDirectInstall().faceId)
    }

    /** An exported custom face comes back a custom face, provenance and all. */
    @Test
    fun anExportedCustomFaceImportsAsOne() = runBlocking {
        val snapshot = repository.openTemplate(download(), "Custom face")
        val file = File(context.cacheDir, "custom-face-tests/out.zip").also { it.parentFile!!.mkdirs() }
        repository.exportProject(snapshot.projectId, Uri.fromFile(file).toString())
        val imported = repository.importProject(Uri.fromFile(file).toString())
        val row = requireNotNull(database.projectDao().findById(imported.id))
        assertTrue(row.sourceUri.startsWith("fit3-template://00006/"))
        assertEquals("00201", row.faceId)
        assertNull(row.packageVersionCode)
        assertEquals(5, repository.openProject(imported.id).widgets.size)
    }

    /**
     * The report: every clock widget removed, and not one of them could be put back —
     * "style0.bin: style needs widgets and images", because the append refused an empty
     * widget table.
     */
    @Test
    fun aFaceEmptiedByHandRestoresEveryWidget() = runBlocking {
        val template = repository.openTemplate(download(), "Custom face")
        var s = template
        while (s.widgets.isNotEmpty()) {
            val widget = s.widgets.last()
            s = repository.removeWidget("style0.bin", widget.globalIndex, widget.type, widget.sequenceId,
                widget.x, widget.y, false, false)
        }
        assertEquals(5, s.removedWidgets.size)
        // Restored in the order they were removed from the bottom of the table.
        s.removedWidgets.sortedBy { it.globalIndex }.forEach { removed ->
            s = repository.restoreWidget(removed.id)
        }
        assertEquals(5, s.widgets.size)
        assertTrue(s.removedWidgets.isEmpty())
        assertEquals(template.containerBytes, s.containerBytes)
        repository = repository()
        assertEquals(5, repository.openProject(template.projectId).widgets.size)
    }

    /** Only Info_4 is a template, and a refusal writes nothing. */
    @Test
    fun anyOtherFaceIsRefusedBeforeARowIsWritten() = runBlocking {
        val other = "com.samsung.fit3watchface.sm_r390_0008@40000.apk"
        assumeTrue(Files.isRegularFile(storePackage(other)))
        val error = runCatching { repository.openTemplate(download(other, "00008"), "Custom face") }
            .exceptionOrNull()
        assertTrue("got $error", error is WatchFaceException)
        assertTrue(repository.observeProjects().first().isEmpty())
    }
}
