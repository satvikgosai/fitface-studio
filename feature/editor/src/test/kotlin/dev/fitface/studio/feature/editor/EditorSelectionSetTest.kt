package dev.fitface.studio.feature.editor

import dev.fitface.studio.core.data.DiagnosticsReporter
import dev.fitface.studio.core.delivery.DirectInstallState
import dev.fitface.studio.core.delivery.Fit3DirectInstaller
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.EditAuditSummary
import dev.fitface.studio.core.model.EditorVariant
import dev.fitface.studio.core.model.VariantKind
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.WatchFaceRepository
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WidgetImageLayer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Several widgets selected at once, and the three things done to all of them.
 *
 * Nothing below the ViewModel knows a set exists: every action is the single-widget call,
 * made once per member, the way importing a set is a loop over the single import. What
 * these tests pin is the part the loop decides — which order, what stays selected, and
 * what is said when it stops partway — against a fake that renumbers on removal exactly as
 * the container does, because that renumbering is the reason for the order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorSelectionSetTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Before fun installDispatcher() = Dispatchers.setMain(dispatcher)
    @After fun restoreDispatcher() = Dispatchers.resetMain()

    private val installer = mockk<Fit3DirectInstaller>(relaxed = true) {
        every { state } returns MutableStateFlow(DirectInstallState())
    }

    private fun settle() = scope.advanceUntilIdle()

    @Test fun styleDeletionKeepsPendingPhotoPlacementAndSelectionOnTheSamePristineVariant() {
        val variants = (0..2).map { EditorVariant("style$it.bin", VariantKind.STYLE, it) }
        val original = snapshot(listOf(widget(1, x = 20, y = 20))).copy(
            styleNames = variants.map { it.basename }, variants = variants, selectedVariant = variants.last(),
            activeStyleName = "style2.bin", originalVariants = variants.associate { it.basename to it.basename })
        val repository = FakeRepository(original)
        val vm = EditorViewModel(repository, installer, DiagnosticsLog(), mockk(relaxed = true))
        vm.loadProject(1); settle(); vm.selectWidget(1)
        vm.prepareBackground("content://test/photo"); settle(); vm.transformImage(1.2f, .1f, .2f)
        vm.markPreviewReviewed()
        val pending = vm.state.value.pendingImage
        val placement = vm.state.value.placement
        val after = original.copy(styleNames = listOf("style0.bin"), variants = listOf(variants.first()),
            selectedVariant = variants.first(), activeStyleName = "style0.bin",
            originalVariants = mapOf("style0.bin" to "style2.bin"), isDirty = true)
        vm.acceptStyleDeletion(after)
        assertEquals(pending, vm.state.value.pendingImage)
        assertEquals(placement, vm.state.value.placement)
        assertEquals(1, vm.state.value.selectedWidgetIndex)
        assertFalse(vm.state.value.previewReviewed)
        vm.acceptStyleDeletion(after.copy(originalVariants = mapOf("style0.bin" to "style1.bin")))
        assertNull(vm.state.value.selectedWidgetIndex)
    }

    private fun opened(widgets: List<WidgetGuide> = listOf(
        widget(1, x = 20, y = 20), widget(2, x = 60, y = 120), widget(3, x = 100, y = 220),
    )): Pair<EditorViewModel, FakeRepository> {
        val repository = FakeRepository(snapshot(widgets))
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), mockk<DiagnosticsReporter>(relaxed = true))
        viewModel.loadProject(1)
        settle()
        return viewModel to repository
    }

    @Test fun arrangingKeepsTheMovedWidgetSelectedClearsReviewAndStopsAtBoundaries() {
        val (vm, repo) = opened(listOf(widget(0,0,0).copy(placement = dev.fitface.studio.core.model.WidgetPlacement.BACKGROUND),
            widget(1,20,20), widget(2,60,120), widget(3,100,220)))
        vm.selectWidget(1); vm.setApplyWidgetEditsToAllStyles(true); vm.markPreviewReviewed()
        vm.arrangeSelectedWidget(dev.fitface.studio.core.model.WidgetArrangement.FRONT); settle()
        assertEquals(3, vm.state.value.selectedWidgetIndex)
        assertEquals(20, vm.state.value.snapshot!!.widgets.last().x)
        assertFalse(vm.state.value.previewReviewed)
        assertEquals(listOf(1 to 3), repo.reorders)
        vm.arrangeSelectedWidget(dev.fitface.studio.core.model.WidgetArrangement.FRONT); settle()
        assertEquals(1, repo.reorders.size)
        vm.arrangeSelectedWidget(dev.fitface.studio.core.model.WidgetArrangement.BACK); settle()
        assertEquals(1, vm.state.value.selectedWidgetIndex)
        vm.beginSelection(1); vm.arrangeSelectedWidget(dev.fitface.studio.core.model.WidgetArrangement.FORWARD); settle()
        assertEquals(2, repo.reorders.size)
        vm.finishSelection(); vm.selectWidget(0)
        vm.arrangeSelectedWidget(dev.fitface.studio.core.model.WidgetArrangement.FRONT); settle()
        assertEquals(2, repo.reorders.size)
    }

    @Test fun refusedArrangementKeepsSelectionAndTheCanvasAndReportsTheReason() {
        val (vm, repo) = opened()
        vm.selectWidget(1); repo.failReorder = true
        val before = vm.state.value.snapshot
        vm.arrangeSelectedWidget(dev.fitface.studio.core.model.WidgetArrangement.FRONT); settle()
        assertEquals(before, vm.state.value.snapshot)
        assertEquals(1, vm.state.value.selectedWidgetIndex)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.isWorking)
    }

    @Test fun rotationKeepsSelectionClearsReviewAndHonoursScopeAndNoOp() {
        val (vm, repo) = opened(listOf(widget(1, 20, 20).copy(type = 13, rotationTenths = 0)))
        vm.selectWidget(1)
        vm.rotateSelectedWidget(3600); settle()
        assertTrue(repo.rotations.isEmpty())
        vm.markPreviewReviewed()
        assertTrue(vm.state.value.previewReviewed)
        vm.rotateSelectedWidget(-421); settle()
        assertEquals(listOf(3179), repo.rotations)
        assertEquals(1, vm.state.value.selectedWidgetIndex)
        assertFalse(vm.state.value.previewReviewed)
        assertFalse(vm.state.value.isWorking)
        vm.beginSelection(1); vm.rotateSelectedWidget(900); settle()
        assertEquals(listOf(3179), repo.rotations)
    }

    @Test fun unsupportedWidgetsDoNotSendRotationEdits() {
        val (vm, repo) = opened()
        vm.selectWidget(1); vm.rotateSelectedWidget(900); settle()
        assertTrue(repo.rotations.isEmpty())
    }

    // -- choosing a set -------------------------------------------------------

    @Test fun firstHoldStartsSelectionAndAnotherTapAddsAWidget() {
        val (vm, _) = opened()
        vm.beginSelection(1)
        assertEquals(listOf(1), vm.state.value.multiSelection)
        assertNull(vm.state.value.selectedWidgetIndex)
        vm.selectWidget(2)
        assertEquals(listOf(1, 2), vm.state.value.multiSelection)
        vm.selectWidget(1)
        assertEquals(listOf(2), vm.state.value.multiSelection)
        vm.selectWidget(3)
        assertEquals(listOf(2, 3), vm.state.value.multiSelection)
    }

    @Test fun holdingTheCurrentWidgetStartsModeAndRepeatedHoldsKeepItSelected() {
        val (vm, _) = opened()
        vm.selectWidget(1)
        vm.beginSelection(1)
        vm.beginSelection(1)
        assertEquals(listOf(1), vm.state.value.multiSelection)
        assertNull(vm.state.value.selectedWidgetIndex)
        vm.beginSelection(2)
        assertEquals(listOf(1, 2), vm.state.value.multiSelection)
        vm.toggleInSelection(1)
        assertEquals(listOf(2), vm.state.value.multiSelection)
        vm.toggleInSelection(2)
        assertTrue(vm.state.value.multiSelection.isEmpty())
        assertNull(vm.state.value.selectedWidgetIndex)
    }

    @Test fun doneReturnsASingletonToOrdinaryEditingAndClearDropsIt() {
        val (vm, _) = opened()
        vm.beginSelection(3)
        vm.finishSelection()
        assertEquals(3, vm.state.value.selectedWidgetIndex)
        assertTrue(vm.state.value.multiSelection.isEmpty())
        vm.beginSelection(3)
        vm.clearSelection()
        assertNull(vm.state.value.selectedWidgetIndex)
        assertTrue(vm.state.value.multiSelection.isEmpty())
        vm.beginSelection(999)
        assertTrue(vm.state.value.multiSelection.isEmpty())
    }

    @Test fun singletonSetCanNudgeDuplicateAndRemove() {
        val (vm, repository) = opened()
        vm.beginSelection(1)
        vm.nudgeSelection(1, 0)
        settle()
        assertEquals(listOf(21 to 20), repository.moves[1])
        assertEquals(listOf(1), vm.state.value.multiSelection)
        vm.duplicateSelection()
        settle()
        assertEquals(listOf(1), repository.duplicated)
        assertEquals(listOf(4), vm.state.value.multiSelection)
        vm.removeSelection()
        settle()
        assertEquals(listOf(4), repository.removed)
        assertTrue(vm.state.value.multiSelection.isEmpty())
        assertNull(vm.state.value.selectedWidgetIndex)
    }

    /** With a set picked, a tap toggles and bare canvas lets go — the import picker's gestures. */
    @Test fun aTapWithASetPickedTogglesAndBareCanvasLetsGo() {
        val (vm, _) = opened()
        vm.selectWidget(1)
        vm.toggleInSelection(2)
        vm.selectWidget(3)
        assertEquals(listOf(1, 2, 3), vm.state.value.multiSelection)
        vm.selectWidget(2)
        assertEquals(listOf(1, 3), vm.state.value.multiSelection)
        vm.selectWidget(null)
        assertTrue(vm.state.value.multiSelection.isEmpty())
        assertNull(vm.state.value.selectedWidgetIndex)
    }

    @Test fun singletonSelectionClearsOnVariantChangeAndReset() {
        val (vm, _) = opened()
        vm.beginSelection(1)
        vm.selectVariant(EditorVariant("aod.bin", VariantKind.AOD))
        settle()
        assertTrue(vm.state.value.multiSelection.isEmpty())
        assertNull(vm.state.value.selectedWidgetIndex)
        vm.beginSelection(2)
        vm.reset()
        settle()
        assertTrue(vm.state.value.multiSelection.isEmpty())
        assertNull(vm.state.value.selectedWidgetIndex)
    }

    @Test fun selectionDoesNotChangeWhileASingletonDuplicateIsSaving() {
        val (vm, repository) = opened()
        repository.parkedDuplicate = CompletableDeferred()
        vm.beginSelection(1)
        vm.duplicateSelection()
        settle()
        assertTrue(vm.state.value.isWorking)
        vm.beginSelection(2)
        vm.toggleInSelection(2)
        vm.finishSelection()
        assertEquals(listOf(1), vm.state.value.multiSelection)
        repository.parkedDuplicate!!.complete(Unit)
        settle()
        assertEquals(listOf(4), vm.state.value.multiSelection)
        assertFalse(vm.state.value.isWorking)
    }

    // -- removing -----------------------------------------------------------------

    /**
     * Highest index first. Removing a widget renumbers the ones after it, so removing in pick
     * order would have removed #1 and then found the widget picked as #3 answering to #2 —
     * the fake refuses exactly that, by checking each call's identity against what is there.
     */
    @Test fun removalGoesHighestIndexFirstSoEveryIndexStillNamesItsWidget() {
        val (vm, repository) = opened()
        vm.selectWidget(1)
        vm.toggleInSelection(3)
        vm.toggleInSelection(2)
        vm.removeSelection()
        settle()
        assertEquals(listOf(3, 2, 1), repository.removed)
        assertTrue(vm.state.value.snapshot!!.widgets.isEmpty())
        assertTrue(vm.state.value.multiSelection.isEmpty())
        assertEquals(3, vm.state.value.widgetRemovals)
        assertNull(vm.state.value.selectionStopped)
    }

    /**
     * There is no rollback across commits. A set that stops at the second keeps the first,
     * publishes the snapshot that produced, and says how far it got; what was not removed
     * stays selected, still under its own index.
     */
    @Test fun aSetEditThatStopsKeepsWhatCommittedAndSaysHowFar() {
        val (vm, repository) = opened()
        repository.failOnCall = 2
        vm.selectWidget(1)
        vm.toggleInSelection(2)
        vm.toggleInSelection(3)
        vm.removeSelection()
        settle()
        assertEquals(listOf(1, 2), vm.state.value.snapshot!!.widgets.map { it.globalIndex })
        val stopped = assertNotNullAndGet(vm.state.value.selectionStopped)
        assertEquals(SelectionAction.REMOVE, stopped.action)
        assertEquals(1, stopped.done)
        assertEquals(3, stopped.total)
        assertEquals("refused", stopped.reason)
        assertEquals(listOf(1, 2), vm.state.value.multiSelection)
        assertEquals(1, vm.state.value.widgetRemovals)
    }

    /** A refusal before anything committed is an ordinary error: nothing changed to report. */
    @Test fun aFirstRefusalIsAnOrdinaryErrorAndChangesNothing() {
        val (vm, repository) = opened()
        repository.failOnCall = 1
        vm.selectWidget(1)
        vm.toggleInSelection(2)
        vm.removeSelection()
        settle()
        assertNull(vm.state.value.selectionStopped)
        assertEquals("refused", vm.state.value.error?.text)
        assertEquals(3, vm.state.value.snapshot!!.widgets.size)
        assertEquals(listOf(1, 2), vm.state.value.multiSelection)
    }

    // -- duplicating ------------------------------------------------------------

    /** Copies are made in pick order — the order they stack — and become the selection. */
    @Test fun aSetDuplicatesInPickOrderAndSelectsTheCopies() {
        val (vm, repository) = opened()
        vm.selectWidget(3)
        vm.toggleInSelection(1)
        vm.duplicateSelection()
        settle()
        assertEquals(listOf(3, 1), repository.duplicated)
        assertEquals(listOf(4, 5), vm.state.value.multiSelection)
        val copies = vm.state.value.snapshot!!.widgets.filter { it.globalIndex >= 4 }
        assertEquals(listOf(3, 1), copies.map { it.sequenceId })
    }

    // -- moving -----------------------------------------------------------------

    /** Every member takes the same step, each through the ordinary per-widget move queue. */
    @Test fun theSetMovesTogether() {
        val (vm, repository) = opened()
        vm.selectWidget(1)
        vm.toggleInSelection(2)
        vm.nudgeSelection(1, 0)
        settle()
        vm.nudgeSelection(0, 2)
        settle()
        assertEquals(listOf(21 to 20, 21 to 22), repository.moves.getValue(1))
        assertEquals(listOf(61 to 120, 61 to 122), repository.moves.getValue(2))
    }

    /**
     * As far as the most constrained member can go. A widget flush with the right edge
     * cannot step right, so neither does the set — clamping each on its own would have let
     * the other carry on and sheared the arrangement.
     */
    @Test fun aMemberAtTheEdgeHoldsTheWholeSet() {
        val (vm, repository) = opened(listOf(
            widget(1, x = 20, y = 20), widget(2, x = PanelWidth - 40, y = 120),
        ))
        vm.selectWidget(1)
        vm.toggleInSelection(2)
        vm.nudgeSelection(5, 0)
        settle()
        assertTrue(repository.moves.isEmpty())
        vm.nudgeSelection(-5, 0)
        settle()
        assertEquals(listOf(15 to 20), repository.moves.getValue(1))
        assertEquals(listOf(PanelWidth - 45 to 120), repository.moves.getValue(2))
    }

    /**
     * The whole set is previewed at once, on the step, whatever order its commits land in.
     *
     * Commits are one widget each and slower than a held arrow repeats. With only the single
     * move preview, one member glided and the rest jumped as their commits landed, so a set
     * stuttered across the face one widget at a time. This parks the commits to hold that
     * window open: every member is already at its target in the preview, none of them in the
     * single-widget preview, and the preview only lets go once the last commit is in.
     */
    @Test fun aSetBeingNudgedIsPreviewedTogetherUntilTheLastCommitLands() {
        val (vm, repository) = opened()
        repository.parked = CompletableDeferred()
        vm.selectWidget(1)
        vm.toggleInSelection(2)
        vm.nudgeSelection(4, 0)
        settle()
        vm.nudgeSelection(4, 0)
        settle()
        assertNull(vm.state.value.pendingWidgetMove)
        assertEquals(
            listOf(1 to 28f, 2 to 68f),
            vm.state.value.pendingSetMove.map { it.globalIndex to it.displayX },
        )
        // Only one commit has started; the others are still queued behind it.
        assertEquals(1, repository.moves.values.sumOf { it.size })
        repository.parked!!.complete(Unit)
        settle()
        assertTrue(vm.state.value.pendingSetMove.isEmpty())
        assertEquals(listOf(28, 68), vm.state.value.snapshot!!.widgets.take(2).map { it.x })
    }

    @Test fun theLayerStackIsCutAtEveryMovingMemberInRecordOrder() {
        fun layer(index: Int) = WidgetImageLayer(index, PreviewFrame(1, 1, intArrayOf(0)))
        val layers = (1..6).map(::layer)
        val segments = setMoveSegments(layers, setOf(2, 3, 5))
        assertEquals(
            listOf("still[1]", "move 2", "move 3", "still[4]", "move 5", "still[6]"),
            segments.map {
                when (it) {
                    is SetMoveSegment.Still -> "still${it.layers.map { l -> l.globalIndex }}"
                    is SetMoveSegment.Moving -> "move ${it.layer.globalIndex}"
                }
            },
        )
        // The first still run is kept even empty: it carries the black the face is drawn on.
        val first = setMoveSegments(layers, setOf(1))
        assertTrue(first.first() is SetMoveSegment.Still)
        assertTrue((first.first() as SetMoveSegment.Still).layers.isEmpty())
        assertEquals(3, first.size)
    }

    /**
     * Off by default. Every style carries its own artwork, so an edit that grows it costs its
     * bytes once per style it reaches — four times the room on a four-style face.
     */
    @Test fun anEditReachesOnlyTheStyleOnScreenUntilAskedOtherwise() {
        val (vm, repository) = opened()
        assertFalse(vm.state.value.applyWidgetEditsToAllStyles)
        vm.nudgeWidget(1, 1, 0)
        settle()
        assertEquals(listOf(false), repository.moveScopes)
    }

    @Test fun theSharedStepIsTheSmallestInTheWantedDirection() {
        assertEquals(3, sharedNudgeStep(listOf(5, 3, 4), 5))
        assertEquals(0, sharedNudgeStep(listOf(5, 0), 5))
        assertEquals(-2, sharedNudgeStep(listOf(-4, -2), -4))
        assertEquals(0, sharedNudgeStep(listOf(-4, 0), -4))
        assertEquals(0, sharedNudgeStep(emptyList(), 3))
    }

    // -- harness ------------------------------------------------------------------

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun widget(globalIndex: Int, x: Int, y: Int) = WidgetGuide(
        ordinal = globalIndex, globalIndex = globalIndex, type = 3, sequenceId = globalIndex,
        x = x, y = y, width = 40, height = 40, recordSize = 40, isFinal = false,
        canEditPosition = true, colorArgb = null, supportMessage = "",
    )

    private fun snapshot(widgets: List<WidgetGuide>) = EditorSnapshot(
        projectId = 1, faceId = "00001", faceName = "Face", sourceName = "Face.apk",
        styleNames = listOf("style0.bin"), activeStyleName = "style0.bin",
        preview = frame(), composedPreview = frame(), widgetOverlay = frame(),
        widgetImageLayers = emptyList(), widgets = widgets, imageCount = 1,
        validationErrors = emptyList(), validationWarnings = emptyList(), isDirty = false,
        audit = EditAuditSummary(changedPayloadBytes = 0, changedStyles = emptyList()),
    )

    private fun frame() = PreviewFrame(PanelWidth, PanelHeight, IntArray(PanelWidth * PanelHeight))

    /**
     * Commits at once, renumbering on removal and appending on duplication as a container
     * does — and checking each call's identity, so a stale index is a failure rather than
     * a silently removed neighbour.
     */
    private class FakeRepository(private var current: EditorSnapshot) :
        WatchFaceRepository by mockk(relaxed = true) {
        override suspend fun prepareReplacementImage(imageUri: String) =
            dev.fitface.studio.core.model.ReplacementImage(imageUri, current.preview)
        val reorders = mutableListOf<Pair<Int, Int>>()
        var failReorder = false
        override suspend fun reorderWidget(styleName: String, globalIndex: Int, widgetType: Int, sequenceId: Int,
            x: Int, y: Int, destination: Int): EditorSnapshot {
            if (failReorder) throw WatchFaceException("Alignment would change")
            identify(globalIndex, sequenceId)
            reorders += globalIndex to destination
            val widgets = current.widgets.toMutableList()
            val widget = widgets.single { it.globalIndex == globalIndex }
            widgets.remove(widget); widgets.add(destination, widget)
            current = current.copy(widgets = widgets.mapIndexed { index, it -> it.copy(globalIndex = index, ordinal = index) }, isDirty = true)
            return current
        }
        val rotations = mutableListOf<Int>()
        override suspend fun rotateWidget(styleName: String, globalIndex: Int, sequenceId: Int,
            x: Int, y: Int, angleTenths: Int, applyToAllStyles: Boolean): EditorSnapshot {
            rotations += angleTenths
            current = current.copy(widgets = current.widgets.map {
                if (it.globalIndex == globalIndex) it.copy(rotationTenths = angleTenths) else it
            }, isDirty = true)
            return current
        }
        val removed = mutableListOf<Int>()
        val duplicated = mutableListOf<Int>()
        val moves = mutableMapOf<Int, MutableList<Pair<Int, Int>>>()
        val moveScopes = mutableListOf<Boolean>()
        var failOnCall: Int? = null
        /** When set, every move commit waits for it — the window a held arrow repeats in. */
        var parked: CompletableDeferred<Unit>? = null
        var parkedDuplicate: CompletableDeferred<Unit>? = null
        private var calls = 0

        override fun observeImageFit() = flowOf(ImageFit.COVER)
        override suspend fun openProject(projectId: Long): EditorSnapshot = current
        override suspend fun currentSnapshot(styleName: String?): EditorSnapshot = current.copy(
            selectedVariant = EditorVariant(styleName ?: "style0.bin", VariantKind.AOD),
        ).also { current = it }
        override suspend fun resetEdits(): EditorSnapshot = current

        private fun identify(globalIndex: Int, sequenceId: Int): WidgetGuide {
            if (++calls == failOnCall) throw WatchFaceException("refused")
            val widget = current.widgets.single { it.globalIndex == globalIndex }
            check(widget.sequenceId == sequenceId) { "#$globalIndex is no longer the widget that was picked" }
            return widget
        }

        override suspend fun removeWidget(
            styleName: String, globalIndex: Int, widgetType: Int, sequenceId: Int,
            x: Int, y: Int, requireFinal: Boolean, applyToAllStyles: Boolean,
        ): EditorSnapshot {
            identify(globalIndex, sequenceId)
            removed += globalIndex
            current = current.copy(widgets = current.widgets.filter { it.globalIndex != globalIndex }.map {
                if (it.globalIndex > globalIndex) it.copy(globalIndex = it.globalIndex - 1, ordinal = it.ordinal - 1) else it
            })
            return current
        }

        override suspend fun duplicateWidget(
            styleName: String, globalIndex: Int, widgetType: Int, sequenceId: Int,
            x: Int, y: Int, applyToAllStyles: Boolean,
        ): EditorSnapshot {
            val source = identify(globalIndex, sequenceId)
            parkedDuplicate?.await()
            duplicated += globalIndex
            val next = current.widgets.maxOf { it.globalIndex } + 1
            current = current.copy(widgets = current.widgets + source.copy(globalIndex = next, ordinal = next))
            return current
        }

        override suspend fun moveWidget(
            styleName: String, globalIndex: Int, widgetType: Int, sequenceId: Int,
            x: Int, y: Int, applyToAllStyles: Boolean,
        ): EditorSnapshot {
            moves.getOrPut(globalIndex) { mutableListOf() }.add(x to y)
            moveScopes += applyToAllStyles
            parked?.await()
            current = current.copy(widgets = current.widgets.map {
                if (it.globalIndex == globalIndex) it.copy(x = x, y = y) else it
            })
            return current
        }
    }

    private companion object {
        const val PanelWidth = 256
        const val PanelHeight = 402
    }
}
