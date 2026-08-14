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
import dev.fitface.studio.core.model.DirectInstallPayload
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

    private val discoveryListener = DiscoveryListener {
            profile,
            peer,
            _,
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
                "The Fit3 is not connected, and discovery needs it to be. Turn the " +
                    "plugin's Nearby access back on if you revoked it, reconnect the watch " +
                    "in the companion app, then discover again — the channel is only " +
                    "released afterwards.",
            )
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
        override fun onPayloadVerified(payload: DirectInstallPayload, windows: Int) {
            armWatchdog(DirectInstallPhase.TRANSFERRING, TRANSFER_WATCHDOG_MS)
            mutableState.update {
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
                    message = "BIN hash verified. Opening the direct transfer.",
                )
            }
        }

        override fun onWindowAcknowledged(
            acknowledgedBytes: Int,
            totalBytes: Int,
            acknowledgedWindows: Int,
            totalWindows: Int,
        ) {
            armWatchdog(DirectInstallPhase.TRANSFERRING, TRANSFER_WATCHDOG_MS)
            mutableState.update {
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

        override fun onTransferStatus(message: String) {
            mutableState.update { it.copy(message = message) }
        }

        override fun onTransferComplete(payload: DirectInstallPayload) {
            val agent = watchfaceAgent
            if (agent == null) {
                fail("Watch-face agent disappeared after transfer")
            } else {
                agent.install(payload)
            }
        }

        override fun onTransferFailed(message: String, peerLost: Boolean) {
            if (peerLost) restartDiscovery(message) else fail(message)
        }
    }

    private val installListener = object : InstallListener {
        override fun onInstallRequested(payload: DirectInstallPayload) {
            armWatchdog(DirectInstallPhase.INSTALLING, PHASE_WATCHDOG_MS)
            mutableState.update {
                it.copy(
                    phase = DirectInstallPhase.INSTALLING,
                    message = "Transfer verified. Sending the one-shot install command.",
                )
            }
        }

        override fun onInstallDelivered(payload: DirectInstallPayload) {
            cancelWatchdog()
            mutableState.update {
                it.copy(
                    phase = DirectInstallPhase.COMPLETE,
                    acknowledgedBytes = payload.size,
                    totalBytes = payload.size,
                    message = "Install request delivered. Check the Fit3, then reconnect it " +
                        "in the companion app — and restore the plugin's Nearby access if " +
                        "you turned it off.",
                )
            }
        }

        override fun onInstallFailed(message: String, peerLost: Boolean) {
            if (peerLost) restartDiscovery(message) else fail(message)
        }
    }

    fun refreshEnvironment() {
        val companions = probeCompanions()
        val helperGranted = helperNearbyGranted()
        val pluginGranted = pluginNearbyGranted(companions.pluginInstalled)
        mutableState.update { current ->
            val nextPhase = when {
                current.isTerminal -> current.phase
                current.isActive -> current.phase
                !companions.isComplete -> DirectInstallPhase.NEEDS_PLUGIN
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
        if (!environment.environment.isComplete) {
            fail(
                "Direct install needs ${environment.environment.missingParts.joinToString()} " +
                    "on this phone.",
            )
            return
        }
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
                message = "Initializing the two accessory agents…",
            )
        }
        armWatchdog(DirectInstallPhase.INITIALIZING, PHASE_WATCHDOG_MS)
        val requestGeneration = generation.get()
        requestWatchfaceAgent(requestGeneration)
        requestOtaAgent(requestGeneration)
    }

    fun install(payload: DirectInstallPayload) {
        if (state.value.isActive) return
        refreshEnvironment()
        val current = state.value
        // Neither of these is a failed transfer: they are setup that has come undone,
        // and each one has a checklist step that says how to put it back. Failing
        // here used to make a recoverable state terminal.
        if (!current.peersCached) {
            restartDiscovery("Discover both Fit3 peers before sending.")
            return
        }
        if (!current.pluginChannelReleased) {
            mutableState.update {
                it.copy(
                    phase = DirectInstallPhase.PEERS_CACHED,
                    message = "Let the stock plugin release the accessory channel first — " +
                        "disconnect the watch in the companion app, or turn the plugin's " +
                        "Nearby access off.",
                )
            }
            return
        }
        mutableState.update {
            it.copy(
                phase = DirectInstallPhase.VERIFYING,
                failure = null,
                faceId = payload.faceId,
                samplerId = payload.samplerId,
                fileName = payload.fileName,
                sha256 = payload.sha256,
                totalBytes = payload.size,
                message = "Freezing and rechecking the validated BIN…",
            )
        }
        armWatchdog(DirectInstallPhase.VERIFYING, PHASE_WATCHDOG_MS)
        otaAgent?.runPayload(payload) ?: fail("OTA agent is unavailable")
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
        otaAgent?.cancelTransfer()
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
                    "Channel handoff acknowledged. Ready to send the validated face."
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
                failure = null,
                message = if (current.setupComplete) {
                    "Edit applied. Ready to send the updated face."
                } else {
                    "Edit applied. Set up direct install again to send it."
                },
            )
        }
    }

    fun openCompanionApp(): Boolean {
        val intent = appContext.packageManager.getLaunchIntentForPackage(COMPANION_PACKAGE)
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
        val companion = usable(COMPANION_PACKAGE)
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
            // The accessory service can also be bundled into the companion app on
            // some builds, so either provider counts.
            accessoryFrameworkAvailable = accessory != null || companion != null,
            probed = true,
        )
    }

    @Synchronized
    fun reset() {
        generation.incrementAndGet()
        cancelWatchdog()
        otaAgent?.cancelTransfer()
        watchfaceAgent?.discoveryListener = null
        watchfaceAgent?.installListener = null
        otaAgent?.discoveryListener = null
        otaAgent?.transferListener = null
        runCatching { watchfaceAgent?.releaseAgent() }
        runCatching { otaAgent?.releaseAgent() }
        watchfaceAgent = null
        otaAgent = null
        discoveryStarted = false
        mutableState.update { DirectInstallState() }
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
                    synchronized(this@Fit3DirectInstaller) {
                        watchfaceAgent = typed
                    }
                    discoverWhenReady()
                }

                override fun onError(errorCode: Int, message: String?) {
                    if (requestGeneration != generation.get()) return
                    fail("Watch-face agent initialization failed: $errorCode ${message.orEmpty()}")
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
                    fail("OTA agent initialization failed: $errorCode ${message.orEmpty()}")
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
                    updated.copy(message = "One peer cached; waiting for the other…")
                // Re-discovery after a rewind clears the acknowledgement, so the
                // handover is normally still outstanding here.
                updated.pluginChannelReleased -> {
                    cancelWatchdog()
                    updated.copy(
                        phase = DirectInstallPhase.READY,
                        message = "Ready to send the validated face.",
                    )
                }
                else -> {
                    cancelWatchdog()
                    updated.copy(
                        phase = DirectInstallPhase.PEERS_CACHED,
                        message = "Both peers are cached and stay cached. Now let the plugin " +
                            "release the channel: disconnect the watch in the companion app, " +
                            "or turn the plugin's Nearby access off.",
                    )
                }
            }
        }
    }

    private fun helperNearbyGranted(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
                    appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
                )

    private fun pluginNearbyGranted(pluginInstalled: Boolean): Boolean? {
        if (Build.VERSION.SDK_INT < 31 || !pluginInstalled) return null
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(
                PLUGIN_PACKAGE,
                PackageManager.GET_PERMISSIONS,
            )
        }.getOrNull() ?: return null
        if (packageInfo.applicationInfo?.targetSdkVersion?.let { it < 31 } != false) {
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
        mutableState.update { it.failed(message) }
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

    private fun cancelWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }

    private fun environmentMessage(
        phase: DirectInstallPhase,
        companions: CompanionEnvironment,
        previous: DirectInstallState,
    ): String = when (phase) {
        DirectInstallPhase.NEEDS_PLUGIN ->
            "Direct install is unavailable: this phone is missing " +
                "${companions.missingParts.joinToString()}."
        DirectInstallPhase.NEEDS_HELPER_PERMISSION ->
            "Grant FitFace Studio Nearby devices access."
        DirectInstallPhase.NEEDS_WATCH_CONNECTION ->
            "Discovery needs the watch connected by the stock plugin. Turn the plugin's " +
                "Nearby access back on, reconnect, then discover."
        DirectInstallPhase.PEERS_CACHED ->
            "Both peers cached. Now let the plugin release the channel — disconnect the " +
                "watch in the companion app, or turn its Nearby access off."
        DirectInstallPhase.READY ->
            "Ready to send the validated face."
        DirectInstallPhase.IDLE ->
            "Connect the Fit3 in the companion app, then discover peers."
        else -> previous.message
    }

    private companion object {
        const val PLUGIN_PACKAGE = "com.samsung.wearable.fit3plugin"
        const val COMPANION_PACKAGE = "com.samsung.android.app.watchmanager"
        const val ACCESSORY_FRAMEWORK_PACKAGE = "com.samsung.accessory"
        val ACTIVE_PHASES = DirectInstallState.ActivePhases
        const val PHASE_WATCHDOG_MS = 20_000L
        const val TRANSFER_WATCHDOG_MS = 20_000L
    }
}
