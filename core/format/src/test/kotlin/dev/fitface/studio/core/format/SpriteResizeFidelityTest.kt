package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.SPRITE_RESIZE_CEILING
import dev.fitface.studio.core.model.WidgetGuide
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * A resize must depend only on the size asked for, never on the sizes asked for before
 * it.
 *
 * Resampling is lossy, so resizing the *current* frames chains loss onto loss: shrink a
 * 114×136 sprite to 56×69 and pull it back to 109×128 and what comes back carries only
 * the detail that survived the small one. Every resize therefore resamples the pristine
 * container's frames, which makes Smaller-then-Larger byte-identical to going straight
 * there — the property this test pins.
 */
class SpriteResizeFidelityTest {
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
    fun shrinkingThenEnlargingMatchesEnlargingStraightAway() {
        val failures = mutableListOf<String>()
        var checked = 0

        containers.forEach { path ->
            val face = path.fileName.toString()
            val original = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@forEach
            val style = original.entries
                .firstOrNull { it.basename.matches(Regex("""style\d+\.bin""")) }
                ?: return@forEach
            val target = FaceRecordParser.widgetGuides(style)
                .firstOrNull { it.canResize && it.width >= 8 && it.height >= 8 }
                ?: return@forEach

            // Clamped to the ceiling: face 00022 ships 114×136 digits, and asking for
            // 135 would simply throw and drop the face out of the sweep unnoticed.
            val large = (target.width - 1).coerceIn(1, SPRITE_RESIZE_CEILING) to
                (target.height - 1).coerceIn(1, SPRITE_RESIZE_CEILING)
            val small = (large.first / 2).coerceAtLeast(1) to (large.second / 2).coerceAtLeast(1)

            val direct = runCatching {
                StructuralEditor.resizeSprite(
                    source = original,
                    entryBasenames = listOf(style.basename),
                    sequenceId = target.sequenceId,
                    width = large.first,
                    height = large.second,
                    pristine = original,
                ).container
            }.getOrNull() ?: return@forEach

            val shrunk = runCatching {
                StructuralEditor.resizeSprite(
                    source = original,
                    entryBasenames = listOf(style.basename),
                    sequenceId = target.sequenceId,
                    width = small.first,
                    height = small.second,
                    pristine = original,
                ).container
            }.getOrNull() ?: return@forEach

            val roundTrip = runCatching {
                StructuralEditor.resizeSprite(
                    source = shrunk,
                    entryBasenames = listOf(style.basename),
                    sequenceId = target.sequenceId,
                    width = large.first,
                    height = large.second,
                    pristine = original,
                ).container
            }.getOrNull() ?: run {
                failures += "$face: could not enlarge after shrinking"
                return@forEach
            }
            checked++

            val expected = framePixels(direct, style.basename, target.sequenceId)
            val actual = framePixels(roundTrip, style.basename, target.sequenceId)
            if (expected.size != actual.size) {
                failures += "$face: frame count/size differs after the round trip"
                return@forEach
            }
            expected.indices.forEach { frame ->
                if (!expected[frame].contentEquals(actual[frame])) {
                    val differing = expected[frame].indices.count {
                        expected[frame][it] != actual[frame][it]
                    }
                    failures += "$face/${style.basename} seq=${target.sequenceId} frame $frame: " +
                        "$differing of ${expected[frame].size} bytes lost to the round trip " +
                        "(${target.width}×${target.height} → ${small.first}×${small.second} → " +
                        "${large.first}×${large.second})"
                }
            }
        }

        assumeTrue("corpus holds no resizable sprite", checked > 0)
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    /** A no-op guard: the same resize twice must be byte-identical. */
    @Test
    fun resizingIsDeterministic() {
        // The first sprite whose resize actually commits, not simply the first one the
        // guides offer. `containers.first()` skipped the assertion outright, and the
        // first offered sprite hits a face where the editor fails closed — the resize
        // is capped at 128 px per side and refuses to relocate a Hand's words.
        fun resize(
            container: Fit3Container,
            styleName: String,
            target: WidgetGuide,
        ): ByteArray = StructuralEditor.resizeSprite(
            source = container,
            entryBasenames = listOf(styleName),
            sequenceId = target.sequenceId,
            width = (target.width - 1).coerceIn(1, SPRITE_RESIZE_CEILING),
            height = (target.height - 1).coerceIn(1, SPRITE_RESIZE_CEILING),
            pristine = container,
        ).container.toByteArray()

        val candidate = containers.firstNotNullOfOrNull { path ->
            val container = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@firstNotNullOfOrNull null
            container.entries
                .filter { it.basename.matches(Regex("""style\d+\.bin""")) }
                .firstNotNullOfOrNull { style ->
                    FaceRecordParser.widgetGuides(style)
                        .filter { it.canResize && it.width >= 8 && it.height >= 8 }
                        .firstNotNullOfOrNull { guide ->
                            runCatching { resize(container, style.basename, guide) }
                                .getOrNull()
                                ?.let { Triple(container, style.basename, guide) }
                        }
                }
        }
        assumeTrue("corpus holds no committable sprite resize", candidate != null)
        val (original, styleName, target) = candidate!!

        assertArrayEquals(
            resize(original, styleName, target),
            resize(original, styleName, target),
        )
    }

    /**
     * The same property, on a container that has had a background added to it.
     *
     * This is where it broke on hardware. The pristine frames used to be matched to the
     * current ones by image index, guarded by "only if both have the same number of
     * records" — and adding a background breaks both halves at once: the count differs by
     * one, so the guard threw the pristine pixels away and every resize resampled the
     * previous resize, and the indices are off by one anyway. Smaller, Larger, Smaller
     * came back visibly softer. Origins are resolved through widget identity now, so the
     * round trip has to land exactly where going straight there does.
     *
     * The face is whichever one first offers both halves of the setup — a style with room
     * for an added background under `WATCH_CONTAINER_BYTE_CEILING` and a resizable sprite
     * in it. It used to be `00022`, which is 4,117,664 bytes and now has room for none.
     */
    @Test
    fun fidelitySurvivesAnAddedBackground() {
        val candidate = containers.firstNotNullOfOrNull { path ->
            val container = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@firstNotNullOfOrNull null
            val style = StructuralEditor.backgroundStylesThatFit(
                source = container,
                entryBasenames = container.entries
                    .filter { it.basename.matches(Regex("""style\d+\.bin""")) }
                    .map { it.basename },
            ).firstOrNull() ?: return@firstNotNullOfOrNull null
            FaceRecordParser.widgetGuides(container.entryByBasename(style))
                .firstOrNull { it.canResize && it.width >= 32 && it.height >= 32 }
                ?.let { Triple(container, style, it) }
        }
        assumeTrue("corpus holds no backgroundless resizable sprite", candidate != null)
        val (pristine, styleName, _) = candidate!!
        val panel = FaceRecordParser.panelSize(pristine.entryByBasename(styleName))
        val withBackground = StructuralEditor.addBackgrounds(
            source = pristine,
            entryBasenames = listOf(styleName),
            width = panel.width,
            height = panel.height,
            argb = IntArray(panel.width * panel.height) { 0xFF102030.toInt() },
        ).container
        val target = FaceRecordParser.widgetGuides(withBackground.entryByBasename(styleName))
            .first { it.canResize && it.width >= 32 && it.height >= 32 }

        fun resize(source: Fit3Container, width: Int, height: Int) =
            StructuralEditor.resizeSprite(
                source = source,
                entryBasenames = listOf(styleName),
                sequenceId = target.sequenceId,
                width = width,
                height = height,
                pristine = pristine,
            ).container

        val direct = resize(withBackground, 96, 96)
        val shrunk = resize(withBackground, 40, 40)
        val roundTrip = resize(shrunk, 96, 96)

        val expected = framePixels(direct, styleName, target.sequenceId)
        val actual = framePixels(roundTrip, styleName, target.sequenceId)
        assertTrue("no frames read back", expected.isNotEmpty())
        expected.indices.forEach { frame ->
            val lost = expected[frame].indices.count { expected[frame][it] != actual[frame][it] }
            assertTrue(
                "frame $frame lost $lost of ${expected[frame].size} bytes to the round trip",
                lost == 0,
            )
        }
        // And the background it was given is still exactly the background.
        val background = requireNotNull(
            FaceRecordParser.backgroundImage(roundTrip.entryByBasename(styleName)),
        )
        assertTrue(background.width == panel.width && background.height == panel.height)
    }

    /**
     * A shrunk sprite can be taken back to exactly the extent its face shipped, and the
     * container comes back to the size the store shipped with it.
     *
     * This is the whole reason the bound is [dev.fitface.studio.core.model.spriteResizeLimit]
     * and not a flat 128: face `00022`'s hour digits are 114×136, and a digit shrunk from
     * there used to be stuck — Larger could not reach its own artwork again. Restoring is
     * safe precisely because it is the shipped geometry: resampling to the original
     * dimensions rewrites each frame record at its original length, so the container's size
     * is the one the watch already accepts. Growing *past* the shipped extent is what stays
     * capped, and this asserts that too.
     */
    @Test
    fun aShrunkSpriteCanBeRestoredToTheExtentItShipped() {
        val candidate = containers.firstNotNullOfOrNull { path ->
            val container = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@firstNotNullOfOrNull null
            container.entries
                .filter { it.basename.matches(Regex("""style\d+\.bin""")) }
                .firstNotNullOfOrNull { style ->
                    FaceRecordParser.widgetGuides(style)
                        .firstOrNull {
                            // Frames larger than the growth ceiling: the case that was stuck.
                            it.canResize && it.height > SPRITE_RESIZE_CEILING
                        }
                        ?.let { Triple(container, style.basename, it) }
                }
        }
        assumeTrue("corpus holds no oversized resizable sprite", candidate != null)
        val (pristine, styleName, target) = candidate!!

        fun resize(source: Fit3Container, width: Int, height: Int) =
            StructuralEditor.resizeSprite(
                source = source,
                entryBasenames = listOf(styleName),
                sequenceId = target.sequenceId,
                width = width,
                height = height,
                pristine = pristine,
            ).container

        val shrunk = resize(pristine, target.width / 2, target.height / 2)
        assertTrue(shrunk.fileSize < pristine.fileSize)

        val restored = resize(shrunk, target.width, target.height)
        val guide = FaceRecordParser.widgetGuides(restored.entryByBasename(styleName))
            .single { it.type == 3 && it.sequenceId == target.sequenceId }
        assertEquals(target.width, guide.width)
        assertEquals(target.height, guide.height)
        // Same dimensions mean same record lengths, so the container is its shipped size
        // again — which is what makes restoring affordable under the 4 MiB ceiling.
        assertEquals(pristine.fileSize, restored.fileSize)
        assertTrue(restored.validate().isValid)

        // One pixel past what it shipped is refused, because that is growth the shipped
        // container never had to hold.
        assertThrows(Fit3FormatException::class.java) {
            resize(shrunk, target.width + 1, target.height + 1)
        }
    }

    private fun framePixels(
        container: Fit3Container,
        styleName: String,
        sequenceId: Int,
    ): List<ByteArray> {
        val entry = container.entryByBasename(styleName)
        val images = FaceRecordParser.scanImages(entry)
        val first = images.first().recordOffset
        val byRelative = images.associateBy { (it.recordOffset - first).toLong() }
        val record = FaceRecordParser.scanWidgets(entry).single {
            it.widgetType == 3 && it.sequenceId == sequenceId
        }
        val frameCount = (record.unknown20 and 0xFF_FFFFL)
            .coerceIn(0L, record.words.size.toLong())
            .toInt()
        return record.words.take(frameCount).mapNotNull { word ->
            byRelative[word]?.let { image ->
                entry.data.copyOfRange(
                    image.pixelOffset,
                    image.pixelOffset + image.pixelDataSize,
                )
            }
        }
    }
}
