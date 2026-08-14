package dev.fitface.studio.core.delivery

import dev.fitface.studio.core.model.DirectInstallPayload
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

internal object WatchfaceInstallProtocol {
    fun request(payload: DirectInstallPayload): ByteArray =
        byteArrayOf(
            0x04,
            0x04,
            payload.faceId.toByte(),
            0x1d,
            payload.samplerId.toByte(),
        )
}

internal object IdentityTransferProtocol {
    const val CHUNK_BYTES: Int = 960
    const val WINDOW_BYTES: Int = 39_600
    const val MAX_WINDOW_RETRIES: Int = 3

    fun descriptor(fileName: String, fileSize: Int): ByteArray =
        "33bin,/user/wf/$fileName,$fileSize".toByteArray(StandardCharsets.US_ASCII)

    fun windowCount(payloadSize: Int): Int =
        (payloadSize + WINDOW_BYTES - 1) / WINDOW_BYTES

    @Throws(IOException::class)
    fun writeWindow(output: OutputStream, payload: ByteArray, windowIndex: Int): Int {
        val windowStart = Math.multiplyExact(windowIndex, WINDOW_BYTES)
        if (windowStart < 0 || windowStart >= payload.size) {
            throw IOException("Invalid window index $windowIndex")
        }
        val windowLength = minOf(WINDOW_BYTES, payload.size - windowStart)
        val windowEnd = windowStart + windowLength
        var cursor = windowStart

        while (cursor + CHUNK_BYTES <= windowEnd) {
            output.write(payload, cursor, CHUNK_BYTES)
            output.flush()
            cursor += CHUNK_BYTES
        }

        val tailLength = windowEnd - cursor
        val finalPacket = ByteArray(tailLength + 4)
        payload.copyInto(finalPacket, endIndex = cursor + tailLength, startIndex = cursor)
        val crc = crc32(payload, windowStart, windowLength)
        finalPacket[tailLength] = crc.toByte()
        finalPacket[tailLength + 1] = (crc ushr 8).toByte()
        finalPacket[tailLength + 2] = (crc ushr 16).toByte()
        finalPacket[tailLength + 3] = (crc ushr 24).toByte()
        output.write(finalPacket)
        output.flush()
        return windowLength
    }

    fun crc32(payload: ByteArray, offset: Int, length: Int): Int =
        CRC32().apply { update(payload, offset, length) }.value.toInt()
}
