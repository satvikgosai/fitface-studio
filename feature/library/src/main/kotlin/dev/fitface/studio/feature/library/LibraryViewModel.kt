package dev.fitface.studio.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.data.DiagnosticsReporter
import dev.fitface.studio.core.model.CatalogSort
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DiagnosticsSection
import dev.fitface.studio.core.model.FaceCatalogRepository
import dev.fitface.studio.core.model.ProjectSummary
import dev.fitface.studio.core.model.UserMessage
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.WatchFaceRepository
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isLoadingCatalog: Boolean = true,
    val isOpeningProject: Boolean = false,
    val faces: List<CatalogFace> = emptyList(),
    val styleCount: Int = 0,
    val catalogFromCache: Boolean = false,
    val catalogFetchedAtEpochMillis: Long = 0,
    val query: String = "",
    val sort: CatalogSort = CatalogSort.RECENT,
    val selectedFace: CatalogFace? = null,
    val selectedStyleId: Int? = null,
    val downloadingProductId: String? = null,
    val downloadFraction: Float = 0f,
    /**
     * Shown inside the face sheet. A snackbar cannot be seen behind a modal bottom
     * sheet, and a failed download is exactly when the sheet is on screen.
     */
    val sheetError: String? = null,
    val uneditableAppIds: Set<String> = emptySet(),
    val error: UserMessage? = null,
    /**
     * Why the catalogue is empty, kept until the next successful load.
     *
     * Separate from [error] because that one is a snackbar: it is cleared the moment it
     * has been shown, and the empty state outlives it by minutes. Leaving the panel to
     * assert "Check your connection" on its own is what made a rejected locale read as a
     * network problem on a phone with five bars.
     */
    val catalogFailure: String? = null,
    /** The pasteable report, non-null while the dialog is open. */
    val diagnosticsReport: String? = null,
    /**
     * The last run ended in a crash whose account has not been shown yet.
     *
     * Surfaced here because there is nowhere else it can be: the process was gone before
     * anything could be said at the time, and a sideloaded APK reports to no console.
     */
    val previousCrash: Boolean = false,
) {
    val isWorking: Boolean
        get() = isLoadingCatalog || isOpeningProject || downloadingProductId != null

    /**
     * Whether a tap on the grid may open the face sheet. Deliberately narrower than
     * [isWorking], and the value the grid's cards should be enabled on.
     *
     * The grid is painted from the on-disk cache before the network is touched, so
     * `isLoadingCatalog` is true for the whole opening window of every launch while the
     * screen already looks completely ready. Refusing the tap on [isWorking] made that
     * window silent — no sheet, no row spinner, no message — and a catalogue refresh
     * conflicts with nothing the sheet does: it only reads the face the tap carried.
     * Opening a project and a download in flight do conflict, so those still refuse.
     */
    val canSelectFace: Boolean
        get() = !isOpeningProject && downloadingProductId == null

    val visibleFaces: List<CatalogFace>
        get() {
            val needle = query.trim()
            val matched = if (needle.isEmpty()) {
                faces
            } else {
                faces.filter { face ->
                    face.name.contains(needle, ignoreCase = true) ||
                        face.description.contains(needle, ignoreCase = true) ||
                        face.faceId.contains(needle) ||
                        face.appId.contains(needle, ignoreCase = true)
                }
            }
            return sort.apply(matched)
        }
}

