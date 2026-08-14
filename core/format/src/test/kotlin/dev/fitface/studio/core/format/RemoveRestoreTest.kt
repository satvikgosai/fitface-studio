package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetPlacement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class RemoveRestoreTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val live00106 = root.resolve("SM-R390_00106/assets/SM-R390_00106_256x402.bin")
    private val live00046 = root.resolve("SM-R390_00046/assets/SM-R390_00046_256x402.bin")

    /** The corpus is never committed, so skip rather than fail without it. */
    @Before
    fun requireCorpus() {
        assumeTrue(
            "corpus containers are not available under $root",
            Files.isRegularFile(live00106) && Files.isRegularFile(live00046),
        )
    }

    private fun load(path: Path) = Fit3Container.parse(Files.readAllBytes(path))

    @Test
    fun removingWidgetZeroIsNoLongerBlockedByAlwaysZeroRecordFields() {
        // Every widget record in the live catalogue stores zero at +0x08, +0x10 and
        // +0x14. Treating those as possible index references made "remove widget 0"
        // fail on every face, because 0 is always in the affected range.
        val source = load(live00106)
        val styles = listOf("style0.bin")
        val target = FaceRecordParser.scanWidgets(source.entryByBasename("style0.bin"))
            .single { it.globalIndex == 0 }

        val edit = StructuralEditor.removeWidget(
            source = source,
            entryBasenames = styles,
            globalIndex = target.globalIndex,
            widgetType = target.widgetType,
            sequenceId = target.sequenceId,
            x = target.x,
            y = target.y,
            requireFinal = false,
        )

        assertTrue(edit.container.validate().isValid)
        assertEquals(
            FaceRecordParser.scanWidgets(source.entryByBasename("style0.bin")).size - 1,
            FaceRecordParser.scanWidgets(edit.container.entryByBasename("style0.bin")).size,
        )
    }

    @Test
    fun removeThenRestorePutsTheRecordBackWithOnlyItsIndexRewritten() {
        val source = load(live00106)
        val styles = listOf("style0.bin", "style1.bin")
        val before = FaceRecordParser.scanWidgets(source.entryByBasename("style0.bin"))
        val target = before.last()

        val removal = StructuralEditor.removeWidget(
            source = source,
            entryBasenames = styles,
            globalIndex = target.globalIndex,
            widgetType = target.widgetType,
            sequenceId = target.sequenceId,
            x = target.x,
            y = target.y,
            requireFinal = true,
        )
        assertEquals(styles.toSet(), removal.removedRecords.keys)

        val restored = StructuralEditor.appendWidget(
            source = removal.container,
            entryBasenames = styles,
            recordsByStyle = removal.removedRecords,
        )
        assertTrue(restored.container.validate().isValid)

        val after = FaceRecordParser.scanWidgets(restored.container.entryByBasename("style0.bin"))
        assertEquals(before.size, after.size)
        assertEquals(before.map { it.globalIndex }, after.map { it.globalIndex })
        val restoredRecord = after.last()
        assertEquals(target.widgetType, restoredRecord.widgetType)
        assertEquals(target.sequenceId, restoredRecord.sequenceId)
        assertEquals(target.x, restoredRecord.x)
        assertEquals(target.y, restoredRecord.y)
        assertEquals(target.words, restoredRecord.words)
        // The final widget was the last record, so removing and re-appending it is a
        // pure round trip.
        assertArrayEquals(source.toByteArray(), restored.container.toByteArray())
    }

    @Test
    fun restoringAMiddleWidgetAppendsItAtTheEnd() {
        val source = load(live00106)
        val styles = listOf("style0.bin")
        val before = FaceRecordParser.scanWidgets(source.entryByBasename("style0.bin"))
        val target = before[1]

        val removal = StructuralEditor.removeWidget(
            source, styles, target.globalIndex, target.widgetType, target.sequenceId,
            target.x, target.y, requireFinal = false,
        )
        val restored = StructuralEditor.appendWidget(
            removal.container, styles, removal.removedRecords,
        )

        val after = FaceRecordParser.scanWidgets(restored.container.entryByBasename("style0.bin"))
        assertEquals(before.size, after.size)
        assertEquals(target.sequenceId, after.last().sequenceId)
        assertTrue(restored.container.validate().isValid)
        // Ordering changed, so the bytes must differ even though the record survived.
        assertNotEquals(
            source.toByteArray().toList(),
            restored.container.toByteArray().toList(),
        )
    }

    @Test
    fun handWidgetsWithNoDrawnExtentAreClassifiedAsHidden() {
        // 00046 is the analog face: its three type-2 hand records store a pivot but
        // no width or height, so the canvas has nothing to draw or drag.
        val guides = FaceRecordParser.widgetGuides(
            load(live00046).entryByBasename("style0.bin"),
        )
        val hands = guides.filter { it.type == 2 }

        assertTrue(hands.isNotEmpty())
        assertTrue(hands.all { it.placement == WidgetPlacement.HIDDEN })
        assertTrue(guides.any { it.placement == WidgetPlacement.BACKGROUND })
        assertTrue(guides.any { it.placement == WidgetPlacement.CANVAS })
    }

    @Test
    fun thumbnailRegenerationTouchesOnlyThePreviewEntry() {
        val source = load(live00106)
        val style = source.entryByBasename("style0.bin")
        val background = FaceRecordParser.scanImages(style).first()
            .let { FaceRecordParser.decodeImage(style, it) }

        val edit = requireNotNull(
            FaceEditor.replacePreviewThumbnail(source, styleIndex = 0, composed = background),
        ) { "the stock thumbnail is not already the style background" }

        assertTrue(edit.container.validate().isValid)
        assertEquals(source.fileSize, edit.container.fileSize)
        assertEquals(listOf("preview.bin"), edit.changedStyles)
        source.entries.filter { it.basename != "preview.bin" }.forEach { entry ->
            assertArrayEquals(
                entry.data,
                edit.container.entryByBasename(entry.basename).data,
            )
        }
        val raster = FaceRecordParser.scanImages(
            edit.container.entryByBasename("preview.bin"),
        ).first()
        assertEquals(IMAGE_RGB565, raster.format)
    }

    /**
     * Re-rendering is automatic now, so it runs on every validation pass. Writing the
     * same pixels back is a no-op, not a failure — otherwise the editor would report
     * an error for a thumbnail that is already correct.
     */
    @Test
    fun regeneratingAnUnchangedThumbnailIsANoOp() {
        val source = load(live00106)
        val previewEntry = source.entryByBasename("preview.bin")
        val stored = FaceRecordParser.scanImages(previewEntry).first()
            .let { FaceRecordParser.decodeImage(previewEntry, it) }

        assertNull(FaceEditor.replacePreviewThumbnail(source, 0, stored))
    }
}
