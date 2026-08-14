package dev.fitface.studio.core.delivery

import dev.fitface.studio.core.model.DirectInstallPayload
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The transfer encoder checked against deterministic payloads rather than recorded
 * ones, so the rules stay covered in a checkout with no fixture corpus.
 *
 * [IdentityTransferProtocolTest] pins the same encoder against payloads a real watch
 * accepted; this pins the arithmetic and framing.
 */
class IdentityTransferProtocolSyntheticTest {
    private fun payloadBytes(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    @Test
    fun windowCountRoundsUpAndIsExactOnBoundaries() {
        val window = IdentityTransferProtocol.WINDOW_BYTES

        assertEquals(0, IdentityTransferProtocol.windowCount(0))
        assertEquals(1, IdentityTransferProtocol.windowCount(1))
        assertEquals(1, IdentityTransferProtocol.windowCount(window))
        assertEquals(2, IdentityTransferProtocol.windowCount(window + 1))
        assertEquals(3, IdentityTransferProtocol.windowCount(window * 3))
        assertEquals(4, IdentityTransferProtocol.windowCount(window * 3 + 1))
    }

    @Test
    fun descriptorNamesTheWatchSideDestinationAndSize() {
        assertArrayEquals(
            "33bin,/user/wf/SM-R390_00046_256x402.bin,1234".encodeToByteArray(),
            IdentityTransferProtocol.descriptor("SM-R390_00046_256x402.bin", 1234),
        )
    }

    @Test
    fun aFullWindowIsWrittenVerbatimWithATrailingLittleEndianCrc32() {
        val payload = payloadBytes(IdentityTransferProtocol.WINDOW_BYTES * 2)
        val stream = ByteArrayOutputStream()

        val written = IdentityTransferProtocol.writeWindow(stream, payload, 0)
        val emitted = stream.toByteArray()

        assertEquals(IdentityTransferProtocol.WINDOW_BYTES, written)
        assertEquals(IdentityTransferProtocol.WINDOW_BYTES + 4, emitted.size)
        assertArrayEquals(
            payload.copyOfRange(0, IdentityTransferProtocol.WINDOW_BYTES),
            emitted.copyOfRange(0, IdentityTransferProtocol.WINDOW_BYTES),
        )
        assertEquals(
            crc32(payload, 0, IdentityTransferProtocol.WINDOW_BYTES),
            emitted.trailingLittleEndianInt(),
        )
    }

    @Test
    fun theLastWindowCarriesOnlyTheRemainingBytes() {
        val window = IdentityTransferProtocol.WINDOW_BYTES
        val tail = 1_234
        val payload = payloadBytes(window + tail)
        val stream = ByteArrayOutputStream()

        val written = IdentityTransferProtocol.writeWindow(stream, payload, 1)
        val emitted = stream.toByteArray()

        assertEquals(tail, written)
        assertEquals(tail + 4, emitted.size)
        assertArrayEquals(payload.copyOfRange(window, window + tail), emitted.copyOfRange(0, tail))
        assertEquals(crc32(payload, window, tail), emitted.trailingLittleEndianInt())
    }

    /** Every window has to be a whole number of chunks plus one CRC-terminated tail. */
    @Test
    fun windowsTileThePayloadExactlyOnceInOrder() {
        val payload = payloadBytes(IdentityTransferProtocol.WINDOW_BYTES * 2 + 17)
        val reassembled = ByteArrayOutputStream()
        val windows = IdentityTransferProtocol.windowCount(payload.size)

        var total = 0
        repeat(windows) { index ->
            val stream = ByteArrayOutputStream()
            val written = IdentityTransferProtocol.writeWindow(stream, payload, index)
            total += written
            val emitted = stream.toByteArray()
            reassembled.write(emitted, 0, emitted.size - 4)
        }

        assertEquals(3, windows)
        assertEquals(payload.size, total)
        assertArrayEquals(payload, reassembled.toByteArray())
    }

    @Test
    fun aWindowIndexPastTheEndIsRefused() {
        val payload = payloadBytes(IdentityTransferProtocol.WINDOW_BYTES)

        assertThrows(Exception::class.java) {
            IdentityTransferProtocol.writeWindow(ByteArrayOutputStream(), payload, 1)
        }
        assertThrows(Exception::class.java) {
            IdentityTransferProtocol.writeWindow(ByteArrayOutputStream(), payload, -1)
        }
    }

    @Test
    fun theInstallRequestCarriesTheFaceAndStyleBytes() {
        val payload = DirectInstallPayload.create(
            faceId = 46,
            samplerId = 2,
            fileName = "SM-R390_00046_256x402.bin",
            bytes = payloadBytes(64),
        )

        assertArrayEquals(
            byteArrayOf(0x04, 0x04, 46, 0x1d, 0x02),
            WatchfaceInstallProtocol.request(payload),
        )
    }

    /** The filename is derived from the face id, so a mismatch must not reach the watch. */
    @Test
    fun aPayloadWhoseNameDisagreesWithItsFaceIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectInstallPayload.create(
                faceId = 46,
                samplerId = 0,
                fileName = "SM-R390_00106_256x402.bin",
                bytes = payloadBytes(64),
            )
        }
    }

    private fun crc32(payload: ByteArray, offset: Int, length: Int): Int =
        CRC32().apply { update(payload, offset, length) }.value.toInt()

    private fun ByteArray.trailingLittleEndianInt(): Int {
        val offset = size - 4
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
    }
}
