package dev.fitface.studio.core.delivery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that stops an abandoned attempt rewriting the state of a live one.
 *
 * Every case here is a real sequence off the device: the agents hold a blocking RFCOMM
 * worker and two delayed handler callbacks, and the accessory framework answers an
 * install whenever it gets round to it, so a watchdog that has fired and a reader who
 * has rewound both routinely still have a worker behind them.
 */
class DeliveryProgressTest {
    @Test
    fun aTimelyCallbackIsAcceptedInThePhaseThatIsWaitingForIt() {
        assertTrue(accepts(DirectInstallPhase.VERIFYING, DeliveryEvent.PAYLOAD_VERIFIED))
        assertTrue(accepts(DirectInstallPhase.VERIFYING, DeliveryEvent.TRANSFER_PROGRESS))
        assertTrue(accepts(DirectInstallPhase.TRANSFERRING, DeliveryEvent.TRANSFER_PROGRESS))
        assertTrue(accepts(DirectInstallPhase.TRANSFERRING, DeliveryEvent.TRANSFER_COMPLETE))
        assertTrue(accepts(DirectInstallPhase.TRANSFERRING, DeliveryEvent.INSTALL_REQUESTED))
        assertTrue(accepts(DirectInstallPhase.INSTALLING, DeliveryEvent.INSTALL_REQUESTED))
        assertTrue(accepts(DirectInstallPhase.INSTALLING, DeliveryEvent.INSTALL_DELIVERED))
    }

    /**
     * A window is acknowledged 12 s into a retry the watchdog gave up on at 20 s.
     * `MAX_WINDOW_RETRIES` is 3 over a `0..3` loop, so one window can spend four
     * `WINDOW_TIMEOUT_MS` waits — 48 s against a 20 s watchdog — which is why this is
     * arithmetic rather than bad luck.
     */
    @Test
    fun aWindowAcknowledgedAfterTheTransferWatchdogFiredCannotUndoTheFailure() {
        assertFalse(accepts(DirectInstallPhase.FAILED, DeliveryEvent.TRANSFER_PROGRESS))
        assertFalse(accepts(DirectInstallPhase.FAILED, DeliveryEvent.TRANSFER_COMPLETE))
    }

    /** A late accessory `onSent` used to turn a timed-out install into COMPLETE. */
    @Test
    fun aLateInstallAcknowledgementCannotReportAFaceTheWatchNeverGot() {
        assertFalse(accepts(DirectInstallPhase.FAILED, DeliveryEvent.INSTALL_DELIVERED))
        assertFalse(accepts(DirectInstallPhase.COMPLETE, DeliveryEvent.INSTALL_DELIVERED))
    }

    /**
     * The one that made a timed-out transfer a dead end. Tapping "Reconnect the watch and
     * discover again" cancels the transfer, which makes the abandoned worker throw within
     * milliseconds — so its failure landed just *after* the rewind and put the page
     * straight back into FAILED, leaving the whole setup again as the only way on.
     */
    @Test
    fun anAbandonedWorkersFailureCannotStompTheRewindThatAbandonedIt() {
        assertFalse(accepts(DirectInstallPhase.IDLE, DeliveryEvent.FAILURE))
        assertFalse(accepts(DirectInstallPhase.NEEDS_WATCH_CONNECTION, DeliveryEvent.FAILURE))
        assertFalse(accepts(DirectInstallPhase.PEERS_CACHED, DeliveryEvent.FAILURE))
        assertFalse(accepts(DirectInstallPhase.READY, DeliveryEvent.FAILURE))
    }

    /** A failure during the attempt itself is still the reason the reader needs. */
    @Test
    fun aFailureIsAcceptedWhileTheAttemptIsStillRunning() {
        DirectInstallState.ActivePhases.forEach { phase ->
            assertTrue("$phase", accepts(phase, DeliveryEvent.FAILURE))
        }
    }

    /** Nothing may restart a finished install, or a second face would land on top. */
    @Test
    fun aTerminalPhaseAcceptsNothing() {
        listOf(DirectInstallPhase.COMPLETE, DirectInstallPhase.FAILED).forEach { phase ->
            DeliveryEvent.entries.forEach { event ->
                assertFalse("$phase/$event", accepts(phase, event))
            }
        }
    }

    /** Nor may a callback arrive before its own attempt has started. */
    @Test
    fun aSetupPhaseAcceptsNothing() {
        val setup = listOf(
            DirectInstallPhase.IDLE,
            DirectInstallPhase.NEEDS_HELPER_PERMISSION,
            DirectInstallPhase.NEEDS_PLUGIN,
            DirectInstallPhase.NEEDS_WATCH_CONNECTION,
            DirectInstallPhase.PEERS_CACHED,
            DirectInstallPhase.READY,
        )
        setup.forEach { phase ->
            DeliveryEvent.entries.forEach { event ->
                assertFalse("$phase/$event", accepts(phase, event))
            }
        }
    }

    private fun accepts(phase: DirectInstallPhase, event: DeliveryEvent): Boolean =
        DeliveryProgress.accepts(phase, event)
}
