package dev.fitface.studio.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

class Crc16Test {
    @Test
    fun standardCheckVector() {
        assertEquals(0x29B1, Crc16.ccittFalse("123456789".encodeToByteArray()))
    }
}
