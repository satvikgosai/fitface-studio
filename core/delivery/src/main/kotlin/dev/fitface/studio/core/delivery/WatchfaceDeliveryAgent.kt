package dev.fitface.studio.core.delivery

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.samsung.android.sdk.accessory.SAMessage
import com.samsung.android.sdk.accessory.SAPeerAgent
import dev.fitface.studio.core.model.DirectInstallPayload
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

internal interface InstallListener {
    fun onInstallRequested(payload: DirectInstallPayload)
    fun onInstallDelivered(payload: DirectInstallPayload)

    /**
     * @param peerLost the cached accessory peer is gone, so re-sending cannot work
     *   and the setup has to rewind to discovery.
     */
    fun onInstallFailed(message: String, peerLost: Boolean = false)

    /** Raw bytes the watch addressed to this agent, recorded for the diagnostics report. */
    fun onWatchReply(data: ByteArray) {}
}

/** What a run of album commands reports; see [WatchfaceDeliveryAgent.sendCommands]. */
internal interface CommandListener {
    /** Every command of [batch] has been handed to the watch. */
    fun onCommandsDelivered(batch: Int)

    /** [sent] of [total] commands of [batch] are out, and the next follows after a pause. */
    fun onCommandProgress(batch: Int, sent: Int, total: Int)

    fun onCommandsFailed(message: String, peerLost: Boolean = false)

    /** A reply that says the watch refused an album command, if one ever reaches this app. */
    fun onCommandRefused(reason: String)
}

class WatchfaceDeliveryAgent(context: Context) :
    DiscoveryAgent("WatchfaceSerevice", context, PROFILE) {
    @Volatile
    internal var installListener: InstallListener? = null

    @Volatile
    internal var commandListener: CommandListener? = null

    private val pendingInstall = PendingInstallSlot()
    private val commands = CommandSequence()
    private val handler = Handler(Looper.getMainLooper())

    private val installMessage = object : SAMessage(this) {
        override fun onReceive(peerAgent: SAPeerAgent, data: ByteArray) {
            // Band replies target the stock plugin's reserved local component ID 6 in
            // the proven coexistence architecture, so this may never run. If one does
            // arrive it is recorded, then read for what it says: only a decoded reply for
            // the pending face and style settles the request, and only success counts as
            // delivered. Anything else stays pending for `onSent` or the watchdog.
            installListener?.onWatchReply(data)
            PhotoAlbumProtocol.refusal(data)?.let { reason ->
                commandListener?.onCommandRefused(reason)
                return
            }
            val reply = WatchfaceInstallProtocol.decodeReply(data) ?: return
            val refusal = if (reply.accepted) null else reply.refusal ?: return
            val payload = pendingInstall.claimIf {
                it.faceId == reply.faceId && it.samplerId == reply.samplerId
            } ?: return
            if (refusal == null) {
                installListener?.onInstallDelivered(payload)
            } else {
                installListener?.onInstallFailed(refusal)
            }
        }

        override fun onSent(peerAgent: SAPeerAgent, transactionId: Int) {
            if (commands.active) {
                advanceCommands(peerAgent)
                return
            }
            val payload = claimPendingInstall() ?: return
            installListener?.onInstallDelivered(payload)
        }

        override fun onError(peerAgent: SAPeerAgent?, transactionId: Int, errorCode: Int) {
            // Guarded like the two above it. This used to report unconditionally, so an
            // error arriving for an install the watchdog had already given up on
            // overwrote whatever the reader was being shown instead.
            if (commands.fail() != null) {
                commandListener?.onCommandsFailed("Photos message failed with code $errorCode")
                return
            }
            claimPendingInstall() ?: return
            installListener?.onInstallFailed("Install message failed with code $errorCode")
        }
    }

    /**
     * The in-flight payload, taken exactly once, or null if this attempt is over.
     *
     * The accessory framework answers whenever it gets round to it, and by then the
     * install watchdog may have fired or the reader may have rewound to the checklist.
     * A late `onSent` used to turn a timed-out install into `COMPLETE` — a face the
     * watch never got, reported as installed.
     */
    private fun claimPendingInstall(): DirectInstallPayload? = pendingInstall.claim()

    /**
     * Abandons the in-flight install request, so nothing it reports later is believed.
     *
     * The one-shot command is already gone by the time this can be called — there is no
     * unsending it — so this only says that its answer is no longer wanted.
     */
    internal fun cancelInstall() {
        pendingInstall.abandon()
        commands.abandon()
    }

    /**
     * Sends [batch] one command at a time: each waits for the framework's `onSent` for the one
     * before it, then [COMMAND_GAP_MS]. The plugin never sends these back to back either — a
     * person tapping through its screens spaces them — and the watch's handling of a burst is
     * unknown.
     */
    internal fun sendCommands(batch: Int, list: List<ByteArray>) {
        val peer = discoveredPeer
        if (peer == null) {
            commandListener?.onCommandsFailed(
                "The cached watch-face peer is gone — the watch has to be connected and " +
                    "discovered again.",
                peerLost = true,
            )
            return
        }
        val step = commands.start(batch, list)
        if (step == null) {
            commandListener?.onCommandsFailed("A Photos request is already in flight")
            return
        }
        sendStep(peer, step)
    }

    /** One command with nothing waiting on it: the cancel sent after a Photos request fails. */
    internal fun sendQuietly(command: ByteArray) {
        val peer = discoveredPeer ?: return
        runCatching { installMessage.send(peer, command) }
    }

    private fun sendStep(peer: SAPeerAgent, step: CommandSequence.Step) {
        try {
            installMessage.send(peer, step.command)
        } catch (error: Exception) {
            if (commands.fail() == null) return
            commandListener?.onCommandsFailed(error.message ?: "Photos message send failed", peerLost = true)
        }
    }

    private fun advanceCommands(peer: SAPeerAgent) {
        when (val next = commands.advance()) {
            is CommandSequence.Advance.Finished -> commandListener?.onCommandsDelivered(next.batch)
            is CommandSequence.Advance.Next -> {
                commandListener?.onCommandProgress(next.step.batch, next.step.index, next.step.commands.size)
                handler.postDelayed({ if (commands.isCurrent(next.step)) sendStep(peer, next.step) }, COMMAND_GAP_MS)
            }
            CommandSequence.Advance.Stale -> Unit
        }
    }

    internal fun install(payload: DirectInstallPayload) {
        val peer = discoveredPeer
        if (peer == null) {
            installListener?.onInstallFailed(
                "The cached watch-face peer is gone — the watch has to be connected and " +
                    "discovered again before the install request can be sent.",
                peerLost = true,
            )
            return
        }
        if (!pendingInstall.offer(payload)) {
            installListener?.onInstallFailed("An install request is already in flight")
            return
        }
        try {
            installListener?.onInstallRequested(payload)
            installMessage.send(peer, WatchfaceInstallProtocol.request(payload))
        } catch (error: IOException) {
            claimPendingInstall() ?: return
            // The bytes are already on the watch; only the peer that carries the
            // one-shot command is gone, so discovery is what has to be repeated.
            installListener?.onInstallFailed(
                error.message ?: "Install send failed",
                peerLost = true,
            )
        } catch (error: RuntimeException) {
            claimPendingInstall() ?: return
            installListener?.onInstallFailed(
                error.message ?: "Install send failed",
                peerLost = true,
            )
        }
    }

    companion object {
        const val PROFILE: String = "/system/WatchfaceSerevice"

        /** Between one album command being handed over and the next being sent. */
        internal const val COMMAND_GAP_MS = 1_000L
    }
}

