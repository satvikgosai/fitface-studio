package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Deleting a widget takes its artwork with it, and nothing any other widget draws.
 *
 * Every widget is deleted this way now — stock, imported, duplicated or added — because
 * there is no Removed list to restore from: Undo, Reset and importing it again from the same
 * face bring one back. The assertions that matter are what goes (exactly the rasters only
 * the widget drew, and anything an older build left undrawn for a restore), what stays
 * (artwork a survivor draws, byte for byte, from its new offsets), and that a style is never
 * left without an image section.
 */
class WidgetDeletionTest {
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

    private fun Fit3Container.images(variant: String = style) = FaceRecordParser.scanImages(entryByBasename(variant))
    private fun Fit3Container.imageSection(): ByteArray = entryByBasename(style).let {
        it.data.copyOfRange(StyleHeader.parse(it).storedImageOffset, it.data.size)
    }
    private fun import(target: Fit3Container, donor: String, index: Int) =
        WidgetImporter.importWidget(target, style, face(donor), style, index).edit.container

    private fun delete(container: Fit3Container, globalIndex: Int, variant: String = style): StructuralEdit {
        val record = FaceRecordParser.scanWidgets(container.entryByBasename(variant))
            .single { it.globalIndex == globalIndex }
        return StructuralEditor.deleteWidget(container, listOf(variant), record.globalIndex, record.widgetType,
            record.sequenceId, record.x, record.y, targetIndices = mapOf(variant to globalIndex))
    }

    private fun lastIndex(container: Fit3Container) =
        FaceRecordParser.scanWidgets(container.entryByBasename(style)).last().globalIndex

    /** What each widget draws: its type and the bytes of every raster it points at. */
    private fun drawing(container: Fit3Container, variant: String = style): List<Pair<Int, List<List<Byte>>>> {
        val entry = container.entryByBasename(variant)
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

    /** The image records each widget points at, by index. */
    private fun drawnBy(entry: ContainerEntry): Map<Int, Set<Int>> {
        val relative = FaceRecordParser.imagesByRelativeOffset(entry)
        return FaceRecordParser.scanWidgets(entry).associate { widget ->
            widget.globalIndex to if (widget.widgetType in FaceRecordParser.POINTER_BEARING_TYPES) {
                FaceRecordParser.imagePointerFields(widget, relative).map { it.image.index }.toSet()
            } else {
                emptySet()
            }
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
            val after = delete(imported, lastIndex(imported)).container
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
        val first = import(original, "00008", 1)
        val firstIndex = lastIndex(first)
        // The same donor widget twice: separate imports get separate copies of its pool.
        val both = import(first, "00008", 1)
        val second = drawing(both).last()
        assertTrue("the later import must carry artwork to be moved", second.second.isNotEmpty())
        val edit = delete(both, firstIndex)
        assertTrue(edit.droppedImages.getValue(style).isNotEmpty())
        assertEquals(second, drawing(edit.container).last())
        assertEquals(drawing(original), drawing(edit.container).dropLast(1))
        val empty = delete(edit.container, lastIndex(edit.container)).container
        assertArrayEquals(original.imageSection(), empty.imageSection())
    }

    /** A duplicate shares the rasters it was copied from, so they stay while it does. */
    @Test
    fun aDuplicateKeepsTheArtworkItShares() {
        val original = face("00106")
        val imported = import(original, "00008", 1)
        val index = lastIndex(imported)
        val record = FaceRecordParser.scanWidgets(imported.entryByBasename(style)).last()
        val duplicated = StructuralEditor.duplicateWidget(imported, listOf(style), index,
            record.widgetType, record.sequenceId, record.x, record.y).container
        val copy = drawing(duplicated).last()
        val edit = delete(duplicated, index)
        assertTrue(edit.droppedImages.isEmpty())
        assertEquals(imported.images().size, edit.container.images().size)
        assertEquals(copy, drawing(edit.container).last())
    }

    /**
     * Every stock widget on every catalogue face, deleted on its own from `style0.bin`: the
     * image section loses exactly the rasters only that widget drew (all of them but one when
     * it was the last thing drawing), every other widget draws byte-identical artwork, and
     * the container validates. A widget others are positioned against is still refused.
     */
    @Test
    fun everyStockWidgetIsDeletedWithOnlyItsOwnArtwork() {
        val directory = root.resolve("SM_R390")
        assumeTrue(Files.isDirectory(directory))
        val files = Files.walk(directory, 3).use { stream -> stream.asSequence()
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".bin") }.sorted().toList() }
        assumeTrue(files.isNotEmpty())
        val failures = mutableListOf<String>()
        var deleted = 0
        var freed = 0L
        files.forEach { file ->
            val container = Fit3Container.parse(Files.readAllBytes(file))
            val entry = container.entryByBasename(style)
            val owners = drawnBy(entry)
            val before = drawing(container)
            FaceRecordParser.scanWidgets(entry).forEach { record ->
                val label = "${file.fileName}/#${record.globalIndex}"
                val edit = try {
                    delete(container, record.globalIndex)
                } catch (_: Fit3WidgetIsAnchorException) {
                    return@forEach
                }
                deleted++
                val own = owners.getValue(record.globalIndex) -
                    owners.filterKeys { it != record.globalIndex }.values.flatten().toSet()
                val expected = if (own.size == container.images().size) own.size - 1 else own.size
                val after = edit.container
                if (container.images().size - after.images().size != expected) {
                    failures += "$label: ${container.images().size - after.images().size} rasters went, expected $expected"
                }
                if (drawing(after) != before.filterIndexed { ordinal, _ -> ordinal != record.ordinal }) {
                    failures += "$label: a surviving widget draws different artwork"
                }
                val errors = after.validate().errors
                if (errors.isNotEmpty()) failures += "$label: ${errors.map { it.code }}"
                freed += container.fileSize - after.fileSize
            }
        }
        println("WidgetDeletionTest: $deleted deletions over ${files.size} faces freed $freed bytes")
        assertTrue("no stock widget was deletable", deleted > 0)
        assertTrue(failures.take(20).joinToString("\n"), failures.isEmpty())
    }

