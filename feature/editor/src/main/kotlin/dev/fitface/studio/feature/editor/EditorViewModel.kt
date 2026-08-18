package dev.fitface.studio.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.fitface.studio.core.delivery.DirectInstallPhase
import dev.fitface.studio.core.delivery.DirectInstallState
import dev.fitface.studio.core.delivery.Fit3DirectInstaller
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.ReplacementImage
import dev.fitface.studio.core.model.WatchFaceRepository
import dev.fitface.studio.core.data.DiagnosticsReporter
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DiagnosticsSection
import dev.fitface.studio.core.model.UserMessage
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.encodeCoordinate
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.spriteResizeLimit
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WidgetMovePreview(
    val globalIndex: Int,
    val displayX: Float,
    val displayY: Float,
)

/** Where a widget is being moved to, in stored coordinate space. */
internal data class PendingWidgetTarget(val x: Int, val y: Int)

/**
 * Display position of a stored coordinate whose anchoring is already known — the exact
 * inverse of [encodeCoordinate].
 *
 * `displayCoordinate` infers the anchoring from the sign, which is right for a value read
 * out of a container and wrong for a coordinate a nudge is still accumulating: a widget
 * stored at `x = 0` stepped one pixel left reaches `-1`, which the sign rule reads as
 * "anchored to the far edge" and places at the opposite side of the face. The anchoring
 * belongs to the widget, so it is passed in rather than guessed.
 */
internal fun storedToDisplay(
    stored: Int,
    extent: Int,
    canvasExtent: Int,
    anchoredFromEnd: Boolean,
): Int = if (anchoredFromEnd) canvasExtent + stored - extent else stored

