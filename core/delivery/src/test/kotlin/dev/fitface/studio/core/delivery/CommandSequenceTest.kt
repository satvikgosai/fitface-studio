package dev.fitface.studio.core.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The album commands go out one at a time, and nothing from a passed step moves a live one. */
class CommandSequenceTest {
    private val commands = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))

    @Test
    fun eachHandedOverCommandMovesToTheNextUntilTheBatchFinishes() {
        val sequence = CommandSequence()
        val first = sequence.start(7, commands)!!
        assertEquals(1, first.command.single().toInt())
        val second = (sequence.advance() as CommandSequence.Advance.Next).step
        assertEquals(1, second.index)
        assertTrue(sequence.isCurrent(second))
        assertFalse(sequence.isCurrent(first))
        sequence.advance()
        assertEquals(CommandSequence.Advance.Finished(7), sequence.advance())
        assertFalse(sequence.active)
        // A late callback after the batch is done changes nothing.
        assertSame(CommandSequence.Advance.Stale, sequence.advance())
    }

    @Test
    fun aSecondBatchCannotStartWhileOneIsInFlight() {
        val sequence = CommandSequence()
        assertNotNull(sequence.start(1, commands))
        assertNull(sequence.start(2, commands))
    }

    /** A failure is reported once, and the delayed send for the abandoned step finds it is not current. */
    @Test
    fun aFailedOrAbandonedRunIsClaimedOnceAndLeavesNothingCurrent() {
        val sequence = CommandSequence()
        sequence.start(1, commands)
        val next = (sequence.advance() as CommandSequence.Advance.Next).step
        assertNotNull(sequence.fail())
        assertNull(sequence.fail())
        assertFalse(sequence.isCurrent(next))
        assertSame(CommandSequence.Advance.Stale, sequence.advance())

        val again = sequence.start(2, commands)!!
        sequence.abandon()
        assertFalse(sequence.isCurrent(again))
        assertNotNull(sequence.start(3, commands))
    }
}