sealed interface LibraryEvent {
    data class OpenEditor(val projectId: Long) : LibraryEvent
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: WatchFaceRepository,
    private val catalog: FaceCatalogRepository,
    private val diagnostics: DiagnosticsLog,
    private val reporter: DiagnosticsReporter,
) : ViewModel() {
    val projects = repository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutableState = MutableStateFlow(LibraryUiState())
    val state = mutableState.asStateFlow()
    private val messageIds = AtomicLong()

    private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            if (reporter.hasPreviousCrash()) {
                mutableState.update { it.copy(previousCrash = true) }
            }
            runCatching { catalog.uneditableAppIds() }.getOrNull()?.let { known ->
                mutableState.update { it.copy(uneditableAppIds = known) }
            }
            // Render whatever was cached before the network is touched, so a warm
            // start shows the grid immediately instead of a spinner.
            runCatching { catalog.cachedCatalog() }.getOrNull()?.let { cached ->
                mutableState.update {
                    it.copy(
                        faces = cached.faces,
                        styleCount = cached.styleCount,
                        catalogFromCache = true,
                        catalogFetchedAtEpochMillis = cached.fetchedAtEpochMillis,
                    )
                }
            }
            loadCatalog(forceRefresh = false)
        }
    }

    fun refreshCatalog() {
        if (mutableState.value.isLoadingCatalog) return
        viewModelScope.launch { loadCatalog(forceRefresh = true) }
    }

    private suspend fun loadCatalog(forceRefresh: Boolean) {
        mutableState.update { it.copy(isLoadingCatalog = true, error = null) }
        runCatching { catalog.loadCatalog(forceRefresh) }
            .onSuccess { loaded ->
                mutableState.update {
                    it.copy(
                        isLoadingCatalog = false,
                        faces = loaded.faces,
                        styleCount = loaded.styleCount,
                        catalogFromCache = loaded.fromCache,
                        catalogFetchedAtEpochMillis = loaded.fetchedAtEpochMillis,
                        catalogFailure = null,
                    )
                }
            }
            .onFailure { error ->
                val message = error.userMessage()
                mutableState.update {
                    it.copy(
                        isLoadingCatalog = false,
                        error = message,
                        catalogFailure = message.text,
                        // Without this the header keeps its default and announces a live
                        // catalogue of nothing, next to a panel saying it is unavailable.
                        catalogFromCache = it.faces.isNotEmpty() && it.catalogFromCache,
                    )
                }
            }
    }

    fun setQuery(value: String) {
        mutableState.update { it.copy(query = value) }
    }

    fun setSort(value: CatalogSort) {
        mutableState.update { it.copy(sort = value) }
    }

    fun selectFace(face: CatalogFace) {
        if (!mutableState.value.canSelectFace) return
        mutableState.update {
            it.copy(
                selectedFace = face,
                selectedStyleId = face.styles.firstOrNull()?.id,
                sheetError = uneditableMessage.takeIf { _ -> face.appId in it.uneditableAppIds },
            )
        }
    }

    fun dismissFace() {
        if (mutableState.value.downloadingProductId != null) return
        mutableState.update {
            it.copy(selectedFace = null, selectedStyleId = null, sheetError = null)
        }
    }

    fun selectStyle(id: Int) {
        val face = mutableState.value.selectedFace ?: return
        if (face.styles.none { it.id == id }) return
        mutableState.update { it.copy(selectedStyleId = id) }
    }

    fun downloadSelectedFace() {
        val face = mutableState.value.selectedFace ?: return
        val styleId = mutableState.value.selectedStyleId ?: return
        if (mutableState.value.downloadingProductId != null) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    downloadingProductId = face.productId,
                    downloadFraction = 0f,
                    sheetError = null,
                    error = null,
                )
            }
            runCatching {
                val downloaded = catalog.downloadPackage(face, styleId) { progress ->
                    mutableState.update { it.copy(downloadFraction = progress.fraction) }
                }
                repository.openPackage(downloaded)
            }.onSuccess { snapshot ->
                mutableState.update {
                    it.copy(
                        downloadingProductId = null,
                        downloadFraction = 0f,
                        selectedFace = null,
                        selectedStyleId = null,
                    )
                }
                eventChannel.send(LibraryEvent.OpenEditor(snapshot.projectId))
            }.onFailure { error ->
                val message = error.userMessage()
                // A package with no container will never become editable, so record
                // it and stop offering the download.
                if ((error as? WatchFaceException)?.isUneditablePackage == true) {
                    runCatching { catalog.markUneditable(face.appId) }
                    mutableState.update { it.copy(uneditableAppIds = it.uneditableAppIds + face.appId) }
                }
                mutableState.update {
                    it.copy(
                        downloadingProductId = null,
                        downloadFraction = 0f,
                        sheetError = message.text,
                        error = message,
                    )
                }
            }
        }
    }

    fun openProject(project: ProjectSummary) {
        // Broader than `canSelectFace` on purpose. The repository holds one open
        // editing session, so this must not race the download that is about to open one,
        // and it navigates away from the library while the refresh is still writing into
        // that screen's state — unlike the sheet, which only reads the face it was given.
        if (mutableState.value.isWorking) return
        viewModelScope.launch {
            mutableState.update { it.copy(isOpeningProject = true, error = null) }
            runCatching { repository.openProject(project.id) }
                .onSuccess { snapshot ->
                    mutableState.update { it.copy(isOpeningProject = false) }
                    eventChannel.send(LibraryEvent.OpenEditor(snapshot.projectId))
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isOpeningProject = false, error = error.userMessage())
                    }
                }
        }
    }

    fun deleteProject(project: ProjectSummary) {
        viewModelScope.launch {
            runCatching { repository.deleteProject(project.id) }
                .onFailure { error ->
                    mutableState.update { it.copy(error = error.userMessage()) }
                }
        }
    }

    fun showDiagnostics() {
        viewModelScope.launch {
            val state = mutableState.value
            val section = DiagnosticsSection(
                title = "catalogue",
                lines = listOfNotNull(
                    "faces=${state.faces.size} styles=${state.styleCount} " +
                        "cached=${state.catalogFromCache}",
                    state.catalogFailure?.let { "failure=$it" },
                ),
            )
            mutableState.update { it.copy(diagnosticsReport = reporter.render(listOf(section))) }
        }
    }

    fun dismissDiagnostics() {
        // The crash is cleared on dismissal rather than on render, so closing the dialog
        // is what marks it seen. Left in place, every later report would carry a crash
        // from an unrelated session and read as one that keeps happening.
        val seen = mutableState.value.previousCrash
        mutableState.update { it.copy(diagnosticsReport = null, previousCrash = false) }
        if (seen) viewModelScope.launch { reporter.clearPreviousCrash() }
    }

    fun clearError(id: Long) {
        mutableState.update { current ->
            if (current.error?.id == id) current.copy(error = null) else current
        }
    }

    private fun Throwable.userMessage(): UserMessage {
        if (this is CancellationException) throw this
        // technicalDetail is the half that explains the failure and it used to stop here:
        // the store's `resultCode=1005 locale not supported` was captured at the throw
        // site and then discarded, leaving a phone that could never load the catalogue
        // with nothing but "Check your connection" to go on.
        diagnostics.warn(
            TAG,
            "Catalogue operation failed",
            (this as? WatchFaceException)?.technicalDetail,
            this,
        )
        val text = when (this) {
            is WatchFaceException -> userMessage
            is SecurityException -> "The downloaded package could not be opened safely."
            else -> message?.takeIf { it.isNotBlank() }
                ?: "That watch-face operation could not be completed"
        }
        return UserMessage(messageIds.incrementAndGet(), text)
    }

    private companion object {
        const val TAG = "LibraryViewModel"
        const val uneditableMessage =
            "This face is customised on the watch rather than shipped as an editable " +
                "container, so FitFace Studio has nothing to open."
    }
}
