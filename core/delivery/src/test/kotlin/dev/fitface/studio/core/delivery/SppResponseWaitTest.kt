package dev.fitface.studio.core.delivery

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SPP response wait against synthetic streams, the same way
 * [IdentityTransferProtocolSyntheticTest] pins the encoder without a watch.
 *
 * The wait used to poll `input.available()` with a bare `Thread.sleep` and check nothing
 * but its own deadline, so an abandoned transfer held a thread until the longest timeout
 * in the protocol — 15 s — expired. The abort test below is the one that pins that.
 */
class SppResponseWaitTest {
    /** A virtual clock the pause advances, so no test actually sleeps. */
    private var elapsed = 0L
    private var pauses = 0

    private fun awaitToken(
        input: InputStream,
        timeoutMs: Long,
        vararg accepted: ByteArray,
        aborted: () -> Boolean = { false },
    ): ByteArray = SppResponseWait.awaitToken(
        input = input,
        timeoutMs = timeoutMs,
        accepted = accepted,
        elapsedMillis = { elapsed },
        pause = { millis ->
            pauses++
            elapsed += millis
        },
        aborted = aborted,
    )

    @Test
    fun aTokenAlreadyInTheStreamIsReturnedWithoutWaiting() {
        val accepted = SPP_WINDOW_ACCEPTED

        val token = awaitToken(ByteArrayInputStream(accepted), 8_000L, accepted)

        assertArrayEquals(accepted, token)
        assertEquals("no poll was needed", 0, pauses)
        assertEquals(0L, elapsed)
    }

    /** The watch answers a window with either acceptance or a retry request. */
    @Test
    fun theTokenThatArrivedIsTheOneReported() {
        val token = awaitToken(
            ByteArrayInputStream(SPP_WINDOW_RETRY),
            12_000L,
            SPP_WINDOW_ACCEPTED,
            SPP_WINDOW_RETRY,
        )

        assertArrayEquals(SPP_WINDOW_RETRY, token)
    }

    /** SPP hands over whatever has arrived, so a token can straddle two reads. */
    @Test
    fun aTokenSplitAcrossReadsIsStillRecognised() {
        val token = awaitToken(
            DripStream(SPP_NEGOTIATION_RESPONSE, perRead = 1),
            8_000L,
            SPP_NEGOTIATION_RESPONSE,
        )

        assertArrayEquals(SPP_NEGOTIATION_RESPONSE, token)
    }

    @Test
    fun aStreamThatNeverAnswersTimesOutOnceTheDeadlinePasses() {
        val failure = assertThrowsIo {
            awaitToken(SilentStream(), 8_000L, SPP_NEGOTIATION_RESPONSE)
        }

        assertEquals(SppResponseWait.TIMED_OUT, failure.message)
        assertTrue("the full timeout is spent before giving up", elapsed >= 8_000L)
    }

    /**
     * The reason this wait is injectable at all: a transfer the user has walked away
     * from has to end within a poll, not at the deadline. Coroutine cancellation cannot
     * interrupt a parked thread, so the loop has to ask.
     */
    @Test
    fun aWaitToldToAbortGivesUpPromptlyRatherThanAtTheDeadline() {
        val failure = assertThrowsIo {
            awaitToken(
                SilentStream(),
                RESULT_TIMEOUT_MS,
                SPP_RESULT_RESPONSE,
                aborted = { elapsed >= 20L },
            )
        }

        assertEquals(SppResponseWait.ABORTED, failure.message)
        assertTrue(
            "gave up after ${elapsed}ms of a ${RESULT_TIMEOUT_MS}ms timeout",
            elapsed <= 20L + SppResponseWait.POLL_MS,
        )
    }

    @Test
    fun anAlreadyAbortedWaitNeverPollsAtAll() {
        val failure = assertThrowsIo {
            awaitToken(SilentStream(), RESULT_TIMEOUT_MS, SPP_RESULT_RESPONSE, aborted = { true })
        }

        assertEquals(SppResponseWait.ABORTED, failure.message)
        assertEquals(0, pauses)
        assertEquals(0L, elapsed)
    }

    /** A closed socket is a different diagnosis from a watch that went quiet. */
    @Test
    fun aClosedInputIsReportedAsClosedRatherThanAsATimeout() {
        val failure = assertThrowsIo {
            awaitToken(ClosedStream(), 8_000L, SPP_CLOSE_RESPONSE)
        }

        assertEquals(SppResponseWait.INPUT_CLOSED, failure.message)
    }

    /** Noise before the token is tolerated; noise instead of it is not. */
    @Test
    fun aResponseThatIsNotTheExpectedTokenStillTimesOut() {
        val failure = assertThrowsIo {
            awaitToken(ByteArrayInputStream("331".toByteArray()), 8_000L, SPP_DESCRIPTOR_RESPONSE)
        }

        assertEquals(SppResponseWait.TIMED_OUT, failure.message)
    }

    private fun assertThrowsIo(block: () -> Unit): IOException = try {
        block()
        throw AssertionError("expected an IOException")
    } catch (error: IOException) {
        error
    }

    /** Yields at most [perRead] bytes at a time, the way a real SPP read does. */
    private class DripStream(private val bytes: ByteArray, private val perRead: Int) :
        InputStream() {
        private var position = 0

        override fun available(): Int = minOf(perRead, bytes.size - position)

        override fun read(): Int =
            if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xff

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            val count = minOf(length, available())
            if (count <= 0) return -1
            bytes.copyInto(target, offset, position, position + count)
            position += count
            return count
        }
    }

    private class SilentStream : InputStream() {
        override fun available(): Int = 0

        override fun read(): Int = throw AssertionError("nothing should be read")
    }

    /** Claims a byte is waiting and then reports end of stream, as a closed socket does. */
    private class ClosedStream : InputStream() {
        override fun available(): Int = 1

        override fun read(): Int = -1

        override fun read(target: ByteArray, offset: Int, length: Int): Int = -1
    }

    private companion object {
        val SPP_NEGOTIATION_RESPONSE = "300".toByteArray()
        val SPP_DESCRIPTOR_RESPONSE = "330".toByteArray()
        val SPP_WINDOW_ACCEPTED = "310".toByteArray()
        val SPP_WINDOW_RETRY = "311".toByteArray()
        val SPP_RESULT_RESPONSE = "320".toByteArray()
        val SPP_CLOSE_RESPONSE = "340".toByteArray()

        /** The long one, and the whole point of item 2.9. */
        const val RESULT_TIMEOUT_MS = 15_000L
    }
}
