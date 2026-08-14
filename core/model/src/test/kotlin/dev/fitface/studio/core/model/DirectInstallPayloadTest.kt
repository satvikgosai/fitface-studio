package dev.fitface.studio.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectInstallPayloadTest {
    @Test
    fun freezesInputBytesAndReturnsDefensiveCopies() {
        val source = byteArrayOf(1, 2, 3, 4)
        val payload = DirectInstallPayload.create(
            faceId = 46,
            samplerId = 2,
            fileName = "SM-R390_00046_256x402.bin",
            bytes = source,
        )

        source[0] = 9
        val firstCopy = payload.copyBytes()
        firstCopy[1] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), payload.copyBytes())
    }

    @Test
    fun rejectsFilenameOrHashThatDoesNotMatchPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectInstallPayload.create(
                faceId = 46,
                samplerId = 2,
                fileName = "SM-R390_00106_256x402.bin",
                bytes = byteArrayOf(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectInstallPayload(
                faceId = 46,
                samplerId = 2,
                fileName = "SM-R390_00046_256x402.bin",
                sha256 = "0".repeat(64),
                bytes = byteArrayOf(1),
            )
        }
    }
}
