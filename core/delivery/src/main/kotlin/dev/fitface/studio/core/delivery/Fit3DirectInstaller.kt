package dev.fitface.studio.core.delivery

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.samsung.android.sdk.accessory.SAAgentV2
import com.samsung.android.sdk.accessory.SAPeerAgent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.fitface.studio.core.model.AlbumPhoto
import dev.fitface.studio.core.model.DirectInstallPayload
import dev.fitface.studio.core.model.PhotoAlbumRequest
import dev.fitface.studio.core.model.WatchFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

@Singleton
class Fit3DirectInstaller @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(DirectInstallState())
    val state: StateFlow<DirectInstallState> = mutableState.asStateFlow()

    @Volatile
    private var watchfaceAgent: WatchfaceDeliveryAgent? = null
    @Volatile
    private var otaAgent: OtaTransferDeliveryAgent? = null
    @Volatile
    private var discoveryStarted = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generation = AtomicInteger()
    @Volatile
    private var watchdog: Job? = null

    /** The Photos request in flight, or null for a face install or nothing. */
    @Volatile
    private var albumRequest: PhotoAlbumRequest? = null

    /**
     * Whether the watch has been sent the opening edit-info request and not yet the closing
     * one, so a request that stops in between owes it the plugin's cancel.
     */
    @Volatile
    private var albumOpen = false

    private val discoveryListener = DiscoveryListener {
            profile,
            peer,
            code,
            outcome,
            detail,
        ->
        when (outcome) {
            "peer_found" -> updatePeer(profile, peer)
            "duplicate_request" -> Unit
            // The watch being disconnected is the normal state after a channel
            // handover, not a dead end: it is exactly what the user has to undo to
            // discover again. FAILED would strand them, so this stays recoverable.
            "device_not_connected" -> needsWatchConnection(
                "The Fit3 is not connected, and discovery needs it to be. $restorePlugin, " +
                    "reconnect the watch in the companion app, then discover again — the " +
                    "channel is only released afterwards.",
            )
            // The accessory framework refusing to initialize arrives here, not through
            // requestAgent's own callback, and it is the shape a phone with nothing
            // installed now reaches — because discovery is what decides that, rather than
            // a package probe refusing in advance. It has to stay recoverable: installing
            // the plugin and connecting the watch is exactly what fixes it, and FAILED can
            // only be left by restarting the whole setup.
            "agent_error" -> failAgentInit("$profile agent", code, detail)
            else -> fail(
                when (outcome) {
                    "service_not_found" ->
                        "The connected accessory does not expose $profile."
                    else -> "Peer discovery failed: $outcome ($detail)"
                },
            )
        }
    }

    private val transferListener = object : TransferListener {
        override fun onPayloadVerified(payload: WatchFile, windows: Int) {
            when (payload) {
                is DirectInstallPayload -> faceVerified(payload, windows)
                is AlbumPhoto -> photoVerified(payload, windows)
            }
        }

        private fun photoVerified(photo: AlbumPhoto, windows: Int) {
            val progress = state.value.album ?: return
            val event = if (progress.photosSent == 0) {
                DeliveryEvent.PAYLOAD_VERIFIED
            } else {
                DeliveryEvent.NEXT_FILE_VERIFIED
            }
            if (!advance(event) {
                    it.copy(
                        phase = DirectInstallPhase.TRANSFERRING,
                        fileName = photo.fileName,
                        sha256 = photo.sha256,
                        acknowledgedWindows = 0,
                        totalWindows = windows,
                        message = "Sending photo ${progress.photosSent + 1} of ${progress.photos}.",
                    )
                }
            ) {
                return
            }
            armWatchdog(DirectInstallPhase.TRANSFERRING, TRANSFER_WATCHDOG_MS)
        }

        private fun faceVerified(payload: DirectInstallPayload, windows: Int) {
            if (!advance(DeliveryEvent.PAYLOAD_VERIFIED) {
                    it.copy(
                        phase = DirectInstallPhase.TRANSFERRING,
                        faceId = payload.faceId,
                        samplerId = payload.samplerId,
                        fileName = payload.fileName,
                        sha256 = payload.sha256,
                        acknowledgedBytes = 0,
                        totalBytes = payload.size,
                        acknowledgedWindows = 0,
                        totalWindows = windows,
                        // The boundary case goes in the message rather than into a
                        // refusal: a payload that divides exactly into windows leaves no
                        // short final window, which is the one transfer shape that has
                        // never been watched end to end. Naming it here means a stall at
                        // the last window arrives in the bug report already explained.
                        message = if (payload.endsOnWindowBoundary) {
                            "BIN hash verified. Opening the direct transfer; this one ends " +
                                "exactly on a window boundary."
                        } else {
                            "BIN hash verified. Opening the direct transfer."
                        },
                    )
                }
            ) {
                return
            }
            armWatchdog(DirectInstallPhase.TRANSFERRING, TRANSFER_WATCHDOG_MS)
        }

        override fun onWindowAcknowledged(
            acknowledgedBytes: Int,
            totalBytes: Int,
            acknowledgedWindows: Int,
            totalWindows: Int,
        ) {
            if (!advance(DeliveryEvent.TRANSFER_PROGRESS) {
                    val album = it.album
                    if (album != null) {
                        // Across the whole album, so the bar does not empty between photos.
                        it.copy(
                            phase = DirectInstallPhase.TRANSFERRING,
                            acknowledgedBytes = album.bytesSent + acknowledgedBytes,
                            acknowledgedWindows = acknowledgedWindows,
                            totalWindows = totalWindows,
                            message = "Photo ${album.photosSent + 1} of ${album.photos}: window " +
                                "$acknowledgedWindows of $totalWindows accepted.",
                        )
                    } else {
                        it.copy(
                            phase = DirectInstallPhase.TRANSFERRING,
                            acknowledgedBytes = acknowledgedBytes,
                            totalBytes = totalBytes,
                            acknowledgedWindows = acknowledgedWindows,
                            totalWindows = totalWindows,
                            message = "Fit3 accepted window $acknowledgedWindows of $totalWindows.",
                        )
                    }
                }
            ) {
                return
            }
            // Re-armed only for an acknowledgement that counted. Re-arming first would
            // let an abandoned worker keep the watchdog alive for a transfer nobody is
            // waiting on.
            armWatchdog(DirectInstallPhase.TRANSFERRING, TRANSFER_WATCHDOG_MS)
        }

        override fun onTransferStatus(message: String) {
            if (!advance(DeliveryEvent.TRANSFER_PROGRESS) { it.copy(message = message) }) return
            // A status line is progress, and re-arming on it is what keeps the protocol's
            // own tail — the 15 s BIN verification, the close handshake, the teardown —
            // inside a watchdog that used to count nothing but acknowledged windows. See
            // `transferProgressRearmsWatchdog` for why that mattered only once a fired
            // watchdog began to actually stop the transfer, and why VERIFYING is excluded.
            if (transferProgressRearmsWatchdog(state.value.phase)) {
                armWatchdog(DirectInstallPhase.TRANSFERRING, TRANSFER_WATCHDOG_MS)
            }
        }

        override fun onTransferComplete(payload: WatchFile) {
            if (!DeliveryProgress.accepts(state.value.phase, DeliveryEvent.TRANSFER_COMPLETE)) {
                return
            }
            when (payload) {
                is DirectInstallPayload -> faceTransferred(payload)
                is AlbumPhoto -> photoTransferred(payload)
            }
        }

        private fun photoTransferred(photo: AlbumPhoto) {
            val request = albumRequest ?: return
            var next: AlbumPhoto? = null
            if (!advance(DeliveryEvent.TRANSFER_COMPLETE) { current ->
                    val album = current.album ?: return@advance current
                    val sent = album.copy(
                        photosSent = album.photosSent + 1,
                        bytesSent = album.bytesSent + photo.size,
                    )
                    next = request.photos.getOrNull(sent.photosSent)
                    current.copy(
                        album = sent,
                        acknowledgedBytes = sent.bytesSent,
                        message = "Photo ${sent.photosSent} of ${sent.photos} sent.",
                    )
                }
            ) {
                return
            }
            val following = next
            if (following == null) {
                startClosingCommands(request)
            } else {
                armWatchdog(DirectInstallPhase.TRANSFERRING, TRANSFER_WATCHDOG_MS)
                otaAgent?.runPayload(following) ?: fail("OTA agent is unavailable")
            }
        }

        private fun faceTransferred(payload: DirectInstallPayload) {
            val agent = watchfaceAgent
            if (agent == null) {
                fail("Watch-face agent disappeared after transfer")
            } else {
                agent.install(payload)
            }
        }

        override fun onTransferFailed(message: String, peerLost: Boolean) {
            if (!DeliveryProgress.accepts(state.value.phase, DeliveryEvent.FAILURE)) return
            if (peerLost) restartDiscovery(message) else fail(message)
        }

        override fun onWatchReply(data: ByteArray) {
            mutableState.update { withWatchReply(it, "ota", data) }
        }
    }

    private val installListener = object : InstallListener {
        override fun onInstallRequested(payload: DirectInstallPayload) {
            if (!advance(DeliveryEvent.INSTALL_REQUESTED) {
                    it.copy(
                        phase = DirectInstallPhase.INSTALLING,
                        message = "Transfer checked. Sending the install request.",
                    )
                }
            ) {
                return
            }
            armWatchdog(DirectInstallPhase.INSTALLING, PHASE_WATCHDOG_MS)
        }

        override fun onInstallDelivered(payload: DirectInstallPayload) {
            if (!advance(DeliveryEvent.INSTALL_DELIVERED) {
                    it.copy(
                        phase = DirectInstallPhase.COMPLETE,
                        acknowledgedBytes = payload.size,
                        totalBytes = payload.size,
                        message = "Install request delivered. Check the Fit3, then reconnect " +
                            "it in the companion app. $restorePlugin.",
                    )
                }
            ) {
                return
            }
            cancelWatchdog()
        }

        override fun onInstallFailed(message: String, peerLost: Boolean) {
            if (!DeliveryProgress.accepts(state.value.phase, DeliveryEvent.FAILURE)) return
            if (peerLost) restartDiscovery(message) else fail(message)
        }

        override fun onWatchReply(data: ByteArray) {
            mutableState.update { withWatchReply(it, "watchface", data) }
        }
    }

    private val commandListener = object : CommandListener {
        override fun onCommandsDelivered(batch: Int) {
            val request = albumRequest ?: return
            when (batch) {
                OPENING_BATCH -> {
                    if (!advance(DeliveryEvent.ALBUM_OPENED) { it }) return
                    albumOpen = true
                    if (request.photos.isEmpty()) {
                        startClosingCommands(request)
                    } else {
                        otaAgent?.runPayload(request.photos.first()) ?: fail("OTA agent is unavailable")
                    }
                }
                CLOSING_BATCH -> {
                    if (!advance(DeliveryEvent.INSTALL_DELIVERED) {
                            it.copy(
                                phase = DirectInstallPhase.COMPLETE,
                                message = if (request.isRemoval) {
                                    "Remove request sent. Check the Photos face on the watch, " +
                                        "then reconnect it in the companion app. $restorePlugin."
                                } else if (request.isSelection) {
                                    "Request sent to show that photo. Check the Photos face on " +
                                        "the watch, then reconnect it in the companion app. $restorePlugin."
                                } else {
                                    "Photos request sent. Check the Photos face on the watch, " +
                                        "then reconnect it in the companion app. $restorePlugin."
                                },
                            )
                        }
                    ) {
                        return
                    }
                    albumOpen = false
                    cancelWatchdog()
                }
            }
        }

        override fun onCommandProgress(batch: Int, sent: Int, total: Int) {
            if (!advance(DeliveryEvent.COMMAND_PROGRESS) {
                    it.copy(message = "Sent request $sent of $total to the Photos face.")
                }
            ) {
                return
            }
            armWatchdog(state.value.phase, PHASE_WATCHDOG_MS)
        }

        override fun onCommandsFailed(message: String, peerLost: Boolean) {
            if (!DeliveryProgress.accepts(state.value.phase, DeliveryEvent.FAILURE)) return
            if (peerLost) restartDiscovery(message) else fail(message)
        }

        override fun onCommandRefused(reason: String) {
            if (!DeliveryProgress.accepts(state.value.phase, DeliveryEvent.FAILURE)) return
            fail(reason)
        }
    }

    /** After the last photo, or at once for a removal: the album change and the face's settings. */
    private fun startClosingCommands(request: PhotoAlbumRequest) {
        if (!advance(DeliveryEvent.ALBUM_COMMANDS_REQUESTED) {
                it.copy(
                    phase = DirectInstallPhase.INSTALLING,
                    message = if (request.isRemoval) {
                        "Asking the watch to remove the photos."
                    } else if (request.isSelection) {
                        "Asking the watch to show the photo."
                    } else {
                        "Photos transferred. Adding them to the Photos face."
                    },
                )
            }
        ) {
            return
        }
        armWatchdog(DirectInstallPhase.INSTALLING, PHASE_WATCHDOG_MS)
        watchfaceAgent?.sendCommands(CLOSING_BATCH, PhotoAlbumProtocol.closing(request))
            ?: fail("Watch-face agent is unavailable")
    }

    /**
     * Applies [change] only if [event] is still welcome in the phase the machine is in.
     *
     * The test happens *inside* the atomic update, so a callback arriving from a worker
     * thread cannot read one phase and write against another. See [DeliveryProgress] for
     * what each of these callbacks used to overwrite when it arrived late.
     *
     * @return whether the change was applied, so the caller knows whether to arm or
     *   cancel a watchdog on the back of it.
     */
    private fun advance(
        event: DeliveryEvent,
        change: (DirectInstallState) -> DirectInstallState,
    ): Boolean {
        var applied = false
        mutableState.update { current ->
            // Assigned rather than latched: `update` is a compare-and-set loop and can
            // run this block more than once under contention, so the verdict has to be
            // the one from the pass that actually wrote. Latching it would report an
            // applied change from a discarded attempt — and `onInstallDelivered` cancels
            // the watchdog on the strength of this answer.
            applied = DeliveryProgress.accepts(current.phase, event)
            if (!applied) return@update current
            change(current)
        }
        return applied
    }

    fun refreshEnvironment() {
        val companions = probeCompanions()
        val helperGranted = helperNearbyGranted()
        val pluginGranted = pluginNearbyGranted(companions.pluginInstalled)
        mutableState.update { current ->
            val nextPhase = when {
                current.isTerminal -> current.phase
                current.isActive -> current.phase
                // What is installed no longer decides anything here. NEEDS_PLUGIN is
                // still reachable, but only once an attempt has actually failed — a probe
                // that pre-emptively refused was how a working phone got told it could
                // not transfer. Discovery is the arbiter.
                !helperGranted -> DirectInstallPhase.NEEDS_HELPER_PERMISSION
                current.peersCached &&
                    (pluginGranted == false || current.pluginNearbyReleaseAcknowledged) ->
                    DirectInstallPhase.READY
                current.peersCached -> DirectInstallPhase.PEERS_CACHED
                pluginGranted == false -> DirectInstallPhase.NEEDS_WATCH_CONNECTION
                else -> DirectInstallPhase.IDLE
            }
            current.copy(
                phase = nextPhase,
                environment = companions,
                helperNearbyGranted = helperGranted,
                pluginNearbyGranted = pluginGranted,
                message = if (nextPhase == current.phase && current.isTerminal) {
                    current.message
                } else {
                    environmentMessage(nextPhase, companions, current)
                },
            )
        }
    }

    fun initializeAndDiscover() {
        refreshEnvironment()
        val environment = state.value
        // Deliberately no environment gate here. Whether the channel opens is a question
        // only the accessory framework can answer, and it answers it below; refusing in
        // advance on a package-name probe is what stopped a paired, connected watch.
        if (!environment.helperNearbyGranted) {
            fail("Grant FitFace Studio Nearby devices access first")
            return
        }
        if (environment.pluginNearbyGranted == false) {
            // Discovery finds peers over a connection the stock plugin owns, so with
            // the plugin's Nearby access switched off there is nothing to discover.
            // This is a setting the user can put back, so it must not land in FAILED,
            // which can only be left by restarting the whole setup.
            mutableState.update {
                it.copy(
                    phase = DirectInstallPhase.NEEDS_WATCH_CONNECTION,
                    message = "Discovery needs the watch connected, and the stock plugin is " +
                        "what keeps it connected. Turn the plugin's Nearby access back on, " +
                        "reconnect the watch, then discover — the channel is only released " +
                        "afterwards.",
                )
            }
            return
        }
        if (watchfaceAgent != null && otaAgent != null) {
            discoverPeers()
            return
        }
        mutableState.update {
            it.copy(
                phase = DirectInstallPhase.INITIALIZING,
                message = "Preparing the watch connection…",
            )
        }
        armWatchdog(DirectInstallPhase.INITIALIZING, PHASE_WATCHDOG_MS)
        val requestGeneration = generation.get()
        requestWatchfaceAgent(requestGeneration)
        requestOtaAgent(requestGeneration)
    }

    /**
     * Whether a send may start. Neither refusal is a failed transfer: each is setup that has
     * come undone, with a checklist step that says how to put it back. Failing here used to make
     * a recoverable state terminal.
     */
    private fun readyToSend(): Boolean {
        if (state.value.isActive) return false
        refreshEnvironment()
        val current = state.value
        if (!current.peersCached) {
            restartDiscovery("Discover both Fit3 peers before sending.")
            return false
        }
        if (!current.pluginChannelReleased) {
            mutableState.update {
                it.copy(
                    phase = DirectInstallPhase.PEERS_CACHED,
                    message = "Complete step 4 to let the stock plugin release the " +
                        "accessory channel before sending.",
                )
            }
            return false
        }
        return true
    }

    fun install(payload: DirectInstallPayload) {
        if (!readyToSend()) return
        albumRequest = null
        mutableState.update {
            it.copy(
                phase = DirectInstallPhase.VERIFYING,
                failure = null,
                faceId = payload.faceId,
                samplerId = payload.samplerId,
                fileName = payload.fileName,
                sha256 = payload.sha256,
                totalBytes = payload.size,
                album = null,
                message = "Checking the face file…",
            )
        }
        armWatchdog(DirectInstallPhase.VERIFYING, PHASE_WATCHDOG_MS)
        otaAgent?.runPayload(payload) ?: fail("OTA agent is unavailable")
    }

    /**
     * Sends a Photos request: the plugin's opening edit-info request, then each photo over the
     * same transfer a face uses (one channel per photo, as for a face), then the album change
     * and the face's settings. A request with no photo, a removal or a change of the shown one,
     * skips the transfer.
     *
     * Like an install, nothing the watch decides comes back here, so the most this can report
     * is that every request was handed over.
     */
    fun sendAlbum(request: PhotoAlbumRequest) {
        if (!readyToSend()) return
        albumRequest = request
        albumOpen = false
        mutableState.update {
            it.copy(
                phase = DirectInstallPhase.VERIFYING,
                failure = null,
                faceId = request.faceId,
                samplerId = null,
                fileName = null,
                sha256 = null,
                acknowledgedBytes = 0,
                totalBytes = request.photos.sumOf { photo -> photo.size },
                acknowledgedWindows = 0,
                totalWindows = 0,
                album = AlbumProgress(photos = request.photos.size, removing = request.isRemoval,
                    showing = request.isSelection),
                message = "Opening the Photos face's settings on the watch…",
            )
        }
        armWatchdog(DirectInstallPhase.VERIFYING, PHASE_WATCHDOG_MS)
        watchfaceAgent?.sendCommands(OPENING_BATCH, PhotoAlbumProtocol.opening(request))
            ?: fail("Watch-face agent is unavailable")
    }

    /**
     * Puts the page back on "connect the watch, then discover" after the channel has
     * already been handed over.
     *
     * Discovery needs the plugin holding the watch and the transfer needs it to let
     * go, in that order, so anything that fails after the handover can only be
     * retried by walking both again. The cached peer handles are dropped here because
     * one does not survive the connection it was found on — re-sending against a
     * stale handle fails identically every time. Everything the phone told us about
     * itself, and both granted permissions, survive: this is not [reset].
     */
    @Synchronized
    fun restartDiscovery(reason: String? = null) {
        cancelWatchdog()
        abandonInFlight()
        watchfaceAgent?.forgetPeer()
        otaAgent?.forgetPeer()
        discoveryStarted = false
        mutableState.update { it.rewoundToDiscovery(reason ?: it.failure) }
        refreshEnvironment()
    }

    fun confirmPluginChannelReleased() {
        mutableState.update {
            it.copy(
                pluginNearbyReleaseAcknowledged = true,
                phase = if (it.peersCached) DirectInstallPhase.READY else it.phase,
                message = if (it.peersCached) {
                    "Channel handoff acknowledged. Ready to send."
                } else {
                    it.message
                },
            )
        }
    }

    /**
     * Re-arms a finished or failed transfer for a payload that has since changed.
     *
     * The peers, the granted permissions and the released channel all survive an
     * edit, so a second install must not demand the whole four-step setup again. Only
     * the transfer bookkeeping is cleared. Without this, editing after a successful
     * install left the Install page showing nothing but "Back to canvas".
     */
    fun payloadChanged() {
        mutableState.update { current ->
            if (current.isActive) return@update current
            // A rewound setup keeps the reason it rewound on screen; those bytes are
            // gone now, so the banner would outlive what it describes.
            if (!current.isTerminal) return@update current.copy(failure = null)
            current.copy(
                phase = if (current.setupComplete) {
                    DirectInstallPhase.READY
                } else {
                    DirectInstallPhase.IDLE
                },
                faceId = null,
                samplerId = null,
                fileName = null,
                sha256 = null,
                acknowledgedBytes = 0,
                totalBytes = 0,
                acknowledgedWindows = 0,
                totalWindows = 0,
                album = null,
                failure = null,
                message = if (current.setupComplete) {
                    "Edit applied. Ready to send the updated face."
                } else {
                    "Edit applied. Set up direct install again to send it."
                },
            )
        }
    }

    /**
     * Clears a finished or failed send of the other kind, so the page that opens next does not
     * report a Photos request as a face install or the other way round. Setup survives, as it
     * does for [payloadChanged].
     *
     * @param album which kind the opening page sends; a result of that kind is left alone.
     */
    fun clearOtherResult(album: Boolean) {
        mutableState.update { current ->
            if (!current.isTerminal || (current.album != null) == album) return@update current
            current.copy(
                phase = if (current.setupComplete) DirectInstallPhase.READY else DirectInstallPhase.IDLE,
                faceId = null,
                samplerId = null,
                fileName = null,
                sha256 = null,
                acknowledgedBytes = 0,
                totalBytes = 0,
                acknowledgedWindows = 0,
                totalWindows = 0,
                album = null,
                failure = null,
                message = if (current.setupComplete) "Ready to send." else environmentMessage(
                    DirectInstallPhase.IDLE, current.environment, current,
                ),
            )
        }
    }

    /**
     * Opens whichever companion app this phone has.
     *
     * Presence is not enough to launch one: only some of the ids in
     * [CompanionResolution.COMPANION_PACKAGES] carry a launcher activity, and the stock
     * plugin carries none at all — every activity in it is unexported — so asking the one
     * app that definitely owns the channel to come to the front is not a thing Android
     * will do. Hence walking the list for something launchable rather than trusting the
     * resolved id, and hence [openPluginSettings] as the caller's fallback.
     */
    fun openCompanionApp(): Boolean {
        val packages = appContext.packageManager
        val intent = CompanionResolution.COMPANION_PACKAGES
            .firstNotNullOfOrNull { runCatching { packages.getLaunchIntentForPackage(it) }.getOrNull() }
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return true
    }

    fun openPluginSettings(): Boolean = try {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$PLUGIN_PACKAGE"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(appContext.packageManager) == null) return false
        appContext.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /**
     * Reads what is actually installed so the UI can say "the plugin is missing"
     * instead of letting the accessory SDK fail with an opaque numeric code later.
     */
    private fun probeCompanions(): CompanionEnvironment {
        val packages = appContext.packageManager
        // A disabled package still resolves through getPackageInfo, and a disabled
        // plugin cannot serve the accessory channel — so presence alone is not
        // enough, the application entry has to be enabled too.
        fun usable(packageName: String) = runCatching {
            packages.getPackageInfo(packageName, 0)
                ?.takeIf { it.applicationInfo?.enabled != false }
        }.getOrNull()

        val plugin = usable(PLUGIN_PACKAGE)
        // The companion app has no single package name — see [CompanionResolution]. Read
        // every id it ships under and take the preferred one that is actually here.
        val companion = CompanionResolution
            .preferred(CompanionResolution.COMPANION_PACKAGES.filterTo(mutableSetOf()) {
                usable(it) != null
            })
            ?.let(::usable)
        val accessory = usable(ACCESSORY_FRAMEWORK_PACKAGE)
        return CompanionEnvironment(
            pluginInstalled = plugin != null,
            pluginLabel = plugin?.applicationInfo
                ?.let { packages.getApplicationLabel(it).toString() }
                ?.takeIf(String::isNotBlank),
            // A sideloaded build can carry an empty version name, and the Install page
            // appends it after a separator — so blank has to read as absent.
            pluginVersionName = plugin?.versionName?.takeIf(String::isNotBlank),
            companionAppInstalled = companion != null,
            companionAppLabel = companion?.applicationInfo
                ?.let { packages.getApplicationLabel(it).toString() }
                ?.takeIf(String::isNotBlank),
            accessoryAgentCount = accessoryAgentApps(packages).size,
            // Only the framework itself counts. The old reading also accepted the
            // companion app as a provider, which is wrong twice over: the companion
            // carries no accessory code, and taking its word for the framework hid the
            // fact that this app could not see the framework at all.
            frameworkVerdict = if (accessory != null) {
                FrameworkVerdict.USABLE
            } else {
                FrameworkVerdict.MISSING
            },
            probed = true,
        )
    }

    /**
     * Apps that declare an accessory agent, found by capability rather than by name.
     *
     * A `REGISTER_AGENT` receiver is what every accessory app declares, this one included,
     * and it is the only signal that keeps working when Samsung ships a companion or a
     * plugin under a package name written after this code. Narrowed to the vendor
     * namespace because the count is reported in the diagnostics report, which is an
     * allowlist — an arbitrary list of the reader's installed apps has no business in it.
     */
    private fun accessoryAgentApps(packages: PackageManager): List<String> = runCatching {
        packages.queryBroadcastReceivers(Intent(ACCESSORY_REGISTER_AGENT_ACTION), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it.startsWith(VENDOR_PACKAGE_PREFIX) }
            .distinct()
    }.getOrDefault(emptyList())

    @Synchronized
    fun reset() {
        generation.incrementAndGet()
        cancelWatchdog()
        abandonInFlight()
        watchfaceAgent?.discoveryListener = null
        watchfaceAgent?.installListener = null
        watchfaceAgent?.commandListener = null
        otaAgent?.discoveryListener = null
        otaAgent?.transferListener = null
        runCatching { watchfaceAgent?.releaseAgent() }
        runCatching { otaAgent?.releaseAgent() }
        watchfaceAgent = null
        otaAgent = null
        discoveryStarted = false
        // Replies survive a reset: they are evidence for the report, and starting over is
        // usually the step taken right before someone copies it.
        mutableState.update { DirectInstallState(watchReplies = it.watchReplies) }
        refreshEnvironment()
    }

    private fun requestWatchfaceAgent(requestGeneration: Int) {
        SAAgentV2.requestAgent(
            appContext,
            WatchfaceDeliveryAgent::class.java.name,
            object : SAAgentV2.RequestAgentCallback {
                override fun onAgentAvailable(agent: SAAgentV2?) {
                    if (requestGeneration != generation.get()) {
                        runCatching { agent?.releaseAgent() }
                        return
                    }
                    val typed = agent as? WatchfaceDeliveryAgent
                    if (typed == null) {
                        fail("The accessory framework returned the wrong watch-face agent type")
                        return
                    }
                    typed.discoveryListener = discoveryListener
                    typed.installListener = installListener
                    typed.commandListener = commandListener
                    synchronized(this@Fit3DirectInstaller) {
                        watchfaceAgent = typed
                    }
                    discoverWhenReady()
                }

                override fun onError(errorCode: Int, message: String?) {
                    if (requestGeneration != generation.get()) return
                    failAgentInit("Watch-face agent", errorCode, message)
                }
            },
        )
    }

    private fun requestOtaAgent(requestGeneration: Int) {
        SAAgentV2.requestAgent(
            appContext,
            OtaTransferDeliveryAgent::class.java.name,
            object : SAAgentV2.RequestAgentCallback {
                override fun onAgentAvailable(agent: SAAgentV2?) {
                    if (requestGeneration != generation.get()) {
                        runCatching { agent?.releaseAgent() }
                        return
                    }
                    val typed = agent as? OtaTransferDeliveryAgent
                    if (typed == null) {
                        fail("The accessory framework returned the wrong OTA agent type")
                        return
                    }
                    typed.discoveryListener = discoveryListener
                    typed.transferListener = transferListener
                    synchronized(this@Fit3DirectInstaller) {
                        otaAgent = typed
                    }
                    discoverWhenReady()
                }

                override fun onError(errorCode: Int, message: String?) {
                    if (requestGeneration != generation.get()) return
                    failAgentInit("OTA agent", errorCode, message)
                }
            },
        )
    }

    @Synchronized
    private fun discoverWhenReady() {
        if (watchfaceAgent != null && otaAgent != null && !discoveryStarted) {
            discoverPeers()
        }
    }

    @Synchronized
    private fun discoverPeers() {
        discoveryStarted = true
        // `discovering()` also voids an earlier release acknowledgement: this only
        // runs with the plugin holding the watch, so the channel is demonstrably not
        // released and step 4 has to be walked again afterwards.
        mutableState.update { it.discovering() }
        armWatchdog(DirectInstallPhase.DISCOVERING, PHASE_WATCHDOG_MS)
        watchfaceAgent?.forgetPeer()
        otaAgent?.forgetPeer()
        watchfaceAgent?.discover()
        otaAgent?.discover()
    }

    /**
     * A recoverable stop: discovery cannot see a watch the plugin is not holding.
     * Distinct from [fail] because the user can put this back from the checklist.
     */
    private fun needsWatchConnection(message: String) {
        cancelWatchdog()
        discoveryStarted = false
        watchfaceAgent?.forgetPeer()
        otaAgent?.forgetPeer()
        mutableState.update {
            it.rewoundToDiscovery().copy(
                phase = DirectInstallPhase.NEEDS_WATCH_CONNECTION,
                message = message,
            )
        }
    }

    private fun updatePeer(profile: String, peer: SAPeerAgent?) {
        if (peer == null) {
            fail("$profile returned no unique peer")
            return
        }
        mutableState.update { current ->
            // The framework pushes peer updates outside a discovery window too, and
            // one arriving mid-transfer or after a finished install must not drag the
            // phase back to a setup step — that disables the send button until the
            // next environment refresh.
            if (
                current.isTerminal ||
                (current.isActive && current.phase != DirectInstallPhase.DISCOVERING)
            ) {
                return@update current
            }
            val updated = when (profile) {
                WatchfaceDeliveryAgent.PROFILE ->
                    current.copy(watchfacePeerCached = true)
                OtaTransferDeliveryAgent.PROFILE ->
                    current.copy(otaPeerCached = true)
                else -> current
            }
            when {
                !updated.peersCached ->
                    updated.copy(message = "Found one watch service; waiting for the other…")
                // Re-discovery after a rewind clears the acknowledgement, so the
                // handover is normally still outstanding here.
                updated.pluginChannelReleased -> {
                    cancelWatchdog()
                    updated.copy(
                        phase = DirectInstallPhase.READY,
                        message = "Ready to send.",
                    )
                }
                else -> {
                    cancelWatchdog()
                    updated.copy(
                        phase = DirectInstallPhase.PEERS_CACHED,
                        message = "Watch found. Complete step 4 to free the connection.",
                    )
                }
            }
        }
    }

    /**
     * Whether `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` are runtime permissions on this phone.
     *
     * The one fact three separate questions here turn on, so it is asked once rather than
     * spelled as an inline API-level test at each of them: whether this app has to request
     * them at all, whether the plugin has a per-app switch worth reading, and which words
     * describe undoing step 4. `EditorScreen.hasPluginNearbySwitch` is the same line drawn
     * for the checklist's strings; keep them in step.
     */
    private val nearbyIsRuntimePermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Undoing step 4, in the terms the reader's own phone offers.
     *
     * Without the runtime permission the plugin has no Nearby devices switch —
     * [pluginNearbyGranted] returns null there for exactly that reason — so telling that
     * reader to restore one sends them looking for a control their phone does not have.
     * The way back is re-enabling the app they disabled instead. Sentence-initial: every
     * use starts a sentence.
     */
    private val restorePlugin: String
        get() = if (nearbyIsRuntimePermission) {
            "Turn the plugin's Nearby access back on if you revoked it"
        } else {
            "Re-enable the plugin if you disabled it"
        }

    private fun helperNearbyGranted(): Boolean =
        !nearbyIsRuntimePermission ||
            (
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
                    appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
                )

    private fun pluginNearbyGranted(pluginInstalled: Boolean): Boolean? {
        if (!nearbyIsRuntimePermission || !pluginInstalled) return null
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(
                PLUGIN_PACKAGE,
                PackageManager.GET_PERMISSIONS,
            )
        }.getOrNull() ?: return null
        val pluginRequestsThemAtRuntime = packageInfo.applicationInfo?.targetSdkVersion
            ?.let { it >= Build.VERSION_CODES.S } ?: false
        if (!pluginRequestsThemAtRuntime) {
            return null
        }
        val requested = packageInfo.requestedPermissions?.toSet().orEmpty()
        if (
            Manifest.permission.BLUETOOTH_CONNECT !in requested ||
            Manifest.permission.BLUETOOTH_SCAN !in requested
        ) {
            return null
        }
        return appContext.packageManager.checkPermission(
            Manifest.permission.BLUETOOTH_CONNECT,
            PLUGIN_PACKAGE,
        ) == PackageManager.PERMISSION_GRANTED &&
            appContext.packageManager.checkPermission(
                Manifest.permission.BLUETOOTH_SCAN,
                PLUGIN_PACKAGE,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun fail(message: String) {
        cancelWatchdog()
        abandonAlbum()
        mutableState.update { it.failed(message) }
    }

    /**
     * A Photos request that stopped between its opening and closing commands sends the
     * plugin's cancel, best effort: the watch may be showing its settings as being edited.
     * Sent before the peer handles are dropped, since it needs one.
     */
    private fun abandonAlbum() {
        val request = albumRequest ?: return
        albumRequest = null
        if (albumOpen) {
            albumOpen = false
            watchfaceAgent?.cancelInstall()
            watchfaceAgent?.sendQuietly(PhotoAlbumProtocol.abandon(request))
        }
    }

    /**
     * An agent that would not initialize.
     *
     * With no environment gate in front of discovery any more, this is where a phone that
     * genuinely cannot open the channel arrives — so it has to be the recoverable
     * `NEEDS_PLUGIN` rather than `FAILED`, which can only be left by restarting the whole
     * setup. Connecting the watch in its companion app is usually the whole fix, and that
     * is a thing the reader can go and do. Anything else is a real failure.
     */
    private fun failAgentInit(what: String, errorCode: Int, detail: String?) {
        cancelWatchdog()
        val recoverable = state.value.environment.let {
            !it.probed || it.frameworkVerdict != FrameworkVerdict.USABLE || !it.hasAccessoryAgent
        }
        if (!recoverable) {
            fail("$what initialization failed: $errorCode ${detail.orEmpty()}")
            return
        }
        // A retry has to start from discovery, so the latch and the peer handles go with
        // it — the same teardown needsWatchConnection() does, for the same reason.
        discoveryStarted = false
        watchfaceAgent?.forgetPeer()
        otaAgent?.forgetPeer()
        mutableState.update {
            it.rewoundToDiscovery().copy(
                phase = DirectInstallPhase.NEEDS_PLUGIN,
                failure = null,
                message = environmentMessage(DirectInstallPhase.NEEDS_PLUGIN, it.environment, it),
            )
        }
    }

    /**
     * The timeout mechanism. What a timeout *means* is [TimeoutRecovery.timedOut] — it
     * was inlined here, which put the "a discovery timeout must stay recoverable" rule
     * inside a coroutine on a class that needs a Context, where no test could reach it.
     */
    private fun armWatchdog(phase: DirectInstallPhase, timeoutMillis: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(timeoutMillis)
            if (state.value.phase != phase) return@launch
            cancelWatchdog()
            // Giving up has to stop the work, not just describe it. This used to change
            // the state and nothing else, so the transfer thread the watchdog had just
            // declared dead went on transferring — and its next acknowledged window
            // dragged FAILED back to TRANSFERRING, after which it reported success.
            abandonInFlight()
            // A discovery timeout rewinds instead of failing, so the peer handles and
            // the discovery latch go with it — the same teardown the explicit
            // device_not_connected outcome gets through needsWatchConnection().
            if (phase == DirectInstallPhase.DISCOVERING) {
                discoveryStarted = false
                watchfaceAgent?.forgetPeer()
                otaAgent?.forgetPeer()
            }
            mutableState.update { TimeoutRecovery.timedOut(it, phase) }
        }
    }

    /**
     * Abandons whatever the agents are still doing for the current attempt.
     *
     * Unconditional rather than branched on the phase: with nothing in flight both calls
     * are no-ops, and a phase test here is one more thing to get wrong on the path that
     * is hardest to reproduce.
     */
    private fun abandonInFlight() {
        otaAgent?.cancelTransfer()
        abandonAlbum()
        watchfaceAgent?.cancelInstall()
    }

    private fun cancelWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }

    private fun environmentMessage(
        phase: DirectInstallPhase,
        companions: CompanionEnvironment,
        previous: DirectInstallState,
    ): String = when (phase) {
        // Reached only after an attempt failed, so it describes what happened rather than
        // predicting it, and it stays recoverable: connecting the watch in its companion
        // app is usually all this needs.
        DirectInstallPhase.NEEDS_PLUGIN ->
            if (companions.frameworkVerdict == FrameworkVerdict.MISSING) {
                "The accessory framework this phone needs is not reachable. Connect the " +
                    "watch in its companion app once, then try again."
            } else {
                "The accessory channel could not be opened. Connect the watch in its " +
                    "companion app, then discover the peers again."
            }
        DirectInstallPhase.NEEDS_HELPER_PERMISSION ->
            "Grant FitFace Studio Nearby devices access."
        DirectInstallPhase.NEEDS_WATCH_CONNECTION ->
            "Discovery needs the watch connected by the stock plugin. $restorePlugin, " +
                "reconnect, then discover."
        // How the channel is released differs by Android version and the checklist's step 4
        // is where that is said in one place, so this points at it rather than repeating
        // half of it. It used to name disconnecting the watch in the companion app, which
        // does not free the channel — see docs/direct-install.md.
        DirectInstallPhase.PEERS_CACHED ->
            "Watch found. Complete step 4 to free the connection."
        DirectInstallPhase.READY ->
            "Ready to send."
        DirectInstallPhase.IDLE ->
            "Connect the watch in its companion app, then find it for transfer."
        else -> previous.message
    }

    private companion object {
        const val PLUGIN_PACKAGE = "com.samsung.wearable.fit3plugin"
        const val ACCESSORY_FRAMEWORK_PACKAGE = "com.samsung.accessory"
        const val ACCESSORY_REGISTER_AGENT_ACTION = "com.samsung.accessory.action.REGISTER_AGENT"
        const val VENDOR_PACKAGE_PREFIX = "com.samsung."
        const val OPENING_BATCH = 1
        const val CLOSING_BATCH = 2
        val ACTIVE_PHASES = DirectInstallState.ActivePhases
        // PHASE_WATCHDOG_MS and TRANSFER_WATCHDOG_MS live in DirectInstallState.kt, beside
        // `transferProgressRearmsWatchdog`, because the budget and the rule that keeps a
        // live transfer inside it are one decision — and because this companion is private,
        // which put the arithmetic that broke out of reach of any test.
    }
}
