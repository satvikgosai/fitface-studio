package dev.fitface.studio.core.format

internal fun ByteArray.u16(offset: Int): Int {
    requireRange(offset, 2)
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)
}

internal fun ByteArray.i16(offset: Int): Int {
    val value = u16(offset)
    return if (value and 0x8000 == 0) value else value - 0x10000
}

internal fun ByteArray.u32(offset: Int): Long {
    requireRange(offset, 4)
    return (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)
}

internal fun ByteArray.putU16(offset: Int, value: Int) {
    require(value in 0..0xFFFF)
    requireRange(offset, 2)
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

internal fun ByteArray.putU32(offset: Int, value: Int) {
    require(value >= 0)
    requireRange(offset, 4)
    repeat(4) { byte ->
        this[offset + byte] = (value ushr (byte * 8)).toByte()
    }
}

internal fun ByteArray.putU32(offset: Int, value: Long) {
    require(value in 0..0xFFFF_FFFFL)
    requireRange(offset, 4)
    repeat(4) { byte ->
        this[offset + byte] = (value ushr (byte * 8)).toByte()
    }
}

internal fun ByteArray.requireRange(offset: Int, size: Int) {
    if (offset < 0 || size < 0 || offset > this.size - size) {
        throw Fit3FormatException(
            "range $offset..${offset + size} exceeds ${this.size} bytes",
        )
    }
}

internal fun Long.checkedInt(label: String): Int {
    if (this !in 0..Int.MAX_VALUE.toLong()) {
        throw Fit3FormatException("$label does not fit an Android byte-array index: $this")
    }
    return toInt()
}
