package dev.fitface.studio.core.delivery

import dev.fitface.studio.core.model.AlbumPhoto
import dev.fitface.studio.core.model.DirectInstallPayload
import dev.fitface.studio.core.model.WatchFile
import java.io.ByteArrayOutputStream
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

    /** The watch's answer to [request], `44 16 <status> 04 <id> 1D <sampler>`, or null. */
    fun decodeReply(data: ByteArray): InstallReply? {
        if (data.size < REPLY_SIZE) return null
        if (data[0] != 0x44.toByte() || data[1] != 0x16.toByte() ||
            data[3] != 0x04.toByte() || data[5] != 0x1d.toByte()
        ) {
            return null
        }
        return InstallReply(
            status = data[2].toInt() and 0xFF,
            faceId = data[4].toInt() and 0xFF,
            samplerId = data[6].toInt() and 0xFF,
        )
    }

    private const val REPLY_SIZE = 7
}

/**
 * What the watch said about an install command.
 *
 * Status values are the stock plugin's own (`InstallBandFaceResponseParser`), corroborated by
 * Fit3-App, which owns the link and decodes them. On this app's coexistence route the reply
 * is addressed to the stock plugin's component, so it may never reach us. If one does, it is
 * read for what it says. Arriving is not success: a low-battery or full-list refusal used to
 * be reported as a delivered install.
 */
internal data class InstallReply(val status: Int, val faceId: Int, val samplerId: Int) {
    val accepted: Boolean get() = status == ACCEPTED

    /** Why the watch refused, in plain words, or null for success or a status not known. */
    val refusal: String?
        get() = when (status) {
            2 -> "The watch refused the install because its battery is low. Charge it, then send again."
            3 -> "The watch could not find the transferred file. Send it again."
            4 -> "The watch rejected the face's format, so the face on it is unchanged."
            5 -> "The watch's face list is full. Remove a face on the watch, then send again."
            6 -> "The watch has no room for another face. Remove a face on the watch, then send again."
            else -> null
        }

    private companion object {
        const val ACCEPTED = 1
    }
}

/** Up to [limit] received bytes as spaced hex, for the diagnostics report. */
internal fun ByteArray.replyHex(limit: Int = 32): String {
    val shown = take(limit).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    return if (size > limit) "$shown …(+${size - limit})" else shown
}

internal object IdentityTransferProtocol {
    const val CHUNK_BYTES: Int = 960

    /** One place, so the protocol and the payload's own boundary check cannot drift. */
    const val WINDOW_BYTES: Int = DirectInstallPayload.TRANSFER_WINDOW_BYTES
    const val MAX_WINDOW_RETRIES: Int = 3

    fun descriptor(fileName: String, fileSize: Int): ByteArray =
        "33bin,/user/wf/$fileName,$fileSize".toByteArray(StandardCharsets.US_ASCII)

    /**
     * The SPP descriptor for [file]: `33<extension>,<directory><name>,<size>`, as the stock
     * plugin's sender builds it. A container goes to `/user/wf/`; a `.png` goes to
     * `/user/wf/album/`, which is where the plugin sends the Photos face's photos.
     */
    fun descriptor(file: WatchFile): ByteArray = when (file) {
        is DirectInstallPayload -> descriptor(file.fileName, file.size)
        is AlbumPhoto -> "33png,/user/wf/album/${file.fileName},${file.size}"
            .toByteArray(StandardCharsets.US_ASCII)
    }

    /**
     * The file-info message sent to the OTA peer before the channel opens:
     * `01 03 06 04 <length> <name> 05 <size, little-endian>`. One layout for both kinds of
     * file: the plugin sends a photo's name exactly where it sends a face's.
     */
    fun fileInfo(file: WatchFile): ByteArray {
        val name = file.fileName.toByteArray(StandardCharsets.UTF_8)
        return ByteArrayOutputStream(10 + name.size).apply {
            write(0x01)
            write(0x03)
            write(0x06)
            write(0x04)
            write(name.size)
            write(name)
            write(0x05)
            write(file.size and 0xff)
            write((file.size ushr 8) and 0xff)
            write((file.size ushr 16) and 0xff)
            write((file.size ushr 24) and 0xff)
        }.toByteArray()
    }

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
