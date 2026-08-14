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
            val payload = pendingInstall
            pendingInstall = null
            installInFlight.set(false)
            if (payload != null) {
                installListener?.onInstallDelivered(payload)
            }
        }

        override fun onSent(peerAgent: SAPeerAgent, transactionId: Int) {
            val payload = pendingInstall
            pendingInstall = null
            installInFlight.set(false)
            if (payload != null) {
                installListener?.onInstallDelivered(payload)
            }
        }

        override fun onError(peerAgent: SAPeerAgent?, transactionId: Int, errorCode: Int) {
            pendingInstall = null
            installInFlight.set(false)
            installListener?.onInstallFailed("Install message failed with code $errorCode")
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
        if (!installInFlight.compareAndSet(false, true)) {
            installListener?.onInstallFailed("An install request is already in flight")
            return
        }
        pendingInstall = payload
        try {
            installListener?.onInstallRequested(payload)
            installMessage.send(peer, WatchfaceInstallProtocol.request(payload))
        } catch (error: IOException) {
            pendingInstall = null
            installInFlight.set(false)
            // The bytes are already on the watch; only the peer that carries the
            // one-shot command is gone, so discovery is what has to be repeated.
            installListener?.onInstallFailed(
                error.message ?: "Install send failed",
                peerLost = true,
            )
        } catch (error: RuntimeException) {
            pendingInstall = null
            installInFlight.set(false)
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
