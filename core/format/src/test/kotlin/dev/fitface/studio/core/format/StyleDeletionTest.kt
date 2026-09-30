package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.survivingStyleNames
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class StyleDeletionTest {
    private fun synthetic(): Fit3Container {
        val entries = linkedMapOf("setting.bin" to ByteArray(256).apply { this[0x34] = 4; this[0x35] = 2 })
        repeat(4) { index -> entries["style$index.bin"] = ByteArray(STYLE_HEADER_SIZE).apply {
            putU32(0, STYLE_MAGIC); this[0x11] = 1; putU32(20, STYLE_HEADER_SIZE)
            this[0x12] = index.toByte()
        } }
        entries["aod.bin"] = entries.getValue("style0.bin").copyOf()
        entries["preview.bin"] = ByteArray(4 * PreviewStream.RECORD_STRIDE).apply { repeat(4) { index ->
            val offset = index * PreviewStream.RECORD_STRIDE
            putU16(offset, PreviewStream.WIDTH); putU16(offset + 2, PreviewStream.HEIGHT)
            putU16(offset + 4, IMAGE_RGB565); putU32(offset + 8, PreviewStream.PAYLOAD_SIZE)
            this[offset + 12] = index.toByte()
        } }
        val start = CONTAINER_HEADER_SIZE + entries.size * DIRECTORY_ENTRY_SIZE
        val bytes = ByteArray(start + entries.values.sumOf { it.size })
        "oppo".encodeToByteArray().copyInto(bytes); bytes.putU32(4, 1)
        bytes.putU32(8, bytes.size - CONTAINER_HEADER_SIZE); bytes.putU32(12, entries.size)
        var cursor = start
        entries.entries.forEachIndexed { i, (name, data) ->
            val dir = CONTAINER_HEADER_SIZE + i * DIRECTORY_ENTRY_SIZE
            "./SM-R390_00000_256x402/$name".encodeToByteArray().copyInto(bytes, dir)
            bytes.putU32(dir + 64, cursor); bytes.putU32(dir + 68, data.size)
            bytes.putU16(dir + 72, Crc16.ccittFalse(data)); data.copyInto(bytes, cursor); cursor += data.size
        }
        bytes.putU16(16, Crc16.ccittFalse(bytes, CONTAINER_HEADER_SIZE, bytes.size))
        return Fit3Container.parse(bytes)
    }
    private fun verify(source: Fit3Container, removed: Set<String>): Fit3Container {
        val styles = FaceResources.selectableStyles(source)
        val mapping = survivingStyleNames(styles.map { it.basename }, removed)
        val edit = StructuralEditor.deleteStyles(source, removed)
        val after = edit.container
        assertEquals(-removed.sumOf { source.entryByBasename(it).size + DIRECTORY_ENTRY_SIZE + PreviewStream.RECORD_STRIDE }, edit.sizeDelta)
        assertTrue(after.validate().isValid)
        assertArrayEquals(after.toByteArray(), Fit3Container.parse(after.toByteArray()).toByteArray())
        mapping.forEach { (old, next) -> assertArrayEquals(source.entryByBasename(old).data, after.entryByBasename(next).data) }
        source.entries.filter { it.basename !in styles.map { s -> s.basename } && it.basename !in setOf("setting.bin", "preview.bin") }
            .forEach { assertArrayEquals(it.data, after.entryByBasename(it.basename).data) }
        val expectedPreview = ByteArrayOutputStream()
        styles.forEachIndexed { index, entry -> if (entry.basename !in removed)
            expectedPreview.write(source.entryByBasename("preview.bin").data, index * PreviewStream.RECORD_STRIDE, PreviewStream.RECORD_STRIDE) }
        assertArrayEquals(expectedPreview.toByteArray(), after.entryByBasename("preview.bin").data)
        val setting = source.entryByBasename("setting.bin").data.copyOf()
        val oldDefault = "style${setting[0x35].toInt() and 255}.bin"
        setting[0x34] = mapping.size.toByte()
        setting[0x35] = mapping.keys.indexOf(oldDefault).coerceAtLeast(0).toByte()
        assertArrayEquals(setting, after.entryByBasename("setting.bin").data)
        return after
    }
    @Test fun arbitraryOrderedSurvivorsAndDefaultsHaveExactSavingsAndUnchangedPayloads() {
        val original = synthetic()
        val removedSets = listOf(setOf("style0.bin"), setOf("style1.bin"), setOf("style3.bin"),
            setOf("style0.bin", "style2.bin"), setOf("style0.bin", "style1.bin", "style3.bin"))
        removedSets.forEach { removed ->
            val after = verify(original, removed)
            val mapping = survivingStyleNames(FaceResources.selectableStyles(original).map { it.basename }, removed) + ("aod.bin" to "aod.bin")
            val identities = SessionLineage.capture(original, original, null).retainVariants(mapping).withResources(original, after)
            identities.validate(original, after, null)
        }
        val first = verify(original, setOf("style0.bin"))
        verify(first, setOf("style1.bin"))
    }
    @Test fun invalidSelectionsAndMalformedPickerAreRefused() {
        val source = synthetic()
        listOf(emptySet(), setOf("aod.bin"), setOf("style4.bin"), (0..3).map { "style$it.bin" }.toSet()).forEach {
            assertThrows(IllegalArgumentException::class.java) { StructuralEditor.deleteStyles(source, it) }
        }
        val broken = StructuralEditor.rebuild(source, mapOf(source.entryByBasename("preview.bin").index to ByteArray(4))).container
        assertThrows(IllegalArgumentException::class.java) { StructuralEditor.deleteStyles(broken, setOf("style0.bin")) }
    }
    @Test fun reorderedForeignMissingOrNonInjectiveSurvivorMapsAreRefused() {
        val source = synthetic(); val after = verify(source, setOf("style0.bin", "style2.bin"))
        val valid = SessionLineage.capture(source, source, null).retainVariants(mapOf("style1.bin" to "style0.bin", "style3.bin" to "style1.bin", "aod.bin" to "aod.bin"))
            .withResources(source, after)
        valid.validate(source, after, null)
        assertThrows(IllegalArgumentException::class.java) { valid.copy(sharedResources = null).validate(source, after, null) }
        listOf(mapOf("style0.bin" to "style3.bin", "style1.bin" to "style1.bin", "aod.bin" to "aod.bin"),
            valid.variants - "aod.bin", valid.variants + ("style0.bin" to "style3.bin"),
            valid.variants + ("style1.bin" to "style5.bin"), valid.variants + ("aod.bin" to "style2.bin")).forEach {
            assertThrows(IllegalArgumentException::class.java) { valid.copy(variants = it).validate(source, after, null) }
        }
    }
    @Test fun everyCorpusDeletionAndKeepOneKeepTwoPreserveAllSurvivors() {
        val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot"))).resolve("SM_R390")
        assumeTrue(Files.isDirectory(root))
        Files.walk(root).use { files -> files.filter { it.toString().endsWith(".bin") }.forEach { path ->
            val source = Fit3Container.parse(Files.readAllBytes(path))
            val names = FaceResources.selectableStyles(source).map { it.basename }
            names.forEach { verify(source, setOf(it)) }
            verify(source, names.drop(1).toSet())
            verify(source, names.filterNot { it == names.first() || it == names.last() }.toSet())
        } }
    }
}
