package dev.fitface.studio.core.delivery

import android.content.Context
import com.samsung.android.sdk.accessory.SAAgentV2
import com.samsung.android.sdk.accessory.SAPeerAgent

internal fun interface DiscoveryListener {
    fun onDiscovery(
        profile: String,
        peer: SAPeerAgent?,
        result: Int,
        outcome: String,
        detail: String,
    )
}

abstract class DiscoveryAgent(
    agentName: String,
    context: Context,
    val profile: String,
) : SAAgentV2(agentName, context) {
    @Volatile
    internal var discoveryListener: DiscoveryListener? = null

    @Volatile
    var discoveredPeer: SAPeerAgent? = null
        private set

    /**
     * Drops the cached handle. A peer only outlives the connection it was found on
     * paper, so once the watch has gone every send against it fails the same way and
     * the only fix is discovering again.
     */
    internal fun forgetPeer() {
        discoveredPeer = null
    }

    fun discover() {
        try {
            findPeerAgents()
        } catch (error: RuntimeException) {
            discoveryListener?.onDiscovery(
                profile,
                null,
                -1,
                "exception",
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    override fun onFindPeerAgentsResponse(peerAgents: Array<out SAPeerAgent>?, result: Int) {
        publishPeers(peerAgents, result)
    }

    override fun onPeerAgentsUpdated(peerAgents: Array<out SAPeerAgent>?, result: Int) {
        publishPeers(peerAgents, result)
    }

    override fun onServiceConnectionRequested(peerAgent: SAPeerAgent?) {
        if (peerAgent != null) {
            rejectServiceConnectionRequest(peerAgent)
        }
    }

    override fun onError(peerAgent: SAPeerAgent?, errorMessage: String?, errorCode: Int) {
        discoveryListener?.onDiscovery(
            profile,
            null,
            errorCode,
            "agent_error",
            errorMessage.orEmpty(),
        )
    }

    private fun publishPeers(peerAgents: Array<out SAPeerAgent>?, result: Int) {
        val peer = peerAgents?.singleOrNull()
        discoveredPeer = peer
        val outcome = when (result) {
            PEER_AGENT_FOUND -> "peer_found"
            FINDPEER_DEVICE_NOT_CONNECTED -> "device_not_connected"
            FINDPEER_SERVICE_NOT_FOUND -> "service_not_found"
            FINDPEER_DUPLICATE_REQUEST -> "duplicate_request"
            else -> "result_$result"
        }
        discoveryListener?.onDiscovery(
            profile,
            peer,
            result,
            outcome,
            "peers=${peerAgents?.size ?: 0}" +
                (peer?.let { ", id=${runCatching { it.peerId }.getOrNull()}" } ?: ""),
        )
    }
}
