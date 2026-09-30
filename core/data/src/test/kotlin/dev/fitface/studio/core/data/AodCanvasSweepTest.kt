package dev.fitface.studio.core.data

import dev.fitface.studio.core.format.ContainerEntry
import dev.fitface.studio.core.format.FaceEditor
import dev.fitface.studio.core.format.FaceRecordParser
import dev.fitface.studio.core.format.FaceResources
import dev.fitface.studio.core.format.Fit3Container
import dev.fitface.studio.core.format.WIDGET_COMP
import dev.fitface.studio.core.format.WIDGET_HAND
import dev.fitface.studio.core.format.WIDGET_PAIR
import dev.fitface.studio.core.format.WIDGET_SPRITE
import dev.fitface.studio.core.format.WIDGET_STATIC
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.WidgetPlacement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * What the always-on canvas must be true of, over every container in the corpus.
 *
 * [CanvasIntegrityTest] exercises edit chains on numbered styles. These assertions
 * sweep AOD-specific artwork and isolation, through the same resource renderer.
 *
 * The failure these guard against is the one the old code had, and it threw nothing and
 * failed no validation — feeding AOD through the diff composer produced a bare background
 * with no widgets on it at all, a picture that looks like a face with everything deleted.
 */
class AodCanvasSweepTest {
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