data class EditorUiState(
    val snapshot: EditorSnapshot? = null,
    val isWorking: Boolean = true,
    val fit: ImageFit = ImageFit.COVER,
    val pendingImage: ReplacementImage? = null,
    val placement: ImagePlacement = ImagePlacement(),
    val selectedWidgetIndex: Int? = null,
    val applyWidgetEditsToAllStyles: Boolean = true,
    val previewReviewed: Boolean = false,
    val pendingWidgetMove: WidgetMovePreview? = null,
    val directInstall: DirectInstallState = DirectInstallState(),
    val error: UserMessage? = null,
    /** The pasteable report, non-null while the dialog is open. */
    val diagnosticsReport: String? = null,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val repository: WatchFaceRepository,
    private val directInstaller: Fit3DirectInstaller,
    private val diagnostics: DiagnosticsLog,
    private val reporter: DiagnosticsReporter,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EditorUiState())
    val state = mutableState.asStateFlow()
    private var loadedProjectId: Long? = null
    private val messageIds = AtomicLong()
    // Move targets waiting to be committed, in the order their widgets were first queued.
    //
    // Every widget move — dragged or nudged — goes through here. A commit is a reparse, a
    // revalidate and a preview recompose, which is slower than a repeat fires and slower
    // than a second gesture starts, so the latest target per widget is committed and the
    // intermediate positions are dropped. That coalescing is the point of the design; it
    // is not a queue of one commit per tick.
    //
    // It has to be a map. As a single slot keyed by one global index, a target queued for
    // widget A and then replaced by one for widget B lost A's move outright, so two
    // back-to-back drags of different widgets only landed the second. Access is guarded
    // because the worker drains it from its own coroutine.
    private val pendingMoves = LinkedHashMap<Int, PendingWidgetTarget>()
    /** Whether a worker is committing [pendingMoves]. Guarded by that map's own lock. */
    private var moveWorkerDraining = false

    init {
        viewModelScope.launch {
            repository.observeImageFit().collect { fit ->
                mutableState.value = mutableState.value.copy(fit = fit)
            }
        }
        viewModelScope.launch {
            var lastPhase: DirectInstallPhase? = null
            directInstaller.state.collect { delivery ->
                // Physical delivery cannot be exercised in this repository's tests, so the
                // phase order a real transfer walked is the only account of where one
                // stopped. Counts and booleans only — no peer handle, address or name.
                if (delivery.phase != lastPhase) {
                    lastPhase = delivery.phase
                    diagnostics.info(
                        TAG,
                        "Install phase ${delivery.phase}",
                        "bytes=${delivery.acknowledgedBytes}/${delivery.totalBytes} " +
                            "windows=${delivery.acknowledgedWindows}/${delivery.totalWindows}" +
                            (delivery.failure?.let { " failure=$it" } ?: ""),
                    )
                }
                mutableState.value = mutableState.value.copy(directInstall = delivery)
            }
        }
        refreshDirectInstallEnvironment()
    }

    fun loadProject(projectId: Long) {
        if (loadedProjectId == projectId && mutableState.value.snapshot != null) return
        loadedProjectId = projectId
        operate { repository.openProject(projectId) }
    }

    fun selectStyle(style: String) {
        mutableState.value = mutableState.value.copy(
            selectedWidgetIndex = null,
            previewReviewed = false,
        )
        operate { repository.currentSnapshot(style) }
    }

    fun selectWidget(globalIndex: Int?) {
        mutableState.value = mutableState.value.copy(selectedWidgetIndex = globalIndex)
    }

    fun setApplyWidgetEditsToAllStyles(value: Boolean) {
        mutableState.value = mutableState.value.copy(applyWidgetEditsToAllStyles = value)
    }

    /**
     * Records that the validated preview has been looked at, which is what unlocks
     * install. Every commit clears it again, so the Validate page re-marks it.
     */
    fun markPreviewReviewed() {
        if (mutableState.value.previewReviewed) return
        mutableState.value = mutableState.value.copy(previewReviewed = true)
    }

    fun selectFit(value: ImageFit) {
        mutableState.value = mutableState.value.copy(
            fit = value,
            placement = centeredPlacement(value),
        )
        viewModelScope.launch {
            repository.setImageFit(value)
        }
    }

    fun prepareBackground(uri: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isWorking = true, error = null)
            runCatching { repository.prepareReplacementImage(uri) }
                .onSuccess { image ->
                    mutableState.value = mutableState.value.copy(
                        pendingImage = image,
                        placement = ImagePlacement(fit = mutableState.value.fit),
                        isWorking = false,
                    )
                }
                .onFailure(::showFailure)
        }
    }

    fun transformImage(zoomChange: Float, panX: Float, panY: Float) {
        val placement = mutableState.value.placement
        mutableState.value = mutableState.value.copy(
            placement = placement.copy(
                zoom = (placement.zoom * zoomChange).coerceIn(0.25f, 8f),
                offsetX = (placement.offsetX + panX).coerceIn(-2f, 2f),
                offsetY = (placement.offsetY + panY).coerceIn(-2f, 2f),
            ),
        )
    }

    /**
     * Steps the pending image's zoom by whole percentage points, so the SIZE buttons are
     * reversible where a multiplying factor was not. See [steppedZoom].
     */
    fun stepImageZoom(grow: Boolean) {
        val placement = mutableState.value.placement
        val zoom = steppedZoom(placement.zoom, grow)
        if (zoom == placement.zoom) return
        mutableState.value = mutableState.value.copy(placement = placement.copy(zoom = zoom))
    }

    fun resetImagePlacement() {
        mutableState.value = mutableState.value.copy(
            placement = ImagePlacement(fit = mutableState.value.fit),
        )
    }

    fun cancelBackground() {
        mutableState.value = mutableState.value.copy(pendingImage = null)
    }

    /**
     * Commits the pending image as the face's background.
     *
     * Which path it takes is decided by the container, not by a mode the user has to
     * pick: a face with a panel raster somewhere takes the same-size replacement, and one
     * with none has a background added, which is the only thing that can work there.
     */
    fun applyBackground() {
        val pending = mutableState.value.pendingImage ?: return
        val placement = mutableState.value.placement
        val adding = mutableState.value.snapshot?.canAddBackground == true
        operate(
            onSuccess = { current ->
                current.copy(pendingImage = null, previewReviewed = false)
            },
        ) {
            if (adding) {
                repository.addBackground(pending.uri, placement)
            } else {
                repository.replaceBackground(pending.uri, placement)
            }
        }
    }

    /**
     * Commits a dragged widget's new position, through the same worker a nudge uses.
     *
     * This used to set `isWorking` for the length of the commit, which turned the canvas
     * off — so a drag started inside that window found `enabled` false, bailed out of
     * `onDragStart` and was dropped silently, with no feedback at all. A commit is a
     * reparse plus a revalidate plus a preview recompose, so that window is not small,
     * and it is half of the reported "sometimes they stick". Queueing the target instead
     * leaves the canvas live: the optimistic preview shows the release position at once
     * and the worker commits it.
     */
    fun moveWidget(globalIndex: Int, x: Int, y: Int) {
        val snapshot = mutableState.value.snapshot ?: return
        val selected = snapshot.widgets.singleOrNull { it.globalIndex == globalIndex } ?: return
        if (selected.x == x && selected.y == y) return
        queueWidgetMove(snapshot, selected, x, y)
    }

    /**
     * Moves a widget by one pixel, coalescing repeats so press-and-hold works.
     *
     * A held nudge fires far faster than a container commit completes. Reading the
     * next position from the on-screen snapshot would therefore compute the same
     * target every time (the snapshot has not advanced yet) and the widget would not
     * move at all. So the target is accumulated here, the preview updates instantly,
     * and one worker commits whatever the latest target is — dropping the
     * intermediate positions instead of queueing a commit per tick.
     */
    fun nudgeWidget(globalIndex: Int, deltaX: Int, deltaY: Int) {
        val snapshot = mutableState.value.snapshot ?: return
        val widget = snapshot.widgets.firstOrNull { it.globalIndex == globalIndex } ?: return
        if (!widget.canEditPosition) return
        val base = pendingTarget(globalIndex) ?: PendingWidgetTarget(widget.x, widget.y)
        val next = clampToPanel(snapshot, widget, base.x + deltaX, base.y + deltaY)
        if (next.x !in Short.MIN_VALUE..Short.MAX_VALUE ||
            next.y !in Short.MIN_VALUE..Short.MAX_VALUE
        ) {
            return
        }
        // The clamp refused the step, so the widget is already as far over as it goes.
        if (next == base) return
        queueWidgetMove(snapshot, widget, next.x, next.y)
    }

    /**
     * Holds a nudged widget's rectangle on the panel.
     *
     * A nudge works in *stored* coordinates, and a stored coordinate is legitimately
     * negative: a widget anchored to the far edge is stored as `x < 0`. Clamping the stored
     * value to `>= 0` would therefore fling every end-anchored widget across the face. So
     * the clamp is applied in display space — by the same [constrainDragCoordinate] the
     * drag uses, offset by `drawOffset` so it is the drawn rectangle that is held, and
     * widened to admit where the widget already is so one that starts outside can still
     * walk back in — and the result is re-encoded with the anchoring it came in with.
     *
     * Without this the nudge had no bound but the Short range, so a held press walked a
     * widget clean off the canvas, after which it could not be tapped at all and only the
     * Widgets list could reach it.
     */
    private fun clampToPanel(
        snapshot: EditorSnapshot,
        widget: WidgetGuide,
        x: Int,
        y: Int,
    ): PendingWidgetTarget {
        val anchoredX = widget.x < 0
        val anchoredY = widget.y < 0
        return PendingWidgetTarget(
            x = encodeCoordinate(
                display = constrainDragCoordinate(
                    proposed = storedToDisplay(
                        x, widget.width, snapshot.preview.width, anchoredX,
                    ).toFloat(),
                    starting = storedToDisplay(
                        widget.x, widget.width, snapshot.preview.width, anchoredX,
                    ).toFloat(),
                    extent = widget.width,
                    canvasExtent = snapshot.preview.width,
                    drawOffset = widget.drawOffsetX,
                ).roundToInt(),
                extent = widget.width,
                canvasExtent = snapshot.preview.width,
                anchoredFromEnd = anchoredX,
            ),
            y = encodeCoordinate(
                display = constrainDragCoordinate(
                    proposed = storedToDisplay(
                        y, widget.height, snapshot.preview.height, anchoredY,
                    ).toFloat(),
                    starting = storedToDisplay(
                        widget.y, widget.height, snapshot.preview.height, anchoredY,
                    ).toFloat(),
                    extent = widget.height,
                    canvasExtent = snapshot.preview.height,
                    drawOffset = widget.drawOffsetY,
                ).roundToInt(),
                extent = widget.height,
                canvasExtent = snapshot.preview.height,
                anchoredFromEnd = anchoredY,
            ),
        )
    }

    private fun queueWidgetMove(
        snapshot: EditorSnapshot,
        widget: WidgetGuide,
        x: Int,
        y: Int,
    ) {
        // Queueing the target and deciding whether a worker is needed happen together, or
        // a target queued in the instant a worker is finishing would sit there with nobody
        // left to commit it.
        val startMoveWorker = synchronized(pendingMoves) {
            pendingMoves[widget.globalIndex] = PendingWidgetTarget(x, y)
            val alreadyDraining = moveWorkerDraining
            moveWorkerDraining = true
            !alreadyDraining
        }
        mutableState.value = mutableState.value.copy(
            previewReviewed = false,
            error = null,
            pendingWidgetMove = WidgetMovePreview(
                globalIndex = widget.globalIndex,
                displayX = storedToDisplay(
                    x, widget.width, snapshot.preview.width, widget.x < 0,
                ).toFloat(),
                displayY = storedToDisplay(
                    y, widget.height, snapshot.preview.height, widget.y < 0,
                ).toFloat(),
            ),
        )
        if (startMoveWorker) {
            viewModelScope.launch { drainWidgetMoves() }
        }
    }

    /**
     * Commits the queued targets, newest per widget, until there are none left.
     *
     * A target is taken out of the map *before* its commit, so a newer one arriving while
     * that commit is in flight is a fresh entry the next turn of the loop picks up — and a
     * commit that leaves the widget where it was cannot be retried forever.
     */
    private suspend fun drainWidgetMoves() {
        while (true) {
            val (globalIndex, target) = takePendingMove() ?: break
            val snapshot = mutableState.value.snapshot
            if (snapshot == null) {
                clearPendingMoves()
                break
            }
            val widget = snapshot.widgets.firstOrNull { it.globalIndex == globalIndex }
            // The widget is gone, or the snapshot has already caught up with the target.
            if (widget == null) continue
            if (widget.x == target.x && widget.y == target.y) continue
            val updated = runCatching {
                repository.moveWidget(
                    styleName = snapshot.selectedStyle,
                    globalIndex = widget.globalIndex,
                    widgetType = widget.type,
                    sequenceId = widget.sequenceId,
                    x = target.x,
                    y = target.y,
                    applyToAllStyles = mutableState.value.applyWidgetEditsToAllStyles,
                )
            }.getOrElse { error ->
                // Every target still queued was accumulated on top of the position the
                // container has just refused, so none of them survive it.
                clearPendingMoves()
                showFailure(error)
                null
            }
            // Round again either way: an emptied map is how this worker stops.
            if (updated == null) continue
            directInstaller.payloadChanged()
            mutableState.value = mutableState.value.copy(snapshot = updated, error = null)
        }
        // Nothing is outstanding, so the snapshot has overtaken the optimistic preview.
        if (mutableState.value.pendingWidgetMove != null) {
            mutableState.value = mutableState.value.copy(pendingWidgetMove = null)
        }
    }

    private fun pendingTarget(globalIndex: Int): PendingWidgetTarget? =
        synchronized(pendingMoves) { pendingMoves[globalIndex] }

    /** The next target to commit, or null — which also retires the worker asking. */
    private fun takePendingMove(): Pair<Int, PendingWidgetTarget>? {
        synchronized(pendingMoves) {
            // Every exit that returns null must retire the worker. Leaving
            // `moveWorkerDraining` true on the way out would convince `queueWidgetMove`
            // that a drain is still running, and no later move would ever start one.
            val entry = pendingMoves.entries.firstOrNull()
            if (entry == null) {
                moveWorkerDraining = false
                return null
            }
            val globalIndex = entry.key
            val target = entry.value
            pendingMoves.remove(globalIndex)
            return globalIndex to target
        }
    }

    private fun clearPendingMoves() {
        synchronized(pendingMoves) { pendingMoves.clear() }
    }

    /**
     * Steps the selected widget one rung along its resize ladder — see [spriteResizeLadder]
     * for why the sizes come from a ladder rather than from scaling what is on screen.
     */
    fun resizeSelectedWidget(grow: Boolean) {
        val snapshot = mutableState.value.snapshot ?: return
        val selected = snapshot.widgets.singleOrNull {
            it.globalIndex == mutableState.value.selectedWidgetIndex
        } ?: return
        if (!selected.canResize) return
        val next = nextSpriteSize(selected, grow) ?: return
        if (next.width == selected.width && next.height == selected.height) return
        operate {
            repository.resizeSprite(
                styleName = snapshot.selectedStyle,
                sequenceId = selected.sequenceId,
                width = next.width,
                height = next.height,
                applyToAllStyles = mutableState.value.applyWidgetEditsToAllStyles,
            )
        }
    }

    fun setSelectedWidgetColor(colorArgb: Int) {
        val snapshot = mutableState.value.snapshot ?: return
        val selected = snapshot.widgets.singleOrNull {
            it.globalIndex == mutableState.value.selectedWidgetIndex
        } ?: return
        if (selected.colorArgb == null || selected.colorArgb == colorArgb) return
        operate {
            repository.recolorPairWidget(
                styleName = snapshot.selectedStyle,
                globalIndex = selected.globalIndex,
                sequenceId = selected.sequenceId,
                x = selected.x,
                y = selected.y,
                colorArgb = colorArgb,
                applyToAllStyles = mutableState.value.applyWidgetEditsToAllStyles,
            )
        }
    }

    fun removeSelectedWidget() {
        val snapshot = mutableState.value.snapshot ?: return
        val selected = snapshot.widgets.singleOrNull {
            it.globalIndex == mutableState.value.selectedWidgetIndex
        } ?: return
        operate(
            onSuccess = { current -> current.copy(selectedWidgetIndex = null) },
        ) {
            repository.removeWidget(
                snapshot.selectedStyle,
                selected.globalIndex,
                selected.type,
                selected.sequenceId,
                selected.x,
                selected.y,
                requireFinal = false,
                applyToAllStyles = mutableState.value.applyWidgetEditsToAllStyles,
            )
        }
    }

    fun duplicateSelectedWidget() {
        val snapshot = mutableState.value.snapshot ?: return
        val selected = snapshot.widgets.singleOrNull {
            it.globalIndex == mutableState.value.selectedWidgetIndex
        } ?: return
        operate(
            onSuccess = { current ->
                current.copy(
                    selectedWidgetIndex = current.snapshot?.widgets
                        ?.maxByOrNull { it.ordinal }
                        ?.globalIndex,
                )
            },
        ) {
            repository.duplicateWidget(
                snapshot.selectedStyle,
                selected.globalIndex,
                selected.type,
                selected.sequenceId,
                selected.x,
                selected.y,
                applyToAllStyles = mutableState.value.applyWidgetEditsToAllStyles,
            )
        }
    }

    fun restoreWidget(removedId: Long) {
        operate(
            onSuccess = { current ->
                current.copy(
                    selectedWidgetIndex = current.snapshot?.widgets
                        ?.maxByOrNull { it.ordinal }
                        ?.globalIndex,
                )
            },
        ) { repository.restoreWidget(removedId) }
    }

    /**
     * Re-renders the watch's face-picker thumbnail from the current edit.
     *
     * Deliberately one-shot: it does nothing unless there is an edit to capture and
     * the thumbnail is not already showing it. Re-rendering a thumbnail that is
     * already current is pure loss — the widget pixels available to the composer come
     * from the vendor's smaller `preview.bin` render, so every pass that resamples
     * them softens the result a little further.
     */
    fun refreshThumbnail() {
        val snapshot = mutableState.value.snapshot ?: return
        if (!snapshot.canRefreshThumbnail) return
        operate { repository.refreshThumbnail() }
    }

    fun tintCyan() = operate { repository.tintBackground(0, 255, 255) }

    fun tintMagenta() = operate { repository.tintBackground(255, 0, 255) }

    fun reset() = operate(
        onSuccess = {
            it.copy(selectedWidgetIndex = null, pendingImage = null)
        },
    ) { repository.resetEdits() }

    fun refreshDirectInstallEnvironment() {
        viewModelScope.launch(Dispatchers.Default) {
            directInstaller.refreshEnvironment()
        }
    }

    fun initializeDirectInstall() {
        viewModelScope.launch(Dispatchers.Default) {
            directInstaller.initializeAndDiscover()
        }
    }

    fun installCurrentBin() {
        if (mutableState.value.directInstall.isActive) return
        viewModelScope.launch {
            runCatching { repository.prepareDirectInstall() }
                .onSuccess { payload ->
                    viewModelScope.launch(Dispatchers.Default) {
                        directInstaller.install(payload)
                    }
                }
                .onFailure(::showFailure)
        }
    }

    fun openCompanionApp() {
        if (!directInstaller.openCompanionApp()) {
            showFailure(
                IllegalStateException("The watch companion app is not installed on this phone"),
            )
        }
    }

    fun openPluginSettings() {
        if (!directInstaller.openPluginSettings()) {
            showFailure(IllegalStateException("The watch plugin's settings are unavailable"))
        }
    }

    fun confirmPluginChannelReleased() {
        directInstaller.confirmPluginChannelReleased()
    }

    /**
     * Back to the discovery step without tearing the whole setup down — the way out
     * of a transfer that failed after the plugin had already released the channel.
     */
    fun restartDirectInstallDiscovery() {
        viewModelScope.launch(Dispatchers.Default) {
            directInstaller.restartDiscovery()
        }
    }

    fun resetDirectInstall() {
        directInstaller.reset()
    }

    fun clearError(id: Long) {
        if (mutableState.value.error?.id == id) {
            mutableState.value = mutableState.value.copy(error = null)
        }
    }

    private fun operate(
        onSuccess: (EditorUiState) -> EditorUiState = { it },
        block: suspend () -> EditorSnapshot,
    ) {
        viewModelScope.launch {
            // A structural edit renumbers global indices, and a queued move target names
            // one — so it is dropped with the preview it was drawing rather than left to
            // land on whichever widget inherits that index.
            clearPendingMoves()
            mutableState.value = mutableState.value.copy(
                isWorking = true,
                pendingWidgetMove = null,
                previewReviewed = false,
                error = null,
            )
            runCatching { block() }
                .onSuccess { snapshot ->
                    // The bytes just changed, so a finished or failed transfer no
                    // longer describes what would be sent. Re-arm it without asking
                    // for the four-step setup again — the peers are still cached.
                    directInstaller.payloadChanged()
                    val selectedStillExists = mutableState.value.selectedWidgetIndex?.let { index ->
                        snapshot.widgets.any { it.globalIndex == index }
                    } ?: true
                    mutableState.value = onSuccess(
                        mutableState.value.copy(
                            snapshot = snapshot,
                            selectedWidgetIndex = mutableState.value.selectedWidgetIndex
                                .takeIf { selectedStillExists },
                            isWorking = false,
                            pendingWidgetMove = null,
                            error = null,
                        ),
                    )
                }
                .onFailure(::showFailure)
        }
    }

    private fun showFailure(error: Throwable) {
        if (error is CancellationException) throw error
        // technicalDetail carries the machine-readable half — which widget, which style,
        // which byte count refused the edit — and it stopped here before this, so the
        // report only ever had the sentence the user had already read.
        diagnostics.warn(
            TAG,
            "Editor operation failed",
            (error as? WatchFaceException)?.technicalDetail,
            error,
        )
        val text = when (error) {
            is WatchFaceException -> error.userMessage
            is SecurityException ->
                "FitFace Studio no longer has permission to access that file. Choose it again."
            is OutOfMemoryError ->
                "The selected image is too large to process safely. Try a smaller image."
            else -> error.message?.takeIf(String::isNotBlank)
                ?: "The operation could not be applied"
        }
        mutableState.value = mutableState.value.copy(
            isWorking = false,
            pendingWidgetMove = null,
            error = UserMessage(messageIds.incrementAndGet(), text),
        )
    }

    fun showDiagnostics() {
        viewModelScope.launch {
            val install = mutableState.value.directInstall
            val sections = listOfNotNull(
                repository.diagnosticsSection(),
                DiagnosticsSection(
                    title = "install",
                    lines = listOfNotNull(
                        "phase=${install.phase} previewReviewed=${mutableState.value.previewReviewed}",
                        // Booleans and counts only. The peer handles and bonded-device
                        // addresses this state machine works with are never collected.
                        "companion=${install.environment.companionAppInstalled} " +
                            "plugin=${install.environment.pluginInstalled}" +
                            (install.environment.pluginVersionName?.let { "@$it" } ?: "") + " " +
                            "accessory=${install.environment.accessoryFrameworkAvailable}",
                        "peers=${install.peersCached} " +
                            "bytes=${install.acknowledgedBytes}/${install.totalBytes} " +
                            "windows=${install.acknowledgedWindows}/${install.totalWindows}",
                        install.failure?.let { "failure=$it" },
                    ),
                ),
            )
            mutableState.value = mutableState.value.copy(diagnosticsReport = reporter.render(sections))
        }
    }

    fun dismissDiagnostics() {
        mutableState.value = mutableState.value.copy(diagnosticsReport = null)
    }

    override fun onCleared() {
        directInstaller.reset()
        super.onCleared()
    }

    private companion object {
        const val TAG = "EditorViewModel"
    }
}

