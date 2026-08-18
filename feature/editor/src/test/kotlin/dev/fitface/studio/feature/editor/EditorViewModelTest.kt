package dev.fitface.studio.feature.editor

import dev.fitface.studio.core.delivery.Fit3DirectInstaller
import dev.fitface.studio.core.delivery.DirectInstallState
import dev.fitface.studio.core.model.EditAuditSummary
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.WatchFaceRepository
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.data.DiagnosticsReporter
import dev.fitface.studio.core.model.DiagnosticsLog
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
 * How queued widget moves are committed, which is the half of "sometimes they stick" that
 * was not a gesture bug.
 *
 * A commit is a reparse plus a revalidate plus a preview recompose. It used to run with
 * `isWorking` set, which turned the canvas off — so a drag begun inside that window found
 * `enabled` false, bailed out of `onDragStart`, and vanished with no feedback at all. Both
 * paths now queue a target and one worker drains them, so the canvas stays live and the
 * intermediate positions of a held nudge are dropped rather than each becoming a commit.
 *
 * The tests own `Dispatchers.Main`, because `viewModelScope` dispatches onto it and the
 * ViewModel launches from its own `init`. [settle] runs whatever those launches queued, so
 * every assertion reads a settled state rather than a half-applied one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Before
    fun installDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun restoreDispatcher() = Dispatchers.resetMain()

    private val installer = mockk<Fit3DirectInstaller>(relaxed = true) {
        every { state } returns MutableStateFlow(DirectInstallState())
    }

    /** Three widgets far enough apart that a move of one says nothing about the others. */
    private val widgets = listOf(
        widget(globalIndex = 1, x = 20, y = 20),
        widget(globalIndex = 2, x = 60, y = 120),
        widget(globalIndex = 3, x = 100, y = 220),
    )

    // ---------------------------------------------------------------------------
    // 2.4 — the coalescing worker
    // ---------------------------------------------------------------------------

    /**
     * Only the newest target per widget reaches the container. A held nudge fires far faster
     * than a commit completes, so committing every tick would queue dozens of reparses to
     * arrive at one position.
     */
    @Test
    fun aRunOfMovesCoalescesToTheLatestTargetPerWidget() {
        val repository = FakeRepository(snapshot(widgets))
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        // The first move starts the worker, which takes the target and parks in the commit.
        viewModel.moveWidget(globalIndex = 1, x = 30, y = 30)
        settle()
        assertEquals(listOf(30 to 30), repository.committed(1))

        // These two land while that commit is in flight; the second replaces the first.
        viewModel.moveWidget(globalIndex = 1, x = 40, y = 40)
        viewModel.moveWidget(globalIndex = 1, x = 50, y = 50)
        repository.releaseAll()
        settle()

        assertEquals(
            "40,40 was superseded before it was ever committed",
            listOf(30 to 30, 50 to 50),
            repository.committed(1),
        )
    }

    /**
     * Targets for different widgets do not evict each other. The queue was a single slot
     * keyed by one global index, so a target queued for one widget and then replaced by a
     * target for another was lost outright — two back-to-back drags landed only the second.
     */
    @Test
    fun queuedMovesForDifferentWidgetsAreNotLostBehindEachOther() {
        val repository = FakeRepository(snapshot(widgets))
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        // Widget 1's commit is in flight, so 2 and 3 are both queued behind it at once.
        viewModel.moveWidget(globalIndex = 1, x = 30, y = 30)
        settle()
        viewModel.moveWidget(globalIndex = 2, x = 70, y = 130)
        viewModel.moveWidget(globalIndex = 3, x = 110, y = 230)
        repository.releaseAll()
        settle()

        assertEquals(listOf(30 to 30), repository.committed(1))
        assertEquals(listOf(70 to 130), repository.committed(2))
        assertEquals(listOf(110 to 230), repository.committed(3))
    }

    /**
     * The canvas stays usable across a commit — that is the whole point. `isWorking` gates
     * `enabled` on the canvas, and a drag that arrives while it is set is dropped in
     * `onDragStart` before it can do anything.
     */
    @Test
    fun committingADragDoesNotTurnTheCanvasOff() {
        val repository = FakeRepository(snapshot(widgets))
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        viewModel.moveWidget(globalIndex = 1, x = 30, y = 30)
        settle()

        assertFalse("a queued move must not disable the canvas", viewModel.state.value.isWorking)
        // The release position is shown immediately rather than after the reparse.
        assertEquals(1, viewModel.state.value.pendingWidgetMove?.globalIndex)

        repository.releaseAll()
        settle()

        assertFalse(viewModel.state.value.isWorking)
        assertNull("the snapshot has overtaken it", viewModel.state.value.pendingWidgetMove)
    }

    /**
     * Every queued target was accumulated on top of a position the container has just
     * refused, so none of them survive it — committing them anyway would walk the widget
     * through positions the user never asked for.
     */
    @Test
    fun aRefusedMoveDropsEveryQueuedTarget() {
        val repository = FakeRepository(snapshot(widgets), failWith = WatchFaceException("no"))
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        viewModel.moveWidget(globalIndex = 1, x = 30, y = 30)
        settle()
        viewModel.moveWidget(globalIndex = 2, x = 70, y = 130)
        repository.releaseAll()
        settle()

        assertEquals("only the in-flight target was attempted", 1, repository.attempts)
        assertNotNull(viewModel.state.value.error)
        assertNull(viewModel.state.value.pendingWidgetMove)
    }

    /** A refusal must not leave the worker flag set, or no later move ever commits. */
    @Test
    fun aMoveStillCommitsAfterAnEarlierOneWasRefused() {
        val repository = FakeRepository(snapshot(widgets), failWith = WatchFaceException("no"))
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.moveWidget(globalIndex = 1, x = 30, y = 30)
        repository.releaseAll()
        settle()
        assertNotNull(viewModel.state.value.error)

        repository.failWith = null
        viewModel.moveWidget(globalIndex = 1, x = 35, y = 35)
        repository.releaseAll()
        settle()

        assertEquals(listOf(30 to 30, 35 to 35), repository.committed(1))
    }

    // ---------------------------------------------------------------------------
    // 2.3 — the nudge clamp
    // ---------------------------------------------------------------------------

    /**
     * A held nudge cannot walk a widget off the panel. It used to be bounded only by the
     * Short range, and a widget nudged past the edge could no longer be tapped at all —
     * only the Widgets list could reach it again.
     */
    @Test
    fun nudgingHoldsTheWidgetOnThePanel() {
        val repository = FakeRepository(snapshot(widgets), commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        repeat(80) {
            viewModel.nudgeWidget(globalIndex = 1, deltaX = -1, deltaY = -1)
            settle()
        }

        val moved = requireNotNull(
            viewModel.state.value.snapshot?.widgets?.single { it.globalIndex == 1 },
        )
        assertEquals("held at the left edge", 0, moved.drawLeft(PanelWidth))
        assertEquals("held at the top edge", 0, moved.drawTop(PanelHeight))
    }

    /**
     * An end-anchored widget is stored as a negative coordinate, so the clamp cannot work on
     * the stored value: `displayCoordinate` reads the sign to decide the anchoring, and a
     * widget stored at 0 stepped one pixel left reaches -1, which that rule places at the
     * opposite side of the face. The anchoring travels with the widget instead.
     */
    @Test
    fun nudgingAnEndAnchoredWidgetKeepsItAgainstTheEndItIsAnchoredTo() {
        val anchored = widget(globalIndex = 1, x = -30, y = -40)
        val repository = FakeRepository(snapshot(listOf(anchored)), commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        val before = anchored.drawLeft(PanelWidth)

        viewModel.nudgeWidget(globalIndex = 1, deltaX = 1, deltaY = 0)
        settle()

        val moved = requireNotNull(
            viewModel.state.value.snapshot?.widgets?.single { it.globalIndex == 1 },
        )
        assertTrue("still stored from the end", moved.x < 0)
        assertEquals("one pixel right, not flung across the face", before + 1, moved.drawLeft(PanelWidth))
    }

    /** Nudging into an edge the widget is already against changes nothing at all. */
    @Test
    fun aNudgeRefusedByTheClampCommitsNothing() {
        val atEdge = widget(globalIndex = 1, x = 0, y = 0)
        val repository = FakeRepository(snapshot(listOf(atEdge)), commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        viewModel.nudgeWidget(globalIndex = 1, deltaX = -1, deltaY = -1)
        settle()

        assertEquals(0, repository.attempts)
        assertNull(viewModel.state.value.pendingWidgetMove)
    }

    // ---------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------

    /** Runs everything `viewModelScope` has queued on the test dispatcher. */
    private fun settle() = scope.advanceUntilIdle()

    private fun widget(globalIndex: Int, x: Int, y: Int) = WidgetGuide(
        ordinal = globalIndex,
        globalIndex = globalIndex,
        type = 3,
        sequenceId = globalIndex,
        x = x,
        y = y,
        width = 40,
        height = 40,
        recordSize = 40,
        isFinal = false,
        canEditPosition = true,
        colorArgb = null,
        supportMessage = "",
    )

    private fun snapshot(widgets: List<WidgetGuide>) = EditorSnapshot(
        projectId = 1,
        faceId = "00001",
        faceName = "Face 00001",
        sourceName = "Face 00001.apk",
        styleNames = listOf("style0.bin"),
        selectedStyle = "style0.bin",
        preview = frame(),
        referencePreview = null,
        composedPreview = frame(),
        widgetOverlay = frame(),
        widgetImageLayers = emptyList(),
        widgets = widgets,
        imageCount = 1,
        validationErrors = emptyList(),
        validationWarnings = emptyList(),
        isDirty = false,
        audit = EditAuditSummary(changedPayloadBytes = 0, changedStyles = emptyList()),
    )

    private fun frame() = PreviewFrame(PanelWidth, PanelHeight, IntArray(PanelWidth * PanelHeight))

    /**
     * Records every committed move and, by default, parks each one until [releaseAll].
     *
     * Parking is what the tests are about: it reproduces the window in which a commit is
     * outstanding and further targets are arriving. [commitImmediately] is for the nudge
     * tests, which care where a run of steps ends up rather than what overlapped what.
     */
    private inner class FakeRepository(
        private var current: EditorSnapshot,
        private val commitImmediately: Boolean = false,
        var failWith: WatchFaceException? = null,
    ) : WatchFaceRepository by mockk(relaxed = true) {
        private val moves = mutableMapOf<Int, MutableList<Pair<Int, Int>>>()

        /**
         * Latches open rather than re-arming. Re-arming would park the *next* commit too,
         * which is not what any test here is asking about — they hold one commit open to
         * queue targets behind it, then want the whole queue to drain.
         */
        private val gate = CompletableDeferred<Unit>()

        var attempts = 0
            private set

        fun committed(globalIndex: Int): List<Pair<Int, Int>> = moves[globalIndex].orEmpty()

        fun releaseAll() {
            gate.complete(Unit)
        }

        override fun observeImageFit() = flowOf(ImageFit.COVER)

        override suspend fun openProject(projectId: Long): EditorSnapshot = current

        override suspend fun moveWidget(
            styleName: String,
            globalIndex: Int,
            widgetType: Int,
            sequenceId: Int,
            x: Int,
            y: Int,
            applyToAllStyles: Boolean,
        ): EditorSnapshot {
            attempts++
            moves.getOrPut(globalIndex) { mutableListOf() }.add(x to y)
            if (!commitImmediately) gate.await()
            failWith?.let { throw it }
            current = current.copy(
                widgets = current.widgets.map {
                    if (it.globalIndex == globalIndex) it.copy(x = x, y = y) else it
                },
                isDirty = true,
            )
            return current
        }
    }

    private companion object {
        const val PanelWidth = 256
        const val PanelHeight = 402
    }

    private fun reporter(): DiagnosticsReporter = mockk(relaxed = true)
}