    @Test
    fun everyAodComposesAtPanelSizeWithItsWidgetsOnIt() {
        val failures = mutableListOf<String>()
        var composed = 0
        var withArtwork = 0

        forEachAod { face, entry ->
            composed++
            val panel = FaceRecordParser.panelSize(entry)
            val render = runCatching { WidgetPreviewComposer.compose(entry) }.getOrElse { error ->
                failures += "$face: ${error::class.simpleName} ${error.message}"
                return@forEachAod
            }

            // The editor sizes its canvas, hit tests and drag clamps from the panel, so a
            // render of any other size puts every rectangle in the wrong place.
            if (render.composed.width != panel.width || render.composed.height != panel.height) {
                failures += "$face: composed ${render.composed.width}x${render.composed.height} " +
                    "for a ${panel.width}x${panel.height} panel"
            }
            if (render.widgetOverlay.width != panel.width ||
                render.widgetOverlay.height != panel.height
            ) {
                failures += "$face: overlay is not panel-sized"
            }

            // Whatever the entry draws with real artwork has to reach the canvas. This is
            // the assertion the old behaviour failed: a background and nothing else.
            //
            // Hands count. 33 of the corpus's AODs are analog and one of them — `00117` —
            // holds nothing but two hands and a Composite, so counting only Static and
            // Sprite would let a face whose entire picture is hands assert nothing at all,
            // which is precisely where the pivot arithmetic lives.
            val guides = FaceRecordParser.widgetGuides(entry)
            val records = FaceRecordParser.scanWidgets(entry).associateBy { it.globalIndex }
            val drawable = guides.filter { guide ->
                val record = records[guide.globalIndex]
                guide.placement != WidgetPlacement.BACKGROUND && when (record?.widgetType) {
                    WIDGET_STATIC, WIDGET_SPRITE -> true
                    WIDGET_HAND -> dev.fitface.studio.core.format.WidgetPreviewSample.fraction(record.sourceId) != null
                    else -> false
                }
            }
            val rasterBacked = drawable.count {
                records[it.globalIndex]?.widgetType in setOf(WIDGET_STATIC, WIDGET_SPRITE)
            }
            if (drawable.isNotEmpty()) {
                withArtwork++
                if (render.composed.argb.contentEquals(bareBackground(entry).argb)) {
                    failures += "$face: ${drawable.size} drawable widgets and none of them drew"
                }
            }
            // Image layers retain the decoded artwork; hands also carry rotation offsets.
            if (rasterBacked > 0 && render.widgetImageLayers.isEmpty()) {
                failures += "$face: $rasterBacked raster widgets and no image layers"
            }

            // A layer bigger than the widget it belongs to means someone else's raster was
            // blitted into it — the "outline from one widget, fill from another" class.
            render.widgetImageLayers.forEach { layer ->
                val guide = guides.firstOrNull { it.globalIndex == layer.globalIndex }
                if (guide == null) {
                    failures += "$face: layer ${layer.globalIndex} belongs to no widget"
                    return@forEach
                }
                if (guide.type in setOf(WIDGET_STATIC, WIDGET_SPRITE) &&
                    (layer.frame.width > guide.width || layer.frame.height > guide.height)) {
                    failures += "$face/${layer.globalIndex}: layer " +
                        "${layer.frame.width}x${layer.frame.height} exceeds the widget's " +
                        "${guide.width}x${guide.height}"
                }
            }
        }

        assertTrue(
            "${failures.size} failures over $composed AOD entries:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
        assumeTrue("corpus holds no AOD entries", composed > 0)
        assertTrue("no corpus AOD carries raster artwork, so nothing was proven", withArtwork > 0)
    }

    /**
     * The same bytes have to give the same picture. A render that varies between
     * recompositions would make every drag look as though it had moved something, and the
     * frame a sprite samples is the part that could drift.
     */
    @Test
    fun composingTheSameEntryTwiceGivesTheSamePicture() {
        val failures = mutableListOf<String>()

        forEachAod { face, entry ->
            val first = WidgetPreviewComposer.compose(entry)
            val second = WidgetPreviewComposer.compose(entry)
            if (!first.composed.argb.contentEquals(second.composed.argb)) {
                failures += "$face: composed twice, differently"
            }
            if (first.isApproximate != second.isApproximate) {
                failures += "$face: isApproximate is not stable"
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * [WidgetPreview.isApproximate] has to mean what it says in both directions: set when
     * the entry holds a record this renderer leaves out, clear when everything in it was
     * drawn. A flag that is always true is no more use than one that is always false.
     */
    @Test
    fun theApproximateFlagAgreesWithWhatTheEntryHolds() {
        val failures = mutableListOf<String>()
        var partial = 0
        var complete = 0

        forEachAod { face, entry ->
            val guides = FaceRecordParser.widgetGuides(entry)
                .filter { it.placement != WidgetPlacement.BACKGROUND }
                .associateBy { it.globalIndex }
            // Expected from the record table alone: text this app has no font for, a hand
            // on a reading with no sampled value, or a type this renderer has no case for.
            val expected = FaceRecordParser.scanWidgets(entry)
                .filter { it.globalIndex in guides }
                .any { record ->
                    when (record.widgetType) {
                        WIDGET_STATIC, WIDGET_SPRITE -> false
                        WIDGET_HAND ->
                            dev.fitface.studio.core.format.WidgetPreviewSample.fraction(record.sourceId) == null
                        WIDGET_PAIR, WIDGET_COMP -> true
                        else -> true
                    }
                }
            if (expected) partial++ else complete++

            val actual = WidgetPreviewComposer.compose(entry).isApproximate
            if (actual != expected) {
                failures += "$face: isApproximate=$actual, entry says $expected"
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        // Both readings have to occur or this proves only one of them.
        assertTrue("no corpus AOD is partially renderable", partial > 0)
        assertTrue("every corpus AOD is partial, so the clear case is unproven", complete > 0)
    }

    /**
     * The render follows the records rather than a cached picture of them, which is the
     * whole reason it exists: an edit that does not show up on the canvas is the state
     * AOD was in before, when the diff composer had no reference to work from.
     *
     * Only a widget with artwork can be asserted this way. A Value or Composite is
     * movable, outlined and deliberately undrawn, so moving one *correctly* leaves the
     * picture identical — which is how this test first failed, on `00057` and `00117`,
     * both of whose first movable widget is a Composite.
     */
    @Test
    fun movingAnAodWidgetWithArtworkChangesThePicture() {
        val failures = mutableListOf<String>()
        var moved = 0

        forEachAod { face, entry ->
            val container = Fit3Container.parse(Files.readAllBytes(entryPath(face)))
            val records = FaceRecordParser.scanWidgets(entry).associateBy { it.globalIndex }
            val guide = FaceRecordParser.widgetGuides(entry).firstOrNull { guide ->
                guide.placement == WidgetPlacement.CANVAS && guide.canEditPosition &&
                    guide.width in 1..200 && guide.height in 1..200 &&
                    records[guide.globalIndex]?.widgetType in setOf(WIDGET_STATIC, WIDGET_SPRITE)
            } ?: return@forEachAod
            val record = records[guide.globalIndex] ?: return@forEachAod

            val before = WidgetPreviewComposer.compose(entry)
            val edit = runCatching {
                FaceEditor.moveWidgetAcrossStyles(
                    source = container,
                    entryBasenames = listOf(FaceResources.aodOrNull(container)!!.basename),
                    globalIndex = record.globalIndex,
                    widgetType = record.widgetType,
                    sequenceId = record.sequenceId,
                    x = record.x + 4,
                    y = record.y + 4,
                )
            }.getOrNull() ?: return@forEachAod
            moved++

            val after = WidgetPreviewComposer.compose(
                FaceResources.aodOrNull(edit.container)!!,
            )
            if (before.composed.argb.contentEquals(after.composed.argb)) {
                failures += "$face: moving widget ${record.globalIndex} by 4px changed nothing"
            }
            if (!edit.container.validate().isValid) {
                failures += "$face: the move left the container invalid"
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("no corpus AOD had a movable widget, so nothing was proven", moved > 0)
    }

    /** The panel the widgets are composed onto: its raster, or the unlit black panel. */
    private fun bareBackground(entry: ContainerEntry): PreviewFrame {
        FaceRecordParser.backgroundImage(entry)?.let {
            return FaceRecordParser.decodeImage(entry, it)
        }
        val panel = FaceRecordParser.panelSize(entry)
        return PreviewFrame(
            panel.width,
            panel.height,
            IntArray(panel.width * panel.height) { 0xFF00_0000.toInt() },
        )
    }

    private fun entryPath(face: String): Path = containers.first { it.fileName.toString() == face }

    private fun forEachAod(
        block: (face: String, entry: ContainerEntry) -> Unit,
    ) {
        containers.forEach { path ->
            val container = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@forEach
            val aod = FaceResources.aodOrNull(container) ?: return@forEach
            block(path.fileName.toString(), aod)
        }
    }
}
