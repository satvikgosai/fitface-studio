package dev.fitface.studio.core.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerIdentityTest {
    @Test
    fun parsesValidSyntheticContainerWithoutChangingBytes() {
        val path = "setting.bin".encodeToByteArray()
        val bodyOffset = CONTAINER_HEADER_SIZE + DIRECTORY_ENTRY_SIZE
        val payload = byteArrayOf(1, 2, 3, 4)
        val data = ByteArray(bodyOffset + payload.size)
        "oppo".encodeToByteArray().copyInto(data)
        putU32(data, 4, 1)
        putU32(data, 8, data.size - CONTAINER_HEADER_SIZE)
        putU32(data, 12, 1)
        path.copyInto(data, CONTAINER_HEADER_SIZE)
        putU32(data, CONTAINER_HEADER_SIZE + 64, bodyOffset)
        putU32(data, CONTAINER_HEADER_SIZE + 68, payload.size)
        payload.copyInto(data, bodyOffset)
        data.putU16(CONTAINER_HEADER_SIZE + 72, Crc16.ccittFalse(payload))
        data.putU16(16, Crc16.ccittFalse(data, CONTAINER_HEADER_SIZE, data.size))

        val parsed = Fit3Container.parse(data)

        assertTrue(parsed.validate().isValid)
        assertArrayEquals(data, parsed.toByteArray())
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { byte -> target[offset + byte] = (value ushr (byte * 8)).toByte() }
    }
}
