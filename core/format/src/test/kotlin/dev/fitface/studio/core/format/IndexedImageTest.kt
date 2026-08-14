package dev.fitface.studio.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Face `00002` ("Layered circles") is the only face in the live 100-face catalogue
 * whose background is stored as an 8-bit indexed raster (format `0x0088`). Before
 * this format was understood the face could be downloaded but never opened: the
 * image scan threw on the very first record.
 */
class IndexedImageTest {
    private val width = 6
    private val height = 4

    @Test
    fun indexedRasterDecodesThroughItsBgraPalette() {
        val entry = styleEntryWithIndexedBackground()
        val images = FaceRecordParser.scanImages(entry)

        assertEquals(1, images.size)
        val image = images.single()
        assertEquals(IMAGE_INDEXED8, image.format)
        assertTrue(image.isIndexed)
        assertEquals(INDEXED_PALETTE_BYTES, image.paletteSize)
        assertEquals(4, image.opaqueTrailerSize)

        val frame = FaceRecordParser.decodeImage(entry, image)
        assertEquals(width, frame.width)
        assertEquals(height, frame.height)
        // Index 1 was written as blue=0x10 green=0x20 red=0x30 alpha=0xFF.
        assertEquals(0xFF302010.toInt(), frame.argb[0])
        assertEquals(0xFF000000.toInt(), frame.argb[1])
    }

    @Test
    fun quantizerEmitsAFixedLengthPaletteFollowedByOneBytePerPixel() {
        val pixels = IntArray(width * height) { index ->
            0xFF000000.toInt() or (index * 0x0A0A0A)
        }

        val encoded = IndexedImage.quantize(pixels)

        assertEquals(INDEXED_PALETTE_BYTES + pixels.size, encoded.size)
        // Every palette entry is opaque, so a re-encoded background never becomes
        // accidentally transparent on the watch.
        repeat(INDEXED_PALETTE_ENTRIES) { entry ->
            assertEquals(0xFF.toByte(), encoded[entry * 4 + 3])
        }
    }

    @Test
    fun quantizerPreservesDistinctColoursUpToThePaletteLimit() {
        val pixels = IntArray(200) { index -> 0xFF000000.toInt() or (index * 0x010203) }

        val encoded = IndexedImage.quantize(pixels)
        val palette = (0 until INDEXED_PALETTE_ENTRIES).map { entry ->
            val base = entry * 4
            ((encoded[base + 2].toInt() and 0xFF) shl 16) or
                ((encoded[base + 1].toInt() and 0xFF) shl 8) or
                (encoded[base].toInt() and 0xFF)
        }

        pixels.forEachIndexed { index, color ->
            val paletteIndex = encoded[INDEXED_PALETTE_BYTES + index].toInt() and 0xFF
            assertEquals(color and 0x00FF_FFFF, palette[paletteIndex])
        }
    }

    @Test
    fun replacingAnIndexedBackgroundKeepsTheContainerLengthIdentical() {
        val container = containerWithIndexedBackground()
        val replacement = IntArray(width * height) { 0xFF1188CC.toInt() }

        val edit = FaceEditor.replaceBackgrounds(container, width, height, replacement)

        assertEquals(container.fileSize, edit.container.fileSize)
        assertTrue(edit.container.validate().isValid)
        val style = edit.container.entryByBasename("style0.bin")
        val frame = FaceRecordParser.decodeImage(style, FaceRecordParser.scanImages(style).single())
        assertTrue(frame.argb.all { it == 0xFF1188CC.toInt() })
        assertNotEquals(0, edit.changedPayloadBytes)
    }

    private fun indexedImageRecord(): ByteArray {
        val payload = ByteArray(IMAGE_HEADER_SIZE + INDEXED_PALETTE_BYTES + width * height + 4)
        payload.putU16(0, width)
        payload.putU16(2, height)
        payload.putU16(4, IMAGE_INDEXED8)
        payload.putU16(6, 0)
        payload.putU32(8, INDEXED_PALETTE_BYTES + width * height + 4)
        // Palette entry 1 = blue 0x10, green 0x20, red 0x30, alpha 0xFF.
        val palette = IMAGE_HEADER_SIZE
        payload[palette + 4] = 0x10
        payload[palette + 5] = 0x20
        payload[palette + 6] = 0x30
        payload[palette + 7] = 0xFF.toByte()
        payload[palette + 3] = 0xFF.toByte()
        payload[IMAGE_HEADER_SIZE + INDEXED_PALETTE_BYTES] = 1
        return payload
    }

    private fun styleEntryWithIndexedBackground(): ContainerEntry {
        val image = indexedImageRecord()
        val data = ByteArray(STYLE_HEADER_SIZE + image.size)
        data.putU32(0, STYLE_MAGIC)
        data.putU32(4, 0)
        data.putU32(8, 0)
        data.putU32(12, image.size)
        data.putU32(20, STYLE_HEADER_SIZE)
        image.copyInto(data, STYLE_HEADER_SIZE)
        return ContainerEntry(
            index = 0,
            path = "style0.bin",
            offset = 0,
            size = data.size,
            checksum = 0,
            rawRecord = ByteArray(DIRECTORY_ENTRY_SIZE),
            data = data,
        )
    }

    private fun containerWithIndexedBackground(): Fit3Container {
        val style = styleEntryWithIndexedBackground().data
        val bodyOffset = CONTAINER_HEADER_SIZE + DIRECTORY_ENTRY_SIZE
        val output = ByteArray(bodyOffset + style.size)
        "oppo".toByteArray(Charsets.US_ASCII).copyInto(output)
        output.putU32(4, 1L)
        output.putU32(8, output.size - CONTAINER_HEADER_SIZE)
        output.putU32(12, 1)
        val record = CONTAINER_HEADER_SIZE
        "style0.bin".toByteArray(Charsets.UTF_8).copyInto(output, record)
        output.putU32(record + DIRECTORY_PATH_SIZE, bodyOffset)
        output.putU32(record + DIRECTORY_PATH_SIZE + 4, style.size)
        style.copyInto(output, bodyOffset)
        output.putU16(record + DIRECTORY_PATH_SIZE + 8, Crc16.ccittFalse(style))
        output.putU16(16, Crc16.ccittFalse(output, CONTAINER_HEADER_SIZE, output.size))
        return Fit3Container.parse(output)
    }
}
