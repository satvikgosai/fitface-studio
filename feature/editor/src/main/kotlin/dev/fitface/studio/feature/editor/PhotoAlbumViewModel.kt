package dev.fitface.studio.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.fitface.studio.core.delivery.DirectInstallPhase
import dev.fitface.studio.core.delivery.DirectInstallState
import dev.fitface.studio.core.delivery.Fit3DirectInstaller
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.FaceCatalogRepository
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.DraftPhoto
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.KeptPhoto
import dev.fitface.studio.core.model.SentPhoto
import dev.fitface.studio.core.model.PHOTO_ALBUM_FACE_ID
import dev.fitface.studio.core.model.PhotoAlbum
import dev.fitface.studio.core.model.PhotoAlbumRepository
import dev.fitface.studio.core.model.PhotoAlbumRequest
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.UserMessage
import dev.fitface.studio.core.model.WatchFaceException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A photo picked to send, or a sent one being placed again, with where it sits on the face. */
data class PendingPhoto(
    val id: Long,
    /** The picked file, or null for a sent photo placed again from its kept original. */
    val uri: String?,
    /** The upright original, reduced: what is drawn here, and what is kept once it is sent. */
    val preview: PreviewFrame,
    /** Cover by default: the watch shows a photo full-face, and black bars are rarely wanted. */
    val placement: ImagePlacement = ImagePlacement(fit = ImageFit.COVER),
    /** The sent photo this one takes the place of, or null for a new one. */
    val replaces: String? = null,
    /** Names its draft files; it outlives this page. */
    val key: String = UUID.randomUUID().toString(),
)

data class PhotoAlbumUiState(
    /** Reading the Photos face's package, which may mean downloading it first. */
    val loading: Boolean = true,
    val downloadFraction: Float = 0f,
    val album: PhotoAlbum? = null,
    /** Why the face could not be read; the page offers a retry. */
    val loadFailure: String? = null,
    val photos: List<PendingPhoto> = emptyList(),
    /** The [PendingPhoto.id] being placed. */
    val selectedId: Long? = null,
    /** Encoding the photos before the send starts. */
    val preparing: Boolean = false,
    val directInstall: DirectInstallState = DirectInstallState(),
    val error: UserMessage? = null,
    /** Shown once after picking more photos than the face has room for. */
    val dropped: Int = 0,
    /** The sent photo chosen on Install, which its actions act on. */
    val chosenSent: String? = null,
) {
    val selected: PendingPhoto?
        get() = photos.firstOrNull { it.id == selectedId } ?: photos.firstOrNull()

    /**
     * How many more photos may be picked: the face's room less the new ones already picked. A
     * sent photo placed again takes its own place.
     */
    val room: Int
        get() = ((album?.room ?: 0) - photos.count { it.replaces == null }).coerceAtLeast(0)

    /** The picked photo the face will show once sent: the first new one. */
    val shownPick: Long?
        get() = photos.firstOrNull { it.replaces == null }?.id

    val busy: Boolean
        get() = loading || preparing || directInstall.isActive
}

/**
 * Sends the user's photos to the watch's Photos face, and removes the ones this app sent.
 *
 * Interaction state is here, the album record and the encoding are the repository's, and the
 * transfer is the shared installer's: one transfer at a time for the whole app, whichever
 * page started it.
 */
