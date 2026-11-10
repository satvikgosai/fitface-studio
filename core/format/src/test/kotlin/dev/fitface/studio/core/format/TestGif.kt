package dev.fitface.studio.core.format

import java.io.ByteArrayOutputStream

/**
 * A small GIF encoder for tests: real LZW (table growth, code-size steps, a clear when the
 * table fills), local and global colours, graphic controls, interlace and the loop extension.
 * It writes what a GIF encoder writes, so the decoder is tested against the format rather
 * than against itself.
 */
internal object TestGif {
    class Frame(
        val indices: ByteArray,
        val width: Int,
        val height: Int,
        val left: Int = 0,
        val top: Int = 0,
        val delayCs: Int = 10,
        val disposal: Int = 0,
        val transparent: Int? = null,
        val palette: IntArray? = null,
        val interlaced: Boolean = false,
    )

    fun encode(
        width: Int,
        height: Int,
        frames: List<Frame>,
        global: IntArray? = null,
        loop: Int? = null,
        trailer: Boolean = true,
        controls: Boolean = true,
    ): ByteArray = ByteArrayOutputStream().apply {
        write("GIF89a".toByteArray(Charsets.US_ASCII))
        u16(width)
        u16(height)
        write(if (global != null) 0x80 or 0x70 or sizeBits(global.size) else 0)
        write(0)
        write(0)
        global?.let { palette(it) }
        loop?.let {
            write(byteArrayOf(0x21, 0xFF.toByte(), 11))
            write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(3, 1, (it and 0xFF).toByte(), (it ushr 8).toByte(), 0))
        }
        frames.forEach { frame ->
            if (controls) {
                write(byteArrayOf(0x21, 0xF9.toByte(), 4))
                write((frame.disposal shl 2) or (if (frame.transparent != null) 1 else 0))
                u16(frame.delayCs)
                write(frame.transparent ?: 0)
                write(0)
            }
            write(0x2C)
            u16(frame.left)
            u16(frame.top)
            u16(frame.width)
            u16(frame.height)
            val local = frame.palette
            write((if (local != null) 0x80 or sizeBits(local.size) else 0) or (if (frame.interlaced) 0x40 else 0))
            local?.let { palette(it) }
            val colours = (local ?: global)!!.size
            val minimum = maxOf(2, Integer.numberOfTrailingZeros(colours))
            write(minimum)
            val stored = if (frame.interlaced) interlace(frame.indices, frame.width, frame.height) else frame.indices
            subBlocks(lzw(stored, minimum))
        }
        if (trailer) write(0x3B)
    }.toByteArray()

    /** Rows reordered as an interlaced GIF stores them: every 8th from 0, 8th from 4, 4th from 2, 2nd from 1. */
    fun interlace(indices: ByteArray, width: Int, height: Int): ByteArray {
        val order = (0 until height step 8) + (4 until height step 8) + (2 until height step 4) + (1 until height step 2)
        return ByteArray(indices.size).also { out ->
            order.forEachIndexed { stored, row -> indices.copyInto(out, stored * width, row * width, row * width + width) }
        }
    }

    fun lzw(indices: ByteArray, minimum: Int): ByteArray {
        val bits = BitWriter()
        val clear = 1 shl minimum
        val end = clear + 1
        var codeSize = minimum + 1
        var next = clear + 2
        val table = HashMap<Int, Int>()
        bits.write(clear, codeSize)
        var current = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (current shl 8) or k
            val found = table[key]
            if (found != null) {
                current = found
                continue
            }
            bits.write(current, codeSize)
            if (next == 4096) {
                bits.write(clear, codeSize)
                next = clear + 2
                codeSize = minimum + 1
                table.clear()
            } else {
                if (next >= 1 shl codeSize) codeSize++
                table[key] = next++
            }
            current = k
        }
        bits.write(current, codeSize)
        bits.write(end, codeSize)
        return bits.bytes()
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var buffer = 0L
        private var count = 0

        fun write(code: Int, size: Int) {
            buffer = buffer or (code.toLong() shl count)
            count += size
            while (count >= 8) {
                out.write((buffer and 0xFF).toInt())
                buffer = buffer ushr 8
                count -= 8
            }
        }

        fun bytes(): ByteArray {
            if (count > 0) out.write((buffer and 0xFF).toInt())
            return out.toByteArray()
        }
    }

    private fun sizeBits(entries: Int): Int {
        require(entries in 2..256 && entries and (entries - 1) == 0) { "palette size must be a power of two" }
        return Integer.numberOfTrailingZeros(entries) - 1
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and 0xFF)
        write(value ushr 8 and 0xFF)
    }

    private fun ByteArrayOutputStream.palette(colours: IntArray) = colours.forEach {
        write(it ushr 16 and 0xFF)
        write(it ushr 8 and 0xFF)
        write(it and 0xFF)
    }

    private fun ByteArrayOutputStream.subBlocks(data: ByteArray) {
        var at = 0
        while (at < data.size) {
            val length = minOf(255, data.size - at)
            write(length)
            write(data, at, length)
            at += length
        }
        write(0)
    }
}