/** One rung of a widget's resize ladder. */
internal data class SpriteSize(val percentOfOriginal: Int, val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
}

/** How much of the original extent one Smaller or Larger tap is worth. */
internal const val SpriteResizeStepPercent = 5

/**
 * Percentages of the *original* extent a resize is allowed to land on, 20% to 200%.
 *
 * Scaling the current extent by a factor instead is what made resizing unpredictable:
 * ×0.875 then ×1.125 does not come back, so 60×60 went to 52×52, back up to 58×58, down
 * to 50×50 — every round trip a little smaller, and no size reachable twice. Each rung
 * here is a fixed fraction of the extent the face shipped with, and
 * [dev.fitface.studio.core.model.WatchFaceRepository.resizeSprite] resamples the pristine
 * frames every time, so the same rung always produces the same pixels and Smaller then
 * Larger is exactly the size it started from.
 */
private val SpriteResizePercents: List<Int> =
    (20..200 step SpriteResizeStepPercent).toList()

/**
 * Every size the selected widget can be resized to, smallest first.
 *
 * The top is [spriteResizeLimit] per side: a sprite can always be taken back to the extent
 * its face shipped — `00022`'s digits are 114×136 — and 128 px is how far past that it may
 * grow. Rungs over that are **dropped, not clamped**, because clamping one side of an
 * aspect-locked pair squashes the sprite: growing 57×68 repeatedly used to end at 128×128.
 * So a face with oversized frames tops out at exactly 100%.
 */
