package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WidgetSize
import dev.fitface.studio.core.model.nextWidgetSize
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Every rung the editor offers has to commit, and the widget has to come back at the size
 * that was asked for.
 *
 * `WidgetPlacementTest.resizableSpritesAreAcceptedByTheStructuralEditor` checks that one
 * step commits for every resizable record in the catalogue, and that is not the same thing:
 * a resize can be perfectly committable and still leave the widget reporting a size that is
 * on no rung, after which `nextWidgetSize` offers the rung already in force and the format
 * layer refuses it. **That is not hypothetical.** A Rule's cross-axis is its thickness, so
 * while the resize left the thickness alone, 56 of the catalogue's 84 Rules threw
 * `a resize requires a dimension change` at the user within a few taps — face `00049` on
 * the second one — with the button still lit, because nothing walked more than one step.
 *
 * These tests walk. They are the reason the ladder lives in `:core:model` rather than in
 * the editor: driving the real ladder against real containers is the only way the two can
 * be shown to agree rather than asserted to.
 */
class ResizeLadderWalkTest {
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

    /**
     * Every Rule in the catalogue, three taps each way. This is the sweep the deadlock
     * needed: one tap commits on all 84 of them.
     */
    @Test
    fun everyRuleWalksItsLadderInBothDirections() {
        val failures = mutableListOf<String>()
        var walked = 0
        containers.forEach { path ->
            val original = Fit3Container.parse(Files.readAllBytes(path))
            val face = path.fileName.toString().removeSuffix(".bin")
            original.entries.filter { it.basename == "style0.bin" }.forEach { entry ->
                FaceRecordParser.widgetGuides(entry)
                    .filter { it.type == WIDGET_BADGE && it.canResize }
                    .forEach { rule ->
                        walked++
                        failures += walk(original, face, "style0.bin", rule, steps = 3)
                    }
            }
        }
        assumeTrue("corpus holds no resizable Rule", walked > 0)
        assertEquals("$walked Rules walked", emptyList<String>(), failures.take(8))
    }

    /**
     * One widget of every resizable type, walked to the bottom of its ladder and back.
     *
     * The round trip is the other half: the last rung up is the extent the face shipped, so
     * the container has to come back to the size the store shipped it at.
     */
    @Test
    fun oneWidgetOfEachTypeWalksTheWholeLadder() {
        val types = listOf(
            WIDGET_STATIC,
            WIDGET_HAND,
            WIDGET_SPRITE,
            WIDGET_VECTOR_ARC,
            WIDGET_BADGE,
            WIDGET_ARC,
            WIDGET_LINE_BAR,
        )
        val failures = mutableListOf<String>()
        val covered = mutableSetOf<Int>()
        containers.forEach { path ->
            if (covered.size == types.size) return@forEach
            val original = Fit3Container.parse(Files.readAllBytes(path))
            val face = path.fileName.toString().removeSuffix(".bin")
            val entry = original.entries.firstOrNull { it.basename == "style0.bin" }
                ?: return@forEach
            val guides = FaceRecordParser.widgetGuides(entry)
            types.filterNot { it in covered }.forEach { type ->
                val guide = guides.firstOrNull { it.type == type && it.canResize }
                    ?: return@forEach
                covered += type
                failures += walk(original, face, "style0.bin", guide, steps = 6)
            }
        }
        assertEquals(
            "every resizable type has to be covered, or this test is not walking it",
            types.toSet(),
            covered,
        )
        assertEquals(emptyList<String>(), failures.take(8))
    }

    /**
     * Steps Smaller [steps] times and then Larger back, committing every rung the editor
     * would have offered and checking the widget reports the size that was asked for.
     */
    private fun walk(
        original: Fit3Container,
        face: String,
        styleName: String,
        start: WidgetGuide,
        steps: Int,
    ): List<String> {
        val failures = mutableListOf<String>()
        var container = original
        var guide = start
        val down = mutableListOf<WidgetSize>()
        repeat(steps) {
            val next = nextWidgetSize(guide, grow = false) ?: return@repeat
            down += next
            val stepped = step(container, original, styleName, guide, next, face, failures)
                ?: return failures
            container = stepped.first
            guide = stepped.second
        }
        repeat(down.size) {
            val next = nextWidgetSize(guide, grow = true) ?: return@repeat
            val stepped = step(container, original, styleName, guide, next, face, failures)
                ?: return failures
            container = stepped.first
            guide = stepped.second
        }
        if (failures.isEmpty() && down.isNotEmpty() && guide.width == start.width) {
            // Back at the top: the shipped extent restores the shipped record lengths, so
            // the whole entry has to be the bytes the store shipped.
            val before = original.entryByBasename(styleName).data
            val after = container.entryByBasename(styleName).data
            if (!before.contentEquals(after)) {
                failures += "$face widget ${start.globalIndex} (type ${start.type}): " +
                    "walking back up did not restore the shipped bytes"
            }
        }
        return failures
    }

    private fun step(
        container: Fit3Container,
        pristine: Fit3Container,
        styleName: String,
        guide: WidgetGuide,
        next: WidgetSize,
        face: String,
        failures: MutableList<String>,
    ): Pair<Fit3Container, WidgetGuide>? {
        val edited = try {
            resizeGuide(
                source = container,
                entryBasenames = listOf(styleName),
                guide = guide,
                width = next.width,
                height = next.height,
                pristine = pristine,
            ).container
        } catch (error: Fit3FormatException) {
            failures += "$face widget ${guide.globalIndex} (type ${guide.type}): the editor " +
                "offered ${next.width}x${next.height} from ${guide.width}x${guide.height} " +
                "and the commit refused it — ${error.message}"
            return null
        }
        val after = FaceRecordParser.widgetGuides(edited.entryByBasename(styleName))
            .single { it.globalIndex == guide.globalIndex }
        if (after.width != next.width || after.height != next.height) {
            failures += "$face widget ${guide.globalIndex} (type ${guide.type}): asked for " +
                "${next.width}x${next.height}, came back ${after.width}x${after.height} — " +
                "the next tap will offer the rung already in force"
            return null
        }
        assertTrue(
            "$face: a resized container must still validate",
            edited.validate().isValid,
        )
        return edited to after.copy(
            originalWidth = guide.originalWidth,
            originalHeight = guide.originalHeight,
            resizeKind = guide.resizeKind,
        )
    }
}
