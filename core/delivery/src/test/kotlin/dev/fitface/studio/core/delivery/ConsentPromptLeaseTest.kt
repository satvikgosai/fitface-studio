package dev.fitface.studio.core.delivery

import dev.fitface.studio.core.delivery.ConsentPromptLease.Opened
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lease that lets the accessory framework's consent prompt open during discovery.
 *
 * The binding itself only exists on a phone; what can go wrong here is the bookkeeping: a
 * finished discovery taking the binding from the one after it, a second bind leaking a
 * connection, or a refused bind never being retried.
 */
class ConsentPromptLeaseTest {
    /** Counts calls; [bound] is how many live bindings it holds, which only a BOUND answer adds. */
    private class FakeBinding(var answer: Opened = Opened.BOUND) : ConsentPromptLease.Binding {
        var binds = 0
        var unbinds = 0
        var bound = 0

        override fun bind(): Opened {
            binds += 1
            if (answer == Opened.BOUND) bound += 1
            return answer
        }

        override fun unbind() {
            unbinds += 1
            bound -= 1
        }
    }

    @Test
    fun oneDiscoveryBindsOnceAndItsEndUnbinds() {
        val binding = FakeBinding()
        val lease = ConsentPromptLease(binding)

        val opened = lease.open()
        assertEquals(Opened.BOUND, opened.opened)
        assertEquals(1, binding.bound)

        lease.close(opened)
        assertEquals(0, binding.bound)
    }

    /**
     * Find, time out, find again: the first discovery's end is observed after the second has
     * started, and must not leave the second without the binding its prompt needs.
     */
    @Test
    fun anEarlierDiscoveryEndingKeepsTheLaterOnesBinding() {
        val binding = FakeBinding()
        val lease = ConsentPromptLease(binding)

        val first = lease.open()
        val second = lease.open()
        lease.close(first)

        assertEquals("a second open reuses the binding", 1, binding.binds)
        assertEquals(1, binding.bound)
        assertEquals(Opened.BOUND, second.opened)

        lease.close(second)
        assertEquals(0, binding.bound)
    }

    @Test
    fun resetReleasesWhoeverOpenedItAndOnlyOnce() {
        val binding = FakeBinding()
        val lease = ConsentPromptLease(binding)
        val opened = lease.open()

        lease.release()
        lease.release()
        lease.close(opened)

        assertEquals(1, binding.unbinds)
        assertEquals(0, binding.bound)
    }

    /** Below Android 14 every binding already lends the right, so nothing is held or undone. */
    @Test
    fun nothingIsHeldWhereTheLeaseIsNotNeeded() {
        val binding = FakeBinding(Opened.NOT_NEEDED)
        val lease = ConsentPromptLease(binding)

        val opened = lease.open()
        lease.close(opened)
        lease.release()

        assertEquals(Opened.NOT_NEEDED, opened.opened)
        assertEquals(0, binding.unbinds)
    }

    /** A framework that was missing or refused the bind is asked again by the next discovery. */
    @Test
    fun aRefusedBindIsRetriedByTheNextDiscovery() {
        val binding = FakeBinding(Opened.UNAVAILABLE)
        val lease = ConsentPromptLease(binding)

        assertEquals(Opened.UNAVAILABLE, lease.open().opened)
        binding.answer = Opened.BOUND
        val retried = lease.open()

        assertEquals(Opened.BOUND, retried.opened)
        assertEquals(2, binding.binds)
        assertEquals(1, binding.bound)
    }
}
