package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.PreviewFrame
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BackgroundImporterTest {
    private fun face(id: String): Fit3Container {
        val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("no corpus face $id", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    @Test fun donorTransparencyIsFlattenedOnBlackWithoutChangingItsPixels() {
        val colors = intArrayOf(0x00FFFFFF, 0x80FF8040.toInt(), 0xFFFFFFFF.toInt())
        assertArrayEquals(intArrayOf(0xFF000000.toInt(), 0xFF804020.toInt(), 0xFFFFFFFF.toInt()),
            BackgroundImporter.flattenOnBlack(PreviewFrame(3, 1, colors)))
        assertEquals(0x00FFFFFF, colors[0])
    }

    @Test fun extractionUsesOnlyThePrimaryPanelRasterAndReportsMissingBackgrounds() {
        val multi = face("00076").entryByBasename("style0.bin")
        val source = BackgroundImporter.read(multi)
        assertEquals(2, source.fullPanelImageCount)
        val primary = FaceRecordParser.backgroundImage(multi)!!
        assertArrayEquals(FaceRecordParser.decodeImage(multi, primary).argb, source.background!!.argb)
        assertNull(BackgroundImporter.read(face("00008").entryByBasename("style0.bin")).background)
        assertThrows(Fit3FormatException::class.java) {
            BackgroundImporter.prepare(face("00112"), "style0.bin", face("00008").entryByBasename("style0.bin"))
        }
    }

    @Test fun replacementKeepsWidgetRecordsOtherRastersMasksAndAodIntact() {
        val donor = face("00076").entryByBasename("style0.bin")
        for (id in listOf("00003", "00002", "00089", "00108", "00112")) {
            val original = face(id)
            val result = BackgroundImporter.prepare(original, "style0.bin", donor)
            assertFalse(result.addedBackground)
            assertEquals(0, result.edit.sizeDelta)
            assertEquals(original.fileSize, result.edit.container.fileSize)
            assertTrue(result.edit.container.validate().isValid)
            original.entries.forEach { entry ->
                val after = result.edit.container.entryByBasename(entry.basename)
                if (entry.basename !in result.edit.changedStyles) {
                    assertArrayEquals("$id/${entry.basename}", entry.data, after.data)
                } else {
                    val images = FaceRecordParser.scanImages(entry)
                    val changedImages = FaceRecordParser.scanImages(after)
                    assertEquals(images.size, changedImages.size)
                    val primary = FaceRecordParser.backgroundImage(entry)!!
                    val header = StyleHeader.parse(entry)
                    assertArrayEquals(entry.data.copyOfRange(0, header.storedImageOffset),
                        after.data.copyOfRange(0, header.storedImageOffset))
                    images.zip(changedImages).forEach { (oldImage, newImage) ->
                        val oldPixels = FaceRecordParser.decodeImage(entry, oldImage).argb
                        val newPixels = FaceRecordParser.decodeImage(after, newImage).argb
                        if (oldImage.index != primary.index) assertArrayEquals(oldPixels, newPixels)
                        else if (!oldImage.isIndexed) {
                            assertArrayEquals(oldPixels.map { it ushr 24 }.toIntArray(),
                                newPixels.map { it ushr 24 }.toIntArray())
                        } else assertTrue(newPixels.all { it ushr 24 == 255 })
                    }
                }
            }
        }
    }

    @Test fun extractionAlsoFindsBackgroundsAfterSmallerImages() {
        val bare = face("00008")
        val pixels = IntArray(256 * 402) { 0xFF1880D0.toInt() }
        val entry = StructuralEditor.addBackgrounds(bare, listOf("style0.bin"), 256, 402, pixels)
            .container.entryByBasename("style0.bin")
        val primary = FaceRecordParser.backgroundImage(entry)!!
        assertTrue(primary.index > 0)
        assertArrayEquals(FaceRecordParser.decodeImage(entry, primary).argb,
            BackgroundImporter.read(entry).background!!.argb)
    }

    @Test fun backgroundAdditionIsCapacityBoundedSelectedFirstAndAodIsIsolated() {
        val donor = face("00112").entryByBasename("style0.bin")
        val bare = face("00008")
        val added = BackgroundImporter.prepare(bare, "style2.bin", donor)
        assertTrue(added.addedBackground)
        assertTrue(added.edit.sizeDelta > 0)
        assertTrue(added.edit.container.validate().isValid)
        assertArrayEquals(bare.entryByBasename("aod.bin").data,
            added.edit.container.entryByBasename("aod.bin").data)
        val aod = BackgroundImporter.prepare(bare, "aod.bin", donor)
        assertEquals(listOf("aod.bin"), aod.edit.changedStyles)
        bare.entries.filter { it.basename != "aod.bin" }.forEach {
            assertArrayEquals(it.data, aod.edit.container.entryByBasename(it.basename).data)
        }
        val partial = BackgroundImporter.prepare(face("00021"), "style2.bin", donor)
        assertEquals(listOf("style2.bin"), partial.edit.changedStyles)
        assertEquals(listOf("style0.bin", "style1.bin"), partial.skippedVariants)
        assertThrows(Fit3FormatException::class.java) {
            BackgroundImporter.prepare(face("00022"), "style0.bin", donor)
        }
    }
}
