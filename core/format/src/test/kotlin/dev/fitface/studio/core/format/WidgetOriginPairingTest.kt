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
 * `removeWidget` renumbers every record after the one it cuts, and `appendWidget` puts
 * it back at the end with the next free number. So on face `00022` a remove-and-restore
 * leaves the seq-10 hour sprite at index 10, which in the *original* container is the
 * seq-37 battery. Everything that resolved the original by raw index then read a
 * different widget: the composer cleared the battery's rectangle, the frame lookup
 * resolved against its 11-frame table and returned null, and the restored sprite
 * disappeared from the canvas leaving only an empty outline.
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
    fun removeAndRestoreKeepsEveryWidgetPairedWithItsOwnOriginal() {
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
            // A widget that is not last, so the removal actually renumbers the table —
            // removing the final record would hide the bug entirely.
            val victim = records.dropLast(1).firstNotNullOfOrNull { record ->
                runCatching {
                    StructuralEditor.removeWidget(
                        source = original,
                        entryBasenames = listOf(style.basename),
                        globalIndex = record.globalIndex,
                        widgetType = record.widgetType,
                        sequenceId = record.sequenceId,
                        x = record.x,
                        y = record.y,
                        requireFinal = false,
                    )
                }.getOrNull()?.let { record to it }
            } ?: return@forEach
            val (removedRecord, removal) = victim

            val restored = runCatching {
                StructuralEditor.appendWidget(
                    source = removal.container,
                    entryBasenames = removal.removedRecords.keys.toList(),
                    recordsByStyle = removal.removedRecords,
                ).container
            }.getOrNull() ?: run {
                failures += "$face: could not restore widget #${removedRecord.globalIndex}"
                return@forEach
            }
            checked++

            val editedStyle = restored.entryByBasename(style.basename)
            val sources = FaceRecordParser.originalWidgetSources(editedStyle, style)
            val originalsByIndex = records.associateBy(WidgetRecord::globalIndex)
            val edited = FaceRecordParser.scanWidgets(editedStyle)

            if (edited.size != records.size) {
                failures += "$face: ${edited.size} records after round trip, expected ${records.size}"
                return@forEach
            }
            // Every record pairs, and pairs with a widget of the same identity.
            edited.forEach { record ->
                val source = sources[record.globalIndex]
                if (source == null) {
                    failures += "$face: widget #${record.globalIndex} " +
                        "(type ${record.widgetType} seq ${record.sequenceId}) lost its original"
                    return@forEach
                }
                val paired = originalsByIndex.getValue(source)
                if (paired.widgetType != record.widgetType ||
                    paired.sequenceId != record.sequenceId
                ) {
                    failures += "$face: widget #${record.globalIndex} " +
                        "(type ${record.widgetType} seq ${record.sequenceId}) paired with " +
                        "original #$source (type ${paired.widgetType} seq ${paired.sequenceId})"
                }
            }
            // And the pairing is a bijection — nothing claims someone else's original.
            val claimed = sources.values
            if (claimed.size != claimed.distinct().size) {
                failures += "$face: two widgets claim the same original after a round trip"
            }
            // The restored widget specifically resolves back to the record it came from.
            val restoredWidget = edited.singleOrNull {
                it.widgetType == removedRecord.widgetType &&
                    it.sequenceId == removedRecord.sequenceId &&
                    it.x == removedRecord.x && it.y == removedRecord.y
            }
            if (restoredWidget != null) {
                assertEquals(
                    "$face: the restored widget must resolve to the record it was cut from",
                    removedRecord.globalIndex,
                    sources[restoredWidget.globalIndex],
                )
            }
        }

        assumeTrue("corpus holds no removable widget", checked > 0)
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

    /** The exact sequence from the report: resize, remove, restore, resize again. */
    @Test
    fun faceOoo22SurvivesResizeRemoveRestoreResize() {
        val path = containers.firstOrNull { it.fileName.toString().contains("00022") }
        assumeTrue("face 00022 not in corpus", path != null)
        val original = Fit3Container.parse(Files.readAllBytes(path!!))
        val styles = listOf("style0.bin", "style1.bin", "style2.bin", "aod.bin")
        val originalStyle = original.entryByBasename("style0.bin")

        val resized = StructuralEditor.resizeSprite(
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

        val removal = StructuralEditor.removeWidget(
            source = resized,
            entryBasenames = styles,
            globalIndex = guide.globalIndex,
            widgetType = guide.type,
            sequenceId = guide.sequenceId,
            x = guide.x,
            y = guide.y,
            requireFinal = false,
        )
        val restored = StructuralEditor.appendWidget(
            source = removal.container,
            entryBasenames = removal.removedRecords.keys.toList(),
            recordsByStyle = removal.removedRecords,
        ).container

        val editedStyle = restored.entryByBasename("style0.bin")
        val moved = FaceRecordParser.widgetGuides(editedStyle)
            .single { it.sequenceId == 10 && it.type == 3 }
        assertEquals("the restore renumbers it to the end", 10, moved.globalIndex)

        // Index 10 in the original is the seq-37 battery. The pairing must not say so.
        val sources = FaceRecordParser.originalWidgetSources(editedStyle, originalStyle)
        assertEquals(
            "the restored hour sprite must resolve to original #4, not whatever now " +
                "holds index 10",
            4,
            sources[moved.globalIndex],
        )
        assertEquals(
            "and the battery must still resolve to itself",
            10,
            sources[FaceRecordParser.widgetGuides(editedStyle)
                .single { it.sequenceId == 37 }.globalIndex],
        )

        // Its frames still resolve, so it still draws.
        val preview = original.entryByBasename("preview.bin")
        val reference = FaceRecordParser.decodeImage(
            preview,
            FaceRecordParser.scanImages(preview).first(),
        )
        val layer = FaceRecordParser
            .widgetImageLayers(editedStyle, originalStyle, reference)
            .singleOrNull { it.globalIndex == moved.globalIndex }
        assertNotNull("the restored sprite must keep an image layer to draw", layer)
        assertEquals(99, layer!!.frame.width)
        assertEquals(119, layer.frame.height)

        // And it can still be resized after all of that.
        val again = StructuralEditor.resizeSprite(
            source = restored,
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
