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
        repository = WatchFaceRepositoryImpl(
            context = context,
            projectDao = database.projectDao(),
            imageSource = AndroidImageSource(context.contentResolver),
            diagnostics = DiagnosticsLog(),
        )
    }

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
