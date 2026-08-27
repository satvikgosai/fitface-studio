package dev.fitface.studio.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.data.db.ProjectDao
import dev.fitface.studio.core.data.db.ProjectEntity
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.FacePackage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That an edit is one commit across the row, the container and the session file.
 *
 * The three used to be written in the order container, session file, row — and the row is
 * the only one of the three behind a cancellable suspension. A commit that threw or was
 * cancelled at the database left the new `edited.bin` on disk while the editor rolled only
 * its in-memory container back, and an already-edited project's row names that same
 * pathname: reopening it therefore loaded the edit the app had just reported as failed.
 * The row goes first now, so every failure leaves the two agreeing.
 */
@RunWith(RobolectricTestRunner::class)
class EditPersistenceTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val packagePath: Path get() = root.resolve("SM-R390_00046.apk")

    private lateinit var context: Context
    private lateinit var database: FitFaceDatabase
    private lateinit var dao: FailableProjectDao
    private lateinit var repository: WatchFaceRepositoryImpl

    @Before
    fun setUp() {
        assumeTrue("no package at $packagePath", Files.isRegularFile(packagePath))
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FitFaceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = FailableProjectDao(database.projectDao())
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
    fun anEditThatFailedToCommitIsNotThereWhenTheProjectIsReopened() = runBlocking {
        val opened = repository.openPackage(facePackage())
        val first = nudge(opened, by = 4)
        val committed = position(first)
        // The project has to be edited *already* for this to bite: the row has to be
        // naming `edited.bin` before the failing commit replaces it.
        assertNotEquals(position(opened), committed)

        dao.failNextInsert = true
        val failure = runCatching { nudge(first, by = 9) }
        dao.failNextInsert = false

        assertTrue("the commit was expected to fail", failure.isFailure)
        assertEquals("the canvas did not roll back", committed, position(repository.currentSnapshot()))
        assertEquals(
            "the failed edit came back from disk",
            committed,
            position(repository.openProject(opened.projectId)),
        )
    }

    /** The successful path is unchanged by the reordering, which is the other half. */
    @Test
    fun aCommittedEditIsThereWhenTheProjectIsReopened() = runBlocking {
        val opened = repository.openPackage(facePackage())
        val moved = position(nudge(opened, by = 4))

        assertEquals(moved, position(repository.openProject(opened.projectId)))
    }

    /**
     * A first edit writes the row before the container exists. If it never appears, the
     * row names a path that is not a file, and that has to read as "no edit" rather than
     * as a project that cannot be opened.
     */
    @Test
    fun aRowPointingAtAContainerThatIsNotThereOpensAsUnedited() = runBlocking {
        val opened = repository.openPackage(facePackage())
        val edited = position(nudge(opened, by = 4))
        assertNotEquals(position(opened), edited)

        assertTrue(File(projectDirectory(opened.projectId), "edited.bin").delete())

        assertEquals(position(opened), position(repository.openProject(opened.projectId)))
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

    /** Where the widget the test moves has ended up, as the thing to compare. */
    private fun position(snapshot: EditorSnapshot): Pair<Int, Int> =
        snapshot.widgets.first { it.width > 0 && it.height > 0 }.let { it.x to it.y }

    private fun projectDirectory(projectId: Long) =
        File(context.filesDir, "projects/$projectId")

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

    /** A [ProjectDao] whose next write can be made to fail, standing in for a full disk. */
    private class FailableProjectDao(private val delegate: ProjectDao) : ProjectDao {
        var failNextInsert = false

        override fun observeAll(): Flow<List<ProjectEntity>> = delegate.observeAll()

        override suspend fun findById(id: Long): ProjectEntity? = delegate.findById(id)

        override suspend fun findByFaceId(faceId: String): List<ProjectEntity> =
            delegate.findByFaceId(faceId)

        override suspend fun rename(id: Long, name: String): Int = delegate.rename(id, name)

        override suspend fun insert(project: ProjectEntity): Long {
            if (failNextInsert) {
                failNextInsert = false
                throw IOException("the project row could not be written")
            }
            return delegate.insert(project)
        }

        override suspend fun deleteById(id: Long): Int = delegate.deleteById(id)
    }
}