/**
 * The album commands in flight, one at a time, claimable exactly once at each step.
 *
 * Kept out of the agent for the reason [PendingInstallSlot] is: the agent needs the accessory
 * SDK, so only a class without it can be asserted on. Every step is a fresh object, so a
 * delayed send or a framework callback for a step that has been passed, failed or abandoned
 * finds it is no longer current and does nothing.
 */
internal class CommandSequence {
    class Step(val batch: Int, val commands: List<ByteArray>, val index: Int) {
        val command: ByteArray get() = commands[index]
    }

    sealed interface Advance {
        data class Next(val step: Step) : Advance
        data class Finished(val batch: Int) : Advance
        data object Stale : Advance
    }

    private val current = AtomicReference<Step?>(null)

    val active: Boolean get() = current.get() != null

    /** The first step, or null if a run is already in flight. */
    fun start(batch: Int, list: List<ByteArray>): Step? {
        require(list.isNotEmpty()) { "No command to send" }
        return Step(batch, list, 0).takeIf { current.compareAndSet(null, it) }
    }

    /** The current step has been handed to the watch: move on to the next, or finish. */
    fun advance(): Advance {
        val step = current.get() ?: return Advance.Stale
        return if (step.index == step.commands.lastIndex) {
            if (current.compareAndSet(step, null)) Advance.Finished(step.batch) else Advance.Stale
        } else {
            val next = Step(step.batch, step.commands, step.index + 1)
            if (current.compareAndSet(step, next)) Advance.Next(next) else Advance.Stale
        }
    }

    fun isCurrent(step: Step): Boolean = current.get() === step

    /** The step that failed, to exactly one caller; null if none was in flight. */
    fun fail(): Step? = current.getAndSet(null)

    fun abandon() {
        current.set(null)
    }
}

/**
 * The one in-flight install request, claimable exactly once.
 *
 * It replaces an `AtomicBoolean` beside a `@Volatile` field, which could not do this job
 * however carefully each half was written: the framework answers on its own thread and
 * `onReceive`, `onSent` and `onError` all raced to take the payload with a read followed by
 * a write, so two of them could read the same non-null value and both report on it. That was
 * survivable only because `DeliveryProgress` threw the second report away — a guard one
 * layer up, doing a job this one had failed to do.
 *
 * A single [AtomicReference] makes the payload itself the token, so exactly one caller can
 * ever win. The ordering matters as much as the atomicity: [offer] publishes the payload
 * *and* claims the slot in one write, where the old pair set the boolean first and the
 * payload a line later — so a callback landing in between would have cleared the guard while
 * finding nothing to report, leaving the send unprotected.
 *
 * Kept out of the agent, and out of reach of a `Context`, because that is the only way any
 * of this is assertable: instantiating [WatchfaceDeliveryAgent] needs the accessory SDK.
 */
internal class PendingInstallSlot {
    private val slot = AtomicReference<DirectInstallPayload?>(null)

    /** @return false if a request is already in flight, which is not this one's to replace. */
    fun offer(payload: DirectInstallPayload): Boolean = slot.compareAndSet(null, payload)

    /** The payload, to exactly one caller. Every later call gets null until the next [offer]. */
    fun claim(): DirectInstallPayload? = slot.getAndSet(null)

    /**
     * The payload, to exactly one caller, but only if [matches] accepts it: a watch reply
     * names the face and style it answers, and an answer about another request settles
     * nothing.
     */
    fun claimIf(matches: (DirectInstallPayload) -> Boolean): DirectInstallPayload? {
        val current = slot.get() ?: return null
        if (!matches(current)) return null
        return current.takeIf { slot.compareAndSet(it, null) }
    }

    /** Forgets the request, so nothing it reports later is believed. */
    fun abandon() {
        slot.set(null)
    }
}
