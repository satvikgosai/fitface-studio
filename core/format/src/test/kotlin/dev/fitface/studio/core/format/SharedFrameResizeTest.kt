package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Resizing a Sprite resizes its whole glyph pool, and never changes the frame count.
 *
 * Faces share frames: on face `00022` the hour's tens digit points at frames 2–4 and
 * its units digit at 2–11. Rewriting only the tens digit's three records left the units
 * digit drawing three small glyphs and seven large ones, its box still reporting the
 * largest — a raster-backed extent is the max over its frames. 740 of the corpus's 859
 * resizable sprites share frames, so refusing the edit would have removed resize from
 * most of the catalogue.
 *
 * Giving the sprite a private copy of its frames fixed the picture but produced a
 * container the watch installs and then ignores: appending image records is not
 * something the firmware accepts, and the analyzer confirms the bytes are otherwise
 * sound. So the edit closes over the pool instead and rewrites those records **in
 * place** — the widgets sharing a pool resize together, and the image count stays
 * byte-for-byte what the face shipped with. These tests pin both halves.
 */
class SharedFrameResizeTest {
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
    fun resizingASharedSpriteMovesThePoolAndKeepsTheFrameCount() {
        val failures = mutableListOf<String>()
        var checkedShared = 0

        containers.forEach { path ->
            val face = path.fileName.toString()
            val original = runCatching { Fit3Container.parse(Files.readAllBytes(path)) }
                .getOrNull() ?: return@forEach
            original.entries
                .filter { it.basename.matches(STYLE_ENTRY) }
                .forEach inner@{ style ->
                    val target = FaceRecordParser.widgetGuides(style)
                        .firstOrNull { it.canResize && it.width >= 8 && it.height >= 8 }
                        ?: return@inner
                    val before = frameSizes(style)
                    val mine = frameIndices(style, target.sequenceId)
                    val owners = owners(style)
                    if (mine.none { owners[it].orEmpty().size > 1 }) return@inner

                    val width = (target.width / 2).coerceAtLeast(1)
                    val height = (target.height / 2).coerceAtLeast(1)
                    val edited = runCatching {
                        StructuralEditor.resizeSprite(
                            source = original,
                            entryBasenames = listOf(style.basename),
                            sequenceId = target.sequenceId,
                            width = width,
                            height = height,
                            pristine = original,
                        ).container
                    }.getOrNull() ?: return@inner
                    checkedShared++

                    val editedStyle = edited.entryByBasename(style.basename)
                    val after = frameSizes(editedStyle)

                    // The watch ignores a container whose image count changed, so this is
                    // the invariant that matters most.
                    if (after.size != before.size) {
                        failures += "$face/${style.basename}: frame count went " +
                            "${before.size} → ${after.size}"
                        return@inner
                    }
                    // The sprite keeps the very records it had — no copies, no repointing.
                    if (frameIndices(editedStyle, target.sequenceId) != mine) {
                        failures += "$face/${style.basename}: the sprite was repointed"
                    }

                    val pool = closureOf(style, target.sequenceId)
                    pool.forEach { index ->
                        if (after[index] != width to height) {
                            failures += "$face/${style.basename}: pool frame $index is " +
                                "${after[index]}, expected ${width}×$height"
                        }
                    }
                    before.indices.filterNot { it in pool }.forEach { index ->
                        if (after[index] != before[index]) {
                            failures += "$face/${style.basename}: frame $index is outside " +
                                "the pool but changed ${before[index]} → ${after[index]}"
                        }
                    }

                    // A widget drawing from the pool resizes; everything else does not.
                    val guides = FaceRecordParser.widgetGuides(editedStyle)
                    FaceRecordParser.widgetGuides(style).forEach { untouched ->
                        val now = guides.single { it.globalIndex == untouched.globalIndex }
                        val inPool = framesOf(style, untouched.globalIndex).any { it in pool }
                        val changed = now.width != untouched.width || now.height != untouched.height
                        if (!inPool && changed) {
                            failures += "$face/${style.basename}: widget " +
                                "#${untouched.globalIndex} is outside the pool but changed " +
                                "${untouched.width}×${untouched.height} → ${now.width}×${now.height}"
                        }
                    }
                }
        }

        assumeTrue("corpus holds no shared-frame sprite resize", checkedShared > 0)
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    /** Face 00022 is the one in the report: widget 2 and widget 3 share frames 2–4. */
    @Test
    fun faceOoo22HourDigitsResizeAsOnePool() {
        val path = containers.firstOrNull { it.fileName.toString().contains("00022") }
        assumeTrue("face 00022 not in corpus", path != null)
        val original = Fit3Container.parse(Files.readAllBytes(path!!))
        val style = original.entryByBasename("style0.bin")

        val tens = FaceRecordParser.widgetGuides(style).single { it.globalIndex == 2 }
        val units = FaceRecordParser.widgetGuides(style).single { it.globalIndex == 3 }
        assertEquals("expected the shipped 114×136 hour digits", 114, tens.width)
        assertEquals(136, tens.height)
        val shared = frameIndices(style, tens.sequenceId)
            .intersect(frameIndices(style, units.sequenceId).toSet())
        assertTrue("widgets 2 and 3 must share frames", shared.isNotEmpty())

        val framesBefore = FaceRecordParser.scanImages(style).size
        val edited = StructuralEditor.resizeSprite(
            source = original,
            entryBasenames = listOf("style0.bin"),
            sequenceId = tens.sequenceId,
            width = 57,
            height = 68,
            pristine = original,
        ).container
        val editedStyle = edited.entryByBasename("style0.bin")
        val guides = FaceRecordParser.widgetGuides(editedStyle)

        assertEquals(
            "the frame count is what the watch refuses to see change",
            framesBefore,
            FaceRecordParser.scanImages(editedStyle).size,
        )
        assertEquals("the selected digit resizes", 57, guides.single { it.globalIndex == 2 }.width)
        assertEquals(68, guides.single { it.globalIndex == 2 }.height)
        // Its neighbour shares the pool, so it moves with it — and, crucially, ends up
        // wholly at the new size rather than half-converted.
        assertEquals(
            "the neighbour sharing the pool resizes too",
            57,
            guides.single { it.globalIndex == 3 }.width,
        )
        assertEquals(68, guides.single { it.globalIndex == 3 }.height)
        val poolSizes = closureOf(style, tens.sequenceId)
            .mapTo(mutableSetOf()) { frameSizes(editedStyle)[it] }
        assertEquals("the whole pool is one size", setOf(57 to 68), poolSizes)
        // The battery sprite draws from its own frames and must be untouched.
        assertEquals(30, guides.single { it.sequenceId == 37 }.width)
        assertEquals(34, guides.single { it.sequenceId == 37 }.height)
        assertTrue(edited.validate().errors.isEmpty())
    }

    /** Repeated resizes stay in place; nothing accumulates. */
    @Test
    fun repeatedResizesNeverChangeTheFrameCount() {
        val path = containers.firstOrNull { it.fileName.toString().contains("00022") }
        assumeTrue("face 00022 not in corpus", path != null)
        val original = Fit3Container.parse(Files.readAllBytes(path!!))
        val style = original.entryByBasename("style0.bin")
        val tens = FaceRecordParser.widgetGuides(style).single { it.globalIndex == 2 }
        val framesBefore = FaceRecordParser.scanImages(style).size

        var container = original
        listOf(100 to 120, 90 to 108, 80 to 96, 70 to 84).forEach { (width, height) ->
            container = StructuralEditor.resizeSprite(
                source = container,
                entryBasenames = listOf("style0.bin"),
                sequenceId = tens.sequenceId,
                width = width,
                height = height,
                pristine = original,
            ).container
            assertEquals(
                "frame count must never move",
                framesBefore,
                FaceRecordParser.scanImages(container.entryByBasename("style0.bin")).size,
            )
        }
        assertTrue(container.validate().errors.isEmpty())
    }

    /**
     * The container must not grow beyond what the new pixels need. Copy-on-write added
     * 279 KB to face 00022 even when *shrinking*, because the originals stayed behind.
     */
    @Test
    fun shrinkingASpriteShrinksTheContainer() {
        val path = containers.firstOrNull { it.fileName.toString().contains("00022") }
        assumeTrue("face 00022 not in corpus", path != null)
        val original = Fit3Container.parse(Files.readAllBytes(path!!))
        val tens = FaceRecordParser.widgetGuides(original.entryByBasename("style0.bin"))
            .single { it.globalIndex == 2 }

        val edited = StructuralEditor.resizeSprite(
            source = original,
            entryBasenames = listOf("style0.bin"),
            sequenceId = tens.sequenceId,
            width = 57,
            height = 68,
            pristine = original,
        ).container
        assertTrue(
            "shrinking must make the container smaller, not larger " +
                "(${original.fileSize} → ${edited.fileSize})",
            edited.fileSize < original.fileSize,
        )
    }

    private fun byRelative(entry: ContainerEntry): Map<Long, ImageRecord> {
        val images = FaceRecordParser.scanImages(entry)
        val first = images.first().recordOffset
        return images.associateBy { (it.recordOffset - first).toLong() }
    }

    private fun frameIndices(entry: ContainerEntry, sequenceId: Int): List<Int> {
        val relative = byRelative(entry)
        val record = FaceRecordParser.scanWidgets(entry).single {
            it.widgetType == WIDGET_SPRITE && it.sequenceId == sequenceId
        }
        return FaceRecordParser.referencedImages(record, relative).map(ImageRecord::index)
    }

    private fun framesOf(entry: ContainerEntry, globalIndex: Int): List<Int> {
        val relative = byRelative(entry)
        val record = FaceRecordParser.scanWidgets(entry).single { it.globalIndex == globalIndex }
        return FaceRecordParser.referencedImages(record, relative).map(ImageRecord::index)
    }

    private fun closureOf(entry: ContainerEntry, sequenceId: Int): Set<Int> {
        val relative = byRelative(entry)
        val records = FaceRecordParser.scanWidgets(entry)
        val target = records.single {
            it.widgetType == WIDGET_SPRITE && it.sequenceId == sequenceId
        }
        return FaceRecordParser.sharedFrameClosure(target, records, relative)
    }

    private fun frameSizes(entry: ContainerEntry): List<Pair<Int, Int>> =
        FaceRecordParser.scanImages(entry).map { it.width to it.height }

    private fun owners(entry: ContainerEntry): Map<Int, Set<Int>> {
        val relative = byRelative(entry)
        val result = mutableMapOf<Int, MutableSet<Int>>()
        FaceRecordParser.scanWidgets(entry).forEach { record ->
            FaceRecordParser.referencedImages(record, relative).forEach { image ->
                result.getOrPut(image.index) { mutableSetOf() } += record.globalIndex
            }
        }
        return result
    }

    private companion object {
        val STYLE_ENTRY = Regex("""style\d+\.bin""")
    }
}
