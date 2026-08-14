package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class AdvancedEditingTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val live00106 = root.resolve(
        "SM-R390_00106/assets/SM-R390_00106_256x402.bin",
    )

    /** The corpus is never committed, so skip rather than fail without it. */
    @Before
    fun requireCorpus() {
        assumeTrue("corpus container is not available at $live00106", Files.isRegularFile(live00106))
    }

    @Test
    fun everyWidgetTypeCanMoveThroughTheSharedValidatedCoordinates() {
        val source = Fit3Container.parse(Files.readAllBytes(live00106))
        val entry = source.entryByBasename("style0.bin")
        val original = FaceRecordParser.scanWidgets(entry)
            .first { it.widgetType != 5 }
        val edit = FaceEditor.moveWidget(
            source = source,
            entryBasename = entry.basename,
            globalIndex = original.globalIndex,
            widgetType = original.widgetType,
            sequenceId = original.sequenceId,
            x = original.x + 1,
            y = original.y + 2,
        )
        val moved = FaceRecordParser.scanWidgets(
            edit.container.entryByBasename(entry.basename),
        ).single { it.globalIndex == original.globalIndex }

        assertEquals(original.x + 1, moved.x)
        assertEquals(original.y + 2, moved.y)
        assertEquals(source.fileSize, edit.container.fileSize)
        assertTrue(edit.container.validate().isValid)
    }

    @Test
    fun spriteGuideBoundsComeFromReferencedFrames() {
        val source = Fit3Container.parse(Files.readAllBytes(live00106))
        val guides = FaceRecordParser.widgetGuides(
            source.entryByBasename("style0.bin"),
        )
        val sprites = guides.filter { it.type == 3 }

        assertTrue(sprites.isNotEmpty())
        assertTrue(sprites.all { it.width > 0 && it.height > 0 })
    }

    @Test
    fun deviceProvenSpriteGuideExposesResize() {
        val source = Fit3Container.parse(Files.readAllBytes(live00106))
        val sprite = FaceRecordParser.widgetGuides(
            source.entryByBasename("style0.bin"),
        ).single { it.type == 3 && it.sequenceId == 69 }

        assertTrue(sprite.canResize)
    }

    @Test
    fun badgeGuideUsesEndpointGeometryInsteadOfTreatingEndpointsAsDimensions() {
        val source = Fit3Container.parse(Files.readAllBytes(live00106))
        val badge = FaceRecordParser.widgetGuides(
            source.entryByBasename("style0.bin"),
        ).single { it.type == WIDGET_BADGE }

        assertEquals(14, badge.x)
        assertEquals(256, badge.y)
        assertEquals(228, badge.width)
        assertEquals(4, badge.height)
    }

    @Test
    fun pairWidgetPositionAndColorUsesProvenSchema() {
        val source = Fit3Container.parse(
            Files.readAllBytes(
                root.resolve("SM-R390_00046/assets/SM-R390_00046_256x402.bin"),
            ),
        )
        val edit = FaceEditor.editPairWidget(
            source = source,
            entryBasename = "style0.bin",
            globalIndex = 1,
            sequenceId = 17,
            x = 10,
            y = 30,
            colorArgb = 0xFF00FF00.toInt(),
        )
        val record = FaceRecordParser.scanWidgets(
            edit.container.entryByBasename("style0.bin"),
        ).single { it.widgetType == 5 && it.sequenceId == 17 }

        assertEquals(10, record.x)
        assertEquals(30, record.y)
        assertEquals(0xFF00FF00L, record.words.first())
        assertEquals(source.fileSize, edit.container.fileSize)
        assertTrue(edit.container.validate().isValid)
    }

    @Test
    fun pairColorCanBeAppliedAcrossEveryStyle() {
        val source = Fit3Container.parse(
            Files.readAllBytes(
                root.resolve("SM-R390_00046/assets/SM-R390_00046_256x402.bin"),
            ),
        )
        val styles = source.styleNames()
        val selected = FaceRecordParser.scanWidgets(
            source.entryByBasename(styles.first()),
        ).single { it.globalIndex == 1 && it.widgetType == 5 && it.sequenceId == 17 }
        val color = 0xFF12AB34.toInt()
        val edit = FaceEditor.recolorPairWidgetAcrossStyles(
            source = source,
            entryBasenames = styles,
            globalIndex = selected.globalIndex,
            sequenceId = selected.sequenceId,
            x = selected.x,
            y = selected.y,
            colorArgb = color,
        )

        assertEquals(styles, edit.changedStyles)
        assertEquals(source.fileSize, edit.container.fileSize)
        assertTrue(edit.container.validate().isValid)
        styles.forEach { style ->
            val record = FaceRecordParser.scanWidgets(
                edit.container.entryByBasename(style),
            ).single {
                it.globalIndex == selected.globalIndex &&
                    it.widgetType == 5 &&
                    it.sequenceId == selected.sequenceId
            }
            assertEquals(color.toLong() and 0xFFFF_FFFFL, record.words.first())
        }
    }

    @Test
    fun live00106BackgroundResizeRelocatesAllStylePointers() {
        val source = Fit3Container.parse(Files.readAllBytes(live00106))
        val styles = source.styleNames()
        val width = 240
        val height = 386
        val edit = StructuralEditor.resizeBackgrounds(
            source,
            styles,
            width,
            height,
            IntArray(width * height) { 0xFFFF00FF.toInt() },
        )

        assertEquals(-82_176, edit.sizeDelta)
        assertTrue(edit.container.validate().isValid)
        styles.forEach { style ->
            val entry = edit.container.entryByBasename(style)
            val images = FaceRecordParser.scanImages(entry)
            assertEquals(width, images.first().width)
            assertEquals(height, images.first().height)
            assertPointersResolve(entry)
        }
    }

    @Test
    fun live00106SpriteResizePreservesFrameMapping() {
        val source = Fit3Container.parse(Files.readAllBytes(live00106))
        val edit = StructuralEditor.resizeSprite(
            source,
            source.styleNames(),
            sequenceId = 69,
            width = 36,
            height = 36,
        )

        assertEquals(156_240, edit.sizeDelta)
        assertTrue(edit.container.validate().isValid)
        source.styleNames().forEach { style ->
            val entry = edit.container.entryByBasename(style)
            val images = FaceRecordParser.scanImages(entry)
            (1..21).forEach { index ->
                assertEquals(36, images[index].width)
                assertEquals(36, images[index].height)
            }
            assertPointersResolve(entry)
        }
    }

    @Test
    fun live00106DeviceProvenStructuralToolsRemainValid() {
        val source = Fit3Container.parse(Files.readAllBytes(live00106))
        val styles = source.styleNames()
        val removed = StructuralEditor.removeWidget(
            source,
            styles,
            globalIndex = 17,
            widgetType = 5,
            sequenceId = 0,
            x = 16,
            y = 370,
            requireFinal = false,
        )
        val duplicated = StructuralEditor.duplicateWidget(
            source,
            styles,
            globalIndex = 17,
            widgetType = 5,
            sequenceId = 0,
            x = 16,
            y = 370,
        )

        assertEquals(-224, removed.sizeDelta)
        assertEquals(224, duplicated.sizeDelta)
        assertTrue(removed.container.validate().isValid)
        assertTrue(duplicated.container.validate().isValid)
        styles.forEach { style ->
            assertEquals(
                18,
                FaceRecordParser.scanWidgets(
                    removed.container.entryByBasename(style),
                ).size,
            )
            assertEquals(
                20,
                FaceRecordParser.scanWidgets(
                    duplicated.container.entryByBasename(style),
                ).size,
            )
        }
        assertEquals(
            17,
            FaceRecordParser.scanWidgets(
                removed.container.entryByBasename("style0.bin"),
            ).last().globalIndex,
        )
        assertEquals(
            17,
            FaceRecordParser.duplicateSourceGlobalIndices(
                duplicated.container.entryByBasename("style0.bin"),
                source.entryByBasename("style0.bin"),
            )[19],
        )
    }

    private fun Fit3Container.styleNames(): List<String> =
        entries.map { it.basename }.filter { it.matches(Regex("""style\d+\.bin""")) }

    private fun assertPointersResolve(entry: ContainerEntry) {
        val images = FaceRecordParser.scanImages(entry)
        val offsets = images.map {
            (it.recordOffset - images.first().recordOffset).toLong()
        }.toSet()
        FaceRecordParser.scanWidgets(entry).forEach { widget ->
            when (widget.widgetType) {
                1 -> assertTrue(widget.words.first() in offsets)
                3 -> assertTrue(widget.words.all { it in offsets })
            }
        }
    }
}
