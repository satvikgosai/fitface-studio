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

/** Whether the accessory framework — the app that owns the channel — is reachable. */
enum class FrameworkVerdict { UNKNOWN, USABLE, MISSING }

/**
 * Something worth telling the reader about the phone, and nothing more than that.
 *
 * None of these stops a transfer being attempted. What is installed is a poor predictor
 * of whether the channel opens — a reporter whose watch was paired and holding a live
 * accessory session was refused because one package name out of the several the companion
 * app ships under was absent — so the only honest arbiter is discovery itself. These say
 * what was not found, in case discovery then fails and the reader needs somewhere to look.
 */
enum class EnvironmentAdvisory { NO_ACCESSORY_APP, NO_COMPANION_APP, FRAMEWORK_MISSING }

/**
 * What is installed on the phone, as an advisory rather than a gate.
 *
 * This used to be a hard stop: three package names ANDed together, and any one missing
 * replaced the install checklist with a dead end. Two of the three were the wrong
 * question. The companion app carries no accessory code at all — no `REGISTER_AGENT`
 * receiver, no `AccessoryServicesLocation` — so its presence never said anything about
 * the channel; and it ships under several package names, so its absence was often just
 * this app looking for the wrong one. See [CompanionResolution].
 *
 * [accessoryAgentCount] is the honest form of the question: how many apps on this phone
 * declare themselves accessory agents, found by capability rather than by name, so that
 * the next id Samsung forks does not read as an empty phone.
 */
