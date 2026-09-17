package dev.fitface.studio.feature.editor

import dev.fitface.studio.core.model.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val catalog = mockk<FaceCatalogRepository>(relaxed = true)
    private val repository = mockk<WatchFaceRepository>(relaxed = true)
    private val face = CatalogFace("one", "00008", "Donor", "", "app.one", "1", 1, 100,
        listOf(FaceStyleOption(0, "https://example.invalid/preview")))
    private val variant = EditorVariant("style0.bin", VariantKind.STYLE, 0)
    private val donor = WidgetDonor("donor", "00008", listOf(variant))
    private val snapshot = EditorSnapshot(projectId = 7, faceId = "00106", faceName = "Target", sourceName = "face.apk",
        styleNames = listOf("style0.bin"), activeStyleName = "style0.bin",
        preview = frame(), composedPreview = frame(), widgetOverlay = frame(), widgetImageLayers = emptyList(),
        widgets = emptyList(), imageCount = 0, validationErrors = emptyList(), validationWarnings = emptyList(),
        isDirty = false, audit = null)
    private lateinit var vm: WidgetImportViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { catalog.cachedCatalog() } returns FaceCatalog(listOf(face), 1)
        coEvery { catalog.uneditableAppIds() } returns emptySet()
        coEvery { catalog.isPackageCached(face) } returns true
        coEvery { catalog.downloadPackage(face, 0, any()) } returns FacePackage(
            FacePackage.sourceKey("one", 1, 0), "donor.apk", "00008", 0, 1, byteArrayOf(1))
        coEvery { repository.inspectWidgetDonor(any()) } returns donor
        coEvery { repository.widgetDonorVariant("donor", "style0.bin") } returns WidgetDonorVariant(
        emptyList(), emptyList(), emptyMap(), frame(), emptyMap())
        vm = WidgetImportViewModel(catalog, repository, DiagnosticsLog())
        vm.start(snapshot); settle()
    }
    @After fun cleanup() { vm.close(); settle(); Dispatchers.resetMain() }
    private fun settle() = scope.advanceUntilIdle()
    private fun open() { vm.openFace(face); settle() }
    private fun frame() = PreviewFrame(1, 1, intArrayOf(0))

    @Test fun cachedPackageUsesTheSameDownloadCacheApiAndNeverOpensAProject() {
        open()
        assertTrue(vm.state.value.selectedFaceCached)
        assertEquals(WidgetImportStage.WIDGETS, vm.state.value.stage)
        coVerify(exactly = 1) { catalog.downloadPackage(face, 0, any()) }
        coVerify(exactly = 0) { repository.openPackage(any()) }
        coVerify(exactly = 0) { repository.openProject(any()) }
    }

    @Test fun downloadCancellationDoesNotBecomeAFailureOrPublishLateProgress() {
        var progress: ((DownloadProgress) -> Unit)? = null
        coEvery { catalog.downloadPackage(face, 0, any()) } coAnswers {
            progress = thirdArg()
            awaitCancellation()
        }
        vm.openFace(face); settle()
        assertTrue(vm.state.value.busy)
        assertFalse(vm.back())
        settle()
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
        assertTrue(runCatching { progress!!(DownloadProgress(90, 100)) }.exceptionOrNull() is CancellationException)
        assertNull(vm.state.value.progress)
        coVerify(exactly = 0) { repository.inspectWidgetDonor(any()) }
    }

    @Test fun failureStaysVisibleAndRetryKeepsTheChosenFace() {
        coEvery { catalog.downloadPackage(face, 0, any()) } throws WatchFaceException("No connection")
        open()
        assertEquals("No connection", vm.state.value.error)
        assertEquals(face, vm.state.value.selectedFace)
        assertFalse(vm.state.value.busy)
        coEvery { catalog.downloadPackage(face, 0, any()) } returns FacePackage(
            FacePackage.sourceKey("one", 1, 0), "donor.apk", "00008", 0, 1, byteArrayOf(1))
        vm.openFace(face); settle()
        assertNull(vm.state.value.error)
        assertEquals(WidgetImportStage.WIDGETS, vm.state.value.stage)
    }

    @Test fun recompositionDoesNotRestartTheDownloadOrForgetTheDonor() {
        coEvery { catalog.isPackageCached(face) } returns false
        vm.loadCatalog(); settle()
        assertTrue(vm.state.value.cachedFaces.isEmpty())
        open()
        vm.start(snapshot); settle()
        assertEquals(donor, vm.state.value.donor)
        coVerify(exactly = 1) { catalog.downloadPackage(face, 0, any()) }
        assertFalse(vm.back())
        assertTrue(vm.state.value.selectedFaceCached)
    }

    @Test fun uneditablePackagesAreRememberedAndNotRetried() {
        coEvery { catalog.downloadPackage(face, 0, any()) } throws WatchFaceException(
            "Not editable", isUneditablePackage = true)
        open()
        assertTrue(face.appId in vm.state.value.uneditable)
        vm.openFace(face); settle()
        coVerify(exactly = 1) { catalog.downloadPackage(face, 0, any()) }
        coVerify(exactly = 1) { catalog.markUneditable(face.appId) }
    }

    /**
     * The panel under the donor face quotes the exact cost, so the edit is built when the
     * widget is picked — and the way back from the review must not throw that away and
     * rebuild the same edit on the next tap.
     */
    @Test fun pickingAWidgetPricesItInPlaceAndTheReviewIsAPageTurn() {
        open()
        val preview = WidgetImportPreview("ticket", 4096, 100, mockk(relaxed = true), frame())
        coEvery { repository.previewWidgetImport("donor", "style0.bin", 3, 7, "style0.bin") } returns preview
        vm.selectWidget(3); settle()
        assertEquals(WidgetImportStage.WIDGETS, vm.state.value.stage)
        assertEquals(3, vm.state.value.selectedWidget)
        assertEquals(preview, vm.state.value.preview)
        vm.useSelectedWidget()
        assertEquals(WidgetImportStage.REVIEW, vm.state.value.stage)
        assertFalse(vm.back())
        assertEquals(WidgetImportStage.WIDGETS, vm.state.value.stage)
        assertEquals(preview, vm.state.value.preview)
        vm.selectWidget(3); settle()
        coVerify(exactly = 1) { repository.previewWidgetImport("donor", "style0.bin", 3, 7, "style0.bin") }
    }

    /** An unavailable widget is refused by the model, not only greyed out by the screen. */
    @Test fun aWidgetTheImporterRefusesIsNeverPriced() {
        coEvery { repository.widgetDonorVariant("donor", "style0.bin") } returns WidgetDonorVariant(
            emptyList(), emptyList(), mapOf(3 to "Full-face backgrounds belong on the Background page."),
            frame(), emptyMap())
        open()
        vm.selectWidget(3); settle()
        assertNull(vm.state.value.selectedWidget)
        coVerify(exactly = 0) { repository.previewWidgetImport(any(), any(), any(), any(), any()) }
    }

    /**
     * Every face is offered whether or not its package is here; the cache decides what a
     * card *says*, never whether it is listed.
     */
    @Test fun theWholeCatalogueIsListedAndOnlyTheSearchNarrowsIt() {
        coEvery { catalog.isPackageCached(face) } returns false
        vm.loadCatalog(); settle()
        assertEquals(listOf(face), vm.state.value.visibleFaces)
        assertTrue(vm.state.value.cachedFaces.isEmpty())
        vm.setQuery("00008")
        assertEquals(listOf(face), vm.state.value.visibleFaces)
        vm.setQuery("nothing here")
        assertTrue(vm.state.value.visibleFaces.isEmpty())
    }

    @Test fun addIsSingleFlightAndCannotBeDismissedMidCommit() {
        open()
        val preview = WidgetImportPreview("ticket", 1, 100, mockk(relaxed = true), frame())
        coEvery { repository.previewWidgetImport("donor", "style0.bin", 3, 7, "style0.bin") } returns preview
        val commit = CompletableDeferred<EditorSnapshot>()
        coEvery { repository.importWidget("ticket") } coAnswers { commit.await() }
        vm.selectWidget(3); settle(); vm.add(); vm.add(); settle()
        assertTrue(vm.state.value.saving)
        assertFalse(vm.back())
        commit.complete(snapshot.copy(isDirty = true)); settle()
        assertNotNull(vm.state.value.imported)
        coVerify(exactly = 1) { repository.importWidget("ticket") }
        vm.close(); settle()
        assertEquals(WidgetImportUiState(), vm.state.value)
        coVerify(exactly = 1) { repository.releaseWidgetDonor("donor") }
    }
}
