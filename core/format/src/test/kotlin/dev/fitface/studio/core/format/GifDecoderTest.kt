package dev.fitface.studio.core.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GifDecoderTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val palette = intArrayOf(red, green, blue, white)

    private fun solid(width: Int, height: Int, index: Int) = ByteArray(width * height) { index.toByte() }

    @Test
    fun framesAreCompositedOntoTheScreenWithTheirDelays() {
        val gif = TestGif.encode(4, 3, listOf(
            TestGif.Frame(solid(4, 3, 0), 4, 3, delayCs = 5),
            TestGif.Frame(solid(4, 3, 2), 4, 3, delayCs = 12),
        ), global = palette, loop = 0)
        val decoded = GifDecoder.decode(gif)
        assertEquals(4, decoded.width)
        assertEquals(3, decoded.height)
        assertEquals(listOf(50, 120), decoded.frames.map { it.delayMs })
        assertArrayEquals(IntArray(12) { red }, decoded.frames[0].argb)
        assertArrayEquals(IntArray(12) { blue }, decoded.frames[1].argb)
        assertEquals(0, decoded.loopCount)
    }

    /** A frame covering part of the screen, in its own colours, with a transparent index. */
    @Test
    fun aPartialFrameDrawsOverWhatIsLeftAndSkipsItsTransparentIndex() {
        val local = intArrayOf(white, green)
        val gif = TestGif.encode(4, 4, listOf(
            TestGif.Frame(solid(4, 4, 0), 4, 4),
            // A 2×2 patch at (1, 1): white, see-through, see-through, green.
            TestGif.Frame(byteArrayOf(0, 1, 1, 1), 2, 2, left = 1, top = 1, transparent = 1, palette = local),
        ), global = palette)
        val second = GifDecoder.decode(gif).frames[1].argb
        val expected = IntArray(16) { red }.also { it[1 * 4 + 1] = white }
        assertArrayEquals(expected, second)
        assertNull(GifDecoder.decode(gif).loopCount)
    }

    @Test
    fun disposalTwoClearsTheRectangleAndThreeRestoresWhatWasUnder() {
        val base = TestGif.Frame(solid(4, 4, 0), 4, 4)
        val patch = { disposal: Int -> TestGif.Frame(solid(2, 2, 2), 2, 2, left = 2, top = 2, disposal = disposal) }
        val dot = TestGif.Frame(byteArrayOf(3), 1, 1)

        val cleared = GifDecoder.decode(TestGif.encode(4, 4, listOf(base, patch(2), dot), global = palette)).frames[2].argb
        val restored = GifDecoder.decode(TestGif.encode(4, 4, listOf(base, patch(3), dot), global = palette)).frames[2].argb
        val kept = GifDecoder.decode(TestGif.encode(4, 4, listOf(base, patch(1), dot), global = palette)).frames[2].argb
        for (y in 0 until 4) for (x in 0 until 4) {
            val at = y * 4 + x
            val inPatch = x >= 2 && y >= 2
            val expected = when {
                at == 0 -> white
                inPatch -> 0
                else -> red
            }
            assertEquals("cleared $x,$y", expected, cleared[at])
            assertEquals("restored $x,$y", if (at == 0) white else red, restored[at])
            assertEquals("kept $x,$y", if (at == 0) white else if (inPatch) blue else red, kept[at])
        }
    }

    @Test
    fun interlacedRowsAreReturnedInOrder() {
        val width = 3
        val height = 11
        val rows = ByteArray(width * height) { (it / width % 4).toByte() }
        val plain = GifDecoder.decode(TestGif.encode(width, height, listOf(TestGif.Frame(rows, width, height)), global = palette))
        val interlaced = GifDecoder.decode(TestGif.encode(width, height,
            listOf(TestGif.Frame(rows, width, height, interlaced = true)), global = palette))
        assertArrayEquals(plain.frames[0].argb, interlaced.frames[0].argb)
        assertEquals(palette[3], interlaced.frames[0].argb[3 * width])
    }

    /** Enough varied data to fill the 4,096-code table, step through every code size and clear. */
    @Test
    fun aLargeFrameDecodesExactlyThroughTableGrowthAndClears() {
        val colours = IntArray(256) { (0xFF shl 24) or (it * 0x010203 and 0xFFFFFF) }
        val width = 300
        val height = 200
        var seed = 12345
        val indices = ByteArray(width * height) {
            seed = seed * 1103515245 + 12345
            // Runs and noise, so both long strings and new ones keep arriving.
            (if (it % 7 < 3) (it / 97) else (seed ushr 16)).toByte()
        }
        val decoded = GifDecoder.decode(TestGif.encode(width, height, listOf(TestGif.Frame(indices, width, height)), global = colours))
        assertArrayEquals(IntArray(indices.size) { colours[indices[it].toInt() and 0xFF] }, decoded.frames[0].argb)
    }

    @Test
    fun aMissingTrailerAfterWholeFramesIsAccepted() {
        val gif = TestGif.encode(2, 2, listOf(TestGif.Frame(solid(2, 2, 1), 2, 2)), global = palette, trailer = false)
        assertEquals(1, GifDecoder.decode(gif).frames.size)
    }

    @Test
    fun aDamagedOrHostileFileIsRefusedWithASentence() {
        fun refusal(bytes: ByteArray) = assertThrows(GifFormatException::class.java) { GifDecoder.decode(bytes) }.message
        val good = TestGif.encode(4, 3, listOf(TestGif.Frame(solid(4, 3, 0), 4, 3)), global = palette)
        assertEquals("This file is not a GIF.", refusal(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0)))
        assertEquals("This GIF is cut short.", refusal(good.copyOf(30)))
        assertEquals("This file is not a GIF.", refusal(good.copyOf(5)))
        assertEquals("This GIF is cut short.", refusal(good.copyOf(9)))
        // A screen one frame of which would pass the limit is refused from its header, before
        // anything is allocated; a wide but short one is not.
        val huge = good.copyOf().also { it[6] = 0xFF.toByte(); it[7] = 0xFF.toByte(); it[8] = 0xFF.toByte(); it[9] = 0x01 }
        assertEquals("This GIF is 65535×511; one frame of it needs more than 64 MB.", refusal(huge))
        val wide = TestGif.encode(5000, 3, listOf(TestGif.Frame(solid(1, 1, 0), 1, 1)), global = palette)
        assertEquals(5000, GifDecoder.decode(wide).width)
        assertEquals("This GIF is damaged.", refusal(good.copyOf().also { it[6] = 0; it[7] = 0 }))
        assertEquals("A frame of this GIF has no colours.", refusal(noColours()))
        assertEquals("This GIF has no frames.", refusal(TestGif.encode(2, 2, emptyList(), global = palette)))
    }

    @Test
    fun anImpossibleCodeIsRefused() {
        // Minimum code size 2: clear is 4, end is 5, and nothing is in the table yet, so 7 is impossible.
        val gif = TestGif.encode(2, 2, listOf(TestGif.Frame(solid(2, 2, 0), 2, 2)), global = palette)
        // Clear (100), then 7 (111), three bits each, least significant first.
        val broken = gif.copyOf().also { it[lzwDataOffset(gif)] = 0b111_100.toByte() }
        assertEquals("A frame of this GIF is damaged.",
            assertThrows(GifFormatException::class.java) { GifDecoder.decode(broken) }.message)
    }

    @Test
    fun anyNumberOfFramesIsReadButNotMoreDecodedImageThanTheLimit() {
        val tiny = TestGif.Frame(byteArrayOf(0), 1, 1)
        // Frames are bounded by what they need in memory together, not by a count.
        val long = TestGif.encode(1, 1, List(1200) { tiny }, global = palette)
        assertEquals(1200, GifDecoder.decode(long).frames.size)
        // Two frames of a 4096 × 4096 screen need 128 MB; each frame is a single pixel in the file.
        val wide = TestGif.encode(4096, 4096, List(2) { tiny }, global = palette)
        assertEquals("This GIF is too large to read: its frames would need more than 64 MB.",
            assertThrows(GifFormatException::class.java) { GifDecoder.decode(wide) }.message)
    }

    /** A frame's own size is bounded before its index buffer is allocated, not only the screen's. */
    @Test
    fun aFrameLargerThanItsScreenIsRefused() {
        fun oversized(frameWidth: Int, frameHeight: Int): ByteArray {
            val gif = TestGif.encode(1, 1, listOf(TestGif.Frame(byteArrayOf(0), 1, 1)), global = palette)
            val descriptor = gif.indexOfFirst { it == 0x2C.toByte() }
            return gif.copyOf().also {
                it[descriptor + 5] = frameWidth.toByte(); it[descriptor + 6] = (frameWidth ushr 8).toByte()
                it[descriptor + 7] = frameHeight.toByte(); it[descriptor + 8] = (frameHeight ushr 8).toByte()
            }
        }
        fun refusal(bytes: ByteArray) = assertThrows(GifFormatException::class.java) { GifDecoder.decode(bytes) }.message
        // 65535 × 65535 overflows an Int; 40000 × 40000 would ask for 1.6 GB.
        assertEquals("A frame of this GIF is 65535×65535, larger than its 1×1 screen.", refusal(oversized(65535, 65535)))
        assertEquals("A frame of this GIF is 40000×40000, larger than its 1×1 screen.", refusal(oversized(40000, 40000)))
        assertEquals("A frame of this GIF is 2×1, larger than its 1×1 screen.", refusal(oversized(2, 1)))
    }

    /**
     * GIFs written by another encoder (ImageMagick): one size-optimised, its later frames small
     * patches over a transparent colour, and one interlaced. Every pixel of every frame matches
     * ImageMagick's own coalesced frames, stored beside them as PNGs.
     */
    @Test
    fun anotherEncodersGifsMatchItsOwnCompositing() {
        mapOf("optimized" to 3, "interlaced" to 2).forEach { (name, count) ->
            val decoded = GifDecoder.decode(resource("$name.gif"))
            assertEquals(name, count, decoded.frames.size)
            decoded.frames.forEachIndexed { index, frame ->
                val reference = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(resource("$name-$index.png")))
                val expected = reference.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width)
                assertArrayEquals("$name frame $index", expected, frame.argb)
            }
        }
        assertEquals(listOf(200, 200, 200), GifDecoder.decode(resource("optimized.gif")).frames.map { it.delayMs })
        assertEquals(0, GifDecoder.decode(resource("optimized.gif")).loopCount)
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/gif/$name")) { "missing test GIF $name" }.use { it.readBytes() }

    /** A GIF whose one frame has no local colours and whose screen has no global ones. Header 6 + screen 7, then 6 palette bytes. */
    private fun noColours(): ByteArray {
        val withGlobal = TestGif.encode(2, 2, listOf(TestGif.Frame(solid(2, 2, 0), 2, 2)), global = intArrayOf(red, green))
        return withGlobal.copyOfRange(0, 10) + byteArrayOf(0) + withGlobal.copyOfRange(11, 13) +
            withGlobal.copyOfRange(19, withGlobal.size)
    }

    /** Where the first image's LZW data starts: after its descriptor, local table and code size, and the first block length. */
    private fun lzwDataOffset(gif: ByteArray): Int {
        var at = gif.indexOfFirst { it == 0x2C.toByte() }
        at += 10 // Descriptor.
        at += 1 // Minimum code size.
        return at + 1 // The first sub-block's length.
    }
}
