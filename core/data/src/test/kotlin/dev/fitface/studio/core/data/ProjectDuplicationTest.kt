package dev.fitface.studio.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.data.db.ProjectDao
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.FacePackage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That a duplicated project is a project of its own and not a second view of the first.
 *
 * The contract is independence, and the reason it needs holding is that a project row is
 * *nearly* copyable: everything on it is either shared with the original by right — the
 * face, the package version, the style — or trivially derived. The two exceptions are
 * `localApkPath` and `editedBinPath`, which are absolute paths into the original's own
 * directory. A row copy that kept them produces a duplicate that reads the original's edits
 * and, once the original is deleted, cannot be opened at all — and neither symptom shows up
 * until after the copy has been made and named.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectDuplicationTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val packagePath: Path get() = root.resolve("SM-R390_00046.apk")

    private lateinit var context: Context
    private lateinit var database: FitFaceDatabase
    private lateinit var dao: ProjectDao
    private lateinit var repository: WatchFaceRepositoryImpl

    @Before
    fun setUp() {
        assumeTrue("no package at $packagePath", Files.isRegularFile(packagePath))
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FitFaceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.projectDao()
        repository = WatchFaceRepositoryImpl(
            context = context,
            projectDao = dao,
            imageSource = AndroidImageSource(context.contentResolver),
            diagnostics = DiagnosticsLog(),
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) File(context.filesDir, "projects").deleteRecursively()
    }

    @Test
    fun aDuplicateCarriesTheEditsTheOriginalHadWhenItWasCopied() = runBlocking {
        val original = repository.openPackage(facePackage())
        val moved = position(nudge(original, by = 7))

        val duplicate = repository.duplicateProject(original.projectId)

        assertEquals(moved, position(repository.openProject(duplicate.id)))
    }

    @Test
    fun editingTheDuplicateLeavesTheOriginalWhereItWas() = runBlocking {
        val original = repository.openPackage(facePackage())
        val before = position(nudge(original, by = 7))
        val duplicate = repository.duplicateProject(original.projectId)

        val opened = repository.openProject(duplicate.id)
        val after = position(nudge(opened, by = 5))
        assertNotEquals("the duplicate did not actually move", before, after)

        assertEquals(
            "editing the copy wrote into the original",
            before,
            position(repository.openProject(original.projectId)),
        )
    }

    @Test
    fun editingTheOriginalLeavesTheDuplicateWhereItWas() = runBlocking {
        val original = repository.openPackage(facePackage())
        val before = position(nudge(original, by = 7))
        val duplicate = repository.duplicateProject(original.projectId)

        val reopened = repository.openProject(original.projectId)
        assertNotEquals(before, position(nudge(reopened, by = 5)))

        assertEquals(
            "editing the original wrote into the copy",
            before,
            position(repository.openProject(duplicate.id)),
        )
    }

    /** The half that a copied `localApkPath` breaks, and only after the original is gone. */
    @Test
    fun deletingTheOriginalLeavesTheDuplicateOpenable() = runBlocking {
        val original = repository.openPackage(facePackage())
        val moved = position(nudge(original, by = 7))
        val duplicate = repository.duplicateProject(original.projectId)

        repository.deleteProject(original.projectId)

        assertEquals(moved, position(repository.openProject(duplicate.id)))
    }

    @Test
    fun deletingTheDuplicateLeavesTheOriginalOpenable() = runBlocking {
        val original = repository.openPackage(facePackage())
        val moved = position(nudge(original, by = 7))
        val duplicate = repository.duplicateProject(original.projectId)

        repository.deleteProject(duplicate.id)

        assertEquals(moved, position(repository.openProject(original.projectId)))
        assertFalse(
            "the duplicate's directory outlived it",
            File(context.filesDir, "projects/${duplicate.id}").exists(),
        )
    }

    /**
     * A removed widget lives in `session.json`, which no column names — so it is copied by
     * convention or not at all. Without it the duplicate shows the widget gone with nothing
     * offering to put it back, which is worse than either keeping or dropping the edit.
     */
    @Test
    fun aDuplicateCanRestoreAWidgetTheOriginalHadRemoved() = runBlocking {
        val original = repository.openPackage(facePackage())
        val widget = original.widgets.first { it.width > 0 && it.height > 0 }
        val removed = repository.removeWidget(
            styleName = original.selectedStyle,
            globalIndex = widget.globalIndex,
            widgetType = widget.type,
            sequenceId = widget.sequenceId,
            x = widget.x,
            y = widget.y,
            requireFinal = false,
            applyToAllStyles = false,
        )
        assertEquals(1, removed.removedWidgets.size)

        val duplicate = repository.duplicateProject(original.projectId)
        val opened = repository.openProject(duplicate.id)

        assertEquals(
            "the copy cannot put back what the original cut out",
            1,
            opened.removedWidgets.size,
        )
        assertEquals(
            removed.widgets.size + 1,
            repository.restoreWidget(opened.removedWidgets.first().id).widgets.size,
        )
    }

    @Test
    fun aDuplicateIsNamedApartFromTheProjectItCameFrom() = runBlocking {
        val original = repository.openPackage(facePackage())
        val first = repository.duplicateProject(original.projectId)
        val second = repository.duplicateProject(original.projectId)

        val names = dao.findByFaceId("00046").mapNotNull { it.projectName }
        assertEquals("three projects, three names", 3, names.toSet().size)
        assertNotEquals(first.name, second.name)
    }

    /**
     * Copying a copy is what people actually do, and it is the case the naming had wrong:
     * "Wish 2" duplicated to "Wish 2 2", and again to "Wish 2 3" — a second series running
     * beside the first, both claiming to be numbered from the same project.
     */
    @Test
    fun duplicatingADuplicateStaysOnOneSeriesOfNames() = runBlocking {
        val original = repository.openPackage(facePackage())
        val second = repository.duplicateProject(original.projectId)
        val third = repository.duplicateProject(second.id)
        val fourth = repository.duplicateProject(third.id)

        val names = dao.findByFaceId("00046").mapNotNull { it.projectName }
        assertEquals("four projects, four names", 4, names.toSet().size)
        assertTrue(
            "a name picked up a second counter: $names",
            names.none { Regex(""".*\s\d+\s\d+$""").matches(it) },
        )
        assertNotEquals(second.name, third.name)
        assertNotEquals(third.name, fourth.name)
    }

    /** Nothing in the copy may still point at the directory it was copied from. */
    @Test
    fun aDuplicateHoldsNoPathIntoTheOriginalsDirectory() = runBlocking {
        val original = repository.openPackage(facePackage())
        nudge(original, by = 7)
        val duplicate = repository.duplicateProject(original.projectId)

        val row = requireNotNull(dao.findById(duplicate.id))
        val ownDirectory = File(context.filesDir, "projects/${duplicate.id}").absolutePath
        assertTrue("localApkPath: ${row.localApkPath}", row.localApkPath!!.startsWith(ownDirectory))
        assertTrue("editedBinPath: ${row.editedBinPath}", row.editedBinPath!!.startsWith(ownDirectory))
    }

    /** A project whose package has gone missing cannot make a working copy of itself. */
    @Test
    fun duplicatingAProjectWithNoPackageIsRefusedRatherThanHalfDone() = runBlocking {
        val original = repository.openPackage(facePackage())
        assertTrue(File(context.filesDir, "projects/${original.projectId}/source.apk").delete())

        val failure = runCatching { repository.duplicateProject(original.projectId) }

        assertTrue("the copy was expected to be refused", failure.isFailure)
        assertEquals(
            "a project that cannot be opened was added to the library",
            1,
            dao.findByFaceId("00046").size,
        )
    }

    private suspend fun nudge(snapshot: EditorSnapshot, by: Int): EditorSnapshot {
        val widget = snapshot.widgets.first { it.width > 0 && it.height > 0 }
        return repository.moveWidget(
            styleName = snapshot.selectedStyle,
            globalIndex = widget.globalIndex,
            widgetType = widget.type,
            sequenceId = widget.sequenceId,
            x = widget.x + by,
            y = widget.y,
            applyToAllStyles = false,
        )
    }

    private fun position(snapshot: EditorSnapshot): Pair<Int, Int> =
        snapshot.widgets.first { it.width > 0 && it.height > 0 }.let { it.x to it.y }

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
