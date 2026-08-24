package dev.fitface.studio.core.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a watchdog timeout does to the state machine.
 *
 * None of it was covered: the decision was inlined in `Fit3DirectInstaller.armWatchdog`,
 * inside the coroutine that waits for it, on a class that needs a Context and the
 * accessory SDK — so the rule the whole recovery path rests on, that a discovery timeout
 * stays recoverable, was asserted nowhere. Timeout recovery is also the one delivery
 * behaviour never exercised on real hardware, which is the other reason it is pinned
 * here phase by phase.
 */
class TimeoutRecoveryTest {
    private val ready = DirectInstallState(
        environment = CompanionEnvironment(
            pluginInstalled = true,
            pluginLabel = "Fit3 plugin",
            companionAppInstalled = true,
            frameworkVerdict = FrameworkVerdict.USABLE,
            probed = true,
        ),
        helperNearbyGranted = true,
        pluginNearbyGranted = true,
        watchfacePeerCached = true,
        otaPeerCached = true,
    )

    private fun waitingIn(phase: DirectInstallPhase) = ready.copy(phase = phase)

    /**
     * The invariant: silence from discovery means the watch is not connected, which the
     * user can put back from the checklist. FAILED can only be left by restarting the
     * whole setup, so landing there would strand them.
     */
    @Test
    fun aDiscoveryTimeoutIsRecoverableAndNeverTerminal() {
        val timedOut = TimeoutRecovery.timedOut(
            waitingIn(DirectInstallPhase.DISCOVERING),
            DirectInstallPhase.DISCOVERING,
        )

        assertEquals(DirectInstallPhase.NEEDS_WATCH_CONNECTION, timedOut.phase)
        assertFalse("a discovery timeout must not dead-end", timedOut.isTerminal)
        assertFalse(timedOut.isActive)
        assertEquals(TimeoutRecovery.DISCOVERY, timedOut.message)
    }

    /** A peer handle does not outlive the connection it was found on. */
    @Test
    fun aDiscoveryTimeoutRewindsThePeersAndVoidsTheReleaseAcknowledgement() {
        val handedOver = waitingIn(DirectInstallPhase.DISCOVERING).copy(
            pluginNearbyReleaseAcknowledged = true,
        )
        assertTrue(handedOver.setupComplete)

        val timedOut = TimeoutRecovery.timedOut(handedOver, DirectInstallPhase.DISCOVERING)

        assertFalse(timedOut.peersCached)
        assertFalse(timedOut.watchfacePeerCached)
        assertFalse(timedOut.otaPeerCached)
        assertFalse(
            "discovery ran, so the plugin was holding the channel again",
            timedOut.pluginNearbyReleaseAcknowledged,
        )
        assertFalse(timedOut.setupComplete)
        assertFalse(timedOut.isStepDone(SetupStep.PEERS_DISCOVERED))
        assertFalse(timedOut.isStepDone(SetupStep.PLUGIN_RELEASED))
        assertTrue("the checklist is back on discovery", timedOut.awaitingDiscovery)
    }

    /**
     * Discovery is a step the user can retry, not a failure to report — the failure
     * banner would outlive what it describes the moment they reconnect the watch.
     */
    @Test
    fun aDiscoveryTimeoutCarriesNoFailureText() {
        val discovering = ready.copy(
            phase = DirectInstallPhase.PEERS_CACHED,
            failure = "an earlier attempt",
        ).discovering()
        assertNull(discovering.failure)

        val timedOut = TimeoutRecovery.timedOut(discovering, DirectInstallPhase.DISCOVERING)

        assertNull(timedOut.failure)
        assertEquals(TimeoutRecovery.DISCOVERY, timedOut.message)
    }

    @Test
    fun anInitializingTimeoutIsTerminalAndTheChecklistKeepsTheReason() {
        val timedOut = TimeoutRecovery.timedOut(
            waitingIn(DirectInstallPhase.INITIALIZING),
            DirectInstallPhase.INITIALIZING,
        )

        assertEquals(DirectInstallPhase.FAILED, timedOut.phase)
        assertTrue(timedOut.isTerminal)
        assertEquals(TimeoutRecovery.INITIALIZATION, timedOut.failure)
        assertEquals(TimeoutRecovery.INITIALIZATION, timedOut.message)
        // `message` is replaced by whatever the setup needs next, so only `failure`
        // survives a rewind — which is what the failure panel reads.
        assertEquals(
            TimeoutRecovery.INITIALIZATION,
            timedOut.rewoundToDiscovery().failure,
        )
    }

