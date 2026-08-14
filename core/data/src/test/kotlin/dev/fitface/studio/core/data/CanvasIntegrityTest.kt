package dev.fitface.studio.core.data

import dev.fitface.studio.core.format.ContainerEntry
import dev.fitface.studio.core.format.FaceEditor
import dev.fitface.studio.core.format.FaceRecordParser
import dev.fitface.studio.core.format.Fit3Container
import dev.fitface.studio.core.format.StructuralEditor
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WidgetImageLayer
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * What the canvas must still be true of after an edit.
 *
 * The bugs this exists to catch do not throw and do not fail validation — the container
 * is perfectly well-formed and the watch would accept it. They show up only as a wrong
 * *picture*: a widget whose outline is drawn somewhere its pixels are not, a sprite that
 * silently stops drawing, an outline sized from one widget's frames and filled from
 * another's. Face `00022` showed all three at once after a remove-and-restore, because
 * every consumer resolved "the original of this widget" by a global index the edit had
 * renumbered.
 *
 * So these are assertions about agreement between the three things the canvas draws
 * from — the guide's rectangle, the image layer's frame, and the widget's own record —
 * checked after each structural edit rather than only on a pristine container. They are
 * deliberately invariants rather than per-face expectations, so dropping more containers
 * into the corpus widens the coverage for free.
 */
class CanvasIntegrityTest {
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

    /** One face's canvas, assembled exactly the way `WatchFaceRepositoryImpl` does. */
    private class Canvas(
        val guides: List<WidgetGuide>,
        val layers: List<WidgetImageLayer>,
        val sources: Map<Int, Int>,
        val preview: EditPreview,
        val width: Int,
        val height: Int,
    ) {
        fun layerFor(index: Int) = layers.singleOrNull { it.globalIndex == index }
    }

    private fun canvasOf(
        original: Fit3Container,
        current: Fit3Container,
        styleName: String,
    ): Canvas? {
        val style = current.entryByBasename(styleName)
        val originalStyle = original.entryByBasename(styleName)
        val reference = referenceFor(original, styleName) ?: return null
        val currentBackground = panelFrame(style)
        val originalBackground = panelFrame(originalStyle)
        val sources = FaceRecordParser.originalWidgetSources(style, originalStyle)
        val duplicates = FaceRecordParser.duplicateSourceGlobalIndices(style, originalStyle)
        val originalGuides = FaceRecordParser.widgetGuides(originalStyle)
            .associateBy { it.globalIndex }
        val guides = FaceRecordParser.widgetGuides(style).map { widget ->
            val duplicate = duplicates[widget.globalIndex]
            val origin = sources[widget.globalIndex]?.let(originalGuides::get)
                ?: duplicate?.let(originalGuides::get)
            widget.copy(
                originalX = origin?.x ?: widget.x,
                originalY = origin?.y ?: widget.y,
                originalWidth = origin?.width ?: widget.width,
                originalHeight = origin?.height ?: widget.height,
                originalColorArgb = origin?.colorArgb ?: widget.colorArgb,
                duplicateSourceGlobalIndex = duplicate,
            )
        }
        val layers = FaceRecordParser.widgetImageLayers(style, originalStyle, reference)
        return Canvas(
            guides = guides,
            layers = layers,
            sources = sources,
            preview = EditPreviewComposer.compose(
                currentBackground = currentBackground,
                originalBackground = originalBackground,
                reference = reference,
                widgets = guides,
                imageLayers = layers,
            ),
            width = currentBackground.width,
            height = currentBackground.height,
        )
    }

    /**
     * The outline the canvas draws and the artwork it fills that outline with have to be
     * the same size. They come from different places — the guide's extent from the
     * widget's frames, the layer from a frame resolved against the original record — so
     * a mis-resolved original shows up here as an outline that does not fit its picture.
     */
    private fun checkLayerMatchesBox(label: String, canvas: Canvas, failures: MutableList<String>) {
        canvas.guides.forEach { guide ->
            val layer = canvas.layerFor(guide.globalIndex) ?: return@forEach
            if (layer.frame.width != guide.width || layer.frame.height != guide.height) {
                failures += "$label: widget #${guide.globalIndex} (seq ${guide.sequenceId}) " +
                    "outlines ${guide.width}×${guide.height} but its artwork is " +
                    "${layer.frame.width}×${layer.frame.height}"
            }
        }
    }

