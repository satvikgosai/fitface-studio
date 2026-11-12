package dev.fitface.studio.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.fitface.studio.core.data.DiagnosticsReporter
import dev.fitface.studio.core.delivery.DirectInstallPhase
import dev.fitface.studio.core.delivery.DirectInstallState
import dev.fitface.studio.core.delivery.Fit3DirectInstaller
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DiagnosticsSection
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
import dev.fitface.studio.core.model.PhotoClock
import dev.fitface.studio.core.model.PhotoClockSetting
import dev.fitface.studio.core.model.PhotoDraft
import dev.fitface.studio.core.model.PHOTO_ALBUM_LIMIT
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

/**
 * One photo of the album as it will be once sent: one already on the watch, or a new one.
 * [key] is the sent photo's name, or a new photo's [PendingPhoto.key].
 */
data class AlbumEntry(
    /** The photo on the watch this is, or null for a new one. */
    val sent: SentPhoto?,
    /** Its placing here: a new photo, or a sent one brought back from its kept original. */
    val pending: PendingPhoto?,
) {
    val key: String get() = sent?.name ?: pending!!.key

    val isNew: Boolean get() = sent == null

    /** A sent photo placed differently from how it went: it goes again, in its own place. */
    val isChanged: Boolean
        get() = sent != null && pending != null && pending.placement != sent.placement

    /**
     * What the face draws for it: its placing, else the sent photo as it went (made at the face's
     * size, so stretching it is drawing it as it is), else nothing for one with no copy kept.
     */
    fun drawn(id: Long): PendingPhoto? = pending
        ?: sent?.preview?.let { PendingPhoto(id, null, it, ImagePlacement(fit = ImageFit.STRETCH)) }
}

/**
 * A photo just taken out of the album, kept so it can be put back: a new one with its placing, or
 * a sent one with the placing it had been given; [id] tells one taking-out from the next.
 */
data class TakenOut(
    val id: Long,
    val key: String,
    val pending: PendingPhoto?,
    val sent: String?,
    val wasShown: Boolean,
    /** Where it stood among the new photos, so a new one goes back in its place. */
    val index: Int,
)

