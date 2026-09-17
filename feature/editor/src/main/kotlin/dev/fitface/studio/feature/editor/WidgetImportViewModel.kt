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

enum class WidgetImportStage { FACES, WIDGETS, REVIEW }
data class WidgetImportUiState(
    val stage: WidgetImportStage = WidgetImportStage.FACES,
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
    val cachedOnly: Boolean = true,
    val query: String = "",
    val selectedFace: CatalogFace? = null,
    val donor: WidgetDonor? = null,
    val variant: EditorVariant? = null,
    val content: WidgetDonorVariant? = null,
    /** The widget picked off the donor face, before it is carried to the review. */
    val selectedWidget: Int? = null,
    /** The list stands in for the face where a widget has no rectangle to tap. */
    val showList: Boolean = false,
    val preview: WidgetImportPreview? = null,
    val busy: Boolean = false,
    val saving: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
    val imported: EditorSnapshot? = null,
) {
    val selectedFaceCached: Boolean get() = selectedFace?.productId in cachedFaces
    /** The faces the picker is showing, after the search box and the "on this phone" chip. */
    val visibleFaces: List<CatalogFace> get() = faces.filter { face ->
        (!cachedOnly || face.productId in cachedFaces) &&
            (face.faceId.contains(query, true) || face.name.contains(query, true))
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

    fun start(snapshot: EditorSnapshot) {
        if (opened && projectId == snapshot.projectId && target == snapshot.selectedVariant.basename) return
        close()
        opened = true
        projectId = snapshot.projectId
        target = snapshot.selectedVariant.basename
        mutable.value = WidgetImportUiState()
        loadCatalog()
    }

    fun setQuery(query: String) { mutable.update { it.copy(query = query) } }
    fun setCachedOnly(cachedOnly: Boolean) { mutable.update { it.copy(cachedOnly = cachedOnly) } }

    fun loadCatalog() = run {
        val faces = (catalog.cachedCatalog()?.takeIf { it.faces.isNotEmpty() } ?: catalog.loadCatalog()).faces
        val unavailable = catalog.uneditableAppIds()
        mutable.update { it.copy(faces = faces, uneditable = unavailable) }
        val cached = faces.filter { catalog.isPackageCached(it) }.map { it.productId }.toSet()
        // The chip defaults to what is already here, and falls back to the whole catalogue
        // when none of it is: an empty first screen is worse than a long one.
        mutable.update { it.copy(cachedFaces = cached, cachedOnly = it.cachedOnly && cached.isNotEmpty()) }
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
            val content = repository.widgetDonorVariant(donor.handle, variant.basename)
            mutable.update { it.copy(content = content) }
        }
    }

    fun selectVariant(variant: EditorVariant) {
        val donor = mutable.value.donor ?: return
        if (mutable.value.busy) return
        mutable.update {
            it.copy(variant = variant, content = null, preview = null, selectedWidget = null)
        }
        run {
            val content = repository.widgetDonorVariant(donor.handle, variant.basename)
            mutable.update { it.copy(content = content) }
        }
    }

    fun setShowList(showList: Boolean) { mutable.update { it.copy(showList = showList) } }

    /**
     * Picks a widget off the donor face and prices it, without leaving the picker.
     *
     * The preview is the priced edit, so it is built on selection rather than on the way to
     * the review page: the panel under the face quotes the exact cost, and
     * [useSelectedWidget] is then a page turn rather than a second wait.
     */
    fun selectWidget(index: Int) {
        val current = mutable.value
        if (current.busy || index in current.content?.unavailable.orEmpty()) return
        if (current.selectedWidget == index && current.preview != null) return
        val donor = current.donor ?: return
        val variant = current.variant ?: return
        mutable.update { it.copy(selectedWidget = index, preview = null) }
        run {
            val preview = repository.previewWidgetImport(donor.handle, variant.basename, index, projectId, target)
            mutable.update { it.copy(preview = preview) }
        }
    }

    fun clearSelectedWidget() {
        if (mutable.value.busy) return
        mutable.update { it.copy(selectedWidget = null, preview = null) }
    }

    fun useSelectedWidget() {
        if (mutable.value.busy || mutable.value.preview == null) return
        mutable.update { it.copy(stage = WidgetImportStage.REVIEW) }
    }

    fun add() {
        val preview = mutable.value.preview ?: return
        if (mutable.value.busy || mutable.value.imported != null) return
        mutable.update { it.copy(saving = true) }
        run {
            val snapshot = repository.importWidget(preview.ticket)
            mutable.update { it.copy(imported = snapshot) }
        }
    }

    /** Returns true when the whole importer should close. Saving is not cancellable. */
    fun back(): Boolean {
        val current = mutable.value
        if (current.saving) return false
        cancelWork()
        if (current.busy) {
            mutable.update { it.copy(busy = false, progress = null) }
            return false
        }
        when (current.stage) {
            // The selection and its priced preview survive the way back: the ticket names
            // this session, container and variant, and coming back to change your mind
            // should not rebuild the same edit.
            WidgetImportStage.REVIEW -> mutable.update {
                it.copy(stage = WidgetImportStage.WIDGETS, error = null)
            }
            WidgetImportStage.WIDGETS -> {
                releaseDonor()
                mutable.update { it.copy(stage = WidgetImportStage.FACES, donor = null, content = null,
                    variant = null, selectedWidget = null, preview = null, showList = false, error = null) }
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
        mutable.update { it.copy(busy = true, error = null) }
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
                    mutable.update { it.copy(error = (error as? WatchFaceException)?.userMessage
                        ?: error.message ?: "The widget could not be imported. Try again.") }
                }
            } finally {
                if (own == generation) mutable.update { it.copy(busy = false, saving = false, progress = null) }
            }
        }
    }
}
