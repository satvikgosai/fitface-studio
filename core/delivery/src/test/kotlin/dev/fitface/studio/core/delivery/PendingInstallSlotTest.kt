package dev.fitface.studio.core.delivery

import dev.fitface.studio.core.model.DirectInstallPayload
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That an install request is answered exactly once, by whoever gets there first.
 *
 * The accessory framework answers on its own thread and three callbacks compete for the
 * same payload — `onReceive`, `onSent` and `onError`. They used to take it with a read
 * followed by a write over a `@Volatile` field, with an `AtomicBoolean` alongside for the
 * "already in flight" guard, so two callbacks could read the same non-null payload and both
 * report on it. Only `DeliveryProgress` stopped the duplicate from being believed — a guard
 * a layer above, covering for one that had not done its job.
 *
 * The slot exists so the claim is a single atomic operation, and so it can be asserted at
 * all: [WatchfaceDeliveryAgent] cannot be instantiated in a JVM test, because the accessory
 * SDK needs a `Context` and its pre-stackmap bytecode fails the verifier.
 */
class PendingInstallSlotTest {

    @Test
    fun theFirstClaimTakesThePayloadAndEveryLaterOneGetsNothing() {
        val slot = PendingInstallSlot()
        val payload = payload()

        assertTrue(slot.offer(payload))
        assertSame(payload, slot.claim())
        // A late `onSent` used to turn a timed-out install into COMPLETE — a face the watch
        // never got, reported as installed.
        assertNull(slot.claim())
        assertNull(slot.claim())
    }

    @Test
    fun anInstallAlreadyInFlightIsNotReplaced() {
        val slot = PendingInstallSlot()
        val first = payload()

        assertTrue(slot.offer(first))
        assertFalse("a second request must be refused, not silently swapped in", slot.offer(payload()))
        assertSame(first, slot.claim())
    }

    @Test
    fun abandoningLeavesNothingToClaim() {
        val slot = PendingInstallSlot()
        slot.offer(payload())

        slot.abandon()

        assertNull("an abandoned request must not still be able to report", slot.claim())
    }

    /** Abandoning has to free the slot as well, or a retry after a timeout is refused. */
    @Test
    fun aRequestCanBeOfferedAgainAfterAbandoning() {
        val slot = PendingInstallSlot()
        slot.offer(payload())
        slot.abandon()

        val retry = payload()
        assertTrue(slot.offer(retry))
        assertSame(retry, slot.claim())
    }

    /** And after a normal claim, so a second install in one session is possible. */
    @Test
    fun aRequestCanBeOfferedAgainAfterBeingClaimed() {
        val slot = PendingInstallSlot()
        slot.offer(payload())
        slot.claim()

        assertTrue(slot.offer(payload()))
    }

    /**
     * The race itself, run for real. Sixteen threads claiming one payload at once: exactly
     * one may come away with it.
     */
    @Test
    fun onlyOneOfManySimultaneousClaimsWins() {
        repeat(200) {
            val slot = PendingInstallSlot()
            slot.offer(payload())
            val winners = AtomicInteger()
            val start = CountDownLatch(1)
            val done = CountDownLatch(16)

            repeat(16) {
                thread {
                    start.await()
                    if (slot.claim() != null) winners.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("claimants did not finish", done.await(10, TimeUnit.SECONDS))

            assertEquals("exactly one claim may win", 1, winners.get())
        }
    }

    /** The same for the guard: two threads offering, one gets the slot. */
    @Test
    fun onlyOneOfManySimultaneousOffersWins() {
        repeat(200) {
            val slot = PendingInstallSlot()
            val accepted = AtomicInteger()
            val start = CountDownLatch(1)
            val done = CountDownLatch(8)

            repeat(8) {
                thread {
                    start.await()
                    if (slot.offer(payload())) accepted.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("offerers did not finish", done.await(10, TimeUnit.SECONDS))

            assertEquals("exactly one offer may take the slot", 1, accepted.get())
        }
    }

    private fun payload() = DirectInstallPayload.create(
        faceId = 46,
        samplerId = 2,
        fileName = "SM-R390_00046_256x402.bin",
        bytes = ByteArray(64) { it.toByte() },
    )
}
