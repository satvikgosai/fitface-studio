package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Font bindings no longer run out (issue #11).
 *
 * A face holds ten. Every text widget imported from another face brought its own binding
 * unless all 92 bytes matched one already there, and deleting the widget kept the binding,
 * because removing one would renumber the rest. A few rounds of add-and-remove filled the
 * table and the next text import was refused. Two changes, neither of which renumbers
 * anything: bindings that draw alike share a slot whatever their unread role names say, and
 * an appended binding nothing names any more is overwritten in place.
 */
class FontSlotsTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val style = "style0.bin"

    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    private fun bindings(c: Fit3Container) = FaceResources.fontBindings(c)
    private fun lastRecord(c: Fit3Container) = FaceRecordParser.scanWidgets(c.entryByBasename(style)).last()

    private fun deleteLast(c: Fit3Container, shipped: Int): Fit3Container {
        val record = lastRecord(c)
        return StructuralEditor.deleteWidget(c, style, record.globalIndex, record.widgetType,
            record.sequenceId, record.x, record.y, shipped).container
    }

    /**
     * Up to [count] corpus text widgets that import into [target] and each need a binding
     * unlike the target's own and unlike each other's — the case that consumed a slot apiece.
     */
    private fun distinctTextDonors(target: Fit3Container, count: Int): List<Pair<Fit3Container, Int>> {
        val faces = root.resolve("SM_R390")
        assumeTrue("corpus is not available at $root", Files.isDirectory(faces))
        val seen = bindings(target).map { it.data }.toMutableList()
        val found = mutableListOf<Pair<Fit3Container, Int>>()
        for (directory in faces.listDirectoryEntries("SM-R390_*_256x402").sorted()) {
            if (found.size == count) break
            val bin = directory.resolve("${directory.name}.bin")
            if (!Files.isRegularFile(bin)) continue
            val donor = Fit3Container.parse(Files.readAllBytes(bin))
            val entry = donor.entries.singleOrNull { it.basename == style } ?: continue
            for (record in FaceRecordParser.scanWidgets(entry)) {
                val binding = record.fontBindingIndex ?: continue
                if (WidgetImporter.unavailableReason(entry, record.globalIndex) != null) continue
                val data = donor.entries.singleOrNull { it.basename == "font_$binding.bin" }?.data ?: continue
                if (seen.any { WidgetImporter.rendersAlike(it, data) }) continue
                val imports = runCatching {
                    WidgetImporter.importWidget(target, style, donor, style, record.globalIndex)
                }.isSuccess
                if (!imports) continue
                seen += data
                found += donor to record.globalIndex
                break
            }
        }
        return found
    }

    @Test
    fun addAndRemoveCyclesNoLongerExhaustTheTable() {
        val original = face("00106")
        val pristine = bindings(original).size
        val donors = distinctTextDonors(original, StyleHeader.MAX_FONT_BINDINGS - pristine + 2)
        assumeTrue("needs ${StyleHeader.MAX_FONT_BINDINGS - pristine + 2} distinct donors, found ${donors.size}",
            donors.size == StyleHeader.MAX_FONT_BINDINGS - pristine + 2)
        val shipped = FaceRecordParser.scanImages(original.entryByBasename(style)).size

        // The old path: every round leaves a binding behind until the table is full.
        var current = original
        val refusal = runCatching {
            donors.forEach { (donor, index) ->
                current = deleteLast(WidgetImporter.importWidget(current, style, donor, style, index).edit.container, shipped)
            }
        }.exceptionOrNull()
        assertTrue("$refusal", refusal is Fit3FormatException && refusal.message.orEmpty().contains("all 10"))

        // With the session's slots, each round reuses the binding the last one left.
        current = original
        donors.forEach { (donor, index) ->
            val result = WidgetImporter.importWidget(current, style, donor, style, index,
                FontSlots.of(original, emptyList(), null))
            val imported = result.edit.container
            assertEquals(emptyList<ValidationIssue>(), imported.validate().errors)
            assertEquals(pristine + 1, bindings(imported).size)
            assertEquals(pristine, lastRecord(imported).fontBindingIndex)
            assertArrayEquals(donor.entryByBasename("font_${
                FaceRecordParser.scanWidgets(donor.entryByBasename(style)).single { it.globalIndex == index }.fontBindingIndex
            }.bin").data, imported.entryByBasename("font_$pristine.bin").data)
            current = deleteLast(imported, shipped)
        }
    }

    @Test
    fun aBindingThatDrawsAlikeIsSharedWhateverItsRoleName() {
        val target = face("00106")
        val donor = face("00001")
        val value = FaceRecordParser.scanWidgets(donor.entryByBasename(style))[1]
        val donorFont = donor.entryByBasename("font_${value.fontBindingIndex}.bin")
        // The donor's binding becomes the target's font_0 in all but the unread role name.
        val renamed = target.entryByBasename("font_0.bin").data.copyOf().also {
            it.fill(0, 0x48, 0x58)
            "WF_SOMETHING".toByteArray().copyInto(it, 0x48)
        }
        val alike = StructuralEditor.rebuild(donor, mapOf(donorFont.index to renamed)).container

        val result = WidgetImporter.importWidget(target, style, alike, style, value.globalIndex)

        assertEquals(bindings(target).size, bindings(result.edit.container).size)
        assertEquals(0, lastRecord(result.edit.container).fontBindingIndex)
    }

    @Test
    fun anOrphanedSlotIsOverwrittenInPlaceAndNothingElseMoves() {
        val target = fullTable(face("00106"))
        val donor = face("00001")
        val pristine = bindings(face("00106")).size

        val result = WidgetImporter.importWidget(target, style, donor, style, 1, FontSlots(pristine))

        val after = result.edit.container
        assertEquals(StyleHeader.MAX_FONT_BINDINGS, bindings(after).size)
        assertEquals(pristine, lastRecord(after).fontBindingIndex)
        // Every other binding is byte for byte what it was: nothing was renumbered.
        bindings(target).forEachIndexed { index, before ->
            if (index != pristine) assertArrayEquals(before.data, after.entryByBasename(before.basename).data)
        }
        assertEquals(emptyList<ValidationIssue>(), after.validate().errors)
    }

    @Test
    fun aReservedSlotIsNotOverwritten() {
        val target = fullTable(face("00106"))
        val pristine = bindings(face("00106")).size
        val slots = FontSlots(pristine, reserved = (pristine until StyleHeader.MAX_FONT_BINDINGS).toSet())

        val error = assertThrows(Fit3FormatException::class.java) {
            WidgetImporter.importWidget(target, style, face("00001"), style, 1, slots)
        }
        assertTrue(error.message.orEmpty().contains("all 10"))
    }

    @Test
    fun aSlotALiveWidgetNamesIsNotOverwritten() {
        val original = face("00106")
        val pristine = bindings(original).size
        val full = fullTable(original)
        // Point a native Value at the last appended slot, so it is in use, and reserve the rest.
        val entry = full.entryByBasename(style)
        val value = FaceRecordParser.scanWidgets(entry).first { it.widgetType == 5 }
        val data = entry.data.copyOf().also { it[value.recordOffset + 0x28] = 9 }
        val inUse = StructuralEditor.rebuild(full, mapOf(entry.index to data)).container
        val slots = FontSlots(pristine, reserved = (pristine until 9).toSet())

        assertThrows(Fit3FormatException::class.java) {
            WidgetImporter.importWidget(inUse, style, face("00001"), style, 1, slots)
        }
    }

    @Test
    fun savedRemovalsAndImportBaselinesReserveTheirBindings() {
        val value = ByteArray(56).also { it.putU32(0, 5); it[0x28] = 6 }
        val composite = ByteArray(100).also { it.putU32(0, 13); it[0x5E] = 7 }
        val picture = ByteArray(40).also { it.putU32(0, 1) }
        val baseline = ByteArray(STYLE_HEADER_SIZE) + ByteArray(56).also { it.putU32(0, 5); it[0x28] = 8 }
        val origins = WidgetImportOrigins("x", listOf(
            WidgetImportOrigin("id", "00001", style, Base64.getEncoder().encodeToString(baseline), listOf(3)),
        ))

        val slots = FontSlots.of(face("00106"), listOf(value, composite, picture), origins)

        assertEquals(setOf(6, 7, 8), slots.reserved)
        assertEquals(bindings(face("00106")).size, slots.pristineCount)
    }

    @Test
    fun drawingAlikeMeansSelectorsAndSizeNotRoleName() {
        val base = ByteArray(FontBinding.SIZE).also { it[1] = 2; it.putU32(0x58, 24) }
        val renamed = base.copyOf().also { "WF_DATE".toByteArray().copyInto(it, 0x48) }
        val resized = base.copyOf().also { it.putU32(0x58, 28) }
        val refamilied = base.copyOf().also { it[1] = 3 }

        assertTrue(WidgetImporter.rendersAlike(base, renamed))
        assertFalse(WidgetImporter.rendersAlike(base, resized))
        assertFalse(WidgetImporter.rendersAlike(base, refamilied))
    }

    /** [source] with its binding table filled to ten by unreferenced copies of its first. */
    private fun fullTable(source: Fit3Container): Fit3Container {
        val current = bindings(source)
        val replacements = FaceResources.variantEntries(source)
            .associate { it.index to it.data.copyOf().also { data -> data[0x11] = StyleHeader.MAX_FONT_BINDINGS.toByte() } }
        val additions = (current.size until StyleHeader.MAX_FONT_BINDINGS)
            .associate { "font_$it.bin" to current.first().data }
        return StructuralEditor.rebuild(source, replacements, additions).container
    }
}
