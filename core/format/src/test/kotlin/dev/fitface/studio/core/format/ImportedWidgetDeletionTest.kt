package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Deleting an imported widget takes its artwork with it, and nothing else.
 *
 * `removeWidget` keeps a widget's rasters so Restore can put it back; for a widget that
 * came from another face that stranded every byte it brought in — one project measured
 * 1.3 MB of artwork drawn by nothing. `deleteWidget` drops what only that widget drew. The
 * assertions that matter are the round trip — import then delete is the face it was — and
 * the three things it must never drop: shipped artwork, a duplicate's shared artwork, and
 * the artwork of a removal still waiting to be restored.
 */
class ImportedWidgetDeletionTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    /** The importer's own sample set: every stock type, from nine donor faces. */
    private val samples = listOf("00008" to 1, "00002" to 2, "00025" to 1,
        "00001" to 1, "00023" to 2, "00004" to 1, "00003" to 3,
        "00028" to 1, "00028" to 13)
    private val style = "style0.bin"

    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    private fun Fit3Container.images() = FaceRecordParser.scanImages(entryByBasename(style))
    private fun Fit3Container.imageSection(): ByteArray = entryByBasename(style).let {
        it.data.copyOfRange(StyleHeader.parse(it).storedImageOffset, it.data.size)
    }
    private fun import(target: Fit3Container, donor: String, index: Int) =
        WidgetImporter.importWidget(target, style, face(donor), style, index).edit.container

    private fun delete(
        container: Fit3Container,
        globalIndex: Int,
        shipped: Int,
        retained: List<ByteArray> = emptyList(),
    ): StructuralEdit {
        val record = FaceRecordParser.scanWidgets(container.entryByBasename(style))
            .single { it.globalIndex == globalIndex }
        return StructuralEditor.deleteWidget(container, style, record.globalIndex, record.widgetType,
            record.sequenceId, record.x, record.y, shipped, retained)
    }

    private fun lastIndex(container: Fit3Container) =
        FaceRecordParser.scanWidgets(container.entryByBasename(style)).last().globalIndex

    /** What each widget draws: its type and the bytes of every raster it points at. */
    private fun drawing(container: Fit3Container): List<Pair<Int, List<List<Byte>>>> {
        val entry = container.entryByBasename(style)
        val relative = FaceRecordParser.imagesByRelativeOffset(entry)
        return FaceRecordParser.scanWidgets(entry).map { widget ->
            val art = if (widget.widgetType in FaceRecordParser.POINTER_BEARING_TYPES) {
                FaceRecordParser.imagePointerFields(widget, relative).map {
                    entry.data.copyOfRange(it.image.recordOffset, it.image.pixelOffset + it.image.dataSize).toList()
                }
            } else {
                emptyList()
            }
            widget.widgetType to art
        }
    }

    /**
     * The round trip, for every stock type. Import then delete leaves the style entry
     * exactly as it shipped — records, header and every raster — apart from the one
     * package-wide font-binding count an import of a text widget may raise. And where the
     * import added nothing but the widget, the whole container comes back byte for byte.
     */
    @Test
    fun importThenDeleteIsTheFaceItWas() {
        val original = face("00106")
        val shipped = original.images().size
        var identical = 0
        samples.forEach { (donor, index) ->
            val imported = import(original, donor, index)
            assertTrue("$donor #$index brought no artwork", imported.images().size >= shipped)
            val edit = delete(imported, lastIndex(imported), shipped)
            val after = edit.container
            assertEquals(emptyList<ValidationIssue>(), after.validate().errors)
            assertEquals("$donor #$index left artwork behind", shipped, after.images().size)
            assertArrayEquals("$donor #$index", original.imageSection(), after.imageSection())
            val expected = original.entryByBasename(style).data.copyOf()
            val actual = after.entryByBasename(style).data.copyOf()
            actual[0x11] = expected[0x11]
            assertArrayEquals("$donor #$index style entry", expected, actual)
            if (after.entries.size == original.entries.size &&
                after.entries.zip(original.entries).all { (a, b) -> a.data.contentEquals(b.data) }
            ) {
                assertArrayEquals(original.toByteArray(), after.toByteArray())
                identical++
            }
        }
        assertTrue("no import round-tripped to identical bytes", identical > 0)
    }

    /**
     * Deleting an import that sits *before* another one moves the later one's artwork, so
     * this is the case that exercises the pointer rewrite: the later widget must go on
     * drawing byte-identical artwork from its new offsets.
     */
    @Test
    fun deletingAnEarlierImportMovesTheLaterOneIntact() {
        val original = face("00106")
        val shipped = original.images().size
        val first = import(original, "00008", 1)
        val firstIndex = lastIndex(first)
        // The same donor widget twice: separate imports get separate copies of its pool.
        val both = import(first, "00008", 1)
        val second = drawing(both).last()
        assertTrue("the later import must carry artwork to be moved", second.second.isNotEmpty())
        val edit = delete(both, firstIndex, shipped)
        assertTrue(edit.droppedImages.getValue(style).isNotEmpty())
        assertEquals(second, drawing(edit.container).last())
        assertEquals(drawing(original), drawing(edit.container).dropLast(1))
        val empty = delete(edit.container, lastIndex(edit.container), shipped).container
        assertArrayEquals(original.imageSection(), empty.imageSection())
    }

    /** A duplicate shares the rasters it was copied from, so they stay while it does. */
    @Test
    fun aDuplicateKeepsTheArtworkItShares() {
        val original = face("00106")
        val shipped = original.images().size
        val imported = import(original, "00008", 1)
        val index = lastIndex(imported)
        val record = FaceRecordParser.scanWidgets(imported.entryByBasename(style)).last()
        val duplicated = StructuralEditor.duplicateWidget(imported, listOf(style), index,
            record.widgetType, record.sequenceId, record.x, record.y).container
        val copy = drawing(duplicated).last()
        val edit = delete(duplicated, index, shipped)
        assertTrue(edit.droppedImages.isEmpty())
        assertEquals(imported.images().size, edit.container.images().size)
        assertEquals(copy, drawing(edit.container).last())
    }

    /**
     * A removal waiting under Removed keeps its artwork too — Restore appends its record
     * verbatim — and the saved record is carried across the images that did go.
     */
    @Test
    fun aRemovalWaitingToBeRestoredKeepsItsArtwork() {
        val original = face("00106")
        val shipped = original.images().size
        val first = import(original, "00008", 1)
        val firstRecord = FaceRecordParser.scanWidgets(first.entryByBasename(style)).last()
        val firstArt = drawing(first).last()
        val removed = StructuralEditor.removeWidget(first, listOf(style), firstRecord.globalIndex,
            firstRecord.widgetType, firstRecord.sequenceId, firstRecord.x, firstRecord.y, requireFinal = false)
        var saved = removed.removedRecords.getValue(style)
        val both = import(removed.container, "00008", 1)
        assertTrue(drawing(both).last().second.isNotEmpty())
        saved = StructuralEditor.relocateSavedWidget(removed.container.entryByBasename(style),
            both.entryByBasename(style), saved)
        val edit = delete(both, lastIndex(both), shipped, retained = listOf(saved))
        assertTrue(edit.droppedImages.getValue(style).isNotEmpty())
        assertEquals(first.images().size, edit.container.images().size)
        val relocated = StructuralEditor.relocateSavedWidget(both.entryByBasename(style),
            edit.container.entryByBasename(style), saved, edit.droppedImages.getValue(style))
        val restored = StructuralEditor.appendWidget(edit.container, listOf(style), mapOf(style to relocated))
        assertEquals(firstArt, drawing(restored.container).last())
    }

    /** Shipped artwork is never deleted, even when nothing draws it any more. */
    @Test
    fun artworkTheFaceShippedWithIsNeverDropped() {
        val original = face("00106")
        val shipped = original.images().size
        val drawn = drawing(original)
        // A stock widget "deleted" drops nothing: every raster it drew shipped with the face.
        val stock = FaceRecordParser.scanWidgets(original.entryByBasename(style)).last()
        val edit = delete(original, stock.globalIndex, shipped)
        assertTrue(edit.droppedImages.isEmpty())
        assertEquals(shipped, edit.container.images().size)
        assertTrue(drawn.size > drawing(edit.container).size)
        val error = runCatching {
            StructuralEditor.dropImages(edit.container, style, setOf(shipped - 1), shipped)
        }.exceptionOrNull()
        assertTrue(error is Fit3FormatException)
    }
}
