package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * A widget's global index is not its identity once a structural edit has run.
 *
 * Deleting a widget renumbers every record after the one it cuts, and takes its artwork,
 * moving every raster after that. On face `00022` a remove-and-restore (when the editor still
 * offered Restore) left the seq-10 hour sprite at index 10, which in the *original* container
 * is the seq-37 battery. Everything that resolved the original by raw index then read a
 * different widget: the composer cleared the battery's rectangle, the frame lookup resolved
 * against its 11-frame table and returned null, and the sprite disappeared from the canvas
 * leaving only an empty outline.
 *
 * These sweeps run the structural edits over the whole corpus and assert the pairing
 * still names the same widget. The invariant is deliberately about *identity*, not
 * about indices, because indices are exactly what the edits are allowed to change.
 */
class WidgetOriginPairingTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    private lateinit var containers: List<Path>

    @Before
    fun locateContainers() {
        val directory = root.resolve("SM_R390")
        assumeTrue("no corpus at $directory", Files.isDirectory(directory))
        containers = Files.walk(directory, 3).asSequence()
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".bin") }
            .sortedBy { it.fileName.toString() }
            .toList()
        assumeTrue("corpus holds no containers", containers.isNotEmpty())
    }

    /** An unedited container must pair every record with itself. */
    @Test
    fun anUneditedContainerPairsEveryWidgetWithItself() {
        val failures = mutableListOf<String>()
        forEachVariant { label, entry ->
            val sources = FaceRecordParser.originalWidgetSources(entry, entry)
            FaceRecordParser.scanWidgets(entry).forEach { record ->
                if (sources[record.globalIndex] != record.globalIndex) {
                    failures += "$label: widget #${record.globalIndex} paired with " +
                        "${sources[record.globalIndex]}"
                }
            }
        }
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun deletionKeepsEverySurvivorPairedWithItsOwnOriginal() {
        val failures = mutableListOf<String>()
        var checked = 0

        containers.forEach { path ->
            val face = path.fileName.toString()
            val original = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@forEach
            val style = original.entries
                .firstOrNull { it.basename.matches(Regex("""style\d+\.bin""")) }
                ?: return@forEach
            val records = FaceRecordParser.scanWidgets(style)
            // A widget that is not last, so the deletion actually renumbers the table —
            // deleting the final record would hide the bug entirely.
            val (deleted, edited) = records.dropLast(1).firstNotNullOfOrNull { record ->
                runCatching {
                    StructuralEditor.deleteWidget(
                        source = original,
                        entryBasenames = listOf(style.basename),
                        globalIndex = record.globalIndex,
                        widgetType = record.widgetType,
                        sequenceId = record.sequenceId,
                        x = record.x,
                        y = record.y,
                        targetIndices = mapOf(style.basename to record.globalIndex),
                    ).container
                }.getOrNull()?.let { record to it }
            } ?: return@forEach
            checked++

            val editedStyle = edited.entryByBasename(style.basename)
            val sources = FaceRecordParser.originalWidgetSources(editedStyle, style)
            val originalsByIndex = records.associateBy(WidgetRecord::globalIndex)
            val survivors = FaceRecordParser.scanWidgets(editedStyle)

            if (survivors.size != records.size - 1) {
                failures += "$face: ${survivors.size} records after deletion, expected ${records.size - 1}"
                return@forEach
            }
            // Every survivor pairs with an original of its own identity. Identical records (nine
            // alike Statics on some faces) may pair with each other's index — the editor tracks
            // identity in its lineage, not through this heuristic — but never with a different
            // kind of widget, and never with the one that was deleted unless a twin remains.
            survivors.forEach { record ->
                val source = sources[record.globalIndex]
                val paired = source?.let(originalsByIndex::get)
                if (paired == null || paired.widgetType != record.widgetType || paired.sequenceId != record.sequenceId) {
                    failures += "$face: widget #${record.globalIndex} " +
                        "(type ${record.widgetType} seq ${record.sequenceId}) paired with " +
                        "original #$source (type ${paired?.widgetType} seq ${paired?.sequenceId})"
                }
            }
            if (sources.values.size != sources.values.distinct().size) {
                failures += "$face: two survivors claim the same original after deleting #${deleted.globalIndex}"
            }
        }

        assumeTrue("corpus holds no deletable widget", checked > 0)
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun aDuplicateIsReportedAsACopyAndItsSourceKeepsItsOwnPairing() {
        val failures = mutableListOf<String>()
        var checked = 0

        containers.forEach { path ->
            val face = path.fileName.toString()
            val original = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@forEach
            val style = original.entries
                .firstOrNull { it.basename.matches(Regex("""style\d+\.bin""")) }
                ?: return@forEach
            val records = FaceRecordParser.scanWidgets(style)
            val pair = records.firstNotNullOfOrNull { record ->
                runCatching {
                    StructuralEditor.duplicateWidget(
                        source = original,
                        entryBasenames = listOf(style.basename),
                        globalIndex = record.globalIndex,
                        widgetType = record.widgetType,
                        sequenceId = record.sequenceId,
                        x = record.x,
                        y = record.y,
                    ).container
                }.getOrNull()?.let { record to it }
            } ?: return@forEach
            val (source, edited) = pair
            checked++

            val editedStyle = edited.entryByBasename(style.basename)
            val sources = FaceRecordParser.originalWidgetSources(editedStyle, style)
            val duplicates = FaceRecordParser.duplicateSourceGlobalIndices(editedStyle, style)
            val copy = FaceRecordParser.scanWidgets(editedStyle).last()

            if (duplicates[copy.globalIndex] != source.globalIndex) {
                failures += "$face: the copy at #${copy.globalIndex} reports source " +
                    "${duplicates[copy.globalIndex]}, expected #${source.globalIndex}"
            }
            if (sources[source.globalIndex] != source.globalIndex) {
                failures += "$face: duplicating moved the source's own pairing to " +
                    "${sources[source.globalIndex]}"
            }
            // A copy is not an original, so it must not claim one.
            if (copy.globalIndex in sources) {
                failures += "$face: the copy at #${copy.globalIndex} claimed original " +
                    "#${sources[copy.globalIndex]}"
            }
        }

        assumeTrue("corpus holds no duplicable widget", checked > 0)
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    /**
     * The report's sequence with a deletion in place of the remove-and-restore: resize the
     * hour sprite, delete a widget ahead of it (renumbering it and moving its frames), resize
     * again. It must keep resolving to original #4, and keep drawing.
     */
    @Test
    fun faceOoo22SurvivesResizeDeleteResize() {
        val path = containers.firstOrNull { it.fileName.toString().contains("00022") }
        assumeTrue("face 00022 not in corpus", path != null)
        val original = Fit3Container.parse(Files.readAllBytes(path!!))
        val styles = listOf("style0.bin", "style1.bin", "style2.bin", "aod.bin")
        val originalStyle = original.entryByBasename("style0.bin")

        val resized = resizeBySource(
            source = original,
            entryBasenames = styles,
            sequenceId = 10,
            width = 99,
            height = 119,
            pristine = original,
        ).container

        val guide = FaceRecordParser.widgetGuides(resized.entryByBasename("style0.bin"))
            .single { it.sequenceId == 10 && it.type == 3 }
        assertEquals("the sprite starts at index 4", 4, guide.globalIndex)

        val (_, deleted) = FaceRecordParser.scanWidgets(resized.entryByBasename("style0.bin"))
            .filter { it.globalIndex < guide.globalIndex }
            .firstNotNullOf { record ->
                runCatching {
                    record to StructuralEditor.deleteWidget(resized, listOf("style0.bin"), record.globalIndex,
                        record.widgetType, record.sequenceId, record.x, record.y,
                        targetIndices = mapOf("style0.bin" to record.globalIndex)).container
                }.getOrNull()
            }

        val editedStyle = deleted.entryByBasename("style0.bin")
        val moved = FaceRecordParser.widgetGuides(editedStyle)
            .single { it.sequenceId == 10 && it.type == 3 }
        assertEquals("the deletion renumbers it", 3, moved.globalIndex)

        val sources = FaceRecordParser.originalWidgetSources(editedStyle, originalStyle)
        assertEquals("the hour sprite must resolve to original #4", 4, sources[moved.globalIndex])
        assertEquals(
            "and the battery must still resolve to itself",
            10,
            sources[FaceRecordParser.widgetGuides(editedStyle).single { it.sequenceId == 37 }.globalIndex],
        )
        assertEquals("one fewer widget pairs", FaceRecordParser.scanWidgets(originalStyle).size - 1, sources.size)

        // Its frames still resolve, so it still draws.
        val layer = FaceRecordParser
            .widgetImageLayers(editedStyle)
            .singleOrNull { it.globalIndex == moved.globalIndex }
        assertNotNull("the sprite must keep an image layer to draw", layer)
        assertEquals(99, layer!!.frame.width)
        assertEquals(119, layer.frame.height)

        // And it can still be resized after all of that.
        val again = resizeBySource(
            source = deleted,
            entryBasenames = styles,
            sequenceId = 10,
            width = 86,
            height = 104,
            pristine = original,
        ).container
        val finalGuide = FaceRecordParser.widgetGuides(again.entryByBasename("style0.bin"))
            .single { it.sequenceId == 10 && it.type == 3 }
        assertEquals(86, finalGuide.width)
        assertEquals(104, finalGuide.height)
        assertTrue(again.validate().errors.isEmpty())
    }

    private fun forEachVariant(action: (String, ContainerEntry) -> Unit) {
        containers.forEach { path ->
            val container = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@forEach
            container.entries.filter {
                it.basename == "aod.bin" || it.basename.matches(Regex("""style\d+\.bin"""))
            }.forEach { entry ->
                action("${path.fileName}/${entry.basename}", entry)
            }
        }
    }
}
