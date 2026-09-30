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
        vm.togglePick(3); settle()
        assertEquals(WidgetImportStage.WIDGETS, vm.state.value.stage)
        assertEquals(3, vm.state.value.selectedWidget)
        assertEquals(preview, vm.state.value.preview)
        vm.useSelectedWidget()
        assertEquals(WidgetImportStage.REVIEW, vm.state.value.stage)
        assertFalse(vm.back())
        assertEquals(WidgetImportStage.WIDGETS, vm.state.value.stage)
        assertEquals(preview, vm.state.value.preview)
        coVerify(exactly = 1) { repository.previewWidgetImport("donor", "style0.bin", 3, 7, "style0.bin") }
        // The same tap now takes it back out of the set, which is what a toggle means.
        vm.togglePick(3); settle()
        assertTrue(vm.state.value.picks.isEmpty())
        assertNull(vm.state.value.preview)
    }

    /**
     * A ticket is only meaningful while one widget is chosen.
     *
     * The repository holds exactly one pending import and `previewWidgetImport` clears it on
     * entry, so pricing a second widget would destroy the first one's ticket. Rather than
     * hold a ticket that no longer describes what is on screen, a second pick drops it — and
     * the panel quotes the row estimates, which are a floor, instead.
     */
    @Test fun aSecondPickDropsTheSingleTicketRatherThanHoldingAStaleOne() {
        open()
        val preview = WidgetImportPreview("ticket", 4096, 100, mockk(relaxed = true), frame())
        coEvery { repository.previewWidgetImport("donor", "style0.bin", 3, 7, "style0.bin") } returns preview
        vm.togglePick(3); settle()
        assertEquals(preview, vm.state.value.preview)
        vm.togglePick(4); settle()
        assertEquals(listOf(3, 4), vm.state.value.picks)
        assertNull(vm.state.value.preview)
        assertNull(vm.state.value.selectedWidget)
        coVerify(exactly = 0) { repository.previewWidgetImport("donor", "style0.bin", 4, 7, "style0.bin") }
        // Falling back to one prices that one again, because now it can be priced exactly.
        vm.togglePick(3); settle()
        assertEquals(listOf(4), vm.state.value.picks)
        coVerify(exactly = 1) { repository.previewWidgetImport("donor", "style0.bin", 4, 7, "style0.bin") }
    }

    /** An unavailable widget is refused by the model, not only greyed out by the screen. */
    @Test fun aWidgetTheImporterRefusesIsNeverPriced() {
        coEvery { repository.widgetDonorVariant("donor", "style0.bin") } returns WidgetDonorVariant(
            emptyList(), emptyList(), mapOf(3 to "Full-face backgrounds belong on the Background page."),
            frame(), emptyMap())
        open()
        vm.togglePick(3); settle()
        assertNull(vm.state.value.selectedWidget)
        assertTrue(vm.state.value.picks.isEmpty())
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
        vm.togglePick(3); settle(); vm.add(); vm.add(); settle()
        assertTrue(vm.state.value.saving)
        assertFalse(vm.back())
        commit.complete(snapshot.copy(isDirty = true)); settle()
        assertNotNull(vm.state.value.imported)
        coVerify(exactly = 1) { repository.importWidget("ticket") }
        vm.close(); settle()
        assertEquals(WidgetImportUiState(), vm.state.value)
        coVerify(exactly = 1) { repository.releaseWidgetDonor("donor") }
    }

    // -----------------------------------------------------------------------
    // Adding a set: one at a time, through the path proven on hardware
    // -----------------------------------------------------------------------

    private fun priceAndCommit(index: Int, result: EditorSnapshot) {
        val preview = WidgetImportPreview("t$index", 1000, 100, mockk(relaxed = true), frame())
        coEvery { repository.previewWidgetImport("donor", "style0.bin", index, 7, "style0.bin") } returns preview
        coEvery { repository.importWidget("t$index") } returns result
    }

    /**
     * The whole point of the feature, and the invariant that makes it safe.
     *
     * A batch is a loop and can only be a loop: the repository holds one pending import,
     * `previewWidgetImport` clears it on entry, and `importWidget` validates its ticket
     * against the target container by reference — which every commit replaces. So each
     * widget must be priced immediately before it is committed, in pick order.
     */
    @Test fun aSetIsPricedAndCommittedOneAtATimeInPickOrder() {
        open()
        val after = snapshot.copy(isDirty = true)
        listOf(3, 5, 4).forEach { priceAndCommit(it, after) }
        vm.togglePick(3); vm.togglePick(5); vm.togglePick(4); settle()
        assertEquals(listOf(3, 5, 4), vm.state.value.picks)
        vm.addPicks(); settle()
        assertEquals(WidgetImportStage.BATCH, vm.state.value.stage)
        coVerifyOrder {
            repository.previewWidgetImport("donor", "style0.bin", 3, 7, "style0.bin")
            repository.importWidget("t3")
            repository.previewWidgetImport("donor", "style0.bin", 5, 7, "style0.bin")
            repository.importWidget("t5")
            repository.previewWidgetImport("donor", "style0.bin", 4, 7, "style0.bin")
            repository.importWidget("t4")
        }
        assertEquals(3, vm.state.value.batchAdded)
        assertTrue(vm.state.value.batch.all { it.outcome == WidgetImportOutcome.ADDED })
        // Held back: the report is on screen, and handing the snapshot up closes the screen.
        assertNull(vm.state.value.imported)
        assertEquals(after, vm.state.value.batchSnapshot)
    }

    /**
     * One `run {}` for the whole batch, never one per item.
     *
     * `run` cancels the call before it, so a loop of them would leave every import but the
     * last one cancelled — and the earlier ones have already committed, so the damage would
     * be silent. Three imports reaching the repository is what proves there is one job.
     */
    @Test fun theBatchIsOneJobSoNoImportCancelsTheOneBeforeIt() {
        open()
        listOf(3, 5, 4).forEach { priceAndCommit(it, snapshot.copy(isDirty = true)) }
        vm.togglePick(3); vm.togglePick(5); vm.togglePick(4); settle()
        vm.addPicks(); settle()
        coVerify(exactly = 3) { repository.importWidget(any()) }
        assertNull(vm.state.value.error)
    }

    /**
     * There is no rollback across commits, so the report has to be honest about it.
     *
     * Each import is its own commit. A set that stops at the second leaves the first on the
     * face and saved, and the snapshot it produced is what the editor must be handed.
     */
    @Test fun theBatchStopsAtTheFirstFailureAndKeepsWhatCommitted() {
        open()
        val afterFirst = snapshot.copy(isDirty = true)
        priceAndCommit(3, afterFirst)
        priceAndCommit(5, afterFirst)
        priceAndCommit(4, afterFirst)
        coEvery { repository.importWidget("t5") } throws
            WatchFaceException("No room left: this would take the face past 4 MB.")
        vm.togglePick(3); vm.togglePick(5); vm.togglePick(4); settle()
        vm.addPicks(); settle()
        val batch = vm.state.value.batch
        assertEquals(WidgetImportOutcome.ADDED, batch[0].outcome)
        assertEquals(WidgetImportOutcome.FAILED, batch[1].outcome)
        assertEquals("No room left: this would take the face past 4 MB.", batch[1].reason)
        // Not attempted, not failed: the limits only get tighter, so it stopped.
        assertEquals(WidgetImportOutcome.QUEUED, batch[2].outcome)
        coVerify(exactly = 0) { repository.importWidget("t4") }
        assertEquals(1, vm.state.value.batchAdded)
        assertEquals(afterFirst, vm.state.value.batchSnapshot)
    }

    /** Carrying on past a refusal is offered, and skips the one that refused. */
    @Test fun continuingAfterAFailureAddsOnlyWhatIsStillQueued() {
        open()
        val after = snapshot.copy(isDirty = true)
        listOf(3, 5, 4).forEach { priceAndCommit(it, after) }
        coEvery { repository.importWidget("t5") } throws WatchFaceException("nope")
        vm.togglePick(3); vm.togglePick(5); vm.togglePick(4); settle()
        vm.addPicks(); settle()
        vm.continueBatch(); settle()
        assertEquals(2, vm.state.value.batchAdded)
        coVerify(exactly = 1) { repository.importWidget("t4") }
        // The refusal stands; continuing does not retry it.
        coVerify(exactly = 1) { repository.importWidget("t5") }
        assertEquals(WidgetImportOutcome.FAILED, vm.state.value.batch[1].outcome)
    }

    /**
     * Leaving the report hands the editor what actually committed — by Done or by back.
     *
     * The widgets are saved either way, so an editor left holding the container from before
     * them would be showing a face that no longer exists on disk.
     */
    @Test fun leavingTheReportHandsUpTheCommittedSnapshotWhicheverWayYouLeave() {
        open()
        val after = snapshot.copy(isDirty = true)
        listOf(3, 5).forEach { priceAndCommit(it, after) }
        vm.togglePick(3); vm.togglePick(5); settle()
        vm.addPicks(); settle()
        assertNull(vm.state.value.imported)
        assertFalse(vm.back())
        assertEquals(after, vm.state.value.imported)
    }

    /** A batch that committed nothing has nothing to hand up, and goes back to the picker. */
    @Test fun aBatchThatCommittedNothingReturnsToThePicker() {
        open()
        listOf(3, 5).forEach { priceAndCommit(it, snapshot) }
        coEvery { repository.importWidget("t3") } throws WatchFaceException("nope")
        vm.togglePick(3); vm.togglePick(5); settle()
        vm.addPicks(); settle()
        assertEquals(0, vm.state.value.batchAdded)
        vm.finishBatch()
        assertNull(vm.state.value.imported)
        assertEquals(WidgetImportStage.WIDGETS, vm.state.value.stage)
    }

    /**
     * A set gets the same review a single widget gets, and nothing is priced to show it.
     *
     * There is no ticket to wait for: each widget of a set is priced immediately before its
     * own commit. So turning to the review costs no repository call, and the way back keeps
     * the picks for the reader to change.
     */
    @Test fun aSetIsReviewedBeforeItIsAddedAndNothingIsPricedToShowIt() {
        open()
        val after = snapshot.copy(isDirty = true)
        listOf(3, 5).forEach { priceAndCommit(it, after) }
        vm.togglePick(3); vm.togglePick(5); settle()
        vm.useSelectedWidget()
        assertEquals(WidgetImportStage.REVIEW, vm.state.value.stage)
        coVerify(exactly = 0) { repository.previewWidgetImport("donor", "style0.bin", 5, 7, "style0.bin") }
        coVerify(exactly = 0) { repository.importWidget(any()) }
        assertFalse(vm.back())
        assertEquals(WidgetImportStage.WIDGETS, vm.state.value.stage)
        assertEquals(listOf(3, 5), vm.state.value.picks)
        vm.useSelectedWidget()
        vm.addPicks(); settle()
        assertEquals(WidgetImportStage.BATCH, vm.state.value.stage)
        assertEquals(2, vm.state.value.batchAdded)
    }

    /**
     * A pick that cannot be priced says so in the panel, and the banner stays empty.
     *
     * The banner is pinned above the page. A ceiling refusal landing there pushed the donor
     * face down by four lines and stayed for every pick after, describing a widget that was
     * no longer the one chosen — which is how five picks shrank the face to a dot.
     */
    @Test fun aPickThatCannotBePricedSaysSoInThePanelAndTheNextPickClearsIt() {
        open()
        coEvery { repository.previewWidgetImport("donor", "style0.bin", 3, 7, "style0.bin") } throws
            WatchFaceException("the edit would make this container too big")
        vm.togglePick(3); settle()
        assertEquals("the edit would make this container too big", vm.state.value.pickError)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.preview)
        assertFalse(vm.state.value.busy)
        // Nothing to review: there is no ticket.
        vm.useSelectedWidget()
        assertEquals(WidgetImportStage.WIDGETS, vm.state.value.stage)
        vm.togglePick(4); settle()
        assertNull(vm.state.value.pickError)
        assertNull(vm.state.value.error)
    }

    // -----------------------------------------------------------------------
    // Switching variant without the page emptying
    // -----------------------------------------------------------------------

    private val v1 = EditorVariant("style1.bin", VariantKind.STYLE, 1)
    private val v2 = EditorVariant("style2.bin", VariantKind.STYLE, 2)
    private fun content(n: Int) = WidgetDonorVariant(emptyList(), emptyList(), emptyMap(),
        PreviewFrame(1, 1, intArrayOf(n)), emptyMap())

    private fun openWithThreeVariants() {
        coEvery { repository.inspectWidgetDonor(any()) } returns WidgetDonor("donor", "00008", listOf(variant, v1, v2))
        coEvery { repository.widgetDonorVariant("donor", "style0.bin") } returns content(0)
        open()
    }

    /**
     * The flicker: a chip tap used to empty the page for the fraction of a second a variant
     * takes to read, while the pinned strip flashed above it. The old face stays now, and
     * nothing on it can be picked, because its indices belong to the other variant.
     */
    @Test fun switchingVariantKeepsTheFaceOnScreenUntilTheNewOneArrives() {
        openWithThreeVariants()
        val next = CompletableDeferred<WidgetDonorVariant>()
        coEvery { repository.widgetDonorVariant("donor", "style1.bin") } coAnswers { next.await() }
        vm.selectVariant(v1); settle()
        with(vm.state.value) {
            assertEquals(content(0).composed.argb.toList(), content!!.composed.argb.toList())
            assertEquals(v1, variant)
            assertTrue(variantLoading)
        }
        vm.togglePick(3); settle()
        assertTrue(vm.state.value.picks.isEmpty())
        next.complete(content(1)); settle()
        with(vm.state.value) {
            assertEquals(listOf(1), content!!.composed.argb.toList())
            assertEquals(v1, contentVariant)
            assertFalse(variantLoading)
        }
    }

    /** A second tap while one loads is taken, not refused — which is why the chips need not dim. */
    @Test fun aSecondSwitchSupersedesTheFirst() {
        openWithThreeVariants()
        coEvery { repository.widgetDonorVariant("donor", "style1.bin") } coAnswers { awaitCancellation() }
        coEvery { repository.widgetDonorVariant("donor", "style2.bin") } returns content(2)
        vm.selectVariant(v1); settle()
        vm.selectVariant(v2); settle()
        assertEquals(v2, vm.state.value.variant)
        assertEquals(v2, vm.state.value.contentVariant)
        assertEquals(listOf(2), vm.state.value.content!!.composed.argb.toList())
        assertFalse(vm.state.value.variantLoading)
    }

    /** Backing out of a switch puts the chip back on the face that is still showing. */
    @Test fun backingOutOfASwitchPutsTheChipBack() {
        openWithThreeVariants()
        coEvery { repository.widgetDonorVariant("donor", "style1.bin") } coAnswers { awaitCancellation() }
        vm.selectVariant(v1); settle()
        assertFalse(vm.back()); settle()
        assertEquals(variant, vm.state.value.variant)
        assertFalse(vm.state.value.variantLoading)
        assertEquals(listOf(0), vm.state.value.content!!.composed.argb.toList())
    }

    /** A variant that cannot be read leaves nothing stale on screen: the retry state, and why. */
    @Test fun aSwitchThatFailsFallsToRetry() {
        openWithThreeVariants()
        coEvery { repository.widgetDonorVariant("donor", "style1.bin") } throws WatchFaceException("unreadable")
        vm.selectVariant(v1); settle()
        assertNull(vm.state.value.content)
        assertFalse(vm.state.value.variantLoading)
        assertEquals("unreadable", vm.state.value.error)
    }

    /** One pick is the single flow, untouched: no batch, no report. */
    @Test fun onePickNeverStartsABatch() {
        open()
        priceAndCommit(3, snapshot)
        vm.togglePick(3); settle()
        vm.addPicks(); settle()
        assertEquals(WidgetImportStage.WIDGETS, vm.state.value.stage)
        assertTrue(vm.state.value.batch.isEmpty())
        coVerify(exactly = 0) { repository.importWidget(any()) }
    }
}
