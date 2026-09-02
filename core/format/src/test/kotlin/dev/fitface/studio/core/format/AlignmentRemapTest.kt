package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * A structural edit has to carry alignment references with it.
 *
 * Four types position themselves against another widget by its global index, and a
 * removal renumbers every index after the one it cuts. Renumbering the records without
 * renumbering the references leaves a survivor measured from whichever widget inherited
 * the number — a container that parses, validates, transfers and installs, showing a face
 * with widgets in the wrong places.
 *
 * The catalogue cannot demonstrate this on its own: its references name either widget zero
 * or nothing at all, and widget zero is exactly the record a removal now refuses to touch.
 * So the reference under test here is written into a real container first.
 */
class AlignmentRemapTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val live00106 = root.resolve("SM-R390_00106/assets/SM-R390_00106_256x402.bin")

    @Before
    fun requireCorpus() {
        assumeTrue("corpus container is not available", Files.isRegularFile(live00106))
    }

    /**
     * Point one widget's alignment reference at another widget by index, keeping every
     * checksum canonical so the result is a container the app would accept normally.
     */
    private fun containerWithReference(
        styleName: String,
        referrerIndex: Int,
        targetIndex: Int,
    ): Fit3Container {
        val bytes = Files.readAllBytes(live00106)
        val source = Fit3Container.parse(bytes)
        val entry = source.entryByBasename(styleName)
        val record = FaceRecordParser.scanWidgets(entry).single { it.globalIndex == referrerIndex }
        val field = requireNotNull(WidgetSchema.spec(record.widgetType).alignment) {
            "widget $referrerIndex does not carry an alignment reference"
        }
        bytes.putU16(entry.offset + record.recordOffset + field.targetOffset, targetIndex)

        // Both checksum layers, in order: the entry's own, then the header's over the
        // whole body. Skipping the second is the classic way to produce a file that
        // fails validation for a reason that looks unrelated.
        val directory = CONTAINER_HEADER_SIZE + entry.index * DIRECTORY_ENTRY_SIZE
        bytes.putU16(
            directory + 0x48,
            Crc16.ccittFalse(bytes, entry.offset, entry.offset + entry.size),
        )
        bytes.putU16(0x10, Crc16.ccittFalse(bytes, CONTAINER_HEADER_SIZE, bytes.size))
        return Fit3Container.parse(bytes)
    }

    private fun referenceOf(container: Fit3Container, styleName: String, globalIndex: Int) =
        FaceRecordParser.scanWidgets(container.entryByBasename(styleName))
            .single { it.globalIndex == globalIndex }
            .liveAlignment
            ?.targetGlobalIndex

    @Test
    fun aRemovalCarriesAReferenceToALaterWidgetDownWithIt() {
        val styleName = "style0.bin"
        // Widget 14 is a Value record; point it at widget 15, which really exists.
        val source = containerWithReference(styleName, referrerIndex = 14, targetIndex = 15)
        assertEquals(15, referenceOf(source, styleName, 14))
        val removed = FaceRecordParser.scanWidgets(source.entryByBasename(styleName))
            .single { it.globalIndex == 6 }

        val edit = StructuralEditor.removeWidget(
            source = source,
            entryBasenames = listOf(styleName),
            globalIndex = removed.globalIndex,
            widgetType = removed.widgetType,
            sequenceId = removed.sequenceId,
            x = removed.x,
            y = removed.y,
            requireFinal = false,
        )

        // Widget 14 is now 13, and the widget it points at is now 14.
        assertEquals(14, referenceOf(edit.container, styleName, 13))
        assertEquals(true, edit.container.validate().isValid)
    }

    /**
     * A reference that named no record is left exactly as it was.
     *
     * The catalogue writes 5, 10, 20, 30 and 40 into this field on faces with far fewer
     * widgets than that. Renumbering those would invent a reference the producer never
     * made — and, worse, could turn "measured from the whole face" into "measured from
     * whichever widget now has that number".
     */
    @Test
    fun aReferenceToNoRecordIsLeftAlone() {
        val styleName = "style0.bin"
        val source = Fit3Container.parse(Files.readAllBytes(live00106))
        val entry = source.entryByBasename(styleName)
        val widgets = FaceRecordParser.scanWidgets(entry)
        val referrer = widgets.single { it.globalIndex == 5 }
        val before = containerWithReference(styleName, referrerIndex = 5, targetIndex = 40)
        assertEquals(40, referenceOf(before, styleName, 5))
        assertNotEquals(
            "40 has to name no record for this test to mean anything",
            40,
            widgets.map(WidgetRecord::globalIndex).maxOrNull(),
        )
        val removed = FaceRecordParser.scanWidgets(before.entryByBasename(styleName))
            .single { it.globalIndex == 6 }

        val edit = StructuralEditor.removeWidget(
            source = before,
            entryBasenames = listOf(styleName),
            globalIndex = removed.globalIndex,
            widgetType = removed.widgetType,
            sequenceId = removed.sequenceId,
            x = removed.x,
            y = removed.y,
            requireFinal = false,
        )

        assertEquals(40, referenceOf(edit.container, styleName, 5))
        assertEquals(referrer.widgetType, WIDGET_COMP)
    }

    /**
     * Adding a background inserts a widget at index 0, so every reference to a real
     * record moves up one — otherwise all of them would silently re-point at the new
     * background instead of the widget they were authored against.
     */
    @Test
    fun addingABackgroundCarriesReferencesUp() {
        // A style that has no full-panel raster is the only kind this edit runs on, and
        // this face's always-on style is one.
        val styleName = "aod.bin"
        // Widget 4 is this style's only record with an alignment reference, and it names
        // nothing as shipped; point it at widget 1 so the renumbering has something to
        // carry.
        val source = containerWithReference(styleName, referrerIndex = 4, targetIndex = 1)
        assertEquals(1, referenceOf(source, styleName, 4))

        val edit = StructuralEditor.addBackgrounds(
            source = source,
            entryBasenames = listOf(styleName),
            width = 256,
            height = 402,
            argb = IntArray(256 * 402),
        )

        // The referrer is now widget 5 and its target is now widget 2.
        assertEquals(2, referenceOf(edit.container, styleName, 5))
        assertEquals(true, edit.container.validate().isValid)
    }
}
