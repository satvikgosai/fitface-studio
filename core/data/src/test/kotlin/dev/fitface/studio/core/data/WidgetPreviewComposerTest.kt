package dev.fitface.studio.core.data

import dev.fitface.studio.core.format.*
import dev.fitface.studio.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic records pin the resource path, including types absent from the catalogue. */
class WidgetPreviewComposerTest {
    @Test fun everyDrawableConstructorProducesItsOwnLayerWithoutAPreview() {
        val types = listOf(1, 2, 3, 4, 5, 6, 7, 13, 16, 17)
        for (type in types) {
            val scene = scene(record(type), texture())
            val rendered = WidgetPreviewComposer.compose(scene, fonts(), textRasterizer = { _, w, h ->
                PreviewFrame(w, h, IntArray(w * h) { 0x80FF0000.toInt() })
            })
            assertEquals("type $type", 1, rendered.widgetImageLayers.size)
            assertTrue("type $type drew nothing", rendered.widgetImageLayers.single().frame.argb
                .any { it ushr 24 != 0 })
        }
    }

    @Test fun reservedConstructorsAreInertAndFirmwareOnlyGroupIsDisclosed() {
        for (type in listOf(8, 10, 11, 12, 14, 15)) {
            val result = WidgetPreviewComposer.compose(scene(record(type)))
            assertTrue(result.widgetImageLayers.isEmpty())
            assertFalse(result.isApproximate)
        }
        val group = WidgetPreviewComposer.compose(scene(record(9)))
        assertTrue(group.widgetImageLayers.isEmpty())
        assertTrue(group.isApproximate)
    }

    @Test fun layersKeepAlphaAndRevealLowerAndUpperArtworkInOrder() {
        val red = record(1).apply { word(0x20, 0) }
        val blue = record(1).apply { word(0x20, texture().size) }
        val entry = scene(red + blue, texture() + texture(blue = true), count = 2)
        val render = WidgetPreviewComposer.compose(entry)
        val guides = FaceRecordParser.widgetGuides(entry)
        val lower = render.widgetImageLayers.take(1)
        val upper = render.widgetImageLayers.drop(1)
        val base = WidgetLayerComposer.compose(32, 32, lower, guides)
        val foreground = WidgetLayerComposer.compose(32, 32, upper, guides, transparent = true)
        assertEquals(128, foreground.argb[4 * 32 + 4] ushr 24)
        val rebuilt = base.argb.indices.map { WidgetLayerComposer.over(base.argb[it], foreground.argb[it]) }
        assertArrayEquals(render.composed.argb, rebuilt.toIntArray())
        assertNotEquals("removing upper must expose lower", render.composed.argb[132], base.argb[132])
        val moved = guides.map { if (it.globalIndex == 1) it.copy(x = 20) else it }
        val after = WidgetLayerComposer.compose(32, 32, render.widgetImageLayers, moved)
        assertEquals("old position must reveal lower, not black", base.argb[132], after.argb[132])
    }

    @Test fun textUsesBindingsNumericSelectorsDictionaryFragmentsAndOrder() {
        val pair = scene(record(5).apply { word(4, 18); half(0x2A, 4) })
        val resources = WidgetTextResources(fonts())
        val value = requireNotNull(resources.text(pair, FaceRecordParser.scanWidgets(pair).single()))
        assertEquals("0028", value.text)
        assertEquals(20, value.font.pixelSize)
        assertEquals(0xFFFF0000.toInt(), value.color)

        val comp = scene(record(13).apply {
            half(0x24, 18); half(0x26, 0); half(0x28, 0xFFFF)
            half(0x2A, 0); half(0x2C, 0x0201)
            half(0x30, 0); half(0x32, 1); half(0x34, 0xFFFF); half(0x36, 0xFFFF)
            half(0x62, 2); half(0x5C, 900); half(0x60, 0xFE)
        })
        val text = requireNotNull(resources.text(comp, FaceRecordParser.scanWidgets(comp).single()))
        assertEquals("BA28", text.text)
        assertEquals(-2, text.letterSpacing)
        assertEquals(90.0, text.rotationDegrees, 0.0)
        val bad = comp.copy(data = comp.data.copyOf().apply { half(24 + 0x62, 50) })
        assertNull(resources.text(bad, FaceRecordParser.scanWidgets(bad).single()))
    }

    @Test fun resizedVectorArcKeepsItsStoredStrokeThickness() {
        val small = WidgetPreviewComposer.compose(scene(record(6))).widgetImageLayers.single().frame
        val large = WidgetPreviewComposer.compose(scene(record(6).apply {
            half(0x1C, 24); half(0x1E, 24)
        })).widgetImageLayers.single().frame
        // At the right midpoint the ring is exactly the stored 4 px in both sizes.
        fun rightStroke(frame: PreviewFrame) = (frame.width / 2 until frame.width)
            .count { frame.argb[(frame.height / 2) * frame.width + it] ushr 24 > 127 }
        assertEquals(4, rightStroke(small))
        assertEquals(4, rightStroke(large))
    }

