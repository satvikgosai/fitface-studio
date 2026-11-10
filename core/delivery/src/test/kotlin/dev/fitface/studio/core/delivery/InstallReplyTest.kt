package dev.fitface.studio.core.delivery

import dev.fitface.studio.core.model.DirectInstallPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's answer to an install command, `44 16 <status> 04 <id> 1D <sampler>`, as the
 * stock plugin's `InstallBandFaceResponseParser` reads it.
 *
 * The point of decoding it at all: the install agent used to report *any* received data as a
 * delivered install, so a low-battery or full-list refusal routed to this app would have been
 * shown as success.
 */
class InstallReplyTest {
    private fun reply(status: Int, id: Int = 6, sampler: Int = 0) =
        byteArrayOf(0x44, 0x16, status.toByte(), 0x04, id.toByte(), 0x1d, sampler.toByte())

    @Test
    fun aSuccessReplyDecodes() {
        val decoded = WatchfaceInstallProtocol.decodeReply(reply(1, id = 0xC9, sampler = 3))
        assertEquals(InstallReply(status = 1, faceId = 201, samplerId = 3), decoded)
        assertTrue(decoded!!.accepted)
        assertNull(decoded.refusal)
    }

    @Test
    fun everyKnownRefusalHasAReason() {
        (2..6).forEach { status ->
            val decoded = WatchfaceInstallProtocol.decodeReply(reply(status))!!
            assertFalse(decoded.accepted)
            assertNotNull("status $status", decoded.refusal)
        }
    }

    @Test
    fun anUnknownStatusIsNeitherSuccessNorAReason() {
        val decoded = WatchfaceInstallProtocol.decodeReply(reply(9))!!
        assertFalse(decoded.accepted)
        assertNull(decoded.refusal)
    }

    @Test
    fun somethingThatIsNotAnInstallReplyDoesNotDecode() {
        assertNull(WatchfaceInstallProtocol.decodeReply(byteArrayOf()))
        assertNull(WatchfaceInstallProtocol.decodeReply(reply(1).copyOf(6)))
        // A select-face answer (`43 04 <id> 1D <s>`) and a BT_OPEN answer (`42 01 01`).
        assertNull(WatchfaceInstallProtocol.decodeReply(byteArrayOf(0x43, 0x04, 6, 0x1d, 0, 0, 0)))
        assertNull(WatchfaceInstallProtocol.decodeReply(byteArrayOf(0x42, 0x01, 0x01)))
        // Right opcode, wrong parameter IDs.
        assertNull(WatchfaceInstallProtocol.decodeReply(byteArrayOf(0x44, 0x16, 1, 0x05, 6, 0x1d, 0)))
    }

    @Test
    fun aReplyClaimsOnlyThePendingRequestItNames() {
        val slot = PendingInstallSlot()
        val pending = payload(faceId = 6, samplerId = 1)
        assertTrue(slot.offer(pending))
        // An answer about another style settles nothing, and leaves the request for onSent.
        assertNull(slot.claimIf { it.faceId == 6 && it.samplerId == 2 })
        assertSame(pending, slot.claimIf { it.faceId == 6 && it.samplerId == 1 })
        assertNull(slot.claim())
    }

    @Test
    fun repliesAreKeptAsBoundedHex() {
        var state = DirectInstallState()
        repeat(MAX_WATCH_REPLIES + 3) { index ->
            state = withWatchReply(state, "watchface", reply(1, id = index))
        }
        assertEquals(MAX_WATCH_REPLIES, state.watchReplies.size)
        assertEquals("watchface 44 16 01 04 0A 1D 00", state.watchReplies.last())
        assertEquals("01 02 …(+1)", byteArrayOf(1, 2, 3).replyHex(limit = 2))
    }

    private fun payload(faceId: Int, samplerId: Int) = DirectInstallPayload.create(
        faceId = faceId,
        samplerId = samplerId,
        fileName = "SM-R390_%05d_256x402.bin".format(faceId),
        bytes = ByteArray(16),
    )
}
