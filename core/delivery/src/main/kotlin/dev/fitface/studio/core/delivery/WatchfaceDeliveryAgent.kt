package dev.fitface.studio.core.delivery

import android.content.Context
import com.samsung.android.sdk.accessory.SAMessage
import com.samsung.android.sdk.accessory.SAPeerAgent
import dev.fitface.studio.core.model.DirectInstallPayload
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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

    private val installInFlight = AtomicBoolean(false)

    @Volatile
    private var pendingInstall: DirectInstallPayload? = null

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
    private fun claimPendingInstall(): DirectInstallPayload? {
        val payload = pendingInstall ?: return null
        pendingInstall = null
        installInFlight.set(false)
        return payload
    }

    /**
     * Abandons the in-flight install request, so nothing it reports later is believed.
     *
     * The one-shot command is already gone by the time this can be called — there is no
     * unsending it — so this only says that its answer is no longer wanted.
     */
    internal fun cancelInstall() {
        pendingInstall = null
        installInFlight.set(false)
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
        if (!installInFlight.compareAndSet(false, true)) {
            installListener?.onInstallFailed("An install request is already in flight")
            return
        }
        pendingInstall = payload
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