    @Test fun aRotatedHandLayerPreservesItsPivotAndTransparentPadding() {
        val strip = PreviewFrame(3, 1, intArrayOf(-65536, -16711936, -16776961))
        val rotated = WidgetPreviewComposer.rotate(strip, 1, 0, 90.0)
        fun pixel(x: Int, y: Int) = rotated.frame.argb[
            (y - rotated.offsetY) * rotated.frame.width + x - rotated.offsetX]
        assertEquals(-16711936, pixel(1, 0))
        assertEquals(-65536, pixel(1, -1))
        assertEquals(-16776961, pixel(1, 1))
    }

    companion object {
        fun ByteArray.half(offset: Int, value: Int) {
            this[offset] = value.toByte(); this[offset + 1] = (value ushr 8).toByte()
        }
        fun ByteArray.word(offset: Int, value: Int) {
            half(offset, value); half(offset + 2, value ushr 16)
        }
        fun entry(name: String, bytes: ByteArray) = ContainerEntry(0,
            "./SM-R390_99999_32x32/$name", 0, bytes.size, 0, byteArrayOf(), bytes)
        fun record(type: Int): ByteArray {
            val size = when (type) { 1, 3 -> 40; 2, 4 -> 44; 5 -> 56; 6 -> 76
                7 -> 52; 9 -> 28; 13 -> 100; 16 -> 60; 17 -> 50; else -> 16 }
            return ByteArray(size).apply {
                word(0, type); word(4, 37); half(0x0C, size)
                if (size < 28) return@apply
                half(0x18, 4); half(0x1A, 4)
                if (size >= 32) { half(0x1C, 16); half(0x1E, 16) }
                when (type) {
                    1 -> { half(0x1C, 0xFFFF); word(0x20, 0) }
                    2 -> { half(0x1C, 0xFFFF); half(0x20, 2); half(0x22, 2); half(0x26, 360) }
                    3 -> half(0x20, 1)
                    4 -> { half(0x24, 0x0101); half(0x26, 100) }
                    5 -> { half(0x20, 0xFFFF); word(0x24, 0x00FF0000); half(0x2C, 0xFFFF) }
                    6 -> { half(0x2A, 360); word(0x34, 0x00FF0000); half(0x3C, 0xFFFF)
                        half(0x40, 4) }
                    7 -> { word(0x28, 0x00FF0000); half(0x30, 4); half(0x32, 1) }
                    13 -> {
                        half(0x20, 0xFFFF)
                        for (b in listOf(0x24, 0x30, 0x3C, 0x48)) {
                            half(b, 0xFFFF); half(b + 2, 0xFFFF); half(b + 4, 0xFFFF); half(b + 6, 0xFFFF)
                        }
                        half(0x24, 18); half(0x26, 0xFFFF); half(0x28, 0xFFFF)
                        half(0x2A, 0); half(0x2C, 1); word(0x58, 0x00FF0000)
                    }
                    16 -> { half(0x24, 4); half(0x2A, 360); word(0x38, 0xFFFF) }
                    17 -> { half(0x26, 100); half(0x30, 0x0108) }
                }
            }
        }
        fun texture(blue: Boolean = false): ByteArray = ByteArray(12 + 16 * 16 * 3 + 4).apply {
            half(0, 16); half(2, 16); half(4, 0x80); word(8, size - 12)
            for (i in 0 until 256) { half(12 + i * 3, if (blue) 0x001F else 0xF800)
                this[14 + i * 3] = if (blue) 128.toByte() else 255.toByte() }
        }
        fun scene(records: ByteArray, image: ByteArray = byteArrayOf(), count: Int = 1): ContainerEntry {
            val header = ByteArray(24).apply { word(0, 0x12345678); word(4, count)
                word(8, records.size); word(12, image.size); word(20, 24 + records.size) }
            val data = header + records + image
            var p = 24
            repeat(count) { index -> data.half(p + 0x0E, index)
                p += (data[p + 0x0C].toInt() and 255) or ((data[p + 0x0D].toInt() and 255) shl 8) }
            return entry("style0.bin", data)
        }
        fun fonts(): List<ContainerEntry> {
            val binding = ByteArray(92).apply { this[1] = 2; word(0x58, 20) }
            val strings = listOf("A", "B", "2134").map { it.toByteArray() }
            val dict = ByteArray(24 + strings.size * 8 + strings.sumOf { it.size })
            dict.word(0, 0x12345678); dict.word(8, strings.size)
            var p = 24 + strings.size * 8
            strings.forEachIndexed { i, s -> dict.word(24 + i * 8, s.size)
                dict.word(28 + i * 8, p); s.copyInto(dict, p); p += s.size }
            return listOf(entry("font_0.bin", binding), entry("font_en.bin", dict))
        }
    }
}
