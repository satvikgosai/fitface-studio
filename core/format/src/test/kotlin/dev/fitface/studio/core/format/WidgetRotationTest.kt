package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.normalizedRotation
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WidgetRotationTest {
    private val style = "style0.bin"
    private fun synthetic(width: Int = 80, height: Int = 30, angle: Int = 0): Fit3Container {
        val payload = ByteArray(STYLE_HEADER_SIZE + 100 + IMAGE_HEADER_SIZE + 2).apply {
            putU32(0, STYLE_MAGIC); putU32(4, 1); putU32(8, 100); putU32(12, (IMAGE_HEADER_SIZE + 2).toLong()); putU32(20, (STYLE_HEADER_SIZE + 100).toLong())
            val image = STYLE_HEADER_SIZE + 100
            putU16(image, 1); putU16(image + 2, 1); putU16(image + 4, IMAGE_RGB565); putU32(image + 8, 2)
            this[0x11] = 1
            val base = STYLE_HEADER_SIZE
            putU32(base, 13); putU32(base + 0x0C, 100)
            putU16(base + 0x18, 20); putU16(base + 0x1A, 40)
            putU16(base + 0x1C, width); putU16(base + 0x1E, height)
            putU16(base + 0x20, 0xFFFF); putU16(base + 0x5C, angle)
        }
        val names = listOf(style, "style1.bin", "aod.bin")
        val offset = CONTAINER_HEADER_SIZE + names.size * DIRECTORY_ENTRY_SIZE
        val bytes = ByteArray(offset + names.size * payload.size)
        "oppo".encodeToByteArray().copyInto(bytes)
        bytes.putU32(4, 1); bytes.putU32(8, (bytes.size - CONTAINER_HEADER_SIZE).toLong())
        bytes.putU32(12, names.size.toLong())
        names.forEachIndexed { i, name ->
            val dir = CONTAINER_HEADER_SIZE + i * DIRECTORY_ENTRY_SIZE
            "./SM-R390_00000_256x402/$name".encodeToByteArray().copyInto(bytes, dir)
            bytes.putU32(dir + 64, (offset + i * payload.size).toLong())
            bytes.putU32(dir + 68, payload.size.toLong())
            bytes.putU16(dir + 72, Crc16.ccittFalse(payload))
            payload.copyInto(bytes, offset + i * payload.size)
        }
        bytes.putU16(16, Crc16.ccittFalse(bytes, CONTAINER_HEADER_SIZE, bytes.size))
        return Fit3Container.parse(bytes)
    }
    private fun rotate(c: Fit3Container, angle: Int, names: List<String> = listOf(style)) =
        FaceEditor.rotateWidget(c, names, 0, 0, 20, 40, angle)
    private fun angle(c: Fit3Container, name: String = style) =
        FaceRecordParser.widgetGuides(c.entryByBasename(name)).single().rotationTenths

    @Test fun nativeMutationChangesOnlyTheAngleAndChecksumsAndResetsWithoutDrift() {
        val original = synthetic()
        for (requested in listOf(900, 1800, 2700, 3180, 3200, -421, 3601)) {
            val edit = rotate(original, requested)
            assertEquals(normalizedRotation(requested), angle(edit.container))
            assertEquals(original.fileSize, edit.container.fileSize)
            assertTrue(edit.container.validate().isValid)
            val entry = original.entryByBasename(style)
            val allowed = setOf(16, 17, CONTAINER_HEADER_SIZE + 72, CONTAINER_HEADER_SIZE + 73,
                entry.offset + STYLE_HEADER_SIZE + 0x5C, entry.offset + STYLE_HEADER_SIZE + 0x5D)
            val before = original.toByteArray(); val after = edit.container.toByteArray()
            assertTrue(before.indices.filter { before[it] != after[it] }.all { it in allowed })
            assertArrayEquals(before, rotate(edit.container, 0).container.toByteArray())
        }
        assertThrows(Fit3FormatException::class.java) { rotate(original, 3600) }
    }

    @Test fun scopeIsExplicitAndSelectedIdentityIsStrict() {
        val original = synthetic()
        val all = rotate(original, 900, listOf(style, "style1.bin")).container
        assertEquals(900, angle(all)); assertEquals(900, angle(all, "style1.bin")); assertEquals(0, angle(all, "aod.bin"))
        val aod = rotate(original, 3200, listOf("aod.bin")).container
        assertEquals(0, angle(aod)); assertEquals(3200, angle(aod, "aod.bin"))
        assertThrows(Fit3FormatException::class.java) { FaceEditor.rotateWidget(original, listOf(style), 1, 0, 20, 40, 900) }
        assertThrows(Fit3FormatException::class.java) { FaceEditor.rotateWidget(original, listOf(style), 0, 0, 21, 40, 900) }
    }

    @Test fun excessiveRuntimeCanvasIsRefusedButZeroRemainsAvailable() {
        assertThrows(Fit3FormatException::class.java) { rotate(synthetic(512, 512), 900) }
        val oversized = synthetic(512, 512, 3200)
        assertEquals(0, angle(rotate(oversized, 0).container))
        assertEquals(65535, angle(synthetic(angle = 65535))) // Preserve vendor values on read.
    }

    @Test fun rotatingAnEditedDuplicateRetainsItsOriginalSource() {
        val original = synthetic()
        val record = FaceRecordParser.scanWidgets(original.entryByBasename(style)).single()
        var current = StructuralEditor.duplicateWidget(original, listOf(style), 0, 13, 0, 20, 40).container
        current = FaceEditor.rotateWidget(current, listOf(style), 1, 0, 20, 40, 3180).container
        assertEquals(0, FaceRecordParser.duplicateSourceGlobalIndices(current.entryByBasename(style),
            original.entryByBasename(style))[1])
        assertEquals(record.globalIndex, FaceRecordParser.originalWidgetSources(current.entryByBasename(style),
            original.entryByBasename(style))[0])
    }

    @Test fun vendorRotatedCompositesRoundTripEveryAngleWithoutChangingOtherResources() {
        val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
        for (id in listOf("00105", "00124", "00112", "00106")) {
            val name = "SM-R390_${id}_256x402"
            val path = root.resolve("SM_R390/$name/$name.bin")
            assumeTrue("no corpus face $id", Files.isRegularFile(path))
            val original = Fit3Container.parse(Files.readAllBytes(path))
            for (entry in FaceResources.variantEntries(original)) {
                val guide = FaceRecordParser.widgetGuides(entry).firstOrNull { it.rotationTenths != null } ?: continue
                var current = original
                for (angle in listOf(900, 1800, 2700, 3181, guide.rotationTenths!!)) {
                    current = FaceEditor.rotateWidget(current, listOf(entry.basename), guide.globalIndex,
                        guide.sequenceId, guide.x, guide.y, angle).container
                    assertTrue(current.validate().isValid)
                }
                assertArrayEquals("$id/${entry.basename}", original.toByteArray(), current.toByteArray())
            }
        }
    }
}
