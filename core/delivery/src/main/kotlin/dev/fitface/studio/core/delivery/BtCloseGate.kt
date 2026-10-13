package dev.fitface.studio.core.delivery

import java.util.concurrent.atomic.AtomicInteger

/**
 * Who sends `BT_CLOSE` (`03`) for a transfer attempt: exactly one caller, and never for an
 * attempt that has already been superseded.
 *
 * The worker used to send it unconditionally from its `finally`. A cancelled worker unwinds
 * through half a second of teardown, and by then a retry may have opened its own session —
 * so the abandoned worker's late `03` could close the *new* attempt's channel on the watch,
 * which is exactly what `docs/direct-install.md` says an abandoned attempt must never do.
 * Now the cancel path closes the attempt it abandons, the live worker closes its own, and
 * whichever comes second finds the close already claimed.
 *
 * Kept out of [OtaTransferDeliveryAgent] for the same reason as [PendingInstallSlot]: the
 * agent needs the accessory SDK to exist, and this decision has to be assertable without it.
 */
internal class BtCloseGate {
    /** The last attempt a close was claimed for; attempt tokens start at 1. */
    private val closed = AtomicInteger(0)

    /**
     * Whether the caller should send `BT_CLOSE` for [token], given that [live] is the attempt
     * current now. True at most once per token, and never when [token] is no longer live.
     */
    fun claim(token: Int, live: Int): Boolean {
        if (token != live) return false
        while (true) {
            val previous = closed.get()
            if (previous >= token) return false
            if (closed.compareAndSet(previous, token)) return true
        }
    }
}