internal fun spriteResizeLadder(originalWidth: Int, originalHeight: Int): List<SpriteSize> {
    if (originalWidth <= 0 || originalHeight <= 0) return emptyList()
    val widthLimit = spriteResizeLimit(originalWidth)
    val heightLimit = spriteResizeLimit(originalHeight)
    return SpriteResizePercents
        .map { percent ->
            SpriteSize(
                percentOfOriginal = percent,
                width = scaledExtent(originalWidth, percent),
                height = scaledExtent(originalHeight, percent),
            )
        }
        .filter { it.width <= widthLimit && it.height <= heightLimit }
        // A small sprite's rungs round to the same pixel size — at 5% steps a 4×4 sprite is
        // 4×4 anywhere from 90% to 110%. Keep one rung per size so a tap always changes
        // something, and label it with the percentage nearest 100 so the extent the face
        // shipped with is always the one that reads "100%".
        .groupBy { it.width to it.height }
        .map { (_, rungs) -> rungs.minBy { abs(it.percentOfOriginal - 100) } }
        .sortedBy { it.area }
}

private fun scaledExtent(extent: Int, percent: Int): Int =
    ((extent * percent + 50) / 100).coerceAtLeast(1)

/**
 * The rung a Smaller or Larger tap moves to, or null at the end of the ladder.
 *
 * Chosen by area rather than by index so an extent that is not on the ladder — a project
 * resized by an earlier build, whose sizes came from repeated multiplication — snaps onto
 * it in the direction of the tap instead of jumping.
 */