    @Test
    fun anInstallingTimeoutIsTerminalAndSaysToReconnectAndRediscover() {
        val timedOut = TimeoutRecovery.timedOut(
            waitingIn(DirectInstallPhase.INSTALLING),
            DirectInstallPhase.INSTALLING,
        )

        assertEquals(DirectInstallPhase.FAILED, timedOut.phase)
        assertTrue(timedOut.isTerminal)
        assertEquals(TimeoutRecovery.INSTALL, timedOut.failure)
        assertTrue(
            "the watch may have taken the face even so — check it first",
            timedOut.failure.orEmpty().contains("Check the watch"),
        )
        assertTrue(timedOut.failure.orEmpty().contains("discover again"))
    }

    /** The generic branch: a stalled transfer is terminal, but not a dead end. */
    @Test
    fun aTransferringTimeoutIsTerminalAndLeavesARewindPossible() {
        val stalled = waitingIn(DirectInstallPhase.TRANSFERRING).copy(
            pluginNearbyGranted = false,
            acknowledgedBytes = 39_600,
            totalBytes = 120_000,
        )

        val timedOut = TimeoutRecovery.timedOut(stalled, DirectInstallPhase.TRANSFERRING)

        assertEquals(DirectInstallPhase.FAILED, timedOut.phase)
        assertEquals(TimeoutRecovery.TRANSFER, timedOut.failure)
        assertTrue("nothing else can be done against this peer handle", timedOut.setupComplete)

        val rewound = timedOut.rewoundToDiscovery()
        assertFalse(rewound.isTerminal)
        assertFalse(rewound.peersCached)
        assertTrue(rewound.awaitingDiscovery)
        assertEquals(TimeoutRecovery.TRANSFER, rewound.failure)
        assertEquals(0, rewound.acknowledgedBytes)
    }

    /**
     * The watchdog that fires after the thing it was waiting for arrived is stale: the
     * phase has moved on, and touching the state would drag the page backwards.
     */
    @Test
    fun aTimeoutForAPhaseTheMachineHasLeftChangesNothing() {
        DirectInstallPhase.entries.forEach { armedFor ->
            DirectInstallPhase.entries.filter { it != armedFor }.forEach { movedOn ->
                val current = waitingIn(movedOn)
                assertSame(
                    "a $armedFor watchdog must not touch a machine now in $movedOn",
                    current,
                    TimeoutRecovery.timedOut(current, armedFor),
                )
            }
        }
    }

    /**
     * Table-driven so a phase added later cannot fall through untested: discovery is
     * the only recoverable timeout, and every other phase is a plain failure.
     */
    @Test
    fun discoveryIsTheOnlyPhaseWhoseTimeoutIsNotAFailure() {
        DirectInstallPhase.entries.forEach { phase ->
            val timedOut = TimeoutRecovery.timedOut(waitingIn(phase), phase)
            if (phase == DirectInstallPhase.DISCOVERING) {
                assertEquals(DirectInstallPhase.NEEDS_WATCH_CONNECTION, timedOut.phase)
                assertFalse(timedOut.isTerminal)
            } else {
                assertEquals("$phase", DirectInstallPhase.FAILED, timedOut.phase)
                assertTrue("$phase", timedOut.isTerminal)
                assertFalse("$phase", timedOut.failure.isNullOrBlank())
            }
        }
    }

    /**
     * A timeout is not a [Fit3DirectInstaller.reset]: nothing the phone told us about
     * itself is re-probed afterwards, so losing it would send the user back through
     * steps that were never in doubt.
     */
    @Test
    fun noTimeoutEverLosesTheEnvironmentOrThePermissions() {
        DirectInstallPhase.entries.forEach { phase ->
            val timedOut = TimeoutRecovery.timedOut(waitingIn(phase), phase)

            assertEquals("$phase", ready.environment, timedOut.environment)
            assertTrue("$phase", timedOut.environment.probed)
            assertTrue("$phase", timedOut.helperNearbyGranted)
            assertEquals("$phase", ready.pluginNearbyGranted, timedOut.pluginNearbyGranted)
            assertTrue("$phase", timedOut.isStepDone(SetupStep.COMPANION_PRESENT))
            assertTrue("$phase", timedOut.isStepDone(SetupStep.HELPER_PERMISSION))
        }
    }

    /** Four distinct branches, so four distinct things the user is told. */
    @Test
    fun eachTimeoutSaysSomethingDifferent() {
        val messages = listOf(
            TimeoutRecovery.DISCOVERY,
            TimeoutRecovery.INITIALIZATION,
            TimeoutRecovery.INSTALL,
            TimeoutRecovery.TRANSFER,
        )

        assertEquals(messages.size, messages.toSet().size)
        messages.forEach { message -> assertNotEquals("", message.trim()) }
    }
}
