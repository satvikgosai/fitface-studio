package dev.fitface.studio.feature.library

import android.os.Looper
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.DownloadProgress
import dev.fitface.studio.core.model.FaceCatalog
import dev.fitface.studio.core.model.FaceCatalogRepository
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.FaceStyleOption
import dev.fitface.studio.core.model.ProjectSummary
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.WatchFaceRepository
import dev.fitface.studio.core.data.DiagnosticsReporter
import dev.fitface.studio.core.model.DiagnosticsLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.DuplicatedProject
import dev.fitface.studio.core.model.ImportedProject
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
 * Which library actions a background catalogue refresh is allowed to block.
 *
 * The grid is deliberately painted from the on-disk cache before the network is touched,
 * so `isLoadingCatalog` is true for the whole opening window of every launch while the
 * screen already looks ready. `selectFace`, and later IMPORT and the project rows, used
 * to refuse on a busy flag that included it, so every tap in that window did nothing at
 * all. A refresh writes only the catalogue's own fields, so it is no reason to refuse any
 * of them; a download in flight and a project being opened hold the editing session, and
 * still are.
 *
 * Robolectric is here for `viewModelScope`: without a main looper `Dispatchers.Main` cannot
 * dispatch, and the ViewModel launches from its own `init`. Nothing below has to wait for a
 * result — the fakes park each network call where the test needs it and [settle] runs
 * whatever the launch posted — so no test needs a timeout or a scheduler of its own.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    private val faces = listOf(
        catalogFace(faceId = "00001", styleId = 3),
        catalogFace(faceId = "00002", styleId = 7),
    )
    private val catalogue = FaceCatalog(
        faces = faces,
        styleCount = 2,
        fetchedAtEpochMillis = 1_700_000_000_000,
    )
    private val project = ProjectSummary(
        id = 12,
        displayName = "Face 00001",
        sourceUri = "${FacePackage.SOURCE_SCHEME}dev.fitface.face00001/4/3",
        faceId = "00001",
        faceName = "Face 00001",
        importedAtEpochMillis = 1_700_000_000_000,
    )
    private val repository = mockk<WatchFaceRepository>(relaxed = true) {
        every { observeProjects() } returns emptyFlow()
    }

    /**
     * Before the first read the list is empty without being known to be, and the Projects
     * tab read "Projects 0" over "No projects yet" until it arrived — seconds, with many
     * edited projects. An empty first read is still a read.
     */
    @Test
    fun theProjectsAreUnknownUntilTheFirstReadEvenAnEmptyOne() {
        val saved = MutableSharedFlow<List<ProjectSummary>>(extraBufferCapacity = 1)
        every { repository.observeProjects() } returns saved
        val viewModel = LibraryViewModel(repository, FakeCatalog(loaded = catalogue), DiagnosticsLog(), reporter())
        settle()
        assertFalse(viewModel.state.value.projectsLoaded)

        saved.tryEmit(emptyList())
        settle()
        assertTrue(viewModel.state.value.projectsLoaded)
        assertEquals(emptyList<ProjectSummary>(), viewModel.state.value.projects)
    }

    /** The bug: a tap in the cache-painted window before the network answers. */
    @Test
    fun tappingAFaceWhileTheCatalogueRefreshesOpensTheSheet() {
        val catalog = FakeCatalog(cached = catalogue.copy(fromCache = true))

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()

        val opening = viewModel.state.value
        assertTrue(opening.isLoadingCatalog)
        assertEquals(faces, opening.faces)

        viewModel.selectFace(faces[1])

        assertEquals(faces[1], viewModel.state.value.selectedFace)
        assertEquals(7, viewModel.state.value.selectedStyleId)
        // The refresh really is still running; it just blocks nothing.
        assertTrue(viewModel.state.value.isLoadingCatalog)
        assertFalse(viewModel.state.value.isBusy)
    }

    /**
     * The app IDs with no editable container are read before the network in `init`, so a
     * sheet opened during the refresh can still say the face will not open — which is what
     * makes showing it that early honest rather than just louder.
     */
    @Test
    fun aSheetOpenedDuringTheRefreshStillKnowsTheFaceIsNotEditable() {
        val catalog = FakeCatalog(
            cached = catalogue.copy(fromCache = true),
            uneditable = setOf(faces[0].appId),
        )

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        viewModel.selectFace(faces[0])

        assertTrue(viewModel.state.value.isLoadingCatalog)
        assertNotNull(viewModel.state.value.sheetError)
    }

    /**
     * A download does conflict: the sheet is the progress UI for the face being fetched,
     * and swapping the face under it would leave the download opening a project for a face
     * nobody is looking at.
     */
    @Test
    fun tappingAnotherFaceWhileADownloadIsInFlightIsIgnored() {
        val catalog = FakeCatalog(loaded = catalogue)

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        assertFalse(viewModel.state.value.isLoadingCatalog)
        viewModel.selectFace(faces[0])
        viewModel.downloadSelectedFace()
        settle()
        assertEquals(faces[0].productId, viewModel.state.value.downloadingProductId)

        viewModel.selectFace(faces[1])

        assertEquals(faces[0], viewModel.state.value.selectedFace)
        assertEquals(3, viewModel.state.value.selectedStyleId)
        assertFalse(viewModel.state.value.canSelectFace)
    }

    /** So does opening a project: the editor is about to take the screen. */
    @Test
    fun tappingAFaceWhileAProjectIsOpeningIsIgnored() {
        coEvery { repository.openProject(any()) } coAnswers { awaitCancellation() }
        val catalog = FakeCatalog(loaded = catalogue)

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        viewModel.openProject(project)
        settle()
        assertTrue(viewModel.state.value.isOpeningProject)

        viewModel.selectFace(faces[0])

        assertNull(viewModel.state.value.selectedFace)
    }

    /**
     * The project rows, IMPORT and Duplicate do not wait for a refresh either. They used to:
     * the guard counted the catalogue, so every launch's sync and every REFRESH greyed out
     * the Projects page for no conflict — the refresh writes only catalogue fields, and the
     * sheet's download already opened an editor during one.
     */
    @Test
    fun projectsOpenImportAndDuplicateWhileTheCatalogueIsRefreshing() {
        coEvery { repository.openProject(any()) } coAnswers { awaitCancellation() }
        coEvery { repository.importProject(any()) } returns ImportedProject(31, "Aurora 3")
        coEvery { repository.duplicateProject(any()) } returns DuplicatedProject(32, "Aurora 4")
        val catalog = FakeCatalog(cached = catalogue.copy(fromCache = true))

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        assertTrue(viewModel.state.value.isLoadingCatalog)
        assertFalse(viewModel.state.value.isBusy)

        viewModel.importProject("content://in")
        viewModel.duplicateProject(project)
        settle()
        coVerify(exactly = 1) { repository.importProject("content://in") }
        coVerify(exactly = 1) { repository.duplicateProject(project.id) }

        viewModel.openProject(project)
        settle()
        assertTrue(viewModel.state.value.isOpeningProject)
        coVerify(exactly = 1) { repository.openProject(project.id) }
    }

    /** Opening a project still holds the session, so IMPORT waits for it. */
    @Test
    fun importWaitsWhileAProjectIsOpening() {
        coEvery { repository.openProject(any()) } coAnswers { awaitCancellation() }
        val viewModel = LibraryViewModel(repository, FakeCatalog(loaded = catalogue), DiagnosticsLog(), reporter())
        settle()
        viewModel.openProject(project)
        settle()

        viewModel.importProject("content://in")
        settle()

        assertTrue(viewModel.state.value.isBusy)
        coVerify(exactly = 0) { repository.importProject(any()) }
    }

    /** And a refresh still will not start a second copy of itself. */
    @Test
    fun aRefreshInFlightIsNotStartedTwice() {
        val catalog = FakeCatalog(cached = catalogue.copy(fromCache = true))

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        viewModel.refreshCatalog()
        settle()

        assertTrue(viewModel.state.value.isLoadingCatalog)
        assertEquals(1, catalog.loadCount)
    }

    /** The predicate the grid's cards are enabled on, which leaves out a refresh. */
    @Test
    fun onlyAConflictingOperationClosesTheGridToTaps() {
        val refreshing = LibraryUiState(isLoadingCatalog = true)
        assertFalse(refreshing.isBusy)
        assertTrue(refreshing.canSelectFace)

        assertTrue(LibraryUiState(isLoadingCatalog = false).canSelectFace)
        assertFalse(
            LibraryUiState(isLoadingCatalog = false, openingProjectId = 12L).canSelectFace,
        )
        assertFalse(
            LibraryUiState(isLoadingCatalog = false, downloadingProductId = "p00001")
                .canSelectFace,
        )
    }

    /**
     * The row that was tapped is named, so it can be the one that shows a spinner.
     *
     * A flag was enough for the guards and not enough for the screen: reading the package
     * and parsing the container takes seconds, and with nothing on the row saying the tap
     * had been taken, people tapped it again and then tapped its neighbours.
     */
    @Test
    fun openingAProjectNamesTheProjectBeingOpened() {
        coEvery { repository.openProject(any()) } coAnswers { awaitCancellation() }
        val catalog = FakeCatalog(loaded = catalogue)

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        viewModel.openProject(project)
        settle()

        assertEquals(project.id, viewModel.state.value.openingProjectId)
        assertTrue(viewModel.state.value.isOpeningProject)
    }

    /** And a second row tapped while the first is still being read is refused. */
    @Test
    fun asecondProjectTappedWhileTheFirstIsOpeningIsIgnored() {
        coEvery { repository.openProject(any()) } coAnswers { awaitCancellation() }
        val catalog = FakeCatalog(loaded = catalogue)

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        viewModel.openProject(project)
        settle()
        viewModel.openProject(project.copy(id = 13))
        settle()

        assertEquals(project.id, viewModel.state.value.openingProjectId)
        coVerify(exactly = 1) { repository.openProject(any()) }
        coVerify(exactly = 0) { repository.openProject(13) }
    }

    /**
     * The sheet's own button is under those rows, and it starts an editing session too.
     *
     * It was gated on a download alone, so through the several seconds an open takes it sat
     * there live — and starting a project from it then claims the repository's one session
     * and pushes a second editor onto the back stack behind the first.
     */
    @Test
    fun startingANewProjectWhileOneIsOpeningIsIgnored() {
        coEvery { repository.openProject(any()) } coAnswers { awaitCancellation() }
        val catalog = FakeCatalog(loaded = catalogue)

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        viewModel.selectFace(faces[0])
        viewModel.openProject(project)
        settle()

        viewModel.downloadSelectedFace()
        settle()

        assertNull(viewModel.state.value.downloadingProductId)
        coVerify(exactly = 0) { repository.openPackage(any()) }
    }

    /** A refresh still is not a reason to refuse it. */
    @Test
    fun startingANewProjectDuringACatalogueRefreshIsAllowed() {
        val catalog = FakeCatalog(cached = catalogue.copy(fromCache = true))

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        assertTrue(viewModel.state.value.isLoadingCatalog)
        viewModel.selectFace(faces[0])

        viewModel.downloadSelectedFace()
        settle()

        assertEquals(faces[0].productId, viewModel.state.value.downloadingProductId)
    }

    /**
     * A finished open closes the face sheet, the way a finished download already did, but
     * nothing else can open until the library is shown again.
     *
     * The editor only takes the screen once navigation gets to it. Releasing the rows the
     * moment the open finished left them live in that gap, so a second tap opened a second
     * project: the first editor arrived, and backing out of it landed in the second.
     */
    @Test
    fun aFinishedOpenHoldsEveryOpenUntilTheLibraryIsShownAgain() {
        val opened = CompletableDeferred<EditorSnapshot>()
        coEvery { repository.openProject(project.id) } coAnswers { opened.await() }
        val catalog = FakeCatalog(loaded = catalogue)

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        val events = mutableListOf<LibraryEvent>()
        val collector = CoroutineScope(Dispatchers.Main.immediate).launch {
            viewModel.events.toList(events)
        }
        viewModel.selectFace(faces[0])
        viewModel.openProject(project)
        settle()
        assertEquals(faces[0], viewModel.state.value.selectedFace)
        assertEquals(project.id, viewModel.state.value.openingProjectId)

        opened.complete(mockk<EditorSnapshot>(relaxed = true) { every { projectId } returns 12 })
        settle()

        assertEquals(listOf(LibraryEvent.OpenEditor(12)), events)
        // The sheet stays, spinning, until the editor has the screen: closed earlier, the
        // face grid flashed between the two.
        assertEquals(faces[0], viewModel.state.value.selectedFace)
        assertTrue("nothing is live while the editor is on its way", viewModel.state.value.isBusy)
        assertFalse(viewModel.state.value.canSelectFace)

        // A second tap in the gap is refused: one open, one editor.
        viewModel.openProject(project.copy(id = 13))
        viewModel.startCustomFace("Custom face")
        settle()
        coVerify(exactly = 1) { repository.openProject(any()) }
        assertEquals(listOf(LibraryEvent.OpenEditor(12)), events)

        // A rotation before the editor is on the back stack: the library leaves and comes
        // back, and the hold and the sheet both stay.
        viewModel.onLibraryHidden()
        viewModel.onLibraryShown()
        assertEquals(faces[0], viewModel.state.value.selectedFace)
        assertTrue(viewModel.state.value.isBusy)

        viewModel.onEditorNavigated()
        viewModel.onLibraryHidden()
        assertNull("the sheet goes with the screen", viewModel.state.value.selectedFace)

        // Back from the editor: the row stops spinning and the list is live again.
        viewModel.onLibraryShown()
        assertNull("the row must not be left spinning", viewModel.state.value.openingProjectId)
        assertFalse(viewModel.state.value.isBusy)
        collector.cancel()
    }

    /**
     * Backed out of during the editor's enter transition, the library never leaves
     * composition: it is shown again without having been hidden. That must still end the
     * hand-off and close the sheet, or every row stays locked.
     */
    @Test
    fun backingOutBeforeTheLibraryWasHiddenStillReleasesTheHold() {
        coEvery { repository.openProject(project.id) } returns
            mockk<EditorSnapshot>(relaxed = true) { every { projectId } returns 12 }
        val catalog = FakeCatalog(loaded = catalogue)

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        viewModel.selectFace(faces[0])
        viewModel.openProject(project)
        settle()
        assertTrue(viewModel.state.value.isBusy)

        viewModel.onEditorNavigated()
        viewModel.onLibraryShown()

        assertFalse("the list must be live again", viewModel.state.value.isBusy)
        assertTrue(viewModel.state.value.canSelectFace)
        assertNull("the row must not be left spinning", viewModel.state.value.openingProjectId)
        assertNull("the sheet closes on the way back", viewModel.state.value.selectedFace)
    }

    /** A failure puts the rows back rather than leaving the list inert. */
    @Test
    fun aFailedOpenReleasesTheRows() {
        coEvery { repository.openProject(project.id) } throws
            WatchFaceException("This project's package is missing.", "no local APK")
        val catalog = FakeCatalog(loaded = catalogue)

        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()
        viewModel.openProject(project)
        settle()

        assertNull(viewModel.state.value.openingProjectId)
        assertNotNull(viewModel.state.value.error)
    }

    /**
     * `Dispatchers.Main.immediate` runs a launch from the test thread inline, but only
     * while it does not suspend — anything the ViewModel resumes lands on the main looper,
     * which Robolectric leaves paused. Draining it keeps each assertion reading a settled
     * state rather than a half-applied one.
     */
    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun catalogFace(faceId: String, styleId: Int) = CatalogFace(
        productId = "p$faceId",
        faceId = faceId,
        name = "Face $faceId",
        description = "A face",
        appId = "dev.fitface.face$faceId",
        versionName = "1.0",
        versionCode = 4,
        packageSize = 1_024,
        styles = listOf(FaceStyleOption(id = styleId, previewUrl = "https://example/$faceId.png")),
    )

    /**
     * Holds the two network calls open, which is the state both guards are about.
     *
     * `loadCatalog` completes only when [loaded] was supplied, so a fake built with a
     * [cached] catalogue alone reproduces the launch window: the grid is on screen and the
     * refresh never finishes.
     */
    private class FakeCatalog(
        private val cached: FaceCatalog? = null,
        loaded: FaceCatalog? = null,
        private val uneditable: Set<String> = emptySet(),
        failure: Throwable? = null,
    ) : FaceCatalogRepository {
        private val refresh = CompletableDeferred<FaceCatalog>().apply {
            if (loaded != null) complete(loaded)
            if (failure != null) completeExceptionally(failure)
        }
        private val download = CompletableDeferred<FacePackage>()

        var loadCount = 0
            private set

        override suspend fun cachedCatalog(): FaceCatalog? = cached

        override suspend fun loadCatalog(forceRefresh: Boolean): FaceCatalog {
            loadCount++
            return refresh.await()
        }

        override suspend fun uneditableAppIds(): Set<String> = uneditable

        override suspend fun markUneditable(appId: String) = Unit

        override suspend fun isPackageCached(face: CatalogFace): Boolean = false

        override suspend fun downloadPackage(
            face: CatalogFace,
            styleId: Int,
            onProgress: (DownloadProgress) -> Unit,
        ): FacePackage = download.await()
    }

    private fun reporter(): DiagnosticsReporter = mockk(relaxed = true)

    /**
     * What the screenshot from the field showed: a phone with full signal being told to
     * check its connection, while the reason the store gave was thrown away.
     */
    @Test
    fun aRefusedCatalogueKeepsItsRealReasonAfterTheSnackbarIsGone() {
        val catalog = FakeCatalog(
            failure = WatchFaceException(
                "The watch-face catalogue did not return any faces.",
                "resultCode=1005 message=locale not supported",
            ),
        )
        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()

        val state = viewModel.state.value
        // The snackbar is transient and is cleared as soon as it has been shown; the
        // panel outlives it by minutes, and used to assert a network fault on its own.
        viewModel.clearError(requireNotNull(state.error).id)
        settle()

        assertEquals(
            "The watch-face catalogue did not return any faces.",
            viewModel.state.value.catalogFailure,
        )
        assertNull("the snackbar should have been cleared", viewModel.state.value.error)
    }

    @Test
    fun anEmptyCatalogueIsNotAnnouncedAsALiveOne() {
        // catalogFromCache kept its default through the failure branch, so the header read
        // "LIVE CATALOGUE - 0 faces" directly above a panel saying it was unavailable.
        val catalog = FakeCatalog(failure = WatchFaceException("nope"))
        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()

        assertTrue(viewModel.state.value.faces.isEmpty())
        assertFalse(viewModel.state.value.catalogFromCache)
    }

    @Test
    fun theStoresResultCodeReachesTheReportInsteadOfBeingDropped() {
        // technicalDetail was captured at the throw site and discarded here, which is why
        // a phone that could never load the catalogue had nothing to send but a sentence.
        val diagnostics = DiagnosticsLog()
        val catalog = FakeCatalog(
            failure = WatchFaceException(
                "The watch-face catalogue did not return any faces.",
                "resultCode=1005 message=locale not supported",
            ),
        )
        LibraryViewModel(repository, catalog, diagnostics, reporter())
        settle()

        val recorded = diagnostics.snapshot().single { it.tag == "LibraryViewModel" }
        assertEquals("resultCode=1005 message=locale not supported", recorded.detail)
    }

    @Test
    fun aSuccessfulLoadClearsAnEarlierFailure() {
        val catalog = FakeCatalog(loaded = catalogue)
        val viewModel = LibraryViewModel(repository, catalog, DiagnosticsLog(), reporter())
        settle()

        assertNull(viewModel.state.value.catalogFailure)
    }
}
