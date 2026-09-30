package dev.fitface.studio.feature.library

import android.os.Looper
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DownloadProgress
import dev.fitface.studio.core.model.ExportedProject
import dev.fitface.studio.core.model.FaceCatalog
import dev.fitface.studio.core.model.FaceCatalogRepository
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.ImportedProject
import dev.fitface.studio.core.model.ProjectSummary
import dev.fitface.studio.core.model.WatchFaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The hidden switch, from the keystroke to the controls appearing.
 *
 * `DeveloperGate` itself is a predicate with its own test; what is worth pinning here is the
 * wiring around it, which is where all three of the ways this can go wrong live. The phrase
 * must be **consumed** rather than searched for, or the list flashes "No matching projects"
 * in answer to a gesture that worked and the phrase is left in the field for the next person
 * to read. It must be written through the repository rather than held in the ViewModel, or
 * every cold start hides the tools again and reads as the gate having failed. And every
 * action behind it has to check the flag itself, because a stale composition holding an
 * `onExport` from before the tools were locked is exactly the kind of thing a UI-only guard
 * misses.
 *
 * Robolectric for `viewModelScope`, like `LibraryViewModelTest`: the ViewModel launches from
 * its own `init` and `Dispatchers.Main` needs a looper to dispatch onto.
 */
@RunWith(RobolectricTestRunner::class)
class DeveloperGateWiringTest {
    private val developerTools = MutableStateFlow(false)

    private val project = ProjectSummary(
        id = 12,
        displayName = "Face 00046",
        sourceUri = "${FacePackage.SOURCE_SCHEME}dev.fitface.face00046/4/0",
        faceId = "00046",
        faceName = "Aurora",
        importedAtEpochMillis = 1_700_000_000_000,
        name = "Aurora 2",
    )

    private val repository = mockk<WatchFaceRepository>(relaxed = true) {
        every { observeProjects() } returns emptyFlow()
        every { observeDeveloperTools() } returns developerTools
        coEvery { setDeveloperTools(any()) } answers { developerTools.value = firstArg() }
    }

    private fun viewModel(): LibraryViewModel =
        LibraryViewModel(repository, FakeCatalog(), DiagnosticsLog(), mockk(relaxed = true))
            .also { settle() }

    /** Off on a fresh install, with nothing on screen that could be tapped towards it. */
    @Test
    fun theToolsStartHidden() {
        assertFalse(viewModel().state.value.developerTools)
    }

    /**
     * The phrase opens the tools and does not stay in the field.
     *
     * Both halves matter. The field is the search, so leaving the phrase in it would filter
     * the list to nothing — a flash of something wrong in answer to something that worked —
     * and would leave the secret sitting on screen.
     */
    @Test
    fun typingThePhraseOpensTheToolsAndLeavesNothingInTheField() {
        val viewModel = viewModel()

        viewModel.setProjectQuery(PHRASE)
        settle()

        assertTrue(viewModel.state.value.developerTools)
        assertEquals("", viewModel.state.value.projectQuery)
    }

    /** Typing it again closes them, so there is a way back to a clean screen. */
    @Test
    fun typingThePhraseAgainClosesThem() {
        val viewModel = viewModel()
        viewModel.setProjectQuery(PHRASE)
        settle()

        viewModel.setProjectQuery(PHRASE)
        settle()

        assertFalse(viewModel.state.value.developerTools)
        assertEquals("", viewModel.state.value.projectQuery)
    }

    /**
     * Every prefix on the way to the phrase is an ordinary search.
     *
     * The field reports each keystroke, so a `startsWith` or a `contains` in the gate would
     * open the tools halfway through typing and close them again on the last character.
     */
    @Test
    fun theKeystrokesOnTheWayToItAreOrdinarySearches() {
        val viewModel = viewModel()

        PHRASE.indices.drop(1).map(PHRASE::take).forEach { typed ->
            viewModel.setProjectQuery(typed)
            settle()
            assertFalse("\"$typed\" opened the tools", viewModel.state.value.developerTools)
            assertEquals(typed, viewModel.state.value.projectQuery)
        }
    }

    /** An ordinary search is not consumed, and does not touch the flag. */
    @Test
    fun anOrdinarySearchIsLeftAlone() {
        val viewModel = viewModel()

        viewModel.setProjectQuery("aurora")
        settle()

        assertEquals("aurora", viewModel.state.value.projectQuery)
        assertFalse(viewModel.state.value.developerTools)
    }

    /**
     * The flag is written through the repository, not held here.
     *
     * It has to survive the process: a flag kept in the ViewModel would be gone on the next
     * cold start, and a gate that has to be re-entered every launch is one that reads as
     * broken.
     */
    @Test
    fun theFlagIsPersisted() {
        val viewModel = viewModel()

        viewModel.setProjectQuery(PHRASE)
        settle()

        coVerify(exactly = 1) { repository.setDeveloperTools(true) }
    }

