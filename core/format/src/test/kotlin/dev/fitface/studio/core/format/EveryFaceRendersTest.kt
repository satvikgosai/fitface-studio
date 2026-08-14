package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetCategory
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.WidgetPlacement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Every container in the corpus, checked for the things that decide whether a face
 * opens and edits correctly on the canvas.
 *
 * This is the sweep that catches a face the editor would mis-draw. It deliberately
 * asserts invariants rather than per-face expectations, so dropping more containers
 * into the corpus extends the coverage without touching the test. Whatever is present
 * is checked; with no corpus at all it skips.
 *
 * What it cannot check is how the watch renders the result — that needs hardware.
 */
class EveryFaceRendersTest {
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

    private fun variants(container: Fit3Container) = container.entries.filter {
        it.basename == "aod.bin" || it.basename.matches(Regex("""style\d+\.bin"""))
    }

    private fun forEachVariant(action: (String, ContainerEntry) -> Unit) {
        val failures = mutableListOf<String>()
        containers.forEach { path ->
            val face = path.fileName.toString()
            val container = try {
                Fit3Container.parse(Files.readAllBytes(path))
            } catch (error: Exception) {
                failures += "$face: does not parse — ${error.message}"
                return@forEach
            }
            variants(container).forEach { entry ->
                try {
                    action("$face/${entry.basename}", entry)
                } catch (error: AssertionError) {
                    failures += "$face/${entry.basename}: ${error.message}"
                } catch (error: Exception) {
                    failures += "$face/${entry.basename}: ${error::class.simpleName} " +
                        "${error.message}"
                }
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size} of ${containers.size} containers failed:\n" +
                    failures.joinToString("\n"),
            )
        }
    }

    @Test
    fun everyContainerParsesValidatesAndRoundTripsByteIdentically() {
        val failures = mutableListOf<String>()
        containers.forEach { path ->
            val bytes = Files.readAllBytes(path)
            val face = path.fileName.toString()
            runCatching {
                val container = Fit3Container.parse(bytes)
                val report = container.validate()
                if (!report.isValid) {
                    failures += "$face: ${report.errors.map { it.code }}"
                }
                if (report.warnings.isNotEmpty()) {
                    failures += "$face: warnings ${report.warnings.map { it.code }}"
                }
                if (!container.toByteArray().contentEquals(bytes)) {
                    failures += "$face: did not round-trip byte-identically"
                }
            }.onFailure { failures += "$face: ${it.message}" }
        }
        assertTrue(
            "${failures.size} of ${containers.size} containers failed:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** The editor's coordinate space has to be the panel the watch renders. */
    @Test
    fun everyVariantResolvesA256x402Panel() {
        forEachVariant { label, entry ->
            assertEquals(label, PanelSize(256, 402), FaceRecordParser.panelSize(entry))
        }
    }

    @Test
    fun everyVariantScansItsImageAndWidgetStreamsCompletely() {
        forEachVariant { label, entry ->
            val images = FaceRecordParser.scanImages(entry)
            val widgets = FaceRecordParser.scanWidgets(entry)
            assertTrue("$label has no rasters", images.isNotEmpty())
            assertTrue("$label has no widgets", widgets.isNotEmpty())
            // A widget record that claims to be longer than its own stream would have
            // thrown in scanWidgets; this pins the declared/parsed agreement.
            assertEquals(
                "$label widget count",
                entry.data.u32(4).toInt(),
                widgets.size,
            )
        }
    }

    /**
     * The failure that made faces render with no selection border: a widget wrongly
     * classified as the full-face background layer.
     *
     * A style may legitimately stack more than one full-panel raster — face `00076`
     * has two 256×402 RGB565+A layers and `00089` has two as well — so the invariant
     * is not "at most one" but "the background widgets are exactly the ones drawing a
     * panel-sized raster".
     */
    @Test
    fun backgroundLayersAreExactlyTheWidgetsDrawingAPanelSizedRaster() {
        forEachVariant { label, entry ->
            val guides = FaceRecordParser.widgetGuides(entry)
            val background = guides.filter { it.placement == WidgetPlacement.BACKGROUND }
            val panelRasters = FaceRecordParser.scanImages(entry)
                .count { it.width == 256 && it.height == 402 }
            assertTrue(
                "$label reports ${background.size} background layers " +
                    "(${background.map { it.globalIndex }}) but has $panelRasters panel rasters",
                background.size <= panelRasters,
            )
            if (panelRasters == 0) {
                assertTrue("$label invented a background layer", background.isEmpty())
            }
            background.forEach { widget ->
                assertEquals(
                    "$label #${widget.globalIndex} is a background layer but is not panel sized",
                    256 to 402,
                    widget.width to widget.height,
                )
            }
        }
    }

    /**
     * A variant with nothing selectable has to be explained by clock hands, which the
     * watch rotates about a pivot and which therefore have no rectangle to outline.
     * Anything else means a widget was wrongly classified and became unreachable.
     *
     * 52 of the 99 faces have such a variant, all analog — so this is the assertion
     * that would catch a regression making *more* of the catalogue uneditable.
     */
    @Test
    fun anyVariantWithNothingSelectableIsExplainedByClockHands() {
        forEachVariant { label, entry ->
            val guides = FaceRecordParser.widgetGuides(entry)
            if (guides.any { it.placement == WidgetPlacement.CANVAS }) return@forEachVariant
            val unexplained = guides.filter {
                it.placement == WidgetPlacement.HIDDEN &&
                    it.category != WidgetCategory.HAND
            }
            assertTrue(
                "$label draws nothing selectable and it is not just clock hands: " +
                    unexplained.map { "#${it.globalIndex} type ${it.type}" },
                unexplained.isEmpty(),
            )
        }
    }

    /** A hand's artwork size is known even though its rectangle is not drawable. */
    @Test
    fun everyClockHandReportsItsArtworkSize() {
        var hands = 0
        forEachVariant { label, entry ->
            FaceRecordParser.widgetGuides(entry)
                .filter { it.category == WidgetCategory.HAND }
                .forEach { hand ->
                    assertEquals(
                        "$label #${hand.globalIndex} hand must not be drawn on the canvas",
                        WidgetPlacement.HIDDEN,
                        hand.placement,
                    )
                    assertTrue(
                        "$label #${hand.globalIndex} hand has no artwork size",
                        hand.width > 0 && hand.height > 0,
                    )
                    hands++
                }
        }
        assertTrue("no clock hand in the corpus", hands > 0)
    }

    /**
     * A degenerate rectangle is what a one-pixel sliver looks like: the widget is on
     * the canvas but cannot be seen or grabbed. A raster-backed widget always has a
     * real extent available, so it must never end up like this.
     */
    @Test
    fun noRasterBackedWidgetIsDrawnAsASliver() {
        forEachVariant { label, entry ->
            val images = FaceRecordParser.scanImages(entry)
            val guides = FaceRecordParser.widgetGuides(entry)
            val rasterSizes = images.map { it.width to it.height }.toSet()
            guides.filter {
                it.category == WidgetCategory.IMAGE || it.category == WidgetCategory.SPRITE
            }.filter { it.placement == WidgetPlacement.CANVAS }.forEach { widget ->
                assertTrue(
                    "$label #${widget.globalIndex} is ${widget.width}x${widget.height}",
                    widget.width > 1 && widget.height > 1,
                )
                assertTrue(
                    "$label #${widget.globalIndex} extent ${widget.width}x${widget.height} " +
                        "matches no raster in the style",
                    (widget.width to widget.height) in rasterSizes,
                )
            }
        }
    }

    /** A widget the canvas cannot reach cannot be selected or dragged. */
    @Test
    fun everyCanvasWidgetOverlapsThePanel() {
        forEachVariant { label, entry ->
            val panel = FaceRecordParser.panelSize(entry)
            FaceRecordParser.widgetGuides(entry)
                .filter { it.placement == WidgetPlacement.CANVAS }
                .forEach { widget ->
                    val left = widget.drawLeft(panel.width)
                    val top = widget.drawTop(panel.height)
                    assertTrue(
                        "$label #${widget.globalIndex} at ($left,$top) " +
                            "${widget.width}x${widget.height} misses the " +
                            "${panel.width}x${panel.height} panel",
                        left < panel.width && top < panel.height &&
                            left + widget.width > 0 && top + widget.height > 0,
                    )
                }
        }
    }

    /** Global indices address widgets; a duplicate would make every edit ambiguous. */
    @Test
    fun globalIndicesAreUniqueWithinAVariant() {
        forEachVariant { label, entry ->
            val indices = FaceRecordParser.widgetGuides(entry).map { it.globalIndex }
            assertEquals("$label has duplicate global indices: $indices", indices.size, indices.distinct().size)
        }
    }

    @Test
    fun everyRasterDecodesToItsDeclaredSize() {
        forEachVariant { label, entry ->
            FaceRecordParser.scanImages(entry).forEach { image ->
                val frame = FaceRecordParser.decodeImage(entry, image)
                assertEquals("$label raster ${image.index} width", image.width, frame.width)
                assertEquals("$label raster ${image.index} height", image.height, frame.height)
                assertEquals(
                    "$label raster ${image.index} pixel count",
                    image.width * image.height,
                    frame.argb.size,
                )
            }
        }
    }

    /**
     * The edit the canvas performs most often. Every selectable widget on every style
     * of every face has to survive a move and leave a container that still parses,
     * still validates and is the same length.
     */
    @Test
    fun everyCanvasWidgetCanBeMovedAndStillValidate() {
        val failures = mutableListOf<String>()
        var moved = 0
        containers.forEach { path ->
            val face = path.fileName.toString()
            val source = Fit3Container.parse(Files.readAllBytes(path))
            source.entries.filter { it.basename.matches(Regex("""style\d+\.bin""")) }
                .forEach { entry ->
                    FaceRecordParser.widgetGuides(entry)
                        .filter { it.placement == WidgetPlacement.CANVAS }
                        .forEach { widget ->
                            val label = "$face/${entry.basename}#${widget.globalIndex}"
                            runCatching {
                                val edit = FaceEditor.moveWidget(
                                    source = source,
                                    entryBasename = entry.basename,
                                    globalIndex = widget.globalIndex,
                                    widgetType = widget.type,
                                    sequenceId = widget.sequenceId,
                                    x = widget.x + 1,
                                    y = widget.y + 1,
                                )
                                if (!edit.container.validate().isValid) {
                                    failures += "$label: edited container is invalid"
                                }
                                if (edit.container.fileSize != source.fileSize) {
                                    failures += "$label: move changed the file size"
                                }
                                val after = FaceRecordParser.widgetGuides(
                                    edit.container.entryByBasename(entry.basename),
                                ).singleOrNull { it.globalIndex == widget.globalIndex }
                                if (after == null) {
                                    failures += "$label: widget vanished after the move"
                                } else if (after.x != widget.x + 1 || after.y != widget.y + 1) {
                                    failures += "$label: moved to ${after.x},${after.y} " +
                                        "instead of ${widget.x + 1},${widget.y + 1}"
                                }
                                moved++
                            }.onFailure { failures += "$label: ${it.message}" }
                        }
                }
        }
        assertTrue("no movable widget in the corpus", moved > 0)
        assertTrue(
            "${failures.size} of $moved widget moves failed:\n" +
                failures.take(40).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /**
     * The same move with "apply to every style" on, which is the editor's default.
     *
     * Styles are independent colourways: face `00001` style0 carries Value widgets
     * for data sources 17 and 18 and style1 has neither, so requiring a match in
     * every variant made 183 of the corpus's selectable widgets — every single one
     * on face `00001` — refuse to move, and the canvas silently snapped them back.
     * A variant without the widget must be skipped, and the selected one must still
     * change.
     */
    @Test
    fun everyCanvasWidgetCanBeMovedAcrossEveryVariantItAppearsIn() {
        val failures = mutableListOf<String>()
        var moved = 0
        containers.forEach { path ->
            val face = path.fileName.toString()
            val source = Fit3Container.parse(Files.readAllBytes(path))
            val allVariants = variants(source).map { it.basename }
            source.entries.filter { it.basename.matches(Regex("""style\d+\.bin""")) }
                .forEach { entry ->
                    val order = listOf(entry.basename) +
                        allVariants.filterNot { it == entry.basename }
                    FaceRecordParser.widgetGuides(entry)
                        .filter { it.placement == WidgetPlacement.CANVAS }
                        .forEach { widget ->
                            val label = "$face/${entry.basename}#${widget.globalIndex}"
                            runCatching {
                                val edit = FaceEditor.moveWidgetAcrossStyles(
                                    source = source,
                                    entryBasenames = order,
                                    globalIndex = widget.globalIndex,
                                    widgetType = widget.type,
                                    sequenceId = widget.sequenceId,
                                    x = widget.x + 1,
                                    y = widget.y + 1,
                                )
                                if (!edit.container.validate().isValid) {
                                    failures += "$label: edited container is invalid"
                                }
                                if (entry.basename !in edit.changedStyles) {
                                    failures += "$label: the selected style did not change"
                                }
                                val after = FaceRecordParser.widgetGuides(
                                    edit.container.entryByBasename(entry.basename),
                                ).singleOrNull { it.globalIndex == widget.globalIndex }
                                if (after == null) {
                                    failures += "$label: widget vanished after the move"
                                } else if (after.x != widget.x + 1 || after.y != widget.y + 1) {
                                    failures += "$label: moved to ${after.x},${after.y} " +
                                        "instead of ${widget.x + 1},${widget.y + 1}"
                                }
                                // Untouched variants have to stay byte-identical.
                                allVariants.filterNot { it in edit.changedStyles }
                                    .forEach { skipped ->
                                        val before = source.entryByBasename(skipped).data
                                        val now = edit.container.entryByBasename(skipped).data
                                        if (!before.contentEquals(now)) {
                                            failures += "$label: $skipped changed but was " +
                                                "not reported as changed"
                                        }
                                    }
                                moved++
                            }.onFailure { failures += "$label: ${it.message}" }
                        }
                }
        }
        assertTrue("no movable widget in the corpus", moved > 0)
        assertTrue(
            "${failures.size} of $moved all-style widget moves failed:\n" +
                failures.take(40).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /**
     * Removal and duplication with "apply to every style" on, which have the same
     * per-variant matching problem as a move and blocked 785 of 2,833 selections
     * across 43 faces before variants without the widget were skipped.
     */
    @Test
    fun everyStylesFirstCanvasWidgetSurvivesAnAllVariantRemoveRestoreAndDuplicate() {
        val failures = mutableListOf<String>()
        var cycles = 0
        containers.forEach { path ->
            val face = path.fileName.toString()
            val source = Fit3Container.parse(Files.readAllBytes(path))
            val styleNames = source.entries.map { it.basename }
                .filter { it.matches(Regex("""style\d+\.bin""")) }
            source.entries.filter { it.basename.matches(Regex("""style\d+\.bin""")) }
                .forEach { entry ->
                    val widget = FaceRecordParser.widgetGuides(entry)
                        .firstOrNull { it.placement == WidgetPlacement.CANVAS }
                        ?: return@forEach
                    val order = listOf(entry.basename) +
                        styleNames.filterNot { it == entry.basename }
                    val label = "$face/${entry.basename}#${widget.globalIndex}"
                    runCatching {
                        val removal = StructuralEditor.removeWidget(
                            source = source,
                            entryBasenames = order,
                            globalIndex = widget.globalIndex,
                            widgetType = widget.type,
                            sequenceId = widget.sequenceId,
                            x = widget.x,
                            y = widget.y,
                            requireFinal = false,
                        )
                        if (!removal.container.validate().isValid) {
                            failures += "$label: container invalid after removal"
                        }
                        if (entry.basename !in removal.removedRecords) {
                            failures += "$label: the selected style was not cut"
                        }
                        val restored = StructuralEditor.appendWidget(
                            source = removal.container,
                            entryBasenames = removal.removedRecords.keys.toList(),
                            recordsByStyle = removal.removedRecords,
                        )
                        if (!restored.container.validate().isValid) {
                            failures += "$label: container invalid after restore"
                        }
                        val duplicated = StructuralEditor.duplicateWidget(
                            source = source,
                            entryBasenames = order,
                            globalIndex = widget.globalIndex,
                            widgetType = widget.type,
                            sequenceId = widget.sequenceId,
                            x = widget.x,
                            y = widget.y,
                        )
                        if (!duplicated.container.validate().isValid) {
                            failures += "$label: container invalid after duplication"
                        }
                        cycles++
                    }.onFailure { failures += "$label: ${it.message}" }
                }
        }
        assertTrue("no style exercised", cycles > 0)
        assertTrue(
            "${failures.size} of $cycles all-style structural edits failed:\n" +
                failures.take(40).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** Removing the final widget is the structural edit the editor offers everywhere. */
    @Test
    fun theFinalWidgetOfEveryStyleCanBeRemovedAndRestored() {
        val failures = mutableListOf<String>()
        var cycles = 0
        containers.forEach { path ->
            val face = path.fileName.toString()
            val source = Fit3Container.parse(Files.readAllBytes(path))
            source.entries.filter { it.basename.matches(Regex("""style\d+\.bin""")) }
                .forEach { entry ->
                    val label = "$face/${entry.basename}"
                    val target = FaceRecordParser.scanWidgets(entry).last()
                    runCatching {
                        val removal = StructuralEditor.removeWidget(
                            source = source,
                            entryBasenames = listOf(entry.basename),
                            globalIndex = target.globalIndex,
                            widgetType = target.widgetType,
                            sequenceId = target.sequenceId,
                            x = target.x,
                            y = target.y,
                            requireFinal = true,
                        )
                        if (!removal.container.validate().isValid) {
                            failures += "$label: container invalid after removal"
                        }
                        val restored = StructuralEditor.appendWidget(
                            source = removal.container,
                            entryBasenames = listOf(entry.basename),
                            recordsByStyle = removal.removedRecords,
                        )
                        if (!restored.container.validate().isValid) {
                            failures += "$label: container invalid after restore"
                        }
                        // Removing then re-appending the last record is a pure round
                        // trip, so the bytes have to come back exactly.
                        if (!restored.container.toByteArray().contentEquals(source.toByteArray())) {
                            failures += "$label: remove/restore was not byte-identical"
                        }
                        cycles++
                    }.onFailure { failures += "$label: ${it.message}" }
                }
        }
        assertTrue("no style exercised", cycles > 0)
        assertTrue(
            "${failures.size} of $cycles remove/restore cycles failed:\n" +
                failures.take(40).joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
