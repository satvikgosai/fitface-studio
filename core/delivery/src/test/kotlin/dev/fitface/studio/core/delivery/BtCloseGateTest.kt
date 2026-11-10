package dev.fitface.studio.core.delivery

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtCloseGateTest {
    @Test
    fun theLiveAttemptClosesOnce() {
        val gate = BtCloseGate()
        assertTrue(gate.claim(token = 1, live = 1))
        assertFalse(gate.claim(token = 1, live = 1))
    }

    @Test
    fun anAbandonedWorkerNeverCloses() {
        // Cancelled (live moved to 2) before its `finally` ran: the late `03` would have
        // reached whatever session attempt 2 had opened.
        val gate = BtCloseGate()
        assertFalse(gate.claim(token = 1, live = 2))
    }

    @Test
    fun cancelClosesAndTheWorkerThenStaysQuiet() {
        val gate = BtCloseGate()
        // cancelTransfer claims for the live attempt before moving the token on…
        assertTrue(gate.claim(token = 1, live = 1))
        // …so the unwinding worker, live or not by the time it checks, sends nothing.
        assertFalse(gate.claim(token = 1, live = 1))
        assertFalse(gate.claim(token = 1, live = 2))
    }

    @Test
    fun aNewAttemptClosesItsOwnChannelAfterAnEarlierOneClosed() {
        val gate = BtCloseGate()
        assertTrue(gate.claim(token = 1, live = 1))
        assertTrue(gate.claim(token = 2, live = 2))
        assertFalse(gate.claim(token = 2, live = 2))
    }

    @Test
    fun anOlderTokenCannotCloseAfterANewerOne() {
        val gate = BtCloseGate()
        assertTrue(gate.claim(token = 3, live = 3))
        assertFalse(gate.claim(token = 2, live = 2))
    }

    @Test
    fun cancelAndWorkerRacingForOneAttemptCloseExactlyOnce() {
        repeat(200) {
            val gate = BtCloseGate()
            val start = CountDownLatch(1)
            val sent = AtomicInteger()
            val racers = List(4) {
                thread {
                    start.await()
                    if (gate.claim(token = 7, live = 7)) sent.incrementAndGet()
                }
            }
            start.countDown()
            racers.forEach(Thread::join)
            assertEquals(1, sent.get())
        }
    }
}