    /** Nothing the canvas draws may spill outside the panel. */
    private fun checkBoxesOnPanel(label: String, canvas: Canvas, failures: MutableList<String>) {
        canvas.guides.filter { it.placement == WidgetPlacement.CANVAS }.forEach { guide ->
            val left = guide.drawLeft(canvas.width)
            val top = guide.drawTop(canvas.height)
            if (left + guide.width <= 0 || top + guide.height <= 0 ||
                left >= canvas.width || top >= canvas.height
            ) {
                failures += "$label: widget #${guide.globalIndex} (seq ${guide.sequenceId}) " +
                    "outlines ${guide.width}×${guide.height} at $left,$top — entirely off a " +
                    "${canvas.width}×${canvas.height} panel"
            }
        }
    }

    /**
     * Every widget resolves to an original of the same identity. This is the direct
     * detector for index drift: after a removal renumbers the table, a widget that
     * resolves to a *different* widget's record poisons its rectangle, its clear and
     * its frame lookup all at once.
     */
    private fun checkOriginIdentity(
        label: String,
        original: ContainerEntry,
        current: ContainerEntry,
        failures: MutableList<String>,
    ) {
        val originals = FaceRecordParser.scanWidgets(original).associateBy { it.globalIndex }
        val sources = FaceRecordParser.originalWidgetSources(current, original)
        val duplicates = FaceRecordParser.duplicateSourceGlobalIndices(current, original)
        FaceRecordParser.scanWidgets(current).forEach { record ->
            val source = sources[record.globalIndex] ?: duplicates[record.globalIndex]
            if (source == null) {
                failures += "$label: widget #${record.globalIndex} (type ${record.widgetType} " +
                    "seq ${record.sequenceId}) resolves to no original at all"
                return@forEach
            }
            val paired = originals.getValue(source)
            if (paired.widgetType != record.widgetType || paired.sequenceId != record.sequenceId) {
                failures += "$label: widget #${record.globalIndex} (type ${record.widgetType} " +
                    "seq ${record.sequenceId}) resolves to original #$source " +
                    "(type ${paired.widgetType} seq ${paired.sequenceId})"
            }
        }
    }

    /**
     * If a widget's original drew, the widget must still draw.
     *
     * Stated per widget, through the pairing, rather than per `(type, sequence)`: a face
     * can carry nine records sharing those two fields, so removing whichever one of them
     * happened to draw looks like a regression when it is just the removal working. The
     * pairing gives each surviving widget the exact original to compare against, and a
     * widget the edit deliberately removed simply is not in the list any more.
     */
    private fun checkNoLayerLost(
        label: String,
        before: Canvas,
        after: Canvas,
        failures: MutableList<String>,
    ) {
        val originalsThatDraw = before.layers.mapTo(mutableSetOf()) { it.globalIndex }
        after.guides.forEach { guide ->
            val origin = after.sources[guide.globalIndex] ?: return@forEach
            if (origin in originalsThatDraw && after.layerFor(guide.globalIndex) == null) {
                failures += "$label: widget #${guide.globalIndex} (seq ${guide.sequenceId}) " +
                    "comes from original #$origin, which draws, but it no longer does"
            }
        }
    }

