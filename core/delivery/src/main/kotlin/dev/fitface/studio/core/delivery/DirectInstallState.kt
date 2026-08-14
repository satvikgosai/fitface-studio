package dev.fitface.studio.core.delivery

enum class DirectInstallPhase {
    IDLE,
    NEEDS_HELPER_PERMISSION,
    NEEDS_PLUGIN,
    NEEDS_WATCH_CONNECTION,
    INITIALIZING,
    DISCOVERING,
    PEERS_CACHED,
    READY,
    VERIFYING,
    TRANSFERRING,
    INSTALLING,
    COMPLETE,
    FAILED,
}

/**
 * What is installed on the phone. Direct install borrows the stock watch plugin's
 * accessory channel, so a missing plugin or companion app is a hard stop that the
 * UI has to state plainly rather than failing later inside the SDK.
 */
data class CompanionEnvironment(
    val pluginInstalled: Boolean = false,
    val pluginLabel: String? = null,
    val pluginVersionName: String? = null,
    val companionAppInstalled: Boolean = false,
    val companionAppLabel: String? = null,
    val accessoryFrameworkAvailable: Boolean = false,
    /** False until the environment has actually been probed once. */
    val probed: Boolean = false,
) {
    val isComplete: Boolean
        get() = pluginInstalled && companionAppInstalled && accessoryFrameworkAvailable

    val missingParts: List<String>
        get() = buildList {
            if (!companionAppInstalled) add("the watch companion app")
            if (!pluginInstalled) add("the Fit3 plugin")
            if (!accessoryFrameworkAvailable) add("the accessory framework")
        }
}

/** One line of the install checklist the Install page renders. */
enum class SetupStep {
    COMPANION_PRESENT,
    HELPER_PERMISSION,
    PEERS_DISCOVERED,
    PLUGIN_RELEASED,
}

data class DirectInstallState(
    val phase: DirectInstallPhase = DirectInstallPhase.IDLE,
    val environment: CompanionEnvironment = CompanionEnvironment(),
    val helperNearbyGranted: Boolean = false,
    val pluginNearbyGranted: Boolean? = null,
    val pluginNearbyReleaseAcknowledged: Boolean = false,
    val watchfacePeerCached: Boolean = false,
    val otaPeerCached: Boolean = false,
    val faceId: Int? = null,
    val samplerId: Int? = null,
    val fileName: String? = null,
    val sha256: String? = null,
    val acknowledgedBytes: Int = 0,
    val totalBytes: Int = 0,
    val acknowledgedWindows: Int = 0,
    val totalWindows: Int = 0,
    /**
     * Why the last attempt stopped, kept across a rewind back into the checklist.
     * [message] is replaced by whatever the setup needs next, so without this the
     * page rewinds with no trace of what went wrong.
     */
    val failure: String? = null,
    val message: String = "Checking what this phone has installed…",
) {
    val peersCached: Boolean
        get() = watchfacePeerCached && otaPeerCached

    val progress: Float
        get() = if (totalBytes <= 0) 0f else acknowledgedBytes.toFloat() / totalBytes

    val isActive: Boolean
        get() = phase in ActivePhases

    val isTerminal: Boolean
        get() = phase in TerminalPhases

    /** The plugin has let go of the channel, either observably or by user say-so. */
    val pluginChannelReleased: Boolean
        get() = pluginNearbyGranted == false || pluginNearbyReleaseAcknowledged

    val setupComplete: Boolean
        get() = environment.isComplete && helperNearbyGranted && peersCached &&
            pluginChannelReleased

    fun isStepDone(step: SetupStep): Boolean = when (step) {
        SetupStep.COMPANION_PRESENT -> environment.isComplete
        SetupStep.HELPER_PERMISSION -> helperNearbyGranted
        SetupStep.PEERS_DISCOVERED -> peersCached
        // The handover is only meaningful once there is something to hand over. With
        // the plugin's access already revoked before discovery ran, this step read as
        // done while the step it depends on was still pending.
        SetupStep.PLUGIN_RELEASED -> peersCached && pluginChannelReleased
    }

    fun isStepBusy(step: SetupStep): Boolean = when (step) {
        SetupStep.PEERS_DISCOVERED -> phase == DirectInstallPhase.INITIALIZING ||
            phase == DirectInstallPhase.DISCOVERING
        else -> false
    }

    /** Discovery is the step the checklist is waiting on. */
    val awaitingDiscovery: Boolean
        get() = environment.isComplete && helperNearbyGranted && !peersCached

    /**
     * Discovery is starting, so every claim that only held while the peers were live
     * is void again.
     *
     * Discovery only works while the stock plugin holds the watch connected, so
     * running it necessarily takes the channel back. An earlier "the plugin has let
     * go" acknowledgement describes a connection that no longer exists, and carrying
     * it forward would let the next transfer start against a channel the plugin is
     * still holding.
     */
    fun discovering(): DirectInstallState = copy(
        phase = DirectInstallPhase.DISCOVERING,
        watchfacePeerCached = false,
        otaPeerCached = false,
        pluginNearbyReleaseAcknowledged = false,
        failure = null,
        message = "Discovering Fit3 watch-face and OTA peers…",
    )

    /**
     * Rewinds the setup to the discovery step, keeping the probed environment and the
     * granted permissions.
     *
     * A peer handle does not outlive the connection it was found on, so once the
     * channel has been handed over there is exactly one way back from a failed
     * transfer: reconnect the watch in the companion app, discover again, hand the
     * channel over again. Nothing used to clear [peersCached], so the Install page
     * stayed on the transfer panel offering only a re-send that could not work, and
     * the sole way out was restarting the whole setup.
     */
    fun rewoundToDiscovery(failure: String? = this.failure): DirectInstallState = copy(
        phase = DirectInstallPhase.IDLE,
        watchfacePeerCached = false,
        otaPeerCached = false,
        pluginNearbyReleaseAcknowledged = false,
        failure = failure,
        acknowledgedBytes = 0,
        totalBytes = 0,
        acknowledgedWindows = 0,
        totalWindows = 0,
        message = "Reconnect the Fit3 in the companion app, then discover the peers again.",
    )

    /**
     * A terminal stop. [failure] outlives [message], which whatever comes next
     * overwrites, so the failure panel can still say why after a rewind.
     */
    internal fun failed(reason: String): DirectInstallState = copy(
        phase = DirectInstallPhase.FAILED,
        failure = reason,
        message = reason,
    )

    companion object {
        val ActivePhases = setOf(
            DirectInstallPhase.INITIALIZING,
            DirectInstallPhase.DISCOVERING,
            DirectInstallPhase.VERIFYING,
            DirectInstallPhase.TRANSFERRING,
            DirectInstallPhase.INSTALLING,
        )
        val TerminalPhases = setOf(
            DirectInstallPhase.COMPLETE,
            DirectInstallPhase.FAILED,
        )

        /** In transfer order, for the phase timeline on the Install page. */
        val TransferPhases = listOf(
            DirectInstallPhase.INITIALIZING to "Initialize accessory session",
            DirectInstallPhase.DISCOVERING to "Discover paired peers",
            DirectInstallPhase.VERIFYING to "Verify SHA-256 against frozen payload",
            DirectInstallPhase.TRANSFERRING to "Transfer windows",
            DirectInstallPhase.INSTALLING to "Commit on watch",
            DirectInstallPhase.COMPLETE to "Installed",
        )
    }
}

