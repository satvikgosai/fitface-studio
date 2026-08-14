package dev.fitface.studio.core.data

import dev.fitface.studio.core.format.ContainerEntry
import dev.fitface.studio.core.format.FaceRecordParser
import dev.fitface.studio.core.format.Fit3Container
import dev.fitface.studio.core.format.StructuralEditor
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.originalDrawLeft
import dev.fitface.studio.core.model.originalDrawTop
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * A resize must take the widget's old pixels off the canvas with it.
 *
 * `preview.bin` is the vendor's render of the *unedited* face and no edit rewrites it,
 * so the reference still shows every resizable Sprite at the size it shipped at. The
 * composer therefore has to clear the rectangle the widget *was* drawn in, not the one
 * it is drawn in now — clearing with the new, smaller rectangle leaves the outer ring
 * of the old sprite sitting on the canvas, which is what face `00022` showed.
 *
 * This drives the real thing: real containers, a real [StructuralEditor.resizeSprite],
 * and the guides assembled the way [WatchFaceRepositoryImpl] assembles them. Whatever
 * corpus is present is swept; with none it skips.
 */
class ResizedWidgetLeavesNoGhostTest {
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
    fun shrinkingASpriteClearsEveryPixelItsOldRectangleCovered() {
        val failures = mutableListOf<String>()
        var resizesChecked = 0

        containers.forEach { path ->
            val face = path.fileName.toString()
            val original = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@forEach
            val style = original.entries
                .firstOrNull { it.basename.matches(Regex("""style\d+\.bin""")) }
                ?: return@forEach
            val reference = referenceFor(original, style.basename) ?: return@forEach
            val target = FaceRecordParser.widgetGuides(style)
                .firstOrNull { it.canResize && it.width > 1 && it.height > 1 }
                ?: return@forEach

            // Halve it. The bug is a shrink: the new rectangle no longer covers the
            // pixels the vendor rendered, so anything left uncleared is a ghost.
            val shrunk = runCatching {
                StructuralEditor.resizeSprite(
                    source = original,
                    entryBasenames = listOf(style.basename),
                    sequenceId = target.sequenceId,
                    width = (target.width / 2).coerceAtLeast(1),
                    height = (target.height / 2).coerceAtLeast(1),
                ).container
            }.getOrNull() ?: return@forEach

            val editedStyle = shrunk.entryByBasename(style.basename)
            val widgets = guidesWithOriginalGeometry(editedStyle, style)
            val resized = widgets.singleOrNull { it.globalIndex == target.globalIndex }
                ?: return@forEach
            if (resized.width >= resized.originalWidth &&
                resized.height >= resized.originalHeight
            ) {
                failures += "$face: resize did not shrink the reported extent"
                return@forEach
            }
            resizesChecked++

            val result = EditPreviewComposer.compose(
                currentBackground = panelFrame(editedStyle),
                originalBackground = panelFrame(style),
                reference = reference,
                widgets = widgets,
                imageLayers = FaceRecordParser.widgetImageLayers(
                    entry = editedStyle,
                    originalEntry = style,
                    reference = reference,
                ),
            )

            val ghosts = ghostPixels(result.widgetOverlay, resized, widgets)
            if (ghosts > 0) {
                failures += "$face/${style.basename}: widget #${resized.globalIndex} " +
                    "left $ghosts pixel(s) of its old " +
                    "${resized.originalWidth}×${resized.originalHeight} rectangle behind " +
                    "after shrinking to ${resized.width}×${resized.height}"
            }
        }

        assumeTrue("corpus holds no resizable sprite", resizesChecked > 0)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * Overlay pixels inside the widget's old rectangle but outside its new one, skipping
     * anything another widget's original rectangle also covers — those belong to that
     * widget and are not this resize's to clear.
     */
    private fun ghostPixels(
        overlay: PreviewFrame,
        resized: WidgetGuide,
        widgets: List<WidgetGuide>,
    ): Int {
        val oldLeft = resized.originalDrawLeft(overlay.width)
        val oldTop = resized.originalDrawTop(overlay.height)
        val newLeft = resized.drawLeft(overlay.width)
        val newTop = resized.drawTop(overlay.height)
        val others = widgets.filter { it.globalIndex != resized.globalIndex }
        var ghosts = 0
        for (row in 0 until resized.originalHeight) {
            for (column in 0 until resized.originalWidth) {
                val x = oldLeft + column
                val y = oldTop + row
                if (x !in 0 until overlay.width || y !in 0 until overlay.height) continue
                val insideNew = x in newLeft until newLeft + resized.width &&
                    y in newTop until newTop + resized.height
                if (insideNew) continue
                if (others.any { other -> covers(other, x, y, overlay) }) continue
                if (overlay.argb[y * overlay.width + x] ushr 24 != 0) ghosts++
            }
        }
        return ghosts
    }

    private fun covers(widget: WidgetGuide, x: Int, y: Int, frame: PreviewFrame): Boolean {
        if (widget.originalWidth <= 0 || widget.originalHeight <= 0) return false
        val left = widget.originalDrawLeft(frame.width)
        val top = widget.originalDrawTop(frame.height)
        return x in left until left + widget.originalWidth &&
            y in top until top + widget.originalHeight
    }

    /** Exactly what `WatchFaceRepositoryImpl.snapshot` does to pair current with original. */
    private fun guidesWithOriginalGeometry(
        edited: ContainerEntry,
        original: ContainerEntry,
    ): List<WidgetGuide> {
        val originals = FaceRecordParser.widgetGuides(original).associateBy { it.globalIndex }
        return FaceRecordParser.widgetGuides(edited).map { widget ->
            val before = originals[widget.globalIndex]
            widget.copy(
                originalX = before?.x ?: widget.x,
                originalY = before?.y ?: widget.y,
                originalWidth = before?.width ?: widget.width,
                originalHeight = before?.height ?: widget.height,
                originalColorArgb = before?.colorArgb ?: widget.colorArgb,
            )
        }
    }

    private fun referenceFor(container: Fit3Container, styleName: String): PreviewFrame? {
        val styles = container.entries.filter {
            it.basename == "aod.bin" || it.basename.matches(Regex("""style\d+\.bin"""))
        }
        val styleIndex = styles.indexOfFirst { it.basename == styleName }
        if (styleIndex < 0) return null
        val preview = container.entries.singleOrNull { it.basename == "preview.bin" }
            ?: return null
        return FaceRecordParser.scanImages(preview).getOrNull(styleIndex)
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
