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
import dev.fitface.studio.core.model.ProjectSort
import dev.fitface.studio.core.model.isOutdated
import dev.fitface.studio.core.model.ProjectSummary
import dev.fitface.studio.core.model.UserMessage
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.WatchFaceRepository
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
    /** Whether [sort] runs against the order its own label names, or backwards. */
    val sortReversed: Boolean = false,
    /**
     * Every saved project.
     *
     * In the state rather than beside it, because the face sheet has to derive from projects
     * and the catalogue at once — "which of these are mine, and has the store moved past
     * them" is a question neither flow can answer alone.
     */
    val projects: List<ProjectSummary> = emptyList(),
    val projectQuery: String = "",
    val projectSort: ProjectSort = ProjectSort.RECENT,
    val projectSortReversed: Boolean = false,
    /** The project a rename dialog is open for, and the one a delete confirmation is. */
    val renaming: ProjectSummary? = null,
    val deleting: ProjectSummary? = null,
    val selectedFace: CatalogFace? = null,
    val selectedStyleId: Int? = null,
    /**
     * Whether [selectedFace]'s current package is already on disk. Resolved when the sheet
     * opens, and false until it is — a caption that promises no download has to be checked,
     * not assumed.
     */
    val selectedFaceCached: Boolean = false,
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
     * A project was just copied, and nothing on screen proves it.
     *
     * Carries the name rather than a finished sentence, so the wording stays in
     * `strings.xml` where the rest of this screen's copy is. Separate from [error] because
     * the two are not the same kind of thing — and needed at all because under any sort but
     * the default the new row can land anywhere in the list, or off the bottom of it.
     */
    val duplicated: DuplicateNotice? = null,
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
            return sort.apply(matched, sortReversed)
        }

    val visibleProjects: List<ProjectSummary>
        get() {
            val needle = projectQuery.trim()
            val matched = if (needle.isEmpty()) {
                projects
            } else {
                projects.filter { project ->
                    project.name.contains(needle, ignoreCase = true) ||
                        project.faceName?.contains(needle, ignoreCase = true) == true ||
                        project.faceId.contains(needle) ||
                        project.displayName.contains(needle, ignoreCase = true)
                }
            }
            return projectSort.apply(matched, projectSortReversed)
        }

    /**
     * The projects started on one face, most recently edited first.
     *
     * Deliberately not [projectSort]: this is the list inside the face sheet, and it answers
     * "which of these was I last working on". The Projects page's chosen order is about that
     * page, and carrying it here would put an A–Z sort in front of someone who came to the
     * sheet to carry on where they left off.
     */
    fun projectsFor(faceId: String): List<ProjectSummary> =
        ProjectSort.RECENT.apply(projects.filter { it.faceId == faceId })
}

/**
 * What the one button at the bottom of the face sheet does.
 *
 * Pure, and separate from the composable, because the priority order is the whole point and
 * a rule about which of these wins should be readable without a screen attached.
 */
internal enum class FaceAction {
    OPENING,
    DOWNLOADING,
    NOT_EDITABLE,
    UPDATE,
    NEW_PROJECT,

    /**
     * The package is already here but nothing has been started on it, so there is no
     * download to offer and no sibling to be "new" beside.
     *
     * Its own case because [DOWNLOAD] promised one: a face whose projects have all been
     * deleted, or whose package was cached by a download whose project is gone, sat under a
     * "Download & edit" button above a caption saying nothing would be downloaded. Both
     * halves could not be right, and the caption was the true one.
     */
    OPEN,
    DOWNLOAD,
}

internal fun faceAction(
    downloading: Boolean,
    uneditable: Boolean,
    packageOnDevice: Boolean,
    projects: List<ProjectSummary>,
    storeVersionCode: Long,
): FaceAction = when {
    // "Downloading…" over a caption saying nothing would be downloaded is the same
    // dishonesty this screen is being fixed for. Opening a cached package still takes a
    // moment — the bytes are copied beside the project and the container is parsed — so it
    // is a state, just not that one.
    downloading && packageOnDevice -> FaceAction.OPENING
    downloading -> FaceAction.DOWNLOADING
    uneditable -> FaceAction.NOT_EDITABLE
    // Before NEW_PROJECT: both start a new project on whatever the store serves now, and
    // they differ only in what the button says. Saying "start a new project" while a newer
    // version is what would actually arrive is the half of this that was wrong.
    projects.any { it.isOutdated(storeVersionCode) } -> FaceAction.UPDATE
    projects.isNotEmpty() -> FaceAction.NEW_PROJECT
    // Last before DOWNLOAD, and only reachable with no projects at all: the package is
    // here, so the button must not promise to fetch it. The caption already said so, which
    // is how the two came to contradict each other.
    packageOnDevice -> FaceAction.OPEN
    else -> FaceAction.DOWNLOAD
}

