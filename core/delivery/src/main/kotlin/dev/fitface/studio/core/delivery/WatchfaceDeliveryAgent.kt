package dev.fitface.studio.core.delivery

import android.content.Context
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
}

class WatchfaceDeliveryAgent(context: Context) :
    DiscoveryAgent("WatchfaceSerevice", context, PROFILE) {
    @Volatile
    internal var installListener: InstallListener? = null

    private val pendingInstall = PendingInstallSlot()

    private val installMessage = object : SAMessage(this) {
        override fun onReceive(peerAgent: SAPeerAgent, data: ByteArray) {
            // Band replies target the stock plugin's reserved local component ID 6 in
            // the proven coexistence architecture. Handle a direct response if
            // a future framework version routes it here, but do not require it.
            val payload = claimPendingInstall() ?: return
            installListener?.onInstallDelivered(payload)
        }

        override fun onSent(peerAgent: SAPeerAgent, transactionId: Int) {
            val payload = claimPendingInstall() ?: return
            installListener?.onInstallDelivered(payload)
        }

        override fun onError(peerAgent: SAPeerAgent?, transactionId: Int, errorCode: Int) {
            // Guarded like the two above it. This used to report unconditionally, so an
            // error arriving for an install the watchdog had already given up on
            // overwrote whatever the reader was being shown instead.
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
    internal fun cancelInstall() = pendingInstall.abandon()

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

    /** Forgets the request, so nothing it reports later is believed. */
    fun abandon() {
        slot.set(null)
    }
}