/**
 * What a watchdog timeout *means*, phase by phase.
 *
 * This decision used to sit inside `Fit3DirectInstaller.armWatchdog`, wrapped in the
 * coroutine that waits for it — so the rule that matters most on this path, that a
 * discovery timeout is recoverable and never terminal, could not be asserted without a
 * Context and the accessory SDK, and nothing tested it at all. The coroutine, the delay
 * and the teardown stay where they were; only the mapping moved here, where a test can
 * walk every phase.
 */
internal object TimeoutRecovery {
    /**
     * Silence from discovery means the same thing as an explicit `device_not_connected`
     * and has the same way out, so it rewinds to the checklist rather than failing.
     */
    const val DISCOVERY =
        "Peer discovery timed out, which normally means the watch is not connected. Check " +
            "it in the companion app — the plugin's Nearby access has to be on for this " +
            "step — then discover again."
    const val INITIALIZATION = "Accessory initialization timed out. Restart setup and retry."
    const val INSTALL = "The install request timed out. Check the watch, then reconnect it and " +
        "discover again before retrying."
    const val TRANSFER = "The direct transfer stopped responding. Reconnect the watch in the " +
        "companion app and discover again before retrying."

    /**
     * The state a timeout on [phase] leaves behind. A timeout for a phase the machine
     * has already left changes nothing: the thing being waited for arrived, and the
     * watchdog that fired is stale. The caller guards on that too, because its own
     * teardown must not run either.
     */
    fun timedOut(
        current: DirectInstallState,
        phase: DirectInstallPhase,
    ): DirectInstallState = when {
        current.phase != phase -> current
        phase == DirectInstallPhase.DISCOVERING -> current.rewoundToDiscovery().copy(
            phase = DirectInstallPhase.NEEDS_WATCH_CONNECTION,
            message = DISCOVERY,
        )
        phase == DirectInstallPhase.INITIALIZING -> current.failed(INITIALIZATION)
        phase == DirectInstallPhase.INSTALLING -> current.failed(INSTALL)
        else -> current.failed(TRANSFER)
    }
}