data class CompanionEnvironment(
    val pluginInstalled: Boolean = false,
    val pluginLabel: String? = null,
    val pluginVersionName: String? = null,
    val companionAppInstalled: Boolean = false,
    val companionAppLabel: String? = null,
    /** Apps declaring an accessory agent, the stock plugin among them when present. */
    val accessoryAgentCount: Int = 0,
    val frameworkVerdict: FrameworkVerdict = FrameworkVerdict.UNKNOWN,
    /** False until the environment has actually been probed once. */
    val probed: Boolean = false,
) {
    /** Something on this phone can serve the accessory channel. */
    val hasAccessoryAgent: Boolean
        get() = pluginInstalled || accessoryAgentCount > 0

    /**
     * The one thing most worth saying, or null when there is nothing to say.
     *
     * Ordered by how much it would explain a later failure: a framework that cannot be
     * reached explains everything, no agent app explains a transfer with nothing to talk
     * to, and a missing companion app explains only that the reader has nowhere to tap to
     * connect the watch.
     */
    val advisory: EnvironmentAdvisory?
        get() = when {
            !probed -> null
            frameworkVerdict == FrameworkVerdict.MISSING -> EnvironmentAdvisory.FRAMEWORK_MISSING
            !hasAccessoryAgent -> EnvironmentAdvisory.NO_ACCESSORY_APP
            !companionAppInstalled -> EnvironmentAdvisory.NO_COMPANION_APP
            else -> null
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
        get() = helperNearbyGranted && peersCached && pluginChannelReleased

    fun isStepDone(step: SetupStep): Boolean = when (step) {
        // Advisory, and never a prerequisite: this step is the reader being told to go and
        // connect the watch, which they may well have done long before opening this app.
        // Anything that could serve the channel, or any companion app to tap, counts.
        SetupStep.COMPANION_PRESENT ->
            environment.probed &&
                (environment.hasAccessoryAgent || environment.companionAppInstalled)
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
        get() = helperNearbyGranted && !peersCached

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

/** What a delivery agent has just reported, reduced to what the phase gate needs. */
internal enum class DeliveryEvent {
    PAYLOAD_VERIFIED,
    TRANSFER_PROGRESS,
    TRANSFER_COMPLETE,
    INSTALL_REQUESTED,
    INSTALL_DELIVERED,
    FAILURE,
}

/**
 * Whether a callback from a delivery agent may still change the state.
 *
 * The agents outlive the attempt that started them. A transfer thread is blocking
 * RFCOMM I/O that no coroutine can interrupt, the OTA agent posts two delayed handler
 * callbacks of its own, and an accessory `onSent` arrives whenever the framework gets
 * round to it — so a watchdog that has already given up, or a user who has already
 * rewound to the checklist, is routinely still holding a live worker. Every one of
 * those callbacks used to write [DirectInstallState.phase] unconditionally, which is
 * three distinct wrong answers:
 *
 *  * a window acknowledged after the transfer watchdog fired dragged `FAILED` back to
 *    `TRANSFERRING`, and the transfer went on to report success;
 *  * a late accessory `onSent` turned an install timeout into `COMPLETE`;
 *  * and the abort the *user* asked for, by tapping "Reconnect the watch and discover
 *    again" on the failure panel, made the old thread throw immediately — so its
 *    failure landed microseconds after the rewind and put the page straight back into
 *    `FAILED`. That left `reset()` as the only way out of a timed-out transfer, which
 *    is the whole setup again.
 *
 * The rule is that a callback may only move the machine on from the phase that was
 * waiting for it. Nothing here rejects a *timely* callback: each event names the phase
 * its own caller has just set.
 *
 * Pure and separate from the agents for the reason [TimeoutRecovery] is: none of this
 * is assertable through a `Context` and the accessory SDK.
 */
internal object DeliveryProgress {
    fun accepts(current: DirectInstallPhase, event: DeliveryEvent): Boolean = when (event) {
        // `install()` sets VERIFYING and then calls straight into the agent.
        DeliveryEvent.PAYLOAD_VERIFIED -> current == DirectInstallPhase.VERIFYING
        // Status lines and window acknowledgements. VERIFYING is accepted because the
        // agent reports the channel it acquired before the first window lands.
        DeliveryEvent.TRANSFER_PROGRESS ->
            current == DirectInstallPhase.VERIFYING ||
                current == DirectInstallPhase.TRANSFERRING
        DeliveryEvent.TRANSFER_COMPLETE -> current == DirectInstallPhase.TRANSFERRING
        // The install request is sent from the completion callback, so the phase is
        // still TRANSFERRING when the agent reports it has asked.
        DeliveryEvent.INSTALL_REQUESTED ->
            current == DirectInstallPhase.TRANSFERRING ||
                current == DirectInstallPhase.INSTALLING
        DeliveryEvent.INSTALL_DELIVERED -> current == DirectInstallPhase.INSTALLING
        // A failure for an attempt nobody is waiting on any more is noise. The reason
        // the reader needs is already on screen — the watchdog's, or the checklist's.
        DeliveryEvent.FAILURE -> current in DirectInstallState.ActivePhases
    }
}

/** How long a phase that is merely waiting on the accessory framework may stay silent. */
internal const val PHASE_WATCHDOG_MS = 20_000L

/**
 * How long a transfer may go without the agent reporting progress.
 *
 * Not "how long a transfer may take": it is re-armed by every progress callback, so it is a
 * silence threshold. See [transferProgressRearmsWatchdog] for why that distinction is the
 * whole safety of the number, and `TransferWatchdogBudgetTest` for the arithmetic.
 */
internal const val TRANSFER_WATCHDOG_MS = 20_000L

/**
 * Whether an accepted [DeliveryEvent.TRANSFER_PROGRESS] should re-arm the transfer
 * watchdog, given the phase it has left the machine in.
 *
 * **The watchdog used to be re-armed only by an acknowledged window, and the protocol's
 * own tail is longer than its budget.** After the last window is acknowledged the agent
 * still has to wait out `RESULT_TIMEOUT_MS` (15 s) while the watch verifies the whole BIN,
 * pause 250 ms, wait out `COMMAND_TIMEOUT_MS` (8 s) for the close handshake, sleep half a
 * second tearing the socket down and post the completion a second later — 24.75 s against
 * `TRANSFER_WATCHDOG_MS`'s 20, with nothing in between to say the transfer was still
 * alive. A single window is the same shape: it may be re-sent
 * `IdentityTransferProtocol.MAX_WINDOW_RETRIES` times, each waiting up to
 * `WINDOW_TIMEOUT_MS`, and an `SPP_WINDOW_RETRY` answer reported nothing at all.
 *
 * That was survivable while a fired watchdog only *described* a dead transfer. Now that it
 * calls `abandonInFlight`, crossing the line kills a transfer the watch has already
 * accepted — the abandoned attempt's token invalidates the queued `onTransferComplete`, so
 * the install command is never sent and a good install reads as a timeout. So every status
 * line re-arms, and the agent reports one on a window retry.
 *
 * Raising the constant would have worked too and is worse: every wait in the protocol is
 * already individually bounded by `SppResponseWait`, so what this watchdog actually guards
 * is the gaps between them. Re-arming on progress keeps 20 s of true silence as the
 * threshold instead of stretching it to cover a transfer that is plainly working.
 *
 * VERIFYING answers false. A status is *accepted* in that phase — the agent reports the
 * channel it acquired before the first window lands — but [Fit3DirectInstaller.armWatchdog]
 * replaces whatever is armed, so arming a TRANSFERRING watchdog here would swap VERIFYING's
 * own for one that returns the moment it fires on a phase the machine is not in. That is
 * not a re-arm, it is a disarm.
 */
internal fun transferProgressRearmsWatchdog(phase: DirectInstallPhase): Boolean =
    phase == DirectInstallPhase.TRANSFERRING

/**
 * Every stretch of `OtaTransferDeliveryAgent.runTransferStateMachine` between one report to
 * the listener and the next, in order, with what it may consume.
 *
 * **Every wait in that method has to end in a `report`**, and this list is why: a report is
 * what re-arms the transfer watchdog, so an unreported wait is silence as far as the watchdog
 * is concerned. Three of them used to be — the negotiation handshake, the descriptor
 * handshake and a re-sent window — which was survivable only while a fired watchdog merely
 * relabelled the phase. It abandons the worker now, so an unreported wait longer than
 * [TRANSFER_WATCHDOG_MS] kills a transfer the watch is happily servicing.
 *
 * It lives here rather than beside the state machine for a reason worth not rediscovering: a
 * non-`const` `val` in `OtaTransferDeliveryAgent`'s companion forces the JVM to load that
 * class, which extends the accessory SDK's `SAAgentV2`, and its pre-stackmap bytecode fails
 * the verifier — so a test reading it dies with `VerifyError` before it asserts anything.
 * The timeouts below are `const val`, which the compiler inlines at each use site, so naming
 * them here pulls in nothing. This file holds no accessory type at all, which is what makes
 * every decision in it assertable.
 *
 * The list cannot *drive* the conversation, and the conversation cannot be unit-tested for
 * the same `VerifyError` reason, so this is the closest thing to coverage the seam allows:
 * `TransferWatchdogBudgetTest` checks the arithmetic, and adding a wait means adding a line
 * here. Keep it honest by hand.
 */
internal val TRANSFER_PROGRESS_GAPS: List<Pair<String, Long>> = listOf(
    "channel acquired → negotiation accepted" to
        OtaTransferDeliveryAgent.COMMAND_TIMEOUT_MS,
    "negotiation accepted → descriptor accepted" to
        OtaTransferDeliveryAgent.COMMAND_TIMEOUT_MS,
    // One attempt at one window, whichever way it is answered: accepted reports through
    // onWindowAcknowledged, re-sent through onTransferStatus. So the retry ladder costs one
    // gap per attempt rather than one gap for all four.
    "descriptor accepted → window answered" to OtaTransferDeliveryAgent.WINDOW_TIMEOUT_MS,
    "window answered → next window answered" to OtaTransferDeliveryAgent.WINDOW_TIMEOUT_MS,
    "last window → BIN verified" to OtaTransferDeliveryAgent.RESULT_TIMEOUT_MS,
    "BIN verified → channel closed" to
        OtaTransferDeliveryAgent.RESULT_TO_CLOSE_PAUSE_MS +
        OtaTransferDeliveryAgent.COMMAND_TIMEOUT_MS,
    // Nothing reports during teardown, so this one is a fixed cost rather than a wait on
    // the watch.
    "channel closed → transfer complete" to
        OtaTransferDeliveryAgent.TEARDOWN_PAUSE_MS + OtaTransferDeliveryAgent.COMPLETION_POST_MS,
)
