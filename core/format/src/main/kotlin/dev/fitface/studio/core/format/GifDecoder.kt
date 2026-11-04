package dev.fitface.studio.core.format

/** One frame of a GIF as a viewer shows it: the whole logical screen, composited, ARGB. */
class GifFrame(val argb: IntArray, val delayMs: Int)

/**
 * A decoded GIF: its logical screen size, every frame composited onto it, and the loop count
 * its `NETSCAPE2.0` extension asks for (0 is forever), or null when it has none.
 */
class GifAnimation(val width: Int, val height: Int, val frames: List<GifFrame>, val loopCount: Int?)

/** A GIF this decoder cannot or will not read; the message says which in plain words. */
class GifFormatException(message: String) : Fit3FormatException(message)

/**
 * A GIF87a/GIF89a decoder in plain Kotlin, for moving backgrounds.
 *
 * Every frame is composited the way a browser shows it: drawn over what the previous frame's
 * disposal left (1 keeps it, 2 clears the frame's rectangle to transparent, 3 restores what
 * was under it), with local or global colours, the graphic control's transparent index, and
 * interlaced rows put back in order. Delays are returned as stored, in milliseconds; the
 * browsers' rule for very short ones belongs to [dev.fitface.studio.core.model.AnimationTiming].
 *
 * It reads untrusted files, so every size is bounded before anything is allocated:
 * [MAX_SIDE] a side, [MAX_FRAMES] frames, [MAX_DECODED_BYTES] for every composited frame
 * together, no frame larger than the screen, and LZW codes of at most 12 bits. A truncated
 * file or an impossible code is refused with a sentence, never a crash or a frame of garbage.
 */
object GifDecoder {
    const val MAX_SIDE = 4096
    const val MAX_FRAMES = 512
    const val MAX_DECODED_BYTES = 64L * 1024 * 1024

    private const val MAX_CODE_BITS = 12
    private const val DICTIONARY = 1 shl MAX_CODE_BITS

    fun decode(bytes: ByteArray): GifAnimation {
        val signature = String(bytes.copyOfRange(0, minOf(6, bytes.size)), Charsets.US_ASCII)
        if (signature != "GIF87a" && signature != "GIF89a") throw GifFormatException("This file is not a GIF.")
        val input = Reader(bytes)
        input.skip(6)
        val width = input.u16()
        val height = input.u16()
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) {
            throw GifFormatException("This GIF is ${width}×$height; at most ${MAX_SIDE}×$MAX_SIDE can be read.")
        }
        val screenFlags = input.u8()
        input.u8() // Background colour index: browsers composite over transparency, not it.
        input.u8() // Pixel aspect ratio.
        val global = if (screenFlags and 0x80 != 0) input.palette(2 shl (screenFlags and 0x07)) else null