    /** Locked, an export cannot be started even by a caller that asks. */
    @Test
    fun exportingIsRefusedWhileTheToolsAreLocked() {
        val viewModel = viewModel()

        viewModel.startExport(project)
        settle()

        assertNull(viewModel.state.value.exporting)
    }

    /**
     * Unlocked, the tap arms the picker with the name it will suggest.
     *
     * The name is derived in the state rather than in the composable so that this, the one
     * part the harness cannot see, is still pinned: `CreateDocument` takes it at launch.
     */
    @Test
    fun exportingArmsThePickerWithTheNameItWillSuggest() {
        val viewModel = viewModel()
        viewModel.setProjectQuery(PHRASE)
        settle()

        viewModel.startExport(project)
        settle()

        assertNotNull(viewModel.state.value.exporting)
        assertEquals("SM-R390_00046_Aurora-2.zip", viewModel.state.value.exportFileName)
    }

    /**
     * A cancelled picker disarms the request rather than leaving it standing.
     *
     * The effect that opens the picker keys on the request, so one left armed would mean the
     * next export of the same project never reopened it — the tap would do nothing at all.
     */
    @Test
    fun cancellingThePickerDisarmsTheRequest() {
        val viewModel = viewModel()
        viewModel.setProjectQuery(PHRASE)
        settle()
        viewModel.startExport(project)
        settle()

        viewModel.finishExport(null)
        settle()

        assertNull(viewModel.state.value.exporting)
        coVerify(exactly = 0) { repository.exportProject(any(), any()) }
    }

    /** A written file is reported by name and size, because nothing else on screen says so. */
    @Test
    fun aFinishedExportIsReported() {
        coEvery { repository.exportProject(12, "content://out") } returns
            ExportedProject("Aurora 2", 1_310_720)
        val viewModel = viewModel()
        viewModel.setProjectQuery(PHRASE)
        settle()
        viewModel.startExport(project)
        settle()

        viewModel.finishExport("content://out")
        settle()

        val notice = viewModel.state.value.exported
        assertEquals("Aurora 2", notice?.name)
        assertEquals(1_310_720L, notice?.byteCount)
        assertNull("the request stayed armed", viewModel.state.value.exporting)
    }

    /** Locked, an import is refused even if a picker result somehow arrives. */
    @Test
    fun importingIsRefusedWhileTheToolsAreLocked() {
        val viewModel = viewModel()

        viewModel.importProject("content://in")
        settle()

        coVerify(exactly = 0) { repository.importProject(any()) }
    }

    /** Unlocked, an import reports the name the project was given here. */
    @Test
    fun aFinishedImportIsReported() {
        coEvery { repository.importProject("content://in") } returns ImportedProject(31, "Aurora 3")
        val viewModel = viewModel()
        viewModel.setProjectQuery(PHRASE)
        settle()

        viewModel.importProject("content://in")
        settle()

        assertEquals("Aurora 3", viewModel.state.value.imported?.name)
    }

    /** A cancelled picker returns null, which is not an import and not an error. */
    @Test
    fun aCancelledImportPickerDoesNothing() {
        val viewModel = viewModel()
        viewModel.setProjectQuery(PHRASE)
        settle()

        viewModel.importProject(null)
        settle()

        coVerify(exactly = 0) { repository.importProject(any()) }
        assertNull(viewModel.state.value.imported)
        assertNull(viewModel.state.value.error)
    }

    /** A failed import says why, on the snackbar every other library failure uses. */
    @Test
    fun aFailedImportIsReportedRatherThanSwallowed() {
        coEvery { repository.importProject(any()) } throws
            IllegalStateException("that file holds no exported project")
        val viewModel = viewModel()
        viewModel.setProjectQuery(PHRASE)
        settle()

        viewModel.importProject("content://in")
        settle()

        assertEquals(
            "that file holds no exported project",
            viewModel.state.value.error?.text,
        )
        assertNull(viewModel.state.value.imported)
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private companion object {
        /** Assembled, not written out — see `ProjectTransferTest` in `:core:model`. */
        const val PHRASE = "quartz" + "ite"
    }

    /** Enough of a catalogue for the ViewModel's `init` to finish. */
    private class FakeCatalog : FaceCatalogRepository {
        override suspend fun cachedCatalog(): FaceCatalog? = null

        override suspend fun loadCatalog(forceRefresh: Boolean): FaceCatalog =
            FaceCatalog(faces = emptyList(), styleCount = 0, fetchedAtEpochMillis = 0)

        override suspend fun uneditableAppIds(): Set<String> = emptySet()

        override suspend fun markUneditable(appId: String) = Unit

        override suspend fun isPackageCached(face: CatalogFace) = false

        override suspend fun downloadPackage(
            face: CatalogFace,
            styleId: Int,
            onProgress: (DownloadProgress) -> Unit,
        ): FacePackage = error("not used")
    }
}
