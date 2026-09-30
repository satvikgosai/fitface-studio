package dev.fitface.studio.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.fitface.studio.core.model.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WidgetImportStage { FACES, WIDGETS, REVIEW, BATCH }

/** Where one widget of a batch got to. */
enum class WidgetImportOutcome { QUEUED, ADDING, ADDED, FAILED }

/**
 * One line of the batch report.
 *
 * The name is resolved from the donor content by the screen; this carries the index so it
 * survives a variant's widget list being rebuilt underneath it.
 */
data class WidgetImportStep(
    val globalIndex: Int,
    val outcome: WidgetImportOutcome = WidgetImportOutcome.QUEUED,
    val reason: String? = null,
)
data class WidgetImportUiState(
    val targetSnapshot: EditorSnapshot? = null,
    val capacity: ContainerCapacity? = null,
    val stylesDeleted: Boolean = false,
    val stage: WidgetImportStage = WidgetImportStage.FACES,
    val backgroundMode: Boolean = false,
    val backgroundContent: BackgroundDonorVariant? = null,
    val backgroundPreview: BackgroundImportPreview? = null,
    val faces: List<CatalogFace> = emptyList(),
    val uneditable: Set<String> = emptySet(),
    /**
     * Product ids whose package is already on the phone.
     *
     * Read for the whole catalogue when it loads, not for one face after it is tapped: a
     * card that cannot say whether it costs a 30 MB download is a card someone has to tap
     * to find out. [FaceCatalogRepository.isPackageCached] is a file check, not a read.
     */
    val cachedFaces: Set<String> = emptySet(),
    val query: String = "",
    val selectedFace: CatalogFace? = null,
    val donor: WidgetDonor? = null,
    val variant: EditorVariant? = null,
    val content: WidgetDonorVariant? = null,
    /** The variant [content] was read from — which is not [variant] while a switch loads. */
    val contentVariant: EditorVariant? = null,
    /**
     * A switch to [variant] is loading, and [content] is still the previous variant's.
     *
     * The previous face stays on screen until the new one arrives, rather than the page
     * emptying for the fraction of a second a variant takes to read — which, with the pinned
     * progress strip flashing in and out above it, read as the whole screen flickering on
     * every chip tap, and worse on a phone than on the emulator. Nothing on the old face can
     * be picked meanwhile: its indices belong to the other variant.
     */
    val variantLoading: Boolean = false,
    /**
     * The widgets picked off the donor face, in pick order.
     *
     * Pick order is add order is record order is z-order, so the list is ordered rather
     * than a set. One pick is the proven single-import flow unchanged — it is priced
     * exactly and gets the before/after review. Two or more can only be estimated, because
     * the repository holds one pending import at a time and pricing the second would
     * destroy the first one's ticket.
     */
    val picks: List<Int> = emptyList(),
    /** Per-widget outcomes while a batch runs, and after it stops. */
    val batch: List<WidgetImportStep> = emptyList(),
    /**
     * The snapshot the last committed import produced, held back until the reader has seen
     * the report. Handing it up closes the importer, and a batch that stopped halfway has
     * something to say first.
     */
    val batchSnapshot: EditorSnapshot? = null,
    /** The list stands in for the face where a widget has no rectangle to tap. */
    val showList: Boolean = false,
    val preview: WidgetImportPreview? = null,
    /**
     * Why the one pick could not be priced — usually that it would take the face past the
     * 4 MiB the watch accepts.
     *
     * Kept apart from [error] on purpose. That one is the banner pinned above the page, and
     * a pricing failure landing there pushed the donor face down by four lines and left it
     * there for every pick after, long after the pick it described was gone. The panel says
     * it instead, beside the button it disables, and the next pick clears it.
     */
    val pickError: String? = null,
    val busy: Boolean = false,
    val saving: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
    val imported: EditorSnapshot? = null,
) {
    val selectedFaceCached: Boolean get() = selectedFace?.productId in cachedFaces

    /** The one pick, when there is exactly one — the case that is priced exactly. */
    val selectedWidget: Int? get() = picks.singleOrNull()
    val batchAdded: Int get() = batch.count { it.outcome == WidgetImportOutcome.ADDED }
    val batchRemaining: List<Int> get() =
        batch.filter { it.outcome == WidgetImportOutcome.QUEUED }.map { it.globalIndex }
    val batchRunning: Boolean get() = batch.any { it.outcome == WidgetImportOutcome.ADDING }
    /** The faces the picker is showing, after the search box. */
    val visibleFaces: List<CatalogFace> get() = faces.filter { face ->
        face.faceId.contains(query, true) || face.name.contains(query, true)
    }
}