internal fun nextSpriteSize(widget: WidgetGuide, grow: Boolean): SpriteSize? {
    val ladder = spriteResizeLadder(widget.originalWidth, widget.originalHeight)
    val area = widget.width.toLong() * widget.height
    return if (grow) {
        ladder.firstOrNull { it.area > area }
    } else {
        ladder.lastOrNull { it.area < area }
    }
}

/** Where the widget currently sits on its ladder, as a percentage of the original. */
internal fun spriteSizePercent(widget: WidgetGuide): Int? =
    spriteResizeLadder(widget.originalWidth, widget.originalHeight)
        .firstOrNull { it.width == widget.width && it.height == widget.height }
        ?.percentOfOriginal

/**
 * Zoom for the pending background image, stepped in whole percentage points.
 *
 * Same rule as the widget ladder, for the milder version of the same reason: the buttons
 * multiplied by 1.02, so the step grew with the zoom — 100 → 102 → 104 → 106 → 108 → 110 →
 * 113 — and percentages in the gaps could not be reached from either direction. After a
 * pinch, which is continuous by design, the grid was whatever the gesture left behind:
 * 137 → 140 → 143, never a round number again. Stepping the percentage and snapping to the
 * grid makes every zoom reachable, every step the same size, and the readout predictable.
 */
internal fun steppedZoom(zoom: Float, grow: Boolean): Float {
    val percent = (zoom * 100).roundToInt().coerceIn(MinZoomPercent, MaxZoomPercent)
    val stepped = if (grow) {
        (percent / ZoomStepPercent + 1) * ZoomStepPercent
    } else {
        // ceil, so a zoom between two rungs still moves down a whole step.
        ((percent + ZoomStepPercent - 1) / ZoomStepPercent - 1) * ZoomStepPercent
    }
    return stepped.coerceIn(MinZoomPercent, MaxZoomPercent) / 100f
}

internal const val ZoomStepPercent = 2
internal const val MinZoomPercent = 25
internal const val MaxZoomPercent = 800

internal fun centeredPlacement(fit: ImageFit): ImagePlacement = ImagePlacement(fit = fit)