/** A copy was made and named. The screen turns it into a sentence. */
data class DuplicateNotice(val id: Long, val name: String)

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
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state = mutableState.asStateFlow()
    private val messageIds = AtomicLong()

    private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.observeProjects().collect { saved ->
                mutableState.update { it.copy(projects = saved) }
            }
        }
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

    /**
     * Picking a different sort always starts in the direction that sort's label names.
     * Carrying a reversal across would land someone on "Face number ↓" having asked for
     * face number, with nothing but the chip to say why the list is upside down.
     */
    fun setSort(value: CatalogSort) {
        mutableState.update { it.copy(sort = value, sortReversed = false) }
    }

    fun reverseSort() {
        mutableState.update { it.copy(sortReversed = !it.sortReversed) }
    }

    fun setProjectQuery(value: String) {
        mutableState.update { it.copy(projectQuery = value) }
    }

    fun setProjectSort(value: ProjectSort) {
        mutableState.update { it.copy(projectSort = value, projectSortReversed = false) }
    }

    fun reverseProjectSort() {
        mutableState.update { it.copy(projectSortReversed = !it.projectSortReversed) }
    }

    fun selectFace(face: CatalogFace) {
        if (!mutableState.value.canSelectFace) return
        mutableState.update {
            it.copy(
                selectedFace = face,
                selectedStyleId = face.styles.firstOrNull()?.id,
                selectedFaceCached = false,
                sheetError = uneditableMessage.takeIf { _ -> face.appId in it.uneditableAppIds },
            )
        }
        viewModelScope.launch {
            val cached = runCatching { catalog.isPackageCached(face) }.getOrDefault(false)
            mutableState.update {
                // The sheet may have been dismissed, or another face chosen, while this
                // touched the disk. Landing the answer on whatever is open now would tell
                // someone about a face they are no longer looking at.
                if (it.selectedFace?.productId == face.productId) {
                    it.copy(selectedFaceCached = cached)
                } else {
                    it
                }
            }
        }
    }

    fun dismissFace() {
        if (mutableState.value.downloadingProductId != null) return
        mutableState.update {
            it.copy(
                selectedFace = null,
                selectedStyleId = null,
                selectedFaceCached = false,
                sheetError = null,
            )
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
                        selectedFaceCached = false,
                    )
                }
                eventChannel.send(LibraryEvent.OpenEditor(snapshot.projectId))
            }.onFailure { error ->
                // `runCatching` catches Throwable, so it catches the cancellation that
                // tearing this ViewModel down throws — and reporting that as a failed
                // download would write an error into a screen that is going away, and
                // swallow the cancellation the coroutine machinery is owed.
                if (error is CancellationException) throw error
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

    fun startRename(project: ProjectSummary) {
        mutableState.update { it.copy(renaming = project) }
    }

    fun dismissRename() {
        mutableState.update { it.copy(renaming = null) }
    }

    fun confirmRename(name: String) {
        val project = mutableState.value.renaming ?: return
        mutableState.update { it.copy(renaming = null) }
        viewModelScope.launch {
            runCatching { repository.renameProject(project.id, name) }
                .onFailure { error ->
                    mutableState.update { it.copy(error = error.userMessage()) }
                }
        }
    }

    /**
     * Copies a project, and says what the copy is called.
     *
     * No confirmation: it takes nothing away, and the copy can be deleted in two taps from
     * the row it appears on. The message is the whole feedback — under any sort but the
     * default the new row can land anywhere in the list.
     */
    fun duplicateProject(project: ProjectSummary) {
        if (mutableState.value.isWorking) return
        viewModelScope.launch {
            runCatching { repository.duplicateProject(project.id) }
                .onSuccess { duplicate ->
                    mutableState.update {
                        it.copy(
                            duplicated = DuplicateNotice(
                                messageIds.incrementAndGet(),
                                duplicate.name,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableState.update { it.copy(error = error.userMessage()) }
                }
        }
    }

    /**
     * Deleting asks first. It discards every edit in the project and cannot be undone, and
     * with more than one project on a face the rows beside it look very much alike.
     */
    fun startDelete(project: ProjectSummary) {
        mutableState.update { it.copy(deleting = project) }
    }

    fun dismissDelete() {
        mutableState.update { it.copy(deleting = null) }
    }

    fun confirmDelete() {
        val project = mutableState.value.deleting ?: return
        mutableState.update { it.copy(deleting = null) }
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

    fun clearDuplicated(id: Long) {
        mutableState.update { current ->
            if (current.duplicated?.id == id) current.copy(duplicated = null) else current
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
