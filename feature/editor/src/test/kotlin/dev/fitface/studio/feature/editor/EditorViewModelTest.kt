package dev.fitface.studio.feature.editor

import dev.fitface.studio.core.delivery.Fit3DirectInstaller
import dev.fitface.studio.core.delivery.DirectInstallState
import dev.fitface.studio.core.model.AnimationSource
import dev.fitface.studio.core.model.VideoClip
import dev.fitface.studio.core.model.animationAddedBytes
import dev.fitface.studio.core.model.animationStepThatFits
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.EditAuditSummary
import dev.fitface.studio.core.model.EditKind
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.MovingBackground
import dev.fitface.studio.core.model.PreparedPicture
import dev.fitface.studio.core.model.ReplacementImage
import dev.fitface.studio.core.model.pictureAddedBytes
import dev.fitface.studio.core.model.pictureSizes
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.WatchFaceRepository
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WidgetResizeKind
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
import org.junit.Assert.assertSame
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
    /** A 200 × 100 picture: kept whole, so Medium is the 60% rung, 120 × 60. */
    private fun picture() = PreparedPicture("p", 200, 100, opaque = false,
        sizes = pictureSizes(200, 100, PanelWidth, PanelHeight, kept = true),
        preview = PreviewFrame(200, 100, IntArray(200 * 100)))

    /**
     * Placing a picture is placing a background: it starts centred at its default size, a
     * drag moves it, the size buttons step its own ladder, and Add takes it where it is in
     * the colour chosen.
     */
    @Test
    fun aPictureIsPlacedLikeABackgroundAndAddedWhereItIs() {
        val repository = FakeRepository(snapshot(widgets), commitImmediately = true).apply { prepared = picture() }
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.preparePicture("content://picture")
        settle()
        var pending = requireNotNull(viewModel.state.value.pendingPicture)
        assertEquals(60, pending.size.percentOfOriginal)
        assertEquals((PanelWidth - 120) / 2 to (PanelHeight - 60) / 2, pending.left to pending.top)
        assertEquals(pictureAddedBytes(120, 60, opaque = false), pending.addedBytes)

        viewModel.transformPicture(1f, 10f / PanelWidth, 0f)
        viewModel.stepPictureSize(grow = true)
        viewModel.setPictureTint(0xFFFF0000.toInt())
        pending = requireNotNull(viewModel.state.value.pendingPicture)
        assertEquals(65, pending.size.percentOfOriginal)
        assertEquals(130 to 65, pending.size.width to pending.size.height)

        viewModel.addPicture()
        settle()
        assertEquals(listOf(listOf<Any?>("p", 65, pending.left, pending.top, 0xFFFF0000.toInt())), repository.addedPictures)
        val state = viewModel.state.value
        assertNull(state.pendingPicture)
        assertEquals("the picture is selected, as an import is", 9, state.selectedWidgetIndex)
        assertNotNull("the screen moves to the face to show it", state.pictureAdded)
        viewModel.clearPictureAdded(requireNotNull(state.pictureAdded).id)
        assertNull(viewModel.state.value.pictureAdded)
    }

    /** Twenty 100 ms frames, each a different shade so the frame shown can be told apart. */
    private fun gif(frames: Int = 20) = AnimationSource("content://gif", 10, 10,
        List(frames) { index -> PreviewFrame(10, 10, IntArray(100) { index }) }, List(frames) { 100 })

    /**
     * A GIF is placed as a still background is, with as many frames as fit: on a face with
     * about 680 KB to spare, 1 in every 4 of twenty. Add takes the placement, colour and step.
     */
    @Test
    fun aGifIsPlacedLikeABackgroundKeepingTheFramesThatFit() {
        val source = gif()
        val repository = FakeRepository(snapshot(widgets).copy(containerBytes = 3_500_000), commitImmediately = true)
            .apply { animation = source }
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.prepareMovingBackground("content://gif")
        settle()
        val pending = requireNotNull(viewModel.state.value.pendingAnimation)
        assertEquals(4, pending.step)
        assertEquals(4, pending.fittingStep)
        assertEquals(listOf(0, 4, 8, 12, 16), pending.plan.frames)
        assertSame("the face shows the first frame where the still picture would be",
            source.frames.first(), viewModel.state.value.pendingImage?.preview)

        viewModel.setMovingBackgroundStep(3)
        assertEquals(3, viewModel.state.value.pendingAnimation?.step)
        viewModel.setMovingBackgroundStep(0)
        assertEquals("below every frame is not a step", 3, viewModel.state.value.pendingAnimation?.step)
        viewModel.setMovingBackgroundStep(4)
        viewModel.transformImage(1f, 0.1f, 0f)
        viewModel.setBackgroundTint(0xFFFF0000.toInt())
        viewModel.applyBackground()
        settle()

        val (placement, step) = repository.addedMoving.single()
        assertEquals(4, step)
        assertEquals(0.1f, placement.offsetX)
        assertEquals(0xFFFF0000.toInt(), placement.tint)
        assertNull(viewModel.state.value.pendingAnimation)
        assertNull(viewModel.state.value.pendingImage)
        assertNull("a still background was not touched", repository.backgroundPlacement)
    }

    /** A GIF of one frame is a still picture, placed as one rather than refused. */
    @Test
    fun aOneFrameGifIsPlacedStillAFullFaceIsRefusedAndDiscardForgetsAGif() {
        val repository = FakeRepository(snapshot(widgets), commitImmediately = true).apply { animation = gif(frames = 1) }
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.prepareMovingBackground("content://gif")
        settle()
        assertNull(viewModel.state.value.error)
        assertNull(viewModel.state.value.pendingAnimation)
        assertEquals("content://gif", viewModel.state.value.pendingImage?.uri)
        viewModel.cancelBackground()

        val full = FakeRepository(snapshot(widgets).copy(containerBytes = WATCH_CONTAINER_BYTE_CEILING - 1000),
            commitImmediately = true).apply { animation = gif() }
        val crowded = EditorViewModel(full, installer, DiagnosticsLog(), reporter())
        crowded.loadProject(1)
        settle()
        crowded.prepareMovingBackground("content://gif")
        settle()
        assertTrue(crowded.state.value.error!!.text.startsWith("There is no room on this face for even one frame"))

        repository.animation = gif()
        viewModel.prepareMovingBackground("content://gif")
        settle()
        assertNotNull(viewModel.state.value.pendingAnimation)
        viewModel.cancelBackground()
        assertNull(viewModel.state.value.pendingAnimation)
        assertNull(viewModel.state.value.pendingImage)
    }

    /** What the one picker returns decides how it is placed: an image still, a GIF or video moving. */
    @Test
    fun oneButtonPlacesAnImageStillAndAGifOrVideoMoving() {
        val repository = FakeRepository(snapshot(widgets), commitImmediately = true)
            .apply { animation = gif(); prepared = picture() }
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        viewModel.chooseBackground("content://png", "image/png")
        settle()
        assertEquals("content://png", viewModel.state.value.pendingImage?.uri)
        assertNull(viewModel.state.value.pendingAnimation)
        viewModel.cancelBackground()
        for (type in listOf("image/gif", "video/mp4")) {
            viewModel.chooseBackground("content://moving", type)
            settle()
            assertNotNull(type, viewModel.state.value.pendingAnimation)
            viewModel.cancelBackground()
        }
        viewModel.chooseBackground("content://unknown", null)
        settle()
        assertNull("a file of no known type is tried as a still picture", viewModel.state.value.pendingAnimation)
        assertNotNull(viewModel.state.value.pendingImage)
        viewModel.cancelBackground()

        viewModel.choosePicture("content://jpeg", "image/jpeg")
        settle()
        assertNull(requireNotNull(viewModel.state.value.pendingPicture).animation)
        viewModel.discardPicture()
        settle()
        viewModel.choosePicture("content://gif", "image/gif")
        settle()
        assertNotNull(requireNotNull(viewModel.state.value.pendingPicture).animation)
    }

    @Test
    fun aGifOrAVideoIsMovingAnythingElseStill() {
        assertTrue(isMovingMedia("image/gif"))
        assertTrue(isMovingMedia("IMAGE/GIF"))
        assertTrue(isMovingMedia("video/mp4"))
        assertTrue(isMovingMedia("video/webm"))
        assertFalse(isMovingMedia("image/png"))
        assertFalse(isMovingMedia("image/webp"))
        assertFalse(isMovingMedia(null))
    }

    /** On the Widgets page a GIF of one frame is added as the picture it is. */
    @Test
    fun aOneFrameGifIsPlacedAsAStillPicture() {
        val repository = FakeRepository(snapshot(widgets), commitImmediately = true)
            .apply { animation = gif(frames = 1); prepared = picture() }
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.choosePicture("content://gif", "image/gif")
        settle()
        val pending = requireNotNull(viewModel.state.value.pendingPicture)
        assertNull(pending.animation)
        assertEquals("p", pending.prepared.handle)
        assertNull(viewModel.state.value.error)
    }

    /**
     * A new moving background replaces the one the style has, so that one's frames count as
     * room: a face too full for one more frame still takes a GIF in its place, and Add sends it.
     */
    @Test
    fun aGifReplacingAMovingBackgroundHasItsRoom() {
        val moving = MovingBackground(globalIndex = 1, frames = 5, entries = 5, periodMs = 100)
        val full = snapshot(widgets).copy(containerBytes = WATCH_CONTAINER_BYTE_CEILING - 1000, movingBackground = moving)
        val freed = animationAddedBytes(5, 5, PanelWidth, PanelHeight)
        assertEquals(freed, full.movingBackgroundBytes())
        assertEquals(1000L + freed, full.roomForMovingBackground())
        assertEquals("nothing to replace, nothing freed", 1000L,
            full.copy(movingBackground = null).roomForMovingBackground())

        val source = gif()
        val repository = FakeRepository(full, commitImmediately = true).apply { animation = source }
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.chooseBackground("content://gif", "image/gif")
        settle()
        assertNull(viewModel.state.value.error)
        val pending = requireNotNull(viewModel.state.value.pendingAnimation)
        assertEquals(animationStepThatFits(source.delaysMs, 1000L + freed, PanelWidth, PanelHeight), pending.step)
        viewModel.applyBackground()
        settle()
        assertEquals(pending.step, repository.addedMoving.single().second)
    }

    /** A still picture applied over a moving background goes through the ordinary replacement. */
    @Test
    fun aStillPictureOverAMovingBackgroundIsAppliedAsAnyBackgroundIs() {
        val moving = MovingBackground(globalIndex = 1, frames = 5, entries = 5, periodMs = 100)
        val repository = FakeRepository(snapshot(widgets).copy(movingBackground = moving), commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.chooseBackground("content://png", "image/png")
        settle()
        assertNull(viewModel.state.value.pendingAnimation)
        viewModel.applyBackground()
        settle()
        assertNotNull(repository.backgroundPlacement)
        assertTrue(repository.addedMoving.isEmpty())
        assertNull(viewModel.state.value.pendingImage)
    }

    /**
     * A GIF placed as a moving picture starts where a still picture would, at its own size
     * here, keeping as many frames as fit at that size; Add sends the frames, not a picture.
     */
    @Test
    fun aGifIsPlacedLikeAPictureKeepingTheFramesThatFitAtItsSize() {
        val source = AnimationSource("content://gif", 120, 90,
            List(20) { index -> PreviewFrame(120, 90, IntArray(120 * 90) { index }) }, List(20) { 100 })
        val repository = FakeRepository(snapshot(widgets).copy(containerBytes = 4_100_000), commitImmediately = true)
            .apply { animation = source }
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.prepareMovingPicture("content://gif")
        settle()
        val pending = requireNotNull(viewModel.state.value.pendingPicture)
        val animation = requireNotNull(pending.animation)
        assertEquals(120 to 90, pending.size.width to pending.size.height)
        val room = (WATCH_CONTAINER_BYTE_CEILING - 4_100_000).toLong()
        assertEquals(animationStepThatFits(source.delaysMs, room, 120, 90), animation.step)
        assertTrue("a face this full keeps some frames, not all", animation.step > 1)
        assertSame(source.frames.first(), pending.prepared.preview)
        assertEquals(animationAddedBytes(animation.plan.frames.size, animation.plan.table.size, 120, 90), pending.addedBytes)

        viewModel.stepPictureSize(grow = false)
        val smaller = requireNotNull(viewModel.state.value.pendingPicture)
        assertTrue(smaller.size.width < 120)
        viewModel.setMovingPictureStep(animation.step - 1)
        viewModel.setPictureTint(0xFF00FF00.toInt())
        viewModel.addPicture()
        settle()
        assertEquals(listOf(listOf<Any?>(animation.step - 1, smaller.size.width, smaller.size.height,
            smaller.left, smaller.top, 0xFF00FF00.toInt())), repository.addedMovingPictures)
        assertTrue("no still picture was added", repository.addedPictures.isEmpty())
        assertNull(viewModel.state.value.pendingPicture)
        assertEquals(9, viewModel.state.value.selectedWidgetIndex)
    }

    /** A video's clip is read again when it is moved, keeping the step and the place. */
    @Test
    fun aVideosClipIsReadAgainWhenItIsMovedOrStretched() {
        val clip = VideoClip.opening(8000)
        val source = AnimationSource("content://video", 64, 36,
            List(clip.frames) { index -> PreviewFrame(64, 36, IntArray(64 * 36) { index }) }, List(clip.frames) { 100 }, clip)
        val repository = FakeRepository(snapshot(widgets), commitImmediately = true).apply { animation = source }
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.prepareMovingPicture("content://video")
        settle()
        viewModel.setMovingPictureStep(2)
        viewModel.transformPicture(1f, 0.05f, 0f)
        val placed = requireNotNull(viewModel.state.value.pendingPicture)
        viewModel.setVideoClip(requireNotNull(clip.later()))
        settle()
        assertEquals(listOf(VideoClip(8000, 500, 3000)), repository.clipsRead)
        val moved = requireNotNull(viewModel.state.value.pendingPicture)
        assertEquals(VideoClip(8000, 500, 3000), moved.animation?.source?.clip)
        assertEquals(2, moved.animation?.step)
        assertEquals(placed.centreX, moved.centreX)

        // The same control on a moving background.
        viewModel.discardPicture()
        viewModel.prepareMovingBackground("content://video")
        settle()
        viewModel.setVideoClip(requireNotNull(clip.longer()))
        settle()
        assertEquals(VideoClip(8000, 0, 3500), viewModel.state.value.pendingAnimation?.source?.clip)
        assertEquals(35, viewModel.state.value.pendingAnimation?.source?.frames?.size)
    }

    /** A pinch lands on a rung; a drag past the edge stops where a widget drag would. */
    @Test
    fun aPinchSnapsToTheLadderAndADragKeepsPartOfThePictureOnTheFace() {
        val repository = FakeRepository(snapshot(widgets)).apply { prepared = picture() }
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.preparePicture("content://picture")
        settle()
        viewModel.transformPicture(1.49f, 0f, 0f)
        assertEquals(90, viewModel.state.value.pendingPicture?.size?.percentOfOriginal)
        viewModel.transformPicture(1f, -5f, 5f)
        val pending = requireNotNull(viewModel.state.value.pendingPicture)
        assertEquals(offFaceKeepVisible(pending.size.width) - pending.size.width, pending.left)
        assertEquals(PanelHeight - offFaceKeepVisible(pending.size.height), pending.top)
        viewModel.resetPicturePlacement()
        assertEquals(60, viewModel.state.value.pendingPicture?.size?.percentOfOriginal)

        viewModel.discardPicture()
        settle()
        assertNull(viewModel.state.value.pendingPicture)
        assertEquals(listOf("p"), repository.released)
    }

    /** The background has the same colour control, and Use applies what the face shows. */
    @Test
    fun aBackgroundsColourGoesWithItsPlacement() {
        val repository = FakeRepository(snapshot(widgets), commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.prepareBackground("content://background")
        settle()
        viewModel.setBackgroundTint(0xFF0000FF.toInt())
        // Found on the emulator: Reset placement and a new fit used to drop the colour.
        viewModel.resetImagePlacement()
        viewModel.selectFit(ImageFit.CONTAIN)
        assertEquals(0xFF0000FF.toInt(), viewModel.state.value.placement.tint)
        viewModel.applyBackground()
        settle()
        assertEquals(0xFF0000FF.toInt(), repository.backgroundPlacement?.tint)
    }

    /**
     * "This style only" reaches the repository where the face has styles to choose between,
     * and nowhere else: one style, or the always-on display, is already edited alone.
     */
    @Test
    fun aBackgroundGoesToTheSelectedStyleAloneOnlyWhereThereIsAChoice() {
        val styled = snapshot(widgets).copy(styleNames = listOf("style0.bin", "style1.bin"),
            backgroundStyles = listOf("style0.bin", "style1.bin"))
        for ((face, expected) in listOf(styled to true, snapshot(widgets).copy(backgroundStyles = listOf("style0.bin")) to false)) {
            val repository = FakeRepository(face, commitImmediately = true)
            val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
            viewModel.loadProject(1)
            settle()
            viewModel.setBackgroundThisStyleOnly(true)
            viewModel.prepareBackground("content://background")
            settle()
            viewModel.applyBackground()
            settle()
            assertEquals(expected, repository.backgroundThisStyleOnly)
        }
    }

    /**
     * Left alone, a background goes to the selected style only, as every other edit does;
     * All styles is the choice that widens it.
     */
    @Test
    fun aBackgroundGoesToTheSelectedStyleAloneUnlessAllStylesIsChosen() {
        val styled = snapshot(widgets).copy(styleNames = listOf("style0.bin", "style1.bin"),
            backgroundStyles = listOf("style0.bin", "style1.bin"))
        val repository = FakeRepository(styled, commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        assertTrue(viewModel.state.value.backgroundThisStyleOnly)
        viewModel.prepareBackground("content://background")
        settle()
        viewModel.applyBackground()
        settle()
        assertEquals(true, repository.backgroundThisStyleOnly)

        viewModel.setBackgroundThisStyleOnly(false)
        viewModel.prepareBackground("content://background")
        settle()
        viewModel.applyBackground()
        settle()
        assertEquals(false, repository.backgroundThisStyleOnly)
    }

    /** Remove background takes the scope a replacement would, and asks nothing first. */
    @Test
    fun removingABackgroundTakesTheScopeChosen() {
        val styled = snapshot(widgets).copy(styleNames = listOf("style0.bin", "style1.bin"),
            backgroundStyles = listOf("style0.bin", "style1.bin"))
        val repository = FakeRepository(styled, commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.removeBackground()
        settle()
        viewModel.setBackgroundThisStyleOnly(false)
        viewModel.removeBackground()
        settle()
        assertEquals(listOf(true, false), repository.removedBackgrounds)
    }

    /**
     * A held − or + keeps its button enabled while a step saves, so a step that arrives
     * mid-save is dropped rather than run against the snapshot being replaced; the next one,
     * after the save, steps from the new size.
     */
    @Test
    fun aResizeStepArrivingWhileOneSavesIsDropped() {
        val resizable = widget(globalIndex = 1, x = 20, y = 20).copy(resizeKind = WidgetResizeKind.RASTER)
        val repository = FakeRepository(snapshot(listOf(resizable)))
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.selectWidget(1)
        viewModel.resizeSelectedWidget(grow = true)
        settle() // Parked: the first step is saving.
        viewModel.resizeSelectedWidget(grow = true)
        viewModel.resizeSelectedWidget(grow = true)
        repository.releaseAll()
        settle()
        assertEquals(listOf(42 to 42), repository.resizes)
        viewModel.resizeSelectedWidget(grow = true)
        repository.releaseAll()
        settle()
        assertEquals("the next step starts from the saved size", listOf(42 to 42, 44 to 44), repository.resizes)
    }

    /** The library has opened it already; the editor takes that session, not a second load. */
    @Test
    fun theEditorTakesTheProjectTheLibraryOpenedInsteadOfOpeningItAgain() {
        val repository = FakeRepository(snapshot(widgets)).apply { alreadyOpen = 1 }
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        assertEquals(0, repository.opens)
        assertEquals(widgets, viewModel.state.value.snapshot?.widgets)

        // Nothing open for it, as after the process was killed: it opens it itself.
        val cold = FakeRepository(snapshot(widgets))
        val restored = EditorViewModel(cold, installer, DiagnosticsLog(), reporter())
        restored.loadProject(1)
        settle()
        assertEquals(1, cold.opens)
        assertEquals(widgets, restored.state.value.snapshot?.widgets)
    }

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

    @Test
    fun nudgesAccumulateFromTheMoveBeingSaved() {
        val repository = FakeRepository(snapshot(widgets))
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        viewModel.nudgeWidget(1, 1, 0)
        settle() // 21 is in flight, but the snapshot still reports 20.
        viewModel.nudgeWidget(1, 1, 0)
        assertEquals(22f, viewModel.state.value.pendingWidgetMove?.displayX)
        viewModel.nudgeWidget(1, 1, 0)
        repository.releaseAll()
        settle()
        assertEquals(listOf(21 to 20, 23 to 20), repository.committed(1))
    }

    @Test
    fun aDragBackToTheSavedPositionSupersedesAnInFlightMove() {
        val repository = FakeRepository(snapshot(widgets))
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.moveWidget(1, 40, 20)
        settle()
        viewModel.moveWidget(1, 20, 20)
        repository.releaseAll()
        settle()
        assertEquals(listOf(40 to 20, 20 to 20), repository.committed(1))
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
     * only the Widgets list could reach it again. It may hang past an edge now (issue #14),
     * but [offFaceKeepVisible] of it always stays on the face to tap.
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
        val overhang = 40 - offFaceKeepVisible(40)
        assertEquals("held with 10 px still on the face", 10, offFaceKeepVisible(40))
        assertEquals("held past the left edge", -overhang, moved.drawLeft)
        assertEquals("held past the top edge", -overhang, moved.drawTop)
    }

    /**
     * A widget aligned to the far edge of something stores a negative offset, so the clamp
     * cannot work on the stored value. It cannot read the sign to decide either: a widget
     * stored at 0 stepped one pixel left reaches -1, and reading that as "anchored to the
     * far edge" put it on the opposite side of the face. The origin travels with the widget
     * instead, and everything is measured through it.
     */
    @Test
    fun nudgingAnEndAnchoredWidgetKeepsItAgainstTheEndItIsAnchoredTo() {
        val anchored = widget(globalIndex = 1, x = -30, y = -40, originX = 216, originY = 362)
        val repository = FakeRepository(snapshot(listOf(anchored)), commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        val before = anchored.drawLeft

        viewModel.nudgeWidget(globalIndex = 1, deltaX = 1, deltaY = 0)
        settle()

        val moved = requireNotNull(
            viewModel.state.value.snapshot?.widgets?.single { it.globalIndex == 1 },
        )
        assertTrue("still stored from the end", moved.x < 0)
        assertEquals("one pixel right, not flung across the face", before + 1, moved.drawLeft)
    }

    /** Nudging past the furthest a widget may hang off the face changes nothing at all. */
    @Test
    fun aNudgeRefusedByTheClampCommitsNothing() {
        val limit = -(40 - offFaceKeepVisible(40))
        val atEdge = widget(globalIndex = 1, x = limit, y = limit)
        val repository = FakeRepository(snapshot(listOf(atEdge)), commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        viewModel.nudgeWidget(globalIndex = 1, deltaX = -1, deltaY = -1)
        settle()

        assertEquals(0, repository.attempts)
        assertNull(viewModel.state.value.pendingWidgetMove)
    }

    /**
     * A typed position is measured from the move being saved, like a nudge,
     * so it lands where it says even while an earlier nudge is still committing.
     */
    @Test
    fun aPlacedPositionLandsWhereItSaysWhileANudgeIsSaving() {
        val repository = FakeRepository(snapshot(widgets))
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        viewModel.nudgeWidget(1, 1, 0)
        settle() // 21 is in flight, but the snapshot still reports 20.
        viewModel.placeWidget(1, left = 100, top = 20)
        assertEquals(100f, viewModel.state.value.pendingWidgetMove?.displayX)
        assertEquals(20f, viewModel.state.value.pendingWidgetMove?.displayY)
        repository.releaseAll()
        settle()
        assertEquals(listOf(21 to 20, 100 to 20), repository.committed(1))
    }

    /** The edges are the drawn ones, written back through the origin and held by the clamp. */
    @Test
    fun aPlacedPositionIsWrittenThroughTheOriginAndClamped() {
        val anchored = widget(globalIndex = 1, x = -30, y = -40, originX = 216, originY = 362)
        val repository = FakeRepository(snapshot(listOf(anchored)), commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        viewModel.placeWidget(1, left = 100, top = 50)
        settle()
        viewModel.placeWidget(1, left = 1000, top = 50)
        settle()
        val limit = PanelWidth - offFaceKeepVisible(40)
        viewModel.placeWidget(1, left = limit, top = 50)
        settle()

        assertEquals(listOf(-116 to -312, limit - 216 to -312), repository.committed(1))
        assertEquals("the last one was already there", 2, repository.attempts)
    }

    /**
     * Undo drops the queued moves and waits for the one being committed, so nothing a nudge
     * started can land after the undo and put back what it took away (R390 Studio's v0.6.12
     * bug).
     */
    @Test
    fun anUndoWaitsForTheMoveInFlightAndDropsTheQueuedOnes() {
        val repository = FakeRepository(snapshot(widgets).copy(undoKind = EditKind.RESIZE))
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        viewModel.beginNudge()
        viewModel.nudgeWidget(1, 1, 0)
        settle() // 21 is being committed, held open by the gate.
        viewModel.nudgeWidget(1, 1, 0) // 22 is queued behind it.
        viewModel.undo()
        repository.releaseAll()
        settle()

        assertEquals(listOf("move 21,20", "undo"), repository.events)
        assertEquals(listOf(21 to 20), repository.committed(1))
        assertEquals(EditKind.MOVE, viewModel.state.value.historyNotice?.kind)
        assertNull(viewModel.state.value.pendingWidgetMove)
    }

    /** Everything one press commits is one undo group; the next press, or a typed position, is another. */
    @Test
    fun onePressIsOneUndoGroup() {
        val repository = FakeRepository(snapshot(widgets), commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()

        viewModel.beginNudge()
        viewModel.nudgeWidget(1, 1, 0)
        settle()
        viewModel.nudgeWidget(1, 2, 0)
        settle()
        viewModel.beginNudge()
        viewModel.nudgeWidget(1, 1, 0)
        settle()
        viewModel.placeWidget(1, left = 100, top = 20)
        settle()

        val (first, second, third, typed) = repository.groups
        assertEquals("a held press", first, second)
        assertTrue("a new press", third != second)
        assertTrue("a typed position", typed != third && typed != null)
    }

    /**
     * Any opaque colour reaches the repository with the widget's own type — text, rule or
     * arc, not only a value — and null asks for the original back. A translucent colour and
     * the colour already in force commit nothing.
     */
    @Test
    fun aColourIsCommittedForTheSelectedWidgetsTypeAndNullResetsIt() {
        val teal = 0xFF75D8D6.toInt()
        val text = widget(globalIndex = 1, x = 20, y = 20).copy(type = 13, colorArgb = teal, originalColorArgb = teal)
        val repository = FakeRepository(snapshot(listOf(text)), commitImmediately = true)
        val viewModel = EditorViewModel(repository, installer, DiagnosticsLog(), reporter())
        viewModel.loadProject(1)
        settle()
        viewModel.selectWidget(1)

        viewModel.setSelectedWidgetColor(0xFF123456.toInt())
        settle()
        viewModel.setSelectedWidgetColor(0x80123456.toInt())
        settle()
        viewModel.setSelectedWidgetColor(0xFF123456.toInt())
        settle()
        viewModel.setSelectedWidgetColor(null)
        settle()

        assertEquals(listOf(13 to 0xFF123456.toInt(), 13 to null), repository.colors)
        assertEquals(teal, viewModel.state.value.snapshot?.widgets?.single()?.colorArgb)
    }

    // ---------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------

    /** Runs everything `viewModelScope` has queued on the test dispatcher. */
    private fun settle() = scope.advanceUntilIdle()

    private fun widget(
        globalIndex: Int,
        x: Int,
        y: Int,
        originX: Int = 0,
        originY: Int = 0,
    ) = WidgetGuide(
        ordinal = globalIndex,
        globalIndex = globalIndex,
        type = 3,
        sequenceId = globalIndex,
        x = x,
        y = y,
        originX = originX,
        originY = originY,
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
        activeStyleName = "style0.bin",
        preview = frame(),
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

        /** Every colour edit, as the widget type and the colour, or null for a reset. */
        val colors = mutableListOf<Pair<Int, Int?>>()

        override suspend fun recolorWidget(
            styleName: String,
            globalIndex: Int,
            widgetType: Int,
            sequenceId: Int,
            x: Int,
            y: Int,
            colorArgb: Int,
            applyToAllStyles: Boolean,
        ): EditorSnapshot {
            colors += widgetType to colorArgb
            current = current.copy(widgets = current.widgets.map {
                if (it.globalIndex == globalIndex) it.copy(colorArgb = colorArgb) else it
            })
            return current
        }

        override suspend fun resetWidgetColor(
            styleName: String,
            globalIndex: Int,
            widgetType: Int,
            sequenceId: Int,
            x: Int,
            y: Int,
            applyToAllStyles: Boolean,
        ): EditorSnapshot {
            colors += widgetType to null
            current = current.copy(widgets = current.widgets.map {
                if (it.globalIndex == globalIndex) it.copy(colorArgb = it.originalColorArgb) else it
            })
            return current
        }

        override fun observeImageFit() = flowOf(ImageFit.COVER)

        var opens = 0
            private set
        /** The project the "library" already opened, which the editor should take as it is. */
        var alreadyOpen: Long? = null

        override suspend fun openedProject(projectId: Long): EditorSnapshot? =
            current.takeIf { alreadyOpen == projectId }

        override suspend fun openProject(projectId: Long): EditorSnapshot = current.also { opens++ }

        var prepared: PreparedPicture? = null
        /** Each picture added: its handle, percent, top-left and colour. */
        val addedPictures = mutableListOf<List<Any?>>()
        val released = mutableListOf<String>()
        var backgroundPlacement: ImagePlacement? = null
        var backgroundThisStyleOnly: Boolean? = null

        override suspend fun preparePicture(imageUri: String, projectId: Long, targetVariant: String) =
            requireNotNull(prepared)

        override suspend fun addPicture(handle: String, projectId: Long, targetVariant: String, percent: Int,
            x: Int, y: Int, tint: Int?, group: Long?): EditorSnapshot {
            addedPictures += listOf(handle, percent, x, y, tint)
            current = current.copy(widgets = current.widgets + widget(globalIndex = 9, x = x, y = y))
            return current
        }

        override suspend fun releasePicture(handle: String) { released += handle }

        override suspend fun prepareReplacementImage(imageUri: String) = ReplacementImage(imageUri, frame())

        var animation: AnimationSource? = null
        /** Each moving background added: its placement and frame step. */
        val addedMoving = mutableListOf<Pair<ImagePlacement, Int>>()

        /** Each clip a video was read again over. */
        val clipsRead = mutableListOf<VideoClip>()

        override suspend fun readAnimation(imageUri: String, clip: VideoClip?): AnimationSource {
            val source = requireNotNull(animation)
            if (clip == null) return source
            clipsRead += clip
            return AnimationSource(source.uri, source.width, source.height,
                List(clip.frames) { source.frames[it % source.frames.size] }, List(clip.frames) { 100 }, clip)
        }

        override suspend fun addMovingBackground(source: AnimationSource, placement: ImagePlacement, frameStep: Int): EditorSnapshot {
            addedMoving += placement to frameStep
            return current
        }

        /** Each moving picture added: its frame step, size, top-left and colour. */
        val addedMovingPictures = mutableListOf<List<Any?>>()

        override suspend fun addMovingPicture(source: AnimationSource, frameStep: Int, width: Int, height: Int,
            x: Int, y: Int, tint: Int?): EditorSnapshot {
            addedMovingPictures += listOf(frameStep, width, height, x, y, tint)
            current = current.copy(widgets = current.widgets + widget(globalIndex = 9, x = x, y = y))
            return current
        }

        /** Each resize reaching the repository, as its new width and height. */
        val resizes = mutableListOf<Pair<Int, Int>>()

        override suspend fun resizeWidget(styleName: String, globalIndex: Int, widgetType: Int, sequenceId: Int,
            x: Int, y: Int, width: Int, height: Int, applyToAllStyles: Boolean): EditorSnapshot {
            resizes += width to height
            if (!commitImmediately) gate.await()
            current = current.copy(widgets = current.widgets.map {
                if (it.globalIndex == globalIndex) it.copy(width = width, height = height) else it
            })
            return current
        }

        /** Each Remove background, as its this-style-only flag. */
        val removedBackgrounds = mutableListOf<Boolean>()

        override suspend fun removeBackground(thisStyleOnly: Boolean): EditorSnapshot {
            removedBackgrounds += thisStyleOnly
            return current
        }

        override suspend fun replaceBackground(imageUri: String, placement: ImagePlacement, thisStyleOnly: Boolean): EditorSnapshot {
            backgroundPlacement = placement
            backgroundThisStyleOnly = thisStyleOnly
            return current
        }

        override suspend fun moveWidget(
            styleName: String,
            globalIndex: Int,
            widgetType: Int,
            sequenceId: Int,
            x: Int,
            y: Int,
            applyToAllStyles: Boolean,
            group: Long?,
        ): EditorSnapshot {
            attempts++
            moves.getOrPut(globalIndex) { mutableListOf() }.add(x to y)
            groups += group
            if (!commitImmediately) gate.await()
            failWith?.let { throw it }
            events += "move $x,$y"
            current = current.copy(
                widgets = current.widgets.map {
                    if (it.globalIndex == globalIndex) it.copy(x = x, y = y) else it
                },
                isDirty = true,
                undoKind = EditKind.MOVE,
            )
            return current
        }

        /** The undo group each move was committed under, in order. */
        val groups = mutableListOf<Long?>()

        /** Moves and undos in the order the repository saw them. */
        val events = mutableListOf<String>()

        override suspend fun undo(): EditorSnapshot {
            events += "undo"
            current = current.copy(undoKind = null, redoKind = EditKind.MOVE)
            return current
        }
    }

    private companion object {
        const val PanelWidth = 256
        const val PanelHeight = 402
    }

    private fun reporter(): DiagnosticsReporter = mockk(relaxed = true)
}