    /**
     * Big info's hour and minute sprites draw from one pool of digits. Deleting the hour tens
     * frees nothing while the hour units still draw those digits; deleting the units too
     * frees them.
     */
    @Test
    fun sharedDigitsStayWhileANeighbourStillDrawsThem() {
        val original = face("00011")
        val records = FaceRecordParser.scanWidgets(original.entryByBasename(style))
        val tens = records.single { it.widgetType == 3 && it.sequenceId == 2 }
        val first = delete(original, tens.globalIndex)
        assertTrue(first.droppedImages.isEmpty())
        assertEquals(original.images().size, first.container.images().size)
        val units = FaceRecordParser.scanWidgets(first.container.entryByBasename(style))
            .single { it.widgetType == 3 && it.sequenceId == 3 }
        val second = delete(first.container, units.globalIndex)
        // The minute sprites still draw the same ten digits, so those stay too.
        assertTrue(second.droppedImages.isEmpty())
        assertEquals(original.images().size, second.container.images().size)
    }

    /**
     * Emptying a style deletes all of its artwork but one raster — the smallest of those left
     * when the last widget goes — because every later edit needs an image section to measure
     * against; and `dropImages` itself refuses to take the last one.
     */
    @Test
    fun theLastWidgetLeavesOneRasterBehind() {
        var current = face("00106")
        var leftBeforeLast = emptyList<ImageRecord>()
        while (FaceRecordParser.scanWidgets(current.entryByBasename(style)).isNotEmpty()) {
            val records = FaceRecordParser.scanWidgets(current.entryByBasename(style))
            leftBeforeLast = current.images()
            val next = records.reversed().firstNotNullOfOrNull { record ->
                runCatching { delete(current, record.globalIndex) }.getOrNull()
            } ?: fail("nothing in the style could be deleted") as Nothing
            current = next.container
        }
        val left = current.images()
        assertEquals(1, left.size)
        assertEquals(emptyList<ValidationIssue>(), current.validate().errors)
        assertEquals(leftBeforeLast.minOf { it.dataSize }, left.single().dataSize)
        val error = runCatching { StructuralEditor.dropImages(current, style, setOf(0)) }.exceptionOrNull()
        assertTrue(error is Fit3FormatException)
        // A picture can still be added to the emptied style.
        PictureWidget.insert(current, style, 40, 40, IntArray(40 * 40) { -1 }, 100)
    }

    /**
     * A project from a build that offered Restore can carry rasters its saved removals kept,
     * drawn by nothing. The next deletion in that style takes them too, and never anything
     * a live widget draws.
     */
    @Test
    fun artworkAnOlderBuildKeptForARestoreGoesWithTheNextDeletion() {
        val original = face("00106")
        val records = FaceRecordParser.scanWidgets(original.entryByBasename(style))
        // The record alone, as `removeWidget` cut it when Restore existed: its rasters stay.
        // One that owns artwork nothing else draws, so something is left stranded.
        val owners = drawnBy(original.entryByBasename(style))
        val target = records.last { record ->
            (owners.getValue(record.globalIndex) - owners.filterKeys { it != record.globalIndex }.values.flatten().toSet())
                .isNotEmpty() && runCatching { delete(original, record.globalIndex) }.isSuccess
        }
        val legacy = StructuralEditor.removeWidget(original, listOf(style), target.globalIndex,
            target.widgetType, target.sequenceId, target.x, target.y, requireFinal = false).container
        assertEquals(original.images().size, legacy.images().size)
        val stranded = StructuralEditor.undrawnImages(legacy.entryByBasename(style))
        assertTrue("the old removal should have left artwork drawn by nothing", stranded.isNotEmpty())
        val other = FaceRecordParser.scanWidgets(legacy.entryByBasename(style))
            .last { runCatching { delete(legacy, it.globalIndex) }.isSuccess }
        val edit = delete(legacy, other.globalIndex)
        assertTrue(edit.droppedImages.getValue(style).containsAll(stranded))
        assertTrue(StructuralEditor.undrawnImages(edit.container.entryByBasename(style)).isEmpty())
        assertEquals(drawing(legacy).filterIndexed { ordinal, _ -> ordinal != other.ordinal }, drawing(edit.container))
    }

    /** Deleting from several styles at once purges each style's own undrawn artwork. */
    @Test
    fun aDeletionAcrossStylesFreesEachStylesArtwork() {
        val original = face("00106")
        val styles = FaceResources.selectableStyles(original).map { it.basename }
        val target = FaceRecordParser.scanWidgets(original.entryByBasename(style)).last()
        val edit = StructuralEditor.deleteWidget(original, styles, target.globalIndex, target.widgetType,
            target.sequenceId, target.x, target.y)
        assertTrue(edit.removedRecords.size > 1)
        edit.removedRecords.keys.forEach { variant ->
            assertTrue(StructuralEditor.undrawnImages(edit.container.entryByBasename(variant)).isEmpty())
        }
        assertTrue(edit.container.fileSize < original.fileSize)
        assertEquals(emptyList<ValidationIssue>(), edit.container.validate().errors)
    }
}