data class PhotoAlbumUiState(
    /** Reading the Photos face's package, which may mean downloading it first. */
    val loading: Boolean = true,
    val downloadFraction: Float = 0f,
    val album: PhotoAlbum? = null,
    /** Why the face could not be read; the page offers a retry. */
    val loadFailure: String? = null,
    /** New photos, and sent ones brought back to be placed; see [entries]. */
    val photos: List<PendingPhoto> = emptyList(),
    /** Sent photos taken out, which the next send asks the watch to delete. */
    val takenOut: Set<String> = emptySet(),
    /** The [AlbumEntry.key] being placed, which is the one the face will show; see [shownEntry]. */
    val shown: String? = null,
    /** Encoding the photos before the send starts. */
    val preparing: Boolean = false,
    val directInstall: DirectInstallState = DirectInstallState(),
    val error: UserMessage? = null,
    /** Shown once after picking more photos than the face has room for. */
    val dropped: Int = 0,
    /**
     * The clock chosen here and not sent yet, or null for the one [clockSetting] falls back to.
     * Sending clears it.
     */
    val clockChoice: PhotoClockSetting? = null,
    /** The request in flight, or the last one, sends only the clock. */
    val clockOnly: Boolean = false,
    /** The app menu's problem report, while it is open. */
    val diagnosticsReport: String? = null,
    /** The photo last taken out, offered back once; see [PhotoAlbumViewModel.putBack]. */
    val takenOutLast: TakenOut? = null,
) {
    /**
     * The album as it will be once sent, in the face's order: the sent photos not taken out, each
     * with its placing if it was brought back, then the new ones.
     */
    val entries: List<AlbumEntry>
        get() {
            val album = album ?: return emptyList()
            val placing = photos.filter { it.replaces != null }.associateBy { it.replaces }
            return album.sent.filter { it.name !in takenOut }.map { AlbumEntry(it, placing[it.name]) } +
                photos.filter { it.replaces == null }.map { AlbumEntry(null, it) }
        }

    /**
     * The photo being placed, which is the one the face will show: the one chosen, else the one
     * it shows now, else the first; null with no photos, when the face shows its own background.
     */
    val shownEntry: AlbumEntry?
        get() {
            val entries = entries
            return entries.firstOrNull { it.key == shown }
                ?: entries.firstOrNull { it.key == album?.shown }
                ?: entries.firstOrNull()
        }

    /** The photo being placed, when it can be: a new one, or a sent one brought back. */
    val selected: PendingPhoto?
        get() = shownEntry?.pending

    /** How many more photos may be picked. */
    val room: Int
        get() = (PHOTO_ALBUM_LIMIT - entries.size).coerceAtLeast(0)

    val added: Int get() = entries.count { it.isNew }

    val changed: Int get() = entries.count { it.isChanged }

    val removed: Int get() = album?.let { album -> takenOut.count { it in album.names } } ?: 0

    /** The face will show something other than it shows now. */
    val showsOther: Boolean
        get() {
            val album = album ?: return false
            val entry = shownEntry ?: return album.shown != null
            return entry.isNew || entry.isChanged || entry.key != album.shown
        }

    val busy: Boolean
        get() = loading || preparing || directInstall.isActive

    /**
     * The clock every request leaves the face with: the one chosen, else the one last sent, else
     * the plugin's default, else the face's. A send without a clock leaves the watch drawing
     * none, so that is only ever [PhotoClockSetting.None] chosen. Null only before the album is read.
     */
    val clockSetting: PhotoClockSetting?
        get() {
            val album = album ?: return null
            return clockChoice
                ?: album.clock?.takeIf { it.clock?.let { clock -> album.clockOptions?.accepts(clock) } != false }
                ?: PhotoClockSetting.Drawn(album.clockOptions?.default ?: PhotoClock.FACE_DEFAULT)
        }

    /** The clock drawn, as requests carry it; null for none, or before the album is read. */
    val clock: PhotoClock?
        get() = clockSetting?.clock

    /** The album or its clock differs from what was sent, so there is something to send. */
    val canSend: Boolean
        get() = added > 0 || changed > 0 || removed > 0 || showsOther || clockChoice != null
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
    private val reporter: DiagnosticsReporter,
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
        /** The clock it leaves the face with. */
        val clock: PhotoClockSetting? = null,
        /** Sends only the clock. */
        val clockOnly: Boolean = false,
        /** The arrangement it sent: its photos' keys, the ones taken out and the one chosen to show. */
        val photoKeys: Set<String> = emptySet(),
        val takenOut: Set<String> = emptySet(),
        val shownKey: String? = null,
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
                // The album as arranged last time and not sent, back as it was. A photo no longer
                // on the watch has nothing left to place again or take out.
                val draft = if (mutable.value.photos.isEmpty() && mutable.value.takenOut.isEmpty()) {
                    repository.draft(album.face.faceId)
                } else {
                    null
                }
                val takenOut = draft?.takenOut.orEmpty().filter { it in album.names }.toSet()
                val restored = draft?.photos.orEmpty()
                    .filter { it.replaces == null || it.replaces in album.names && it.replaces !in takenOut }
                    .map { PendingPhoto(ids.incrementAndGet(), null, it.source, it.placement, it.replaces, it.key) }
                mutable.update {
                    it.copy(loading = false, album = album, photos = it.photos + restored,
                        takenOut = it.takenOut + takenOut, shown = it.shown ?: draft?.shown)
                }
                // The photo shown, ready to be placed again if it can be.
                mutable.value.shownEntry?.let { bringBack(it.key) }
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
                    mutable.update { it.copy(photos = it.photos + photo, shown = photo.key) }
                    saveDraft()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    showFailure(error)
                }
            }
        }
    }

    fun clearDropped() = mutable.update { it.copy(dropped = 0) }

    /**
     * Chooses the photo to place, which is the one the face will show. A sent photo is brought
     * back on its kept original, placed as it went, so moving it is changing it.
     */
    fun select(key: String) {
        if (mutable.value.busy) return
        mutable.update { it.copy(shown = key) }
        saveDraft()
        bringBack(key)
    }

    private fun bringBack(key: String) {
        val state = mutable.value
        val album = state.album ?: return
        val entry = state.entries.firstOrNull { it.key == key } ?: return
        val sent = entry.sent ?: return
        val placement = sent.placement ?: return
        if (entry.pending != null) return
        viewModelScope.launch {
            val source = repository.keptSource(album.face.faceId, sent.name) ?: return@launch
            mutable.update { now ->
                // Taken out, or brought back already, while the original was read.
                if (now.photos.any { it.replaces == sent.name } || sent.name in now.takenOut) return@update now
                now.copy(photos = now.photos + PendingPhoto(ids.incrementAndGet(), null, source, placement, replaces = sent.name))
            }
        }
    }

    /**
     * Takes a photo out of the album: a new one is dropped, a sent one is deleted from the watch
     * by the next send. The photo beside it is shown instead.
     */
    fun remove(key: String) {
        if (mutable.value.busy) return
        mutable.update { state ->
            val entries = state.entries
            val index = entries.indexOfFirst { it.key == key }
            if (index < 0) return@update state
            val entry = entries[index]
            val left = entries - entry
            val wasShown = state.shownEntry?.key == key
            val next = if (wasShown) left.getOrNull(index) ?: left.lastOrNull() else state.shownEntry
            val pending = entry.pending
            state.copy(
                photos = state.photos.filterNot { it.key == key || entry.sent != null && it.replaces == entry.sent.name },
                takenOut = entry.sent?.let { state.takenOut + it.name } ?: state.takenOut,
                shown = next?.key,
                takenOutLast = TakenOut(ids.incrementAndGet(), key, pending, entry.sent?.name, wasShown,
                    pending?.let { state.photos.indexOf(it) } ?: -1),
            )
        }
        saveDraft()
        mutable.value.shownEntry?.let { bringBack(it.key) }
    }

    /** Puts back the photo last taken out, as it was, and shows it again if it was shown. */
    fun putBack(id: Long) {
        if (mutable.value.busy) return
        mutable.update { state ->
            val last = state.takenOutLast?.takeIf { it.id == id } ?: return@update state
            val photos = last.pending?.let { photo ->
                state.photos.toMutableList().apply { add(last.index.coerceIn(0, size), photo) }
            } ?: state.photos
            // Room may have been taken since: a new photo comes back only if it fits.
            if (last.sent == null && state.room == 0) return@update state.copy(takenOutLast = null)
            state.copy(
                photos = photos,
                takenOut = last.sent?.let { state.takenOut - it } ?: state.takenOut,
                shown = if (last.wasShown) last.key else state.shown,
                takenOutLast = null,
            )
        }
        saveDraft()
    }

    /** The offer to put back has gone without being taken. */
    fun clearTakenOut(id: Long) = mutable.update { if (it.takenOutLast?.id == id) it.copy(takenOutLast = null) else it }

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

    /**
     * The app menu's problem report, as the editor's: the shared report with this page's
     * transfer state, in counts and flags only, never a peer, address or photo name.
     */
    fun showDiagnostics() {
        viewModelScope.launch {
            val state = mutable.value
            val install = state.directInstall
            val section = DiagnosticsSection(
                title = "photos",
                lines = listOfNotNull(
                    "new=${state.added} changed=${state.changed} takenOut=${state.removed} sent=${state.album?.sent?.size ?: 0} " +
                        "clockOptions=${state.album?.clockOptions != null} clockChanged=${state.clockChoice != null} " +
                        "clockOff=${state.clockSetting == PhotoClockSetting.None}",
                    "phase=${install.phase} " +
                        "companion=${install.environment.companionAppInstalled} " +
                        "plugin=${install.environment.pluginInstalled}" +
                        (install.environment.pluginVersionName?.let { "@$it" } ?: ""),
                    install.failure?.let { "failure=$it" },
                ),
            )
            val report = reporter.render(listOf(section))
            mutable.update { it.copy(diagnosticsReport = report) }
        }
    }

    fun dismissDiagnostics() = mutable.update { it.copy(diagnosticsReport = null) }

    /**
     * Chooses the clock type, keeping the colour the face has or will have; from no clock, the
     * colour of the clock it falls back to, else the plugin's default.
     */
    fun chooseClockType(id: Int) {
        val state = mutable.value
        val options = state.album?.clockOptions ?: return
        val base = state.clock ?: state.copy(clockChoice = null).clock ?: options.default
        chooseClock(base.copy(type = id))
    }

    /** Chooses the clock colour, keeping the type the face has or will have. A clock that is off has none. */
    fun chooseClockColour(id: Int) {
        val clock = mutable.value.clock ?: return
        chooseClock(clock.copy(colour = id))
    }

    /** Chooses no clock: sends then leave its settings out, and the watch draws none. */
    fun chooseNoClock() {
        if (mutable.value.album?.clockOptions == null) return
        chooseSetting(PhotoClockSetting.None)
    }

    private fun chooseClock(next: PhotoClock) {
        val options = mutable.value.album?.clockOptions ?: return
        if (!options.accepts(next)) return
        chooseSetting(PhotoClockSetting.Drawn(next))
    }

    private fun chooseSetting(next: PhotoClockSetting) {
        if (mutable.value.busy) return
        // Choosing what the face falls back to, the clock last sent or else the default, is
        // choosing nothing new.
        mutable.update { it.copy(clockChoice = next.takeIf { setting -> setting != it.copy(clockChoice = null).clockSetting }) }
    }

    private fun place(change: (ImagePlacement) -> ImagePlacement) {
        if (mutable.value.preparing || mutable.value.directInstall.isActive) return
        mutable.update { state ->
            val target = state.selected ?: return@update state
            state.copy(photos = state.photos.map { if (it.key == target.key) it.copy(placement = change(it.placement)) else it })
        }
        saveDraft()
    }

    /**
     * Sends the album as arranged, worked out against what the watch has: new photos and ones
     * placed differently are encoded and added, a changed one in its own place with its old
     * version deleted; photos taken out are deleted; the one being placed is shown; and the
     * clock goes as it always does. With nothing to add or delete it only sets what is shown and
     * the clock.
     */
    fun send() {
        val current = mutable.value
        val album = current.album ?: return
        if (current.busy || !current.canSend) return
        val entries = current.entries
        val shownKey = current.shownEntry?.key
        mutable.update { it.copy(preparing = true) }
        viewModelScope.launch {
            try {
                val encoded = mutableMapOf<String, KeptPhoto>()
                for (entry in entries) {
                    if (!entry.isNew && !entry.isChanged) continue
                    val pick = entry.pending ?: continue
                    val photo = pick.uri?.let { repository.encode(it, pick.placement) }
                        ?: repository.encode(pick.preview, pick.placement)
                    encoded[entry.key] = KeptPhoto(photo, pick.preview, pick.placement)
                }
                val name = { entry: AlbumEntry -> encoded[entry.key]?.photo?.fileName ?: entry.sent!!.name }
                val sent = entries.map(name).distinct()
                val removed = (current.takenOut + entries.filter { it.isChanged }.map { it.sent!!.name })
                    .filter { it in album.names && it !in sent }
                val shown = entries.firstOrNull { it.key == shownKey }?.let(name)
                val photos = encoded.values.map { it.photo }.distinctBy { it.fileName }
                val request = PhotoAlbumRequest(album.face.faceId, album.face.preloaded, sent,
                    photos = photos,
                    removed = removed,
                    selected = shown ?: album.face.preloaded.first(),
                    clock = current.clock)
                val clockOnly = photos.isEmpty() && removed.isEmpty() && shown == album.shown
                start(request, Awaiting(sent, shown, encoded.values.distinctBy { it.photo.fileName },
                    clock = current.clockSetting, clockOnly = clockOnly,
                    photoKeys = current.photos.map { it.key }.toSet(), takenOut = current.takenOut,
                    shownKey = current.shown))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showFailure(error)
            } finally {
                mutable.update { it.copy(preparing = false) }
            }
        }
    }

    private fun start(request: PhotoAlbumRequest, after: Awaiting) {
        awaiting = after
        mutable.update { it.copy(clockOnly = after.clockOnly) }
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
                        repository.recordAlbum(album.face.faceId, after.sent, after.shown, after.keep, after.clock)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        showFailure(error)
                        // Kept in memory at least, so the page says what was sent.
                        after.sent.map { name -> album.sent.firstOrNull { it.name == name } ?: SentPhoto(name, null, null) }
                    }
                    mutable.update { state ->
                        state.copy(
                            album = state.album?.copy(sent = sent, shown = after.shown,
                                clock = after.clock ?: state.album.clock),
                            // Sent, so no longer waiting to go; a newer choice made meanwhile stays.
                            clockChoice = state.clockChoice.takeUnless { it == after.clock },
                            // Sent as arranged, so the album is now what the watch has. Anything
                            // arranged since the request was handed over stays to go next.
                            photos = state.photos.filter { it.key !in after.photoKeys && it.replaces in after.sent.plus(null) },
                            takenOut = state.takenOut - after.takenOut,
                            shown = if (state.shown == after.shownKey) after.shown else state.shown,
                        )
                    }
                    saveDraft()
                    mutable.value.shownEntry?.let { bringBack(it.key) }
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
            val state = mutable.value
            repository.saveDraft(album.face.faceId, PhotoDraft(
                photos = state.photos.map { DraftPhoto(it.key, it.preview, it.placement, it.replaces) },
                takenOut = state.takenOut.toList(),
                // Only a choice that differs from what the watch shows is unsent.
                shown = state.shown?.takeIf { it != album.shown },
            ))
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