@HiltViewModel
class PhotoAlbumViewModel @Inject constructor(
    private val repository: PhotoAlbumRepository,
    private val catalog: FaceCatalogRepository,
    private val directInstaller: Fit3DirectInstaller,
    private val diagnostics: DiagnosticsLog,
) : ViewModel() {
    private val mutable = MutableStateFlow(PhotoAlbumUiState())
    val state: StateFlow<PhotoAlbumUiState> = mutable.asStateFlow()

    private val ids = AtomicLong()
    private val messageIds = AtomicLong()

    /** What the album will be once the request in flight is done, recorded only when it is. */
    private class Awaiting(
        val sent: List<String>,
        val shown: String?,
        val keep: List<KeptPhoto>,
        /** The picks went with it, so they are no longer waiting to go. */
        val sendsPicks: Boolean,
    )

    @Volatile
    private var awaiting: Awaiting? = null

    /** A draft save waiting for changes to settle; see [saveDraft]. */
    private var draftSave: Job? = null

    /** The picks have changed since the draft was last saved. */
    @Volatile
    private var draftChanged = false

    init {
        // A face install's result is not this page's to show.
        directInstaller.clearOtherResult(album = true)
        viewModelScope.launch {
            var lastPhase: DirectInstallPhase? = null
            directInstaller.state.collect { delivery ->
                if (delivery.phase != lastPhase) {
                    lastPhase = delivery.phase
                    diagnostics.info(TAG, "Photos phase ${delivery.phase}",
                        "photos=${delivery.album?.photosSent}/${delivery.album?.photos} " +
                            "bytes=${delivery.acknowledgedBytes}/${delivery.totalBytes}")
                }
                mutable.update { it.copy(directInstall = delivery) }
                settle(delivery)
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutable.update { it.copy(loading = true, loadFailure = null, downloadFraction = 0f) }
            try {
                val faceId = PHOTO_ALBUM_FACE_ID.toString().padStart(5, '0')
                val face = catalog.cachedCatalog()?.faces?.firstOrNull { it.faceId == faceId }
                    ?: catalog.loadCatalog().faces.firstOrNull { it.faceId == faceId }
                    ?: throw WatchFaceException("The catalogue has no Photos face to send photos to.")
                val album = repository.album(face) { progress ->
                    mutable.update { it.copy(downloadFraction = progress.fraction) }
                }
                // What was picked and placed last time and not sent, back where it was. A change
                // of a photo no longer on the watch has nothing left to replace.
                val restored = if (mutable.value.photos.isEmpty()) {
                    repository.draft(album.face.faceId)
                        .filter { it.replaces == null || it.replaces in album.names }
                        .map { PendingPhoto(ids.incrementAndGet(), null, it.source, it.placement, it.replaces, it.key) }
                } else {
                    emptyList()
                }
                mutable.update {
                    it.copy(loading = false, album = album, photos = it.photos + restored,
                        selectedId = it.selectedId ?: restored.firstOrNull()?.id)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                diagnostics.warn(TAG, "Photos face unreadable", (error as? WatchFaceException)?.technicalDetail, error)
                mutable.update { it.copy(loading = false, loadFailure = text(error)) }
            }
        }
    }

    /** Adds picked photos, in order, as many as the face has room for. */
    fun pick(uris: List<String>) {
        if (uris.isEmpty() || mutable.value.busy) return
        val taken = uris.take(mutable.value.room)
        viewModelScope.launch {
            mutable.update { it.copy(dropped = uris.size - taken.size) }
            for (uri in taken) {
                try {
                    val preview = repository.preview(uri)
                    val photo = PendingPhoto(ids.incrementAndGet(), uri, preview)
                    mutable.update { it.copy(photos = it.photos + photo, selectedId = photo.id) }
                    saveDraft()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    showFailure(error)
                }
            }
        }
    }

    fun clearDropped() = mutable.update { it.copy(dropped = 0) }

    fun select(id: Long) = mutable.update { it.copy(selectedId = id) }

    fun remove(id: Long) {
        if (mutable.value.busy) return
        mutable.update { state ->
            val left = state.photos.filterNot { it.id == id }
            state.copy(photos = left, selectedId = state.selectedId.takeIf { it != id } ?: left.firstOrNull()?.id)
        }
        saveDraft()
    }

    fun transform(zoomChange: Float, panX: Float, panY: Float) = place { placement ->
        placement.copy(
            zoom = (placement.zoom * zoomChange).coerceIn(0.25f, 8f),
            offsetX = (placement.offsetX + panX).coerceIn(-MaxImageOffset, MaxImageOffset),
            offsetY = (placement.offsetY + panY).coerceIn(-MaxImageOffset, MaxImageOffset),
        )
    }

    fun stepZoom(grow: Boolean) = place { it.copy(zoom = steppedZoom(it.zoom, grow)) }

    /** A new fit starts that fit afresh, keeping the colour: the Background page's rule. */
    fun setFit(fit: ImageFit) = place { ImagePlacement(fit = fit, tint = it.tint) }

    /** Back to centred and unzoomed; the colour chosen is not placement and stays. */
    fun resetPlacement() = place { ImagePlacement(fit = it.fit, tint = it.tint) }

    /** The Background page's colour rule (`tintedColor`), for the photo being placed. */
    fun setTint(tint: Int?) = place { it.copy(tint = tint) }

    private fun place(change: (ImagePlacement) -> ImagePlacement) {
        if (mutable.value.preparing || mutable.value.directInstall.isActive) return
        mutable.update { state ->
            val target = state.selected ?: return@update state
            state.copy(photos = state.photos.map { if (it.id == target.id) it.copy(placement = change(it.placement)) else it })
        }
        saveDraft()
    }

    /**
     * Encodes every picked photo as placed, then sends them. A new photo joins the end of the
     * album and the first new one is shown; a sent photo placed again takes its old place, its
     * old version is deleted, and what was shown stays shown.
     */
    fun send() {
        val current = mutable.value
        val album = current.album ?: return
        if (current.photos.isEmpty() || current.busy) return
        mutable.update { it.copy(preparing = true) }
        viewModelScope.launch {
            try {
                val encoded = current.photos.map { pick ->
                    pick to (pick.uri?.let { repository.encode(it, pick.placement) }
                        ?: repository.encode(pick.preview, pick.placement))
                }.distinctBy { it.second.fileName }
                val after = album.names.toMutableList()
                val renamed = mutableMapOf<String, String>()
                encoded.forEach { (pick, photo) ->
                    val old = pick.replaces?.takeIf { it in after }
                    when {
                        old != null -> {
                            after[after.indexOf(old)] = photo.fileName
                            renamed[old] = photo.fileName
                        }
                        photo.fileName !in after -> after += photo.fileName
                    }
                }
                val sent = after.distinct()
                val firstNew = encoded.firstOrNull { it.first.replaces == null }?.second?.fileName
                val shown = firstNew
                    ?: album.shown?.let { renamed[it] ?: it }?.takeIf { it in sent }
                    ?: sent.first()
                val request = PhotoAlbumRequest(album.face.faceId, album.face.preloaded, sent,
                    photos = encoded.map { it.second },
                    removed = renamed.keys.filter { it !in sent },
                    selected = shown)
                start(request, Awaiting(sent, shown,
                    encoded.map { (pick, photo) -> KeptPhoto(photo, pick.preview, pick.placement) },
                    sendsPicks = true))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showFailure(error)
            } finally {
                mutable.update { it.copy(preparing = false) }
            }
        }
    }

    /** Asks the watch to delete every photo this app sent it, and show the face's own background. */
    fun removeSent() {
        val album = mutable.value.album ?: return
        if (album.sent.isEmpty() || mutable.value.busy) return
        request(Awaiting(emptyList(), null, emptyList(), sendsPicks = false)) {
            PhotoAlbumRequest(album.face.faceId, album.face.preloaded, emptyList(),
                removed = album.names, selected = album.face.preloaded.first())
        }
    }

    fun chooseSent(name: String) = mutable.update { it.copy(chosenSent = name) }

    /** Asks the face to show a photo it already has: no transfer, just its settings. */
    fun showSent(name: String) {
        val album = mutable.value.album ?: return
        if (name !in album.names || mutable.value.busy) return
        request(Awaiting(album.names, name, emptyList(), sendsPicks = false)) {
            PhotoAlbumRequest(album.face.faceId, album.face.preloaded, album.names, selected = name)
        }
    }

    /** Asks the watch to delete one sent photo; what was shown stays shown if it is still there. */
    fun removeSentPhoto(name: String) {
        val album = mutable.value.album ?: return
        if (name !in album.names || mutable.value.busy) return
        val left = album.names - name
        val shown = album.shown?.takeIf { it in left } ?: left.firstOrNull()
        request(Awaiting(left, shown, emptyList(), sendsPicks = false)) {
            PhotoAlbumRequest(album.face.faceId, album.face.preloaded, left, removed = listOf(name),
                selected = shown ?: album.face.preloaded.first())
        }
    }

    /**
     * Sends every kept photo again, as it was sent, in its place and with the same one shown:
     * for a watch that lost them. Photos sent before copies were kept cannot go again.
     */
    fun resendAll() {
        val album = mutable.value.album ?: return
        if (album.sent.isEmpty() || mutable.value.busy) return
        mutable.update { it.copy(preparing = true) }
        viewModelScope.launch {
            try {
                val kept = album.names.mapNotNull { repository.keptPhoto(album.face.faceId, it) }
                if (kept.isEmpty()) {
                    throw WatchFaceException("No copies of these photos are kept, so they cannot be sent again.")
                }
                val shown = album.shown ?: album.names.first()
                request(Awaiting(album.names, shown, emptyList(), sendsPicks = false)) {
                    PhotoAlbumRequest(album.face.faceId, album.face.preloaded, album.names, photos = kept,
                        selected = shown)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showFailure(error)
            } finally {
                mutable.update { it.copy(preparing = false) }
            }
        }
    }

    /**
     * Brings a sent photo back to Background, on its kept original where it was placed, to be
     * placed again; sending it then replaces the old one.
     */
    fun changeSent(name: String) {
        val current = mutable.value
        val album = current.album ?: return
        val sent = album.sent.firstOrNull { it.name == name } ?: return
        if (current.busy) return
        current.photos.firstOrNull { it.replaces == name }?.let { already ->
            mutable.update { it.copy(selectedId = already.id) }
            return
        }
        viewModelScope.launch {
            val source = repository.keptSource(album.face.faceId, name)
            val placement = sent.placement
            if (source == null || placement == null) {
                showFailure(WatchFaceException("This photo was sent before copies were kept. Pick it again to change it."))
                return@launch
            }
            val photo = PendingPhoto(ids.incrementAndGet(), null, source, placement, replaces = name)
            mutable.update { it.copy(photos = it.photos + photo, selectedId = photo.id) }
            saveDraft()
        }
    }

    private inline fun request(after: Awaiting, build: () -> PhotoAlbumRequest) {
        try {
            start(build(), after)
        } catch (error: Exception) {
            showFailure(error)
        }
    }

    private fun start(request: PhotoAlbumRequest, after: Awaiting) {
        awaiting = after
        directInstaller.sendAlbum(request)
        // Refused before it began (setup came undone), so nothing will finish it.
        if (directInstaller.state.value.album == null) awaiting = null
    }

    /**
     * Records the album once its request has been handed over, which is the most this app can
     * know: the watch's own answer goes to the stock plugin. Photos that went are no longer
     * waiting to go.
     */
    private fun settle(delivery: DirectInstallState) {
        val after = awaiting ?: return
        val album = mutable.value.album ?: return
        when {
            delivery.phase == DirectInstallPhase.COMPLETE && delivery.album != null -> {
                awaiting = null
                viewModelScope.launch {
                    val sent = try {
                        repository.recordAlbum(album.face.faceId, after.sent, after.shown, after.keep)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        showFailure(error)
                        // Kept in memory at least, so the page says what was sent.
                        after.sent.map { name -> album.sent.firstOrNull { it.name == name } ?: SentPhoto(name, null, null) }
                    }
                    mutable.update { state ->
                        state.copy(
                            album = state.album?.copy(sent = sent, shown = after.shown),
                            photos = if (after.sendsPicks) emptyList() else state.photos,
                            selectedId = if (after.sendsPicks) null else state.selectedId,
                            chosenSent = state.chosenSent?.takeIf { it in after.sent },
                        )
                    }
                    if (after.sendsPicks) saveDraft()
                }
            }
            delivery.phase == DirectInstallPhase.FAILED || delivery.album == null && !delivery.isActive ->
                awaiting = null
        }
    }

    /**
     * Saves the picks as a draft once they have stopped changing for a moment: a drag changes
     * the placement on every frame, and only where it ends matters. Closing the page saves at
     * once ([onCleared]).
     */
    private fun saveDraft() {
        draftChanged = true
        draftSave?.cancel()
        draftSave = viewModelScope.launch {
            delay(DRAFT_SETTLE_MS)
            writeDraft()
        }
    }

    private fun writeDraft() {
        val album = mutable.value.album ?: return
        runCatching {
            repository.saveDraft(album.face.faceId,
                mutable.value.photos.map { DraftPhoto(it.key, it.preview, it.placement, it.replaces) })
        }.onFailure { diagnostics.warn(TAG, "Photos draft not saved", null, it) }
        draftChanged = false
    }

    override fun onCleared() {
        // A flag, not the waiting job: the scope that job ran in is already cancelled by now.
        // The repository writes on its own scope, so this save outlives the page.
        if (draftChanged) writeDraft()
        super.onCleared()
    }

    fun refreshEnvironment() {
        viewModelScope.launch(Dispatchers.Default) { directInstaller.refreshEnvironment() }
    }

    fun discover() {
        viewModelScope.launch(Dispatchers.Default) { directInstaller.initializeAndDiscover() }
    }

    fun openCompanionApp() {
        if (!directInstaller.openCompanionApp()) {
            showFailure(IllegalStateException("The watch companion app is not installed on this phone"))
        }
    }

    fun openPluginSettings() {
        if (!directInstaller.openPluginSettings()) {
            showFailure(IllegalStateException("The watch plugin's settings are unavailable"))
        }
    }

    fun confirmPluginChannelReleased() = directInstaller.confirmPluginChannelReleased()

    fun rediscover() {
        viewModelScope.launch(Dispatchers.Default) { directInstaller.restartDiscovery() }
    }

    fun resetDelivery() = directInstaller.reset()

    fun clearError(id: Long) = mutable.update { if (it.error?.id == id) it.copy(error = null) else it }

    private fun showFailure(error: Throwable) {
        if (error is CancellationException) throw error
        diagnostics.warn(TAG, "Photos operation failed", (error as? WatchFaceException)?.technicalDetail, error)
        mutable.update { it.copy(error = UserMessage(messageIds.incrementAndGet(), text(error))) }
    }

    private fun text(error: Throwable): String = when (error) {
        is WatchFaceException -> error.userMessage
        is SecurityException -> "FitFace Studio no longer has permission to read that photo. Choose it again."
        else -> error.message?.takeIf(String::isNotBlank) ?: "The photos could not be prepared"
    }

    private companion object {
        const val TAG = "PhotoAlbumViewModel"
        const val DRAFT_SETTLE_MS = 400L
    }
}
