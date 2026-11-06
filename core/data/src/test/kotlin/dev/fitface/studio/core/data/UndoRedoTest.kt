package dev.fitface.studio.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditKind
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.WidgetGuide
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Undo and redo walk the session's checkpoints: every edit is taken back and put back
 * byte for byte, with the state around it (removed widgets, imports, the active style)
 * coming back too, and the files on disk are exactly the ones history still names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UndoRedoTest {
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
        if (::context.isInitialized) File(context.filesDir, "projects").deleteRecursively()
    }

    private fun repository() = WatchFaceRepositoryImpl(context, database.projectDao(),
        AndroidImageSource(context.contentResolver), context.contentResolver, DiagnosticsLog())

    private fun bin(id: String): ByteArray {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("no corpus face $id", Files.isRegularFile(path))
        return Files.readAllBytes(path)
    }

    private fun face(id: String) = FacePackage(FacePackage.sourceKey("test-$id", 1, 0),
        "SM-R390_$id.apk", id, 0, 1, ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("assets/SM-R390_${id}_256x402.bin")); zip.write(bin(id)); zip.closeEntry()
            }
        }.toByteArray())

    private suspend fun bytes() = repository.prepareDirectInstall().copyBytes()

    private suspend fun move(s: EditorSnapshot, w: WidgetGuide, dx: Int = 3, group: Long? = null) =
        repository.moveWidget(s.selectedVariant.basename, w.globalIndex, w.type, w.sequenceId,
            w.x + dx, w.y, false, group)

    private fun checkpoints(projectId: Long) = File(context.filesDir, "projects/$projectId").listFiles().orEmpty()
        .filter { it.name.matches(Regex("edit-[a-f0-9-]{36}\\.checkpoint")) }

    private suspend fun import(s: EditorSnapshot, id: String, index: Int): EditorSnapshot {
        val donor = repository.inspectWidgetDonor(face(id))
        val preview = repository.previewWidgetImport(donor.handle, "style0.bin", index,
            s.projectId, s.selectedVariant.basename)
        return repository.importWidget(preview.ticket)
    }

    @Test fun everyKindOfEditUndoesAndRedoesByteForByte() = runBlocking {
        var s = repository.openPackage(face("00112"))
        val states = mutableListOf(bytes())
        val kinds = mutableListOf<EditKind>()
        fun record(next: EditorSnapshot, kind: EditKind) = runBlocking {
            s = next; kinds += kind; states += bytes()
            assertEquals(kind, s.undoKind)
            assertNull(s.redoKind)
        }
        val sprite = s.widgets.first { it.type == 3 && it.canEditPosition }
        record(move(s, sprite), EditKind.MOVE)
        val resized = s.widgets.single { it.globalIndex == sprite.globalIndex }
        record(repository.resizeWidget(s.selectedVariant.basename, resized.globalIndex, resized.type,
            resized.sequenceId, resized.x, resized.y, resized.width - 2, resized.height - 3, false), EditKind.RESIZE)
        val text = s.widgets.first { it.colorArgb != null }
        record(repository.recolorWidget(s.selectedVariant.basename, text.globalIndex, text.type, text.sequenceId,
            text.x, text.y, 0xFF3366CC.toInt(), false), EditKind.COLOUR)
        val last = s.widgets.last()
        record(repository.removeWidget(s.selectedVariant.basename, last.globalIndex, last.type, last.sequenceId,
            last.x, last.y, false, false), EditKind.REMOVE)
        val copied = s.widgets.first { it.type == 3 }
        record(repository.duplicateWidget(s.selectedVariant.basename, copied.globalIndex, copied.type,
            copied.sequenceId, copied.x, copied.y, false), EditKind.DUPLICATE)
        record(import(s, "00008", 1), EditKind.IMPORT)

        // All the way back: each state in turn, ending at the package itself.
        for (index in kinds.indices.reversed()) {
            assertEquals(kinds[index], s.undoKind)
            s = repository.undo()
            assertArrayEquals("after undoing ${kinds[index]}", states[index], bytes())
            assertEquals(kinds[index], s.redoKind)
        }
        assertFalse("back at the package", s.isDirty)
        assertNull(s.undoKind)

        // And forward again.
        for (index in kinds.indices) {
            s = repository.redo()
            assertArrayEquals("after redoing ${kinds[index]}", states[index + 1], bytes())
            assertEquals(kinds[index], s.undoKind)
        }
        assertNull(s.redoKind)
        assertTrue(s.widgets.last().importedFromFaceId != null)
    }

    /** Deleting takes the artwork; Undo brings widget and artwork back, Redo takes them again. */
    @Test fun undoingADeletionBringsTheWidgetAndItsArtworkBack() = runBlocking {
        var s = repository.openPackage(face("00106"))
        val pristine = bytes()
        val opened = s
        val last = s.widgets.last()
        s = repository.removeWidget(s.selectedVariant.basename, last.globalIndex, last.type, last.sequenceId,
            last.x, last.y, false, false)
        val deleted = bytes()
        assertEquals(opened.widgets.size - 1, s.widgets.size)
        s = repository.undo()
        assertArrayEquals(pristine, bytes())
        assertEquals(last.globalIndex, s.widgets.last().globalIndex)
        s = repository.redo()
        assertArrayEquals(deleted, bytes())
    }

    @Test fun undoingAStyleDeletionRestoresTheStylesAndTheActiveOne() = runBlocking {
        var s = repository.openPackage(face("00112"))
        s = repository.currentSnapshot("style2.bin")
        val before = bytes()
        s = repository.deleteStyles(setOf("style0.bin", "style1.bin"), repository.styleManagement().revision)
        assertEquals(2, s.styleNames.size)
        s = repository.undo()
        assertEquals(4, s.styleNames.size)
        assertEquals("style2.bin", s.activeStyleName)
        assertArrayEquals(before, bytes())
        s = repository.redo()
        assertEquals(2, s.styleNames.size)
    }

    @Test fun oneGroupIsOneStepAndAnUngroupedEditIsItsOwn() = runBlocking {
        var s = repository.openPackage(face("00106"))
        val widget = s.widgets.first { it.type == 5 && it.canEditPosition }
        val start = bytes()
        repeat(4) { s = move(s, s.widgets.single { it.globalIndex == widget.globalIndex }, dx = 1, group = 7L) }
        val afterHold = bytes()
        s = move(s, s.widgets.single { it.globalIndex == widget.globalIndex }, dx = 1)
        s = repository.undo()
        assertArrayEquals(afterHold, bytes())
        s = repository.undo()
        assertArrayEquals("four grouped moves are one step", start, bytes())
        assertNull(s.undoKind)
        // A later edit in the same group after an undo is a new step, not a continuation.
        s = repository.redo()
        s = move(s, s.widgets.single { it.globalIndex == widget.globalIndex }, dx = 1, group = 7L)
        s = repository.undo()
        assertArrayEquals(afterHold, bytes())
    }

    @Test fun historyKeepsTheNewestThirtyStepsAndTheirFilesOnly() = runBlocking {
        // The smallest catalogue face, so thirty steps fit the byte budget and the step
        // bound is the one reached.
        var s = repository.openPackage(face("00006"))
        val widget = s.widgets.first { it.canEditPosition }
        repeat(35) { s = move(s, s.widgets.single { it.globalIndex == widget.globalIndex }, dx = 1) }
        // The current state plus one file per kept step: none of them is the package.
        assertEquals(31, checkpoints(s.projectId).size)
        var undone = 0
        while (s.undoKind != null) { s = repository.undo(); undone++ }
        assertEquals(30, undone)
        assertTrue("the oldest five moves are no longer undoable", s.isDirty)
        // A new edit drops everything that could be redone, files included.
        s = move(s, s.widgets.single { it.globalIndex == widget.globalIndex }, dx = 1)
        assertNull(s.redoKind)
        assertEquals(2, checkpoints(s.projectId).size)
    }

    @Test fun resetClearsHistoryAndLeavesNoCheckpoint() = runBlocking {
        var s = repository.openPackage(face("00106"))
        val widget = s.widgets.first { it.type == 5 && it.canEditPosition }
        s = move(s, widget)
        s = move(s, s.widgets.single { it.globalIndex == widget.globalIndex })
        s = repository.undo()
        s = repository.resetEdits()
        assertNull(s.undoKind)
        assertNull(s.redoKind)
        assertFalse(s.isDirty)
        assertTrue(checkpoints(s.projectId).isEmpty())
    }

    @Test fun historyEndsWithTheSessionAndReopeningSweepsLeftovers() = runBlocking {
        var s = repository.openPackage(face("00106"))
        val widget = s.widgets.first { it.type == 5 && it.canEditPosition }
        s = move(s, widget)
        s = move(s, s.widgets.single { it.globalIndex == widget.globalIndex })
        val edited = bytes()
        // A crash's leftover beside the history files.
        File(context.filesDir, "projects/${s.projectId}/edit-${UUID.randomUUID()}.checkpoint").writeText("{}")
        repository = repository()
        s = repository.openProject(s.projectId)
        assertNull(s.undoKind)
        assertEquals(1, checkpoints(s.projectId).size)
        assertArrayEquals(edited, bytes())
    }

    /**
     * A process killed with history on disk leaves its steps behind. The next process deletes
     * them the first time the library reads the projects, without the project being opened.
     */
    @Test fun aKilledSessionsHistoryIsSweptWhenTheNextProcessListsProjects() = runBlocking {
        var s = repository.openPackage(face("00106"))
        val widget = s.widgets.first { it.type == 5 && it.canEditPosition }
        repeat(3) { s = move(s, s.widgets.single { it.globalIndex == widget.globalIndex }) }
        val edited = bytes()
        assertEquals("the current state and two steps", 3, checkpoints(s.projectId).size)

        // A new repository is a new process: the old one never closed its session.
        repository = repository()
        repository.observeProjects().first()
        repository.historySweep?.join()

        assertEquals(1, checkpoints(s.projectId).size)
        s = repository.openProject(s.projectId)
        assertArrayEquals("the current state is untouched", edited, bytes())
    }

    /** The editor takes the session the library opened, and only that project's. */
    @Test fun theOpenedProjectIsHandedOverOnlyForItsOwnId() = runBlocking {
        var s = repository.openPackage(face("00106"))
        val widget = s.widgets.first { it.type == 5 && it.canEditPosition }
        s = move(s, widget)
        val opened = requireNotNull(repository.openedProject(s.projectId))
        assertEquals(s.projectId, opened.projectId)
        assertEquals(s.widgets, opened.widgets)
        assertEquals("history is the session's, not a reopen's", EditKind.MOVE, opened.undoKind)
        assertNull(repository.openedProject(s.projectId + 1))
        assertNull(repository().openedProject(s.projectId))
    }

    /**
     * The editor's first ask after an open takes the snapshot the open built rather than
     * composing every preview again — once, and only while nothing has changed since.
     */
    @Test fun theEditorTakesTheSnapshotTheOpenBuiltOnceAndOnlyWhileUnchanged() = runBlocking {
        val s = repository.openPackage(face("00106"))
        val first = requireNotNull(repository.openedProject(s.projectId))
        assertSame("handed over, not rebuilt", s.preview, first.preview)
        val again = requireNotNull(repository.openedProject(s.projectId))
        assertNotSame("only once", s.preview, again.preview)

        val reopened = repository.openProject(s.projectId)
        val widget = reopened.widgets.first { it.type == 5 && it.canEditPosition }
        val moved = move(reopened, widget)
        val afterEdit = requireNotNull(repository.openedProject(s.projectId))
        assertNotSame("an edit since the open builds a fresh one", reopened.preview, afterEdit.preview)
        assertEquals(moved.widgets, afterEdit.widgets)
    }

    @Test fun aDuplicateCopiesOnlyTheCurrentStateAndOpeningAnotherProjectDropsHistory() = runBlocking {
        var s = repository.openPackage(face("00106"))
        val widget = s.widgets.first { it.type == 5 && it.canEditPosition }
        s = move(s, widget)
        s = move(s, s.widgets.single { it.globalIndex == widget.globalIndex })
        val original = s.projectId
        // The current state and the first move's; before that is the package, which has no file.
        assertEquals(2, checkpoints(original).size)
        val copy = repository.duplicateProject(original)
        assertEquals(1, checkpoints(copy.id).size)
        s = repository.openProject(copy.id)
        assertNull(s.undoKind)
        assertEquals("the original's history went with its session", 1, checkpoints(original).size)
    }

    @Test fun anUndoneStateSurvivesReopeningAsTheCurrentOne() = runBlocking {
        var s = repository.openPackage(face("00106"))
        val widget = s.widgets.first { it.type == 5 && it.canEditPosition }
        s = move(s, widget)
        val first = bytes()
        s = move(s, s.widgets.single { it.globalIndex == widget.globalIndex })
        s = repository.undo()
        repository = repository()
        s = repository.openProject(s.projectId)
        assertArrayEquals(first, bytes())
        assertTrue(s.isDirty)
    }
}