/** A cancellable catalogue-only flow. It never asks for or opens another project. */
@HiltViewModel
class WidgetImportViewModel @Inject constructor(
    private val catalog: FaceCatalogRepository,
    private val repository: WatchFaceRepository,
    private val diagnostics: DiagnosticsLog,
) : ViewModel() {
    private val mutable = MutableStateFlow(WidgetImportUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0L
    private var projectId = 0L
    private var target = ""
    private var opened = false
    private var targetOriginal: String? = null

    fun start(snapshot: EditorSnapshot, backgroundMode: Boolean = false) {
        if (opened && projectId == snapshot.projectId && target == snapshot.selectedVariant.basename &&
            mutable.value.backgroundMode == backgroundMode) return
        close()
        opened = true
        projectId = snapshot.projectId
        target = snapshot.selectedVariant.basename
        targetOriginal = snapshot.originalVariants[target]
        mutable.value = WidgetImportUiState(backgroundMode = backgroundMode)
        loadCatalog()
    }

    fun setQuery(query: String) { mutable.update { it.copy(query = query) } }

    /** Keep donor choices, but all review tickets must be rebuilt against the new bytes. */
    fun acceptStyleDeletion(snapshot: EditorSnapshot) {
        val current = mutable.value
        if (current.busy || snapshot.projectId != projectId) return
        target = snapshot.originalVariants.entries.single { it.value == targetOriginal }.key
        val remaining = if (current.batch.isEmpty()) current.picks else current.batch
            .filter { it.outcome != WidgetImportOutcome.ADDED }.map { it.globalIndex }
        mutable.update { it.copy(targetSnapshot = snapshot, capacity = null, stylesDeleted = true,
            preview = null, backgroundPreview = null, pickError = null, error = null,
            batchSnapshot = snapshot.takeIf { current.batch.isNotEmpty() }, picks = remaining,
            stage = WidgetImportStage.WIDGETS) }
        if (current.backgroundMode) reviewBackground()
        else if (remaining.size == 1) priceSingle(remaining.single(), reviewAfter = true)
        else if (remaining.isNotEmpty()) mutable.update { it.copy(stage = WidgetImportStage.REVIEW) }
    }
    fun loadCatalog() = run {
        val faces = (catalog.cachedCatalog()?.takeIf { it.faces.isNotEmpty() } ?: catalog.loadCatalog()).faces
        val unavailable = catalog.uneditableAppIds()
        mutable.update { it.copy(faces = faces, uneditable = unavailable) }
        // Read for the whole catalogue, for the badge on every card: a face that costs a
        // 30 MB download should say so before it is tapped, not after.
        val cached = faces.filter { catalog.isPackageCached(it) }.map { it.productId }.toSet()
        mutable.update { it.copy(cachedFaces = cached) }
    }

    /**
     * Opens a donor in one tap.
     *
     * Choosing a face and then confirming it was two taps for one decision, and the second
     * one carried the only thing the first could not say — whether a download was coming.
     * The card says that now, so this is the whole gesture.
     */
    fun openFace(face: CatalogFace) {
        if (mutable.value.busy || face.appId in mutable.value.uneditable) return
        mutable.update { it.copy(selectedFace = face, error = null) }
        run {
            val active = currentCoroutineContext()
            val packageBytes = catalog.downloadPackage(face, face.styles.firstOrNull()?.id ?: 0) { progress ->
                active.ensureActive()
                mutable.update { it.copy(progress = progress.fraction) }
            }
            active.ensureActive()
            mutable.update { it.copy(cachedFaces = it.cachedFaces + face.productId, progress = null) }
            val donor = repository.inspectWidgetDonor(packageBytes)
            active.ensureActive()
            val variant = donor.variants.firstOrNull() ?: throw WatchFaceException("This face has no editable variants.")
            mutable.update { it.copy(donor = donor, variant = variant, stage = WidgetImportStage.WIDGETS, progress = null) }
            readVariant(donor, variant)
        }
    }

    fun selectVariant(variant: EditorVariant) {
        val current = mutable.value
        val donor = current.donor ?: return
        // Not refused on `busy`: a switch still loading, or a pick being priced, is simply
        // superseded — `run` cancels the call before it. Refusing it dimmed every chip for
        // the length of each load, which was half of the flicker.
        if (current.saving || current.progress != null) return
        if (variant == current.variant && !current.variantLoading &&
            (current.content != null || current.backgroundContent != null)) return
        mutable.update {
            it.copy(variant = variant, preview = null, backgroundPreview = null, picks = emptyList(), pickError = null,
                variantLoading = true)
        }
        run {
            try {
                readVariant(donor, variant)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Nothing of the new variant to show, and the old one is not what the chip
                // now says — so the page falls to its "could not be read" state and retry.
                mutable.update { it.copy(content = null, backgroundContent = null, contentVariant = null, variantLoading = false) }
                throw error
            }
        }
    }

    private suspend fun readVariant(donor: WidgetDonor, variant: EditorVariant) {
        if (mutable.value.backgroundMode) {
            val content = repository.backgroundDonorVariant(donor.handle, variant.basename)
            currentCoroutineContext().ensureActive()
            mutable.update { it.copy(backgroundContent = content, contentVariant = variant, variantLoading = false) }
        } else {
            val content = repository.widgetDonorVariant(donor.handle, variant.basename)
            currentCoroutineContext().ensureActive()
            mutable.update { it.copy(content = content, contentVariant = variant, variantLoading = false) }
        }
    }

    fun reviewBackground() {
        val current = mutable.value
        val source = current.donor ?: return
        val variant = current.contentVariant ?: return
        if (!current.backgroundMode || current.busy || current.backgroundContent?.background == null) return
        run {
            val preview = repository.previewBackgroundImport(source.handle, variant.basename, projectId, target)
            currentCoroutineContext().ensureActive()
            mutable.update { it.copy(backgroundPreview = preview, stage = WidgetImportStage.REVIEW) }
        }
    }

    fun applyBackground() {
        val current = mutable.value
        val preview = current.backgroundPreview ?: return
        if (!current.backgroundMode || current.busy || current.stage != WidgetImportStage.REVIEW) return
        mutable.update { it.copy(saving = true) }
        run {
            val snapshot = repository.importBackground(preview.ticket)
            mutable.update { it.copy(imported = snapshot) }
        }
    }

    fun setShowList(showList: Boolean) { mutable.update { it.copy(showList = showList) } }

    /**
     * Adds a widget to the picks, or takes it out again.
     *
     * Falling to exactly one pick prices it: the repository holds a single pending import,
     * so a ticket is only meaningful while one widget is chosen. Two or more clear it — the
     * set is quoted from [WidgetDonorVariant.addedBytes], which is a floor, and the panel
     * says so rather than promising a fit it cannot know.
     */
    fun togglePick(index: Int) {
        val current = mutable.value
        val content = current.content ?: return
        // Not blocked on `busy`. Picking the first widget starts pricing it, and the guard
        // that used to be here then swallowed the very next tap — so building a set of
        // three was a tap, a wait, a tap, a wait. Only a commit is uninterruptible.
        if (current.saving || current.variantLoading || index in content.unavailable) return
        setPicks(if (index in current.picks) current.picks - index else current.picks + index)
    }

    fun clearSelectedWidget() {
        if (mutable.value.saving) return
        setPicks(emptyList())
    }

    private fun setPicks(picks: List<Int>) {
        val current = mutable.value
        if (picks == current.picks) return
        val only = picks.singleOrNull()
        // Whatever was said about the previous picks no longer describes these.
        if (only == null) {
            // No single ticket can describe a set, so stop any pricing still in flight.
            // Left running it would land a `preview` that belongs to a pick the reader has
            // already moved past, and `add()` would happily commit it.
            cancelWork()
            mutable.update {
                it.copy(picks = picks, preview = null, pickError = null, error = null, capacity = null,
                    busy = false, progress = null)
            }
            return
        }
        mutable.update { it.copy(picks = picks, preview = null, pickError = null, error = null) }
        priceSingle(only)
    }

    private fun priceSingle(only: Int, reviewAfter: Boolean = false) {
        val current = mutable.value
        val picks = current.picks
        val donor = current.donor ?: return
        val variant = current.variant ?: return
        run {
            try {
                val preview = repository.previewWidgetImport(donor.handle, variant.basename, only, projectId, target)
                mutable.update { it.copy(preview = preview, capacity = null,
                    stage = if (reviewAfter) WidgetImportStage.REVIEW else it.stage) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostics.warn("WidgetImport", "Pricing a widget failed",
                    (error as? WatchFaceException)?.technicalDetail, error)
                // Only for the pick it was about: a reason landing after the reader has
                // moved on would disable a button for a widget that never failed.
                mutable.update {
                    if (it.picks != picks) it
                    else it.copy(capacity = error.containerCapacity(), pickError = (error as? WatchFaceException)?.userMessage
                        ?: error.message ?: "This widget could not be added.")
                }
            }
        }
    }

    /**
     * Turns to the review, for one pick or for a set.
     *
     * One pick needs its exact price first, because the review commits that ticket. A set
     * has no ticket to wait for — each widget is priced immediately before its own commit —
     * so its review is the face with every pick painted in, and the way on from it is
     * [addPicks].
     */
    fun useSelectedWidget() {
        val current = mutable.value
        if (current.busy || current.saving) return
        if (current.picks.size < 2 && current.preview == null) return
        mutable.update { it.copy(stage = WidgetImportStage.REVIEW) }
    }

    fun add() {
        val preview = mutable.value.preview ?: return
        if (mutable.value.busy || mutable.value.imported != null) return
        mutable.update { it.copy(saving = true) }
        run {
            val snapshot = repository.importWidget(preview.ticket)
            mutable.update { state -> if (state.batch.isEmpty()) state.copy(imported = snapshot) else
                state.copy(stage = WidgetImportStage.BATCH, batchSnapshot = snapshot,
                    batch = state.batch.filter { it.outcome == WidgetImportOutcome.ADDED } +
                        WidgetImportStep(state.picks.single(), WidgetImportOutcome.ADDED)) }
        }
    }

    /**
     * Adds every pick, one at a time, through the single path that is proven on hardware.
     *
     * This is a loop and it can only be a loop. The repository holds exactly one pending
     * import and `previewWidgetImport` clears it on entry, so pricing the second widget
     * destroys the first one's ticket; and `importWidget` validates the ticket against the
     * session, the donor and the target container **by reference**, which every commit
     * replaces. "Price them all, then commit them all" is not expressible. So each widget
     * is priced and committed in turn, exactly as one widget always was.
     *
     * One `run {}` for the whole batch, never one per item: `run` cancels the call before
     * it, so a loop of them would leave every import but the last one cancelled.
     *
     * **There is no rollback across commits.** Each import is a full commit. If the third
     * of five fails, the first two are on the face and saved, and the report says so in
     * those words. It stops at the first failure because all three limits a batch can hit
     * — the 4 MiB ceiling, the saved-artwork budget, the ten ROM font bindings — only get
     * tighter as it proceeds, so carrying on is more likely to fail again than to succeed.
     */
    fun addPicks() {
        val current = mutable.value
        if (current.busy || current.imported != null || current.picks.size < 2) return
        mutable.update {
            it.copy(stage = WidgetImportStage.BATCH, batch = it.batch.filter { step -> step.outcome == WidgetImportOutcome.ADDED } +
                it.picks.map(::WidgetImportStep))
        }
        runBatch()
    }

    /** Carries on with whatever a failure left queued, skipping the one that refused. */
    fun continueBatch() {
        val current = mutable.value
        if (current.busy || current.batchRemaining.isEmpty()) return
        runBatch()
    }

    private fun runBatch() {
        val current = mutable.value
        val donor = current.donor ?: return
        val variant = current.variant ?: return
        mutable.update { it.copy(saving = true, error = null) }
        run {
            for (index in mutable.value.batchRemaining) {
                mark(index, WidgetImportOutcome.ADDING)
                try {
                    val preview = repository.previewWidgetImport(
                        donor.handle, variant.basename, index, projectId, target,
                    )
                    val snapshot = repository.importWidget(preview.ticket)
                    mutable.update { it.copy(batchSnapshot = snapshot) }
                    mark(index, WidgetImportOutcome.ADDED)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutable.update { it.copy(capacity = error.containerCapacity()) }
                    diagnostics.warn("WidgetImport", "Batch import failed",
                        (error as? WatchFaceException)?.technicalDetail, error)
                    mark(index, WidgetImportOutcome.FAILED, (error as? WatchFaceException)?.userMessage
                        ?: error.message ?: "This widget could not be added.")
                    break
                }
            }
        }
    }

    private fun mark(index: Int, outcome: WidgetImportOutcome, reason: String? = null) {
        mutable.update { state ->
            state.copy(batch = state.batch.map {
                if (it.globalIndex == index) it.copy(outcome = outcome, reason = reason) else it
            })
        }
    }

    /**
     * Leaves the batch report, handing the editor whatever actually committed.
     *
     * The snapshot is held back while the report is on screen — a batch that stopped
     * halfway has something to say, and setting `imported` closes the importer. Backing out
     * of the report goes through here too, because the widgets are saved either way and an
     * editor left holding the container from before them would show a face that no longer
     * exists on disk.
     */
    fun finishBatch() {
        val snapshot = mutable.value.batchSnapshot
        if (snapshot == null) {
            mutable.update {
                it.copy(stage = WidgetImportStage.WIDGETS, batch = emptyList(), error = null)
            }
        } else {
            mutable.update { it.copy(imported = snapshot) }
        }
    }

    /** Returns true when the whole importer should close. Saving is not cancellable. */
    fun back(): Boolean {
        val current = mutable.value
        if (current.saving) return false
        cancelWork()
        if (current.busy) {
            // A switch given up on puts the chip back on the face still showing.
            mutable.update {
                it.copy(busy = false, progress = null, variantLoading = false,
                    variant = if (it.variantLoading) it.contentVariant ?: it.variant else it.variant)
            }
            return false
        }
        when (current.stage) {
            // The selection and its priced preview survive the way back: the ticket names
            // this session, container and variant, and coming back to change your mind
            // should not rebuild the same edit.
            WidgetImportStage.REVIEW -> mutable.update {
                it.copy(stage = WidgetImportStage.WIDGETS, error = null)
            }
            WidgetImportStage.BATCH -> finishBatch()
            WidgetImportStage.WIDGETS -> {
                releaseDonor()
                mutable.update { it.copy(stage = WidgetImportStage.FACES, donor = null, content = null,
                    backgroundContent = null, backgroundPreview = null, contentVariant = null, variant = null, picks = emptyList(), preview = null,
                    batch = emptyList(), batchSnapshot = null, capacity = null,
                    pickError = null, showList = false, error = null) }
            }
            WidgetImportStage.FACES -> return true
        }
        return false
    }

    fun close() {
        opened = false
        cancelWork()
        releaseDonor()
        // The ViewModel outlives the dialog. Do not retain the donor layers or a stale
        // target snapshot while the editor goes on producing new raster generations.
        mutable.value = WidgetImportUiState()
    }
    override fun onCleared() {
        val handle = mutable.value.donor?.handle
        work?.cancel()
        if (handle != null) viewModelScope.launch(NonCancellable) { repository.releaseWidgetDonor(handle) }
        super.onCleared()
    }
    private fun releaseDonor() {
        val handle = mutable.value.donor?.handle ?: return
        viewModelScope.launch { repository.releaseWidgetDonor(handle) }
    }
    private fun cancelWork() { generation++; work?.cancel(); work = null }
    private fun run(block: suspend () -> Unit) {
        cancelWork()
        val own = generation
        mutable.update { it.copy(busy = true, error = null, capacity = null) }
        work = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (own == generation) {
                    val face = mutable.value.selectedFace
                    if (error is WatchFaceException && error.isUneditablePackage && face != null) {
                        try { catalog.markUneditable(face.appId) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (cacheError: Exception) { diagnostics.warn("WidgetImport", "Could not remember uneditable face", error = cacheError) }
                        mutable.update { it.copy(uneditable = it.uneditable + face.appId) }
                    }
                    diagnostics.warn("WidgetImport", "Widget import failed", (error as? WatchFaceException)?.technicalDetail, error)
                    mutable.update { it.copy(capacity = error.containerCapacity(), error = (error as? WatchFaceException)?.userMessage
                        ?: error.message ?: "The widget could not be imported. Try again.") }
                }
            } finally {
                if (own == generation) mutable.update { it.copy(busy = false, saving = false, progress = null) }
            }
        }
    }
}
