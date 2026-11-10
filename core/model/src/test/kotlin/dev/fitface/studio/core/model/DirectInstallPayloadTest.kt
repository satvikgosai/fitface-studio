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

    @Test
    fun acceptsTheFullRangeTheInstallCommandCarries() {
        listOf(1 to 0, 6 to 9, 255 to 0).forEach { (face, sampler) ->
            DirectInstallPayload.create(face, sampler, "SM-R390_%05d_256x402.bin".format(face), byteArrayOf(1))
        }
    }

    @Test
    fun rejectsFaceZeroAndSamplersPastTheTenthStyle() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectInstallPayload.create(0, 0, "SM-R390_00000_256x402.bin", byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectInstallPayload.create(256, 0, "SM-R390_00256_256x402.bin", byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectInstallPayload.create(46, WATCH_MAX_STYLES, "SM-R390_00046_256x402.bin", byteArrayOf(1))
        }
    }
}