    /**
     * The fixed sequences below each start from a pristine container. Real use does not:
     * a widget gets moved, then removed, then restored, then something else is
     * duplicated on top. Those compose in ways a single edit does not, and the pairing
     * that resolves a widget's original is a set of heuristics — payload, position,
     * type-and-sequence — which a *combination* of edits can make ambiguous even though
     * each one alone is fine. So this walks a deterministic pseudo-random chain of edits
     * per face and checks the same invariants after every step.
     */
    @Test
    fun longEditChainsKeepTheCanvasSelfConsistent() {
        val failures = mutableListOf<String>()
        var steps = 0

        containers.forEach { path ->
            val face = path.fileName.toString()
            val original = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@forEach
            val styleName = original.entries
                .firstOrNull { it.basename.matches(Regex("""style\d+\.bin""")) }
                ?.basename ?: return@forEach
            val baseline = canvasOf(original, original, styleName) ?: return@forEach

            var current = original
            var cut: Map<String, ByteArray>? = null
            // A fixed, face-derived walk: no Math.random, so a failure reproduces.
            var seed = face.hashCode() and 0x7FFF_FFFF
            repeat(10) { step ->
                seed = (seed * 1_103_515_245 + 12_345) and 0x7FFF_FFFF
                val records = FaceRecordParser.scanWidgets(current.entryByBasename(styleName))
                if (records.isEmpty()) return@repeat
                val pick = records[seed % records.size]
                val next = when (seed % 4) {
                    0 -> runCatching {
                        FaceEditor.moveWidget(
                            source = current,
                            entryBasename = styleName,
                            globalIndex = pick.globalIndex,
                            widgetType = pick.widgetType,
                            sequenceId = pick.sequenceId,
                            x = (pick.x + 7).coerceIn(0, 200),
                            y = (pick.y + 5).coerceIn(0, 340),
                        ).container
                    }.getOrNull()

                    1 -> runCatching {
                        StructuralEditor.removeWidget(
                            source = current,
                            entryBasenames = listOf(styleName),
                            globalIndex = pick.globalIndex,
                            widgetType = pick.widgetType,
                            sequenceId = pick.sequenceId,
                            x = pick.x,
                            y = pick.y,
                            requireFinal = false,
                        )
                    }.getOrNull()?.also { cut = it.removedRecords }?.container

                    2 -> cut?.let { saved ->
                        runCatching {
                            StructuralEditor.appendWidget(
                                source = current,
                                entryBasenames = saved.keys.toList(),
                                recordsByStyle = saved,
                            ).container
                        }.getOrNull()?.also { cut = null }
                    }

                    else -> runCatching {
                        StructuralEditor.duplicateWidget(
                            source = current,
                            entryBasenames = listOf(styleName),
                            globalIndex = pick.globalIndex,
                            widgetType = pick.widgetType,
                            sequenceId = pick.sequenceId,
                            x = pick.x,
                            y = pick.y,
                        ).container
                    }.getOrNull()
                } ?: return@repeat

                current = next
                val canvas = canvasOf(original, current, styleName) ?: return@repeat
                steps++
                val tag = "$face/$styleName chain step $step (op ${seed % 4})"
                checkOriginIdentity(
                    tag,
                    original.entryByBasename(styleName),
                    current.entryByBasename(styleName),
                    failures,
                )
                checkLayerMatchesBox(tag, canvas, failures)
                checkNoLayerLost(tag, baseline, canvas, failures)
            }
        }

        println("CanvasIntegrityTest: $steps chained edits")
        assumeTrue("no chained edit produced a canvas", steps > 0)
        assertTrue(failures.take(15).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun everyStructuralEditLeavesTheCanvasSelfConsistent() {
        val failures = mutableListOf<String>()
        var facesChecked = 0
        var editsChecked = 0
        var skippedNoReference = 0

        containers.forEach { path ->
            val face = path.fileName.toString()
            val original = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@forEach
            val styleName = original.entries
                .firstOrNull { it.basename.matches(Regex("""style\d+\.bin""")) }
                ?.basename ?: return@forEach
            val baseline = canvasOf(original, original, styleName) ?: run {
                skippedNoReference++
                return@forEach
            }
            facesChecked++

            fun check(label: String, edited: Fit3Container) {
                val canvas = canvasOf(original, edited, styleName) ?: return
                editsChecked++
                val tag = "$face/$styleName $label"
                checkOriginIdentity(
                    tag,
                    original.entryByBasename(styleName),
                    edited.entryByBasename(styleName),
                    failures,
                )
                checkLayerMatchesBox(tag, canvas, failures)
                checkBoxesOnPanel(tag, canvas, failures)
                checkNoLayerLost(tag, baseline, canvas, failures)
            }

            check("pristine", original)

            val records = FaceRecordParser.scanWidgets(original.entryByBasename(styleName))

            // Remove a widget that is not last, so the table really is renumbered, then
            // put it back — the sequence that broke face 00022.
            records.dropLast(1).firstNotNullOfOrNull { record ->
                runCatching {
                    StructuralEditor.removeWidget(
                        source = original,
                        entryBasenames = listOf(styleName),
                        globalIndex = record.globalIndex,
                        widgetType = record.widgetType,
                        sequenceId = record.sequenceId,
                        x = record.x,
                        y = record.y,
                        requireFinal = false,
                    )
                }.getOrNull()
            }?.let { removal ->
                check("after remove", removal.container)
                runCatching {
                    StructuralEditor.appendWidget(
                        source = removal.container,
                        entryBasenames = removal.removedRecords.keys.toList(),
                        recordsByStyle = removal.removedRecords,
                    ).container
                }.getOrNull()?.let { check("after remove+restore", it) }
            }

            // Duplicate a widget.
            records.firstNotNullOfOrNull { record ->
                runCatching {
                    StructuralEditor.duplicateWidget(
                        source = original,
                        entryBasenames = listOf(styleName),
                        globalIndex = record.globalIndex,
                        widgetType = record.widgetType,
                        sequenceId = record.sequenceId,
                        x = record.x,
                        y = record.y,
                    ).container
                }.getOrNull()
            }?.let { check("after duplicate", it) }

            // Resize a sprite, then remove-restore-resize it — the full reported flow.
            FaceRecordParser.widgetGuides(original.entryByBasename(styleName))
                .firstOrNull { it.canResize && it.width >= 8 && it.height >= 8 }
                ?.let { target ->
                    val resized = runCatching {
                        StructuralEditor.resizeSprite(
                            source = original,
                            entryBasenames = listOf(styleName),
                            sequenceId = target.sequenceId,
                            width = target.width / 2,
                            height = target.height / 2,
                            pristine = original,
                        ).container
                    }.getOrNull() ?: return@let
                    check("after resize", resized)

                    val moved = FaceRecordParser.widgetGuides(resized.entryByBasename(styleName))
                        .single { it.sequenceId == target.sequenceId && it.type == target.type }
                    val removal = runCatching {
                        StructuralEditor.removeWidget(
                            source = resized,
                            entryBasenames = listOf(styleName),
                            globalIndex = moved.globalIndex,
                            widgetType = moved.type,
                            sequenceId = moved.sequenceId,
                            x = moved.x,
                            y = moved.y,
                            requireFinal = false,
                        )
                    }.getOrNull() ?: return@let
                    val restored = runCatching {
                        StructuralEditor.appendWidget(
                            source = removal.container,
                            entryBasenames = removal.removedRecords.keys.toList(),
                            recordsByStyle = removal.removedRecords,
                        ).container
                    }.getOrNull() ?: return@let
                    check("after resize+remove+restore", restored)

                    runCatching {
                        StructuralEditor.resizeSprite(
                            source = restored,
                            entryBasenames = listOf(styleName),
                            sequenceId = target.sequenceId,
                            width = (target.width / 3).coerceAtLeast(1),
                            height = (target.height / 3).coerceAtLeast(1),
                            pristine = original,
                        ).container
                    }.getOrNull()?.let { check("after resize+remove+restore+resize", it) }
                }
        }

        println(
            "CanvasIntegrityTest: $facesChecked faces, $editsChecked edited canvases" +
                if (skippedNoReference > 0) ", $skippedNoReference skipped for no preview" else "",
        )
        assumeTrue("no face produced a canvas", editsChecked > 0)
        assertTrue(failures.take(15).joinToString("\n"), failures.isEmpty())
    }

    private fun referenceFor(container: Fit3Container, styleName: String): PreviewFrame? {
        val styles = container.entries.filter {
            it.basename == "aod.bin" || it.basename.matches(Regex("""style\d+\.bin"""))
        }
        val index = styles.indexOfFirst { it.basename == styleName }
        if (index < 0) return null
        val preview = container.entries.singleOrNull { it.basename == "preview.bin" }
            ?: return null
        return FaceRecordParser.scanImages(preview).getOrNull(index)
            ?.let { FaceRecordParser.decodeImage(preview, it) }
    }

    private fun panelFrame(entry: ContainerEntry): PreviewFrame {
        FaceRecordParser.backgroundImage(entry)?.let {
            return FaceRecordParser.decodeImage(entry, it)
        }
        val panel = FaceRecordParser.panelSize(entry)
        return PreviewFrame(
            width = panel.width,
            height = panel.height,
            argb = IntArray(panel.width * panel.height) { 0xFF00_0000.toInt() },
        )
    }
}
