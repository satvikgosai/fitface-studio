package dev.fitface.studio.core.format

object Crc16 {
    private val table = IntArray(256) { value ->
        var crc = value shl 8
        repeat(8) {
            crc = if (crc and 0x8000 != 0) {
                ((crc shl 1) xor 0x1021) and 0xFFFF
            } else {
                (crc shl 1) and 0xFFFF
            }
        }
        crc
    }

    fun ccittFalse(data: ByteArray, start: Int = 0, endExclusive: Int = data.size): Int {
        require(start in 0..data.size)
        require(endExclusive in start..data.size)
        var crc = 0xFFFF
        for (index in start until endExclusive) {
            val tableIndex = ((crc ushr 8) xor (data[index].toInt() and 0xFF)) and 0xFF
            crc = ((crc shl 8) xor table[tableIndex]) and 0xFFFF
        }
        return crc
    }
}
