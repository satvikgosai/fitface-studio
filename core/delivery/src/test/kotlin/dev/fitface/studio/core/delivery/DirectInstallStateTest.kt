package dev.fitface.studio.core.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The channel-handoff rules, which are the part of direct install users get stuck on.
 *
 * Two facts drive them: discovery needs the watch connected (so the stock plugin has
 * to be holding it), and the peer handles stay valid once cached (so the plugin only
 * has to let go afterwards, by any means).
 */
class DirectInstallStateTest {
    private val ready = DirectInstallState(
        environment = CompanionEnvironment(
            pluginInstalled = true,
            companionAppInstalled = true,
            frameworkVerdict = FrameworkVerdict.USABLE,
            probed = true,
        ),
        helperNearbyGranted = true,
        watchfacePeerCached = true,
        otaPeerCached = true,
    )

    @Test
    fun revokingThePluginPermissionCountsAsReleasingTheChannel() {
        val state = ready.copy(pluginNearbyGranted = false)

        assertTrue(state.pluginChannelReleased)
        assertTrue(state.setupComplete)
    }

    /**
     * The permission can stay granted: disconnecting the watch in the companion app
     * frees the channel too, and that is not observable from here, so the user's
     * acknowledgement is what completes the step.
     */
    @Test
    fun acknowledgementReleasesTheChannelWithThePermissionStillGranted() {
        val granted = ready.copy(pluginNearbyGranted = true)
        assertFalse(granted.pluginChannelReleased)
        assertFalse(granted.setupComplete)
        assertFalse(granted.isStepDone(SetupStep.PLUGIN_RELEASED))

        val acknowledged = granted.copy(pluginNearbyReleaseAcknowledged = true)
        assertTrue(acknowledged.pluginChannelReleased)
        assertTrue(acknowledged.setupComplete)
        assertTrue(acknowledged.isStepDone(SetupStep.PLUGIN_RELEASED))
    }

    @Test
    fun discoveryIsTheThirdStepAndPrecedesTheHandoff() {
        val beforeDiscovery = ready.copy(
            watchfacePeerCached = false,
            otaPeerCached = false,
            pluginNearbyGranted = true,
        )

        assertTrue(beforeDiscovery.isStepDone(SetupStep.COMPANION_PRESENT))
        assertTrue(beforeDiscovery.isStepDone(SetupStep.HELPER_PERMISSION))
        assertFalse(beforeDiscovery.isStepDone(SetupStep.PEERS_DISCOVERED))
        assertFalse(beforeDiscovery.isStepDone(SetupStep.PLUGIN_RELEASED))
    }

    /**
     * Discovery only runs while the plugin is holding the watch, so starting it
     * proves the channel is held again — whatever the user said earlier.
     */
    @Test
    fun rediscoveryVoidsAnEarlierReleaseAcknowledgement() {
        val handedOver = ready.copy(
            pluginNearbyGranted = true,
            pluginNearbyReleaseAcknowledged = true,
        )
        assertTrue(handedOver.setupComplete)

        val discovering = handedOver.discovering()

        assertEquals(DirectInstallPhase.DISCOVERING, discovering.phase)
        assertFalse("a claim made before the watch reconnected is void", discovering.pluginChannelReleased)
        assertFalse(discovering.setupComplete)
        assertFalse(discovering.peersCached)
    }

    /**
     * The case this whole recovery path exists for: the transfer fails after the
     * channel has been handed over, and the only way back is reconnecting the watch,
     * discovering again, and handing the channel over again.
     */
    @Test
    fun aFailedTransferRewindsToDiscoveryWithoutLosingTheEnvironment() {
        val failed = ready.copy(
            phase = DirectInstallPhase.FAILED,
            pluginNearbyGranted = false,
            failure = "The cached OTA peer is gone",
            totalBytes = 4_096,
            acknowledgedBytes = 512,
        )
        assertTrue("without a rewind the page stays on the transfer panel", failed.setupComplete)

        val rewound = failed.rewoundToDiscovery()

        assertFalse(rewound.isTerminal)
        assertFalse(rewound.setupComplete)
        assertFalse(rewound.peersCached)
        assertFalse(rewound.pluginNearbyReleaseAcknowledged)
        assertEquals("the reason survives into the checklist", "The cached OTA peer is gone", rewound.failure)
        assertEquals(0, rewound.acknowledgedBytes)
        // Nothing the phone told us about itself is thrown away: this is not a reset.
        assertTrue(rewound.environment.probed)
        assertTrue(rewound.helperNearbyGranted)
        assertTrue(rewound.isStepDone(SetupStep.COMPANION_PRESENT))
        assertTrue(rewound.isStepDone(SetupStep.HELPER_PERMISSION))
        assertFalse(rewound.isStepDone(SetupStep.PEERS_DISCOVERED))
        assertTrue("the checklist is back on discovery", rewound.awaitingDiscovery)
    }

    /**
     * A revoked plugin permission before discovery is not progress. Reading it as a
     * finished step 4 left the checklist claiming the handover was done while the
     * discovery it depends on had not run.
     */
    @Test
    fun theHandoverIsNotDoneBeforeThereArePeers() {
        val revokedEarly = ready.copy(
            watchfacePeerCached = false,
            otaPeerCached = false,
            pluginNearbyGranted = false,
        )

        assertTrue(revokedEarly.pluginChannelReleased)
        assertFalse(revokedEarly.isStepDone(SetupStep.PLUGIN_RELEASED))
        assertFalse(revokedEarly.setupComplete)
        assertTrue(revokedEarly.awaitingDiscovery)
    }

    @Test
    fun discoveryStopsBeingTheOutstandingStepOnceThePeersAreCached() {
        assertFalse(ready.copy(pluginNearbyGranted = true).awaitingDiscovery)
        assertTrue(ready.copy(otaPeerCached = false).awaitingDiscovery)
    }

    @Test
    fun onePeerIsNotEnough() {
        assertFalse(ready.copy(otaPeerCached = false).peersCached)
        assertFalse(ready.copy(watchfacePeerCached = false).peersCached)
        assertTrue(ready.peersCached)
    }

    @Test
    fun needsWatchConnectionIsRecoverableRatherThanTerminal() {
        val blocked = DirectInstallState(phase = DirectInstallPhase.NEEDS_WATCH_CONNECTION)

        assertFalse("discovery without the plugin must not dead-end", blocked.isTerminal)
        assertFalse(blocked.isActive)
    }

    @Test
    fun completeAndFailedAreTheOnlyTerminalPhases() {
        assertEquals(
            setOf(DirectInstallPhase.COMPLETE, DirectInstallPhase.FAILED),
            DirectInstallPhase.entries.filter {
                DirectInstallState(phase = it).isTerminal
            }.toSet(),
        )
    }

    @Test
    fun progressIsSafeBeforeTheTotalIsKnown() {
        assertEquals(0f, DirectInstallState().progress, 0f)
        assertEquals(
            0.5f,
            DirectInstallState(acknowledgedBytes = 50, totalBytes = 100).progress,
            0f,
        )
    }
}
