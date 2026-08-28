package dev.fitface.studio.core.delivery

import dev.fitface.studio.core.delivery.OtaTransferDeliveryAgent.Companion.COMMAND_TIMEOUT_MS
import dev.fitface.studio.core.delivery.OtaTransferDeliveryAgent.Companion.COMPLETION_POST_MS
import dev.fitface.studio.core.delivery.OtaTransferDeliveryAgent.Companion.RESULT_TIMEOUT_MS
import dev.fitface.studio.core.delivery.OtaTransferDeliveryAgent.Companion.RESULT_TO_CLOSE_PAUSE_MS
import dev.fitface.studio.core.delivery.OtaTransferDeliveryAgent.Companion.TEARDOWN_PAUSE_MS
import dev.fitface.studio.core.delivery.OtaTransferDeliveryAgent.Companion.WINDOW_TIMEOUT_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the transfer watchdog cannot fire on a transfer that is merely slow.
 *
 * The watchdog is armed for [TRANSFER_WATCHDOG_MS] and re-armed by the
 * agent's progress callbacks, so what it really measures is **the longest gap between two
 * of those callbacks**. It used to be re-armed by an acknowledged window and nothing else,
 * and three stretches of a perfectly healthy transfer are longer than its budget:
 *
 *  * the opening handshake — negotiation, descriptor and the first window, 8 + 8 + 12 s,
 *    with nothing reported in between;
 *  * one window re-sent up to [IdentityTransferProtocol.MAX_WINDOW_RETRIES] times, because
 *    an `SPP_WINDOW_RETRY` answer reported nothing;
 *  * and the tail after the last window — 15 s while the watch verifies the whole BIN, a
 *    250 ms pause, an 8 s close handshake, 500 ms of teardown and a 1 s completion post,
 *    24.75 s in total.
 *
 * While a fired watchdog only rewrote the phase, that was a cosmetic lie. Now that it calls
 * `abandonInFlight`, it takes the transfer with it: the abandoned attempt's token discards
 * the queued `onTransferComplete`, the install command is never sent, and an install the
 * watch had already accepted and verified reports as a timeout. Every wait therefore ends
 * in a report, and this test is the arithmetic — the thing that actually broke, and the
 * thing a comment cannot hold.
 *
 * It is deliberately about the *constants* rather than about a run. Driving the real state
 * machine needs a `Context`, an accessory session and an RFCOMM socket; the budget needs
 * none of those to be wrong.
 */
class TransferWatchdogBudgetTest {

    /**
     * The production list, not a copy of it — see [TRANSFER_PROGRESS_GAPS], including why it
     * cannot live in the agent's own companion without this test dying of `VerifyError`.
     */
    private val gapsBetweenProgressReports = TRANSFER_PROGRESS_GAPS

    @Test
    fun noGapBetweenProgressReportsCanOutlastTheTransferWatchdog() {
        assertTrue("the timeline is empty", gapsBetweenProgressReports.isNotEmpty())
        for ((stretch, budget) in gapsBetweenProgressReports) {
            assertTrue(
                "$stretch may take ${budget}ms, which a " +
                    "${TRANSFER_WATCHDOG_MS}ms watchdog would call dead",
                budget <= TRANSFER_WATCHDOG_MS,
            )
        }
    }

    /**
     * The retry ladder has to be survivable, which is the whole reason it exists. Four
     * attempts at one window is 48 s of legitimate work; it is only affordable because
     * each attempt reports, so the watchdog sees four gaps and not one.
     */
    @Test
    fun aWindowSpendingItsWholeRetryLadderOutlastsTheWatchdogAndMustStillSurvive() {
        val attempts = IdentityTransferProtocol.MAX_WINDOW_RETRIES + 1
        val ladder = attempts * WINDOW_TIMEOUT_MS

        assertTrue(
            "the ladder is meant to be longer than the watchdog — that is what makes the " +
                "per-attempt report load-bearing rather than cosmetic",
            ladder > TRANSFER_WATCHDOG_MS,
        )
        assertTrue(
            "one attempt must still fit, or no amount of reporting saves it",
            WINDOW_TIMEOUT_MS <= TRANSFER_WATCHDOG_MS,
        )
    }

    /**
     * The tail, spelled out. This is the number the old code lost a good install to, and
     * it is here so that lengthening any part of the shutdown is caught rather than
     * discovered on a wrist.
     */
    @Test
    fun theTailAfterTheLastWindowIsLongerThanTheWatchdogWithoutItsIntermediateReports() {
        val unreportedTail = RESULT_TIMEOUT_MS + RESULT_TO_CLOSE_PAUSE_MS + COMMAND_TIMEOUT_MS +
            TEARDOWN_PAUSE_MS + COMPLETION_POST_MS

        assertFalse(
            "if the tail ever fits the watchdog whole, the re-arms in it are no longer " +
                "what keeps a verified install alive — say so here before removing them",
            unreportedTail <= TRANSFER_WATCHDOG_MS,
        )
    }

    @Test
    fun onlyTransferringRearmsTheTransferWatchdog() {
        assertTrue(transferProgressRearmsWatchdog(DirectInstallPhase.TRANSFERRING))
        // Accepted by DeliveryProgress, but arming here would replace VERIFYING's own
        // watchdog with one that returns the instant it fires. That is a disarm.
        assertFalse(transferProgressRearmsWatchdog(DirectInstallPhase.VERIFYING))
        for (phase in DirectInstallPhase.entries - DirectInstallPhase.TRANSFERRING) {
            assertFalse(phase.name, transferProgressRearmsWatchdog(phase))
        }
    }

    /**
     * A status accepted in VERIFYING must still be *accepted* — it is how the channel the
     * agent acquired reaches the screen. Only the re-arm is withheld.
     */
    @Test
    fun aStatusIsStillWelcomeWhileVerifying() {
        assertTrue(
            DeliveryProgress.accepts(
                DirectInstallPhase.VERIFYING,
                DeliveryEvent.TRANSFER_PROGRESS,
            ),
        )
    }
}