        val pixels = width * height
        val canvas = IntArray(pixels)
        val frames = ArrayList<GifFrame>()
        var decodedBytes = 0L
        var loopCount: Int? = null
        var disposal = 0
        var transparent = -1
        var delayMs = 0
        while (true) {
            // A missing trailer after whole frames is common, and every viewer shows the file.
            if (input.atEnd && frames.isNotEmpty()) break
            when (input.u8()) {
                0x21 -> when (input.u8()) {
                    0xF9 -> {
                        val size = input.u8()
                        if (size < 4) throw GifFormatException("This GIF's frame timing is damaged.")
                        val flags = input.u8()
                        delayMs = input.u16() * 10
                        val index = input.u8()
                        input.skip(size - 4)
                        input.skipSubBlocks()
                        disposal = flags ushr 2 and 0x07
                        transparent = if (flags and 0x01 != 0) index else -1
                    }
                    0xFF -> {
                        val size = input.u8()
                        val identifier = String(input.bytes(size), Charsets.US_ASCII)
                        if (identifier == "NETSCAPE2.0" || identifier == "ANIMEXTS1.0") {
                            while (true) {
                                val length = input.u8()
                                if (length == 0) break
                                val data = input.bytes(length)
                                if (length >= 3 && data[0].toInt() == 1) {
                                    loopCount = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                                }
                            }
                        } else {
                            input.skipSubBlocks()
                        }
                    }
                    else -> input.skipSubBlocks()
                }
                0x2C -> {
                    if (frames.size == MAX_FRAMES) {
                        throw GifFormatException("This GIF has more than $MAX_FRAMES frames.")
                    }
                    decodedBytes += pixels * 4L
                    if (decodedBytes > MAX_DECODED_BYTES) {
                        throw GifFormatException("This GIF is too large to read: its frames would need more than " +
                            "${MAX_DECODED_BYTES / (1024 * 1024)} MB.")
                    }
                    val left = input.u16()
                    val top = input.u16()
                    val frameWidth = input.u16()
                    val frameHeight = input.u16()
                    // Its index buffer is allocated at this size, so it is bounded as the screen is.
                    if (frameWidth > MAX_SIDE || frameHeight > MAX_SIDE || frameWidth.toLong() * frameHeight > pixels) {
                        throw GifFormatException("A frame of this GIF is ${frameWidth}×$frameHeight, larger than its ${width}×$height screen.")
                    }
                    val flags = input.u8()
                    val local = if (flags and 0x80 != 0) input.palette(2 shl (flags and 0x07)) else null
                    val colours = local ?: global ?: throw GifFormatException("A frame of this GIF has no colours.")
                    val minimumCodeSize = input.u8()
                    if (minimumCodeSize !in 1..8) throw GifFormatException("A frame of this GIF is damaged.")
                    val indices = lzw(input.subBlocks(), minimumCodeSize, frameWidth * frameHeight)
                    val restore = if (disposal == 3) canvas.copyOf() else null
                    draw(canvas, width, height, indices, left, top, frameWidth, frameHeight,
                        interlaced = flags and 0x40 != 0, colours, transparent)
                    frames += GifFrame(canvas.copyOf(), delayMs)
                    when (disposal) {
                        2 -> for (y in maxOf(top, 0) until minOf(top + frameHeight, height)) {
                            for (x in maxOf(left, 0) until minOf(left + frameWidth, width)) canvas[y * width + x] = 0
                        }
                        3 -> restore!!.copyInto(canvas)
                    }
                    // A graphic control applies to the one image after it.
                    disposal = 0
                    transparent = -1
                    delayMs = 0
                }
                0x3B -> break
                else -> throw GifFormatException(
                    if (frames.isEmpty()) "This GIF is damaged." else "This GIF is damaged after frame ${frames.size}.",
                )
            }
        }
        if (frames.isEmpty()) throw GifFormatException("This GIF has no frames.")
        return GifAnimation(width, height, frames, loopCount)
    }

    /**
     * Draws [indices] onto [canvas] at [left], [top], clipped to the screen. Interlaced rows
     * arrive as every 8th row from 0, every 8th from 4, every 4th from 2, then every 2nd from 1.
     * Pixels the data ran short of, and the transparent index, leave the canvas as it was.
     */
    private fun draw(
        canvas: IntArray, width: Int, height: Int, indices: Indices,
        left: Int, top: Int, frameWidth: Int, frameHeight: Int,
        interlaced: Boolean, colours: IntArray, transparent: Int,
    ) {
        val rows = if (!interlaced) (0 until frameHeight).toList() else
            (0 until frameHeight step 8) + (4 until frameHeight step 8) + (2 until frameHeight step 4) + (1 until frameHeight step 2)
        rows.forEachIndexed { stored, row ->
            val y = top + row
            if (y !in 0 until height) return@forEachIndexed
            for (column in 0 until frameWidth) {
                val position = stored * frameWidth + column
                if (position >= indices.count) return
                val x = left + column
                if (x !in 0 until width) continue
                val index = indices.data[position].toInt() and 0xFF
                if (index == transparent || index >= colours.size) continue
                canvas[y * width + x] = colours[index]
            }
        }
    }

    private class Indices(val data: ByteArray, val count: Int)

    /**
     * The variable-width LZW of a GIF image: codes read least-significant bit first, a clear
     * code resets the table, the end code stops it. Output past the frame's pixel count is
     * dropped; output short of it is left undrawn, as viewers do.
     */
    private fun lzw(data: ByteArray, minimumCodeSize: Int, pixelCount: Int): Indices {
        val clear = 1 shl minimumCodeSize
        val end = clear + 1
        val prefix = IntArray(DICTIONARY)
        val suffix = ByteArray(DICTIONARY)
        val first = ByteArray(DICTIONARY)
        val length = IntArray(DICTIONARY)
        for (code in 0 until clear) {
            prefix[code] = -1
            suffix[code] = code.toByte()
            first[code] = code.toByte()
            length[code] = 1
        }
        val output = ByteArray(pixelCount)
        var written = 0
        var codeSize = minimumCodeSize + 1
        var next = clear + 2
        var previous = -1
        var bits = 0L
        var bitCount = 0
        var position = 0
        while (written < pixelCount) {
            while (bitCount < codeSize && position < data.size) {
                bits = bits or ((data[position++].toLong() and 0xFF) shl bitCount)
                bitCount += 8
            }
            if (bitCount < codeSize) break
            val code = (bits and ((1L shl codeSize) - 1)).toInt()
            bits = bits ushr codeSize
            bitCount -= codeSize
            if (code == clear) {
                codeSize = minimumCodeSize + 1
                next = clear + 2
                previous = -1
                continue
            }
            if (code == end) break
            val entry = when {
                code < clear || (code in clear + 2 until next) -> code
                code == next && previous >= 0 -> -1 // The one code not in the table yet: previous + its own first.
                else -> throw GifFormatException("A frame of this GIF is damaged.")
            }
            if (previous >= 0 && next < DICTIONARY) {
                prefix[next] = previous
                suffix[next] = if (entry >= 0) first[entry] else first[previous]
                first[next] = first[previous]
                length[next] = length[previous] + 1
                next++
                if (next == 1 shl codeSize && codeSize < MAX_CODE_BITS) codeSize++
            }
            val emitted = if (entry >= 0) entry else next - 1
            // Strings are written back to front from the prefix chain, clipped to the frame.
            val size = length[emitted]
            var cursor = emitted
            for (offset in size - 1 downTo 0) {
                val at = written + offset
                if (at < pixelCount) output[at] = suffix[cursor]
                cursor = prefix[cursor]
            }
            written = minOf(pixelCount, written + size)
            previous = code
        }
        return Indices(output, written)
    }

    /** Bounded reads over the whole file; running out is a truncated GIF, not an exception leak. */
    private class Reader(private val data: ByteArray) {
        private var position = 0

        val atEnd: Boolean get() = position >= data.size

        fun u8(): Int {
            if (position >= data.size) throw GifFormatException("This GIF is cut short.")
            return data[position++].toInt() and 0xFF
        }

        fun u16(): Int = u8() or (u8() shl 8)

        fun bytes(count: Int): ByteArray {
            if (count < 0 || count > data.size - position) throw GifFormatException("This GIF is cut short.")
            return data.copyOfRange(position, position + count).also { position += count }
        }

        fun skip(count: Int) {
            bytes(count)
        }

        /** An RGB colour table of [entries] colours, as opaque ARGB. */
        fun palette(entries: Int): IntArray {
            val raw = bytes(entries * 3)
            return IntArray(entries) { index ->
                (0xFF shl 24) or ((raw[index * 3].toInt() and 0xFF) shl 16) or
                    ((raw[index * 3 + 1].toInt() and 0xFF) shl 8) or (raw[index * 3 + 2].toInt() and 0xFF)
            }
        }

        fun skipSubBlocks() {
            while (true) {
                val length = u8()
                if (length == 0) return
                skip(length)
            }
        }

        /** Every data sub-block of one image, joined; never longer than the file itself. */
        fun subBlocks(): ByteArray {
            val start = position
            var total = 0
            while (true) {
                val length = u8()
                if (length == 0) break
                skip(length)
                total += length
            }
            val joined = ByteArray(total)
            var cursor = start
            var at = 0
            while (true) {
                val length = data[cursor].toInt() and 0xFF
                cursor++
                if (length == 0) break
                data.copyInto(joined, at, cursor, cursor + length)
                at += length
                cursor += length
            }
            return joined
        }
    }
}
