package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WidgetReorderTest {
    private fun synthetic(references: Map<Int, Pair<Int, Int>> = emptyMap(), background: Boolean = false): Fit3Container {
        val types = listOf(1, 5, 13, 2)
        val records = types.mapIndexed { index, type ->
            ByteArray(requireNotNull(WidgetSchema.spec(type).exactSize)).apply {
                putU32(0, type); putU32(4, 37); putU16(0x0C, size); putU16(0x0E, index)
                putU16(0x18, 0); putU16(0x1A, 0)
                if (WidgetSchema.spec(type).hasStoredExtent) { putU16(0x1C, 20); putU16(0x1E, 10) }
                WidgetSchema.spec(type).alignment?.let { field ->
                    putU16(field.codeOffset, references[index]?.first ?: WidgetSchema.ALIGNMENT_DISABLED)
                    putU16(field.targetOffset, references[index]?.second ?: 0)
                }
                if (type == 2) { putU16(0x24, 0); putU16(0x26, 360) }
            }
        }
        val width = if (background) 256 else 2
        val height = if (background) 402 else 2
        val images = ByteArray(IMAGE_HEADER_SIZE + width * height * 2).apply {
            putU16(0, width); putU16(2, height); putU16(4, IMAGE_RGB565); putU32(8, width * height * 2)
        }
        val header = ByteArray(STYLE_HEADER_SIZE).apply {
            putU32(0, STYLE_MAGIC); putU32(4, records.size); putU32(8, records.sumOf { it.size }); putU32(12, images.size)
            this[0x11] = 1; putU32(20, STYLE_HEADER_SIZE + records.sumOf { it.size })
        }
        val payload = header + records.fold(ByteArray(0)) { a, b -> a + b } + images
        val names = listOf("style0.bin", "style1.bin", "aod.bin")
        val start = CONTAINER_HEADER_SIZE + names.size * DIRECTORY_ENTRY_SIZE
        val bytes = ByteArray(start + payload.size * names.size)
        "oppo".encodeToByteArray().copyInto(bytes); bytes.putU32(4, 1)
        bytes.putU32(8, bytes.size - CONTAINER_HEADER_SIZE); bytes.putU32(12, names.size)
        names.forEachIndexed { i, name ->
            val dir = CONTAINER_HEADER_SIZE + i * DIRECTORY_ENTRY_SIZE
            "./SM-R390_00000_256x402/$name".encodeToByteArray().copyInto(bytes, dir)
            bytes.putU32(dir + 64, start + i * payload.size); bytes.putU32(dir + 68, payload.size)
            bytes.putU16(dir + 72, Crc16.ccittFalse(payload)); payload.copyInto(bytes, start + i * payload.size)
        }
        bytes.putU16(16, Crc16.ccittFalse(bytes, CONTAINER_HEADER_SIZE, bytes.size))
        return Fit3Container.parse(bytes)
    }
    private fun reorder(source: Fit3Container, from: Int, to: Int, variant: String = "style0.bin"): WidgetReorderEdit {
        val w = FaceRecordParser.scanWidgets(source.entryByBasename(variant)).single { it.globalIndex == from }
        return StructuralEditor.reorderWidget(source, variant, from, w.widgetType, w.sequenceId, w.x, w.y, to)
    }
    private fun assertArtworkPreserved(before: Fit3Container, result: WidgetReorderEdit, variant: String) {
        val old = before.entryByBasename(variant); val now = result.edit.container.entryByBasename(variant)
        val header = StyleHeader.parse(old)
        assertArrayEquals(old.data.copyOfRange(header.storedImageOffset, old.size), now.data.copyOfRange(header.storedImageOffset, now.size))
        val after = FaceRecordParser.scanWidgets(now)
        FaceRecordParser.scanWidgets(old).forEach { w ->
            val moved = after[result.indices.getValue(w.globalIndex)]
            val expected = old.data.copyOfRange(w.recordOffset, w.recordOffset + w.recordSize)
            expected.putU16(0x0E, moved.globalIndex)
            w.liveAlignment?.let { alignment -> WidgetSchema.spec(w.widgetType).alignment?.let { field ->
                result.indices[alignment.targetGlobalIndex]?.let { expected.putU16(field.targetOffset, it) }
            } }
            assertArrayEquals(expected, now.data.copyOfRange(moved.recordOffset, moved.recordOffset + moved.recordSize))
        }
        before.entries.filter { it.basename != variant }.forEach {
            assertArrayEquals(it.data, result.edit.container.entryByBasename(it.basename).data)
        }
        assertEquals(before.fileSize, result.edit.container.fileSize)
        assertTrue(result.edit.container.validate().isValid)
    }

    @Test fun mixedRecordSizesPointersAndOpaqueWordsSurviveAnExactRoundTrip() {
        val source = synthetic()
        val moved = reorder(source, 0, 3)
        assertEquals(mapOf(1 to 0, 2 to 1, 3 to 2, 0 to 3), moved.indices)
        assertArtworkPreserved(source, moved, "style0.bin")
        assertArrayEquals(source.toByteArray(), reorder(moved.edit.container, 3, 0).edit.container.toByteArray())
    }
    @Test fun changingResolvedOrForwardReferenceBasisIsRefusedEvenWhenCoordinatesAgree() {
        assertThrows(Fit3FormatException::class.java) { reorder(synthetic(mapOf(2 to (1 to 1))), 1, 3) }
        assertThrows(Fit3FormatException::class.java) { reorder(synthetic(mapOf(0 to (1 to 2))), 2, 0) }
        assertThrows(Fit3FormatException::class.java) { reorder(synthetic(mapOf(2 to (17 to 1))), 0, 3) }
    }
    @Test fun namedTargetsSelfReferencesAndUnresolvedProducerValuesArePreserved() {
        for (references in listOf(mapOf(2 to (1 to 1)), mapOf(0 to (1 to 0)), mapOf(2 to (1 to 40)))) {
            val source = synthetic(references)
            val moved = reorder(source, 0, 3)
            assertArtworkPreserved(source, moved, "style0.bin")
            assertArrayEquals(source.toByteArray(), reorder(moved.edit.container, 3, 0).edit.container.toByteArray())
        }
    }
    @Test fun backgroundBoundaryNoOpAndStaleIdentityAreRefusedAndAodIsIndependent() {
        val source = synthetic(background = true)
        assertThrows(Fit3FormatException::class.java) { reorder(source, 0, 1) }
        assertThrows(Fit3FormatException::class.java) { reorder(source, 3, 0) }
        assertThrows(Fit3FormatException::class.java) { reorder(source, 1, 1) }
        assertThrows(Fit3FormatException::class.java) { StructuralEditor.reorderWidget(source,"style0.bin",1,5,999,0,0,2) }
        assertArtworkPreserved(source, reorder(source, 1, 2, "aod.bin"), "aod.bin")
    }
    @Test fun savedRemovalRetainsItsNamedAnchorAfterReorderAndRestore() {
        val source = synthetic(mapOf(2 to (1 to 1)))
        val w = FaceRecordParser.scanWidgets(source.entryByBasename("style0.bin"))[2]
        val cut = StructuralEditor.removeWidget(source, listOf("style0.bin"), 2, w.widgetType, w.sequenceId, w.x, w.y, false)
        val moved = reorder(cut.container, 0, 2)
        val raw = StructuralEditor.remapSavedAlignmentTargets(moved.edit.container.entryByBasename("style0.bin"),
            cut.removedRecords.getValue("style0.bin"), moved.indices)
        assertEquals(0, raw.u16(0x22))
        val restored = StructuralEditor.appendWidget(moved.edit.container, listOf("style0.bin"), mapOf("style0.bin" to raw)).container
        val place = FaceRecordParser.placements(restored.entryByBasename("style0.bin")).getValue(3)
        assertEquals(0, place.targetGlobalIndex)
        assertEquals(PlacementBasis.ALIGNED_TO_WIDGET, place.basis)
    }
    @Test fun everyCorpusVariantEitherRefusesAlignmentChangesOrReordersLosslessly() {
        val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot"))).resolve("SM_R390")
        assumeTrue(Files.isDirectory(root))
        var accepted = 0
        Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".bin") }.forEach { path ->
            val source = Fit3Container.parse(Files.readAllBytes(path))
            FaceResources.variantEntries(source).forEach { entry ->
                val guides = FaceRecordParser.widgetGuides(entry)
                val first = guides.firstOrNull { it.placement != dev.fitface.studio.core.model.WidgetPlacement.BACKGROUND } ?: return@forEach
                val destination = dev.fitface.studio.core.model.arrangementTarget(guides, first.globalIndex,
                    dev.fitface.studio.core.model.WidgetArrangement.FRONT) ?: return@forEach
                val result = try { reorder(source, first.globalIndex, destination, entry.basename) }
                    catch (e: Fit3FormatException) { assertTrue(e.message.orEmpty().contains("alignment")); return@forEach }
                accepted++
                assertArtworkPreserved(source, result, entry.basename)
                assertArrayEquals(source.toByteArray(), reorder(result.edit.container, destination, first.globalIndex, entry.basename).edit.container.toByteArray())
            }
        } }
        assertTrue(accepted > 0)
    }
}
