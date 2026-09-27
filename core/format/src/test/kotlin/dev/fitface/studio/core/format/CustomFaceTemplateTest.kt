package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The "start a custom face" recipe, run against the real face `00006`.
 *
 * Its design note made claims about this face from an independent analyser — nine records,
 * nothing anchored, eleven rasters all belonging to the clock, the styles identical once
 * stripped. None of that had been through `StructuralEditor` until this test, which is the
 * only reason to believe the recipe does what the note says.
 */
class CustomFaceTemplateTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    @Test
    fun itIsOneStyleStrippedToItsClockAndNothingElseChanges() {
        val source = face(CustomFaceTemplate.FACE_ID)
        val template = CustomFaceTemplate.strip(source)
        assertEquals(emptyList<ValidationIssue>(), template.validate().issues)
        // One style, counted the same way everywhere the watch counts one.
        val styles = FaceResources.selectableStyles(template)
        assertEquals(listOf("style0.bin"), styles.map { it.basename })
        assertEquals(1, SettingRecord.parse(template.entryByBasename("setting.bin")).styleCount)
        assertEquals(0, SettingRecord.parse(template.entryByBasename("setting.bin")).defaultStyle)
        assertEquals(1, PreviewStream.recordCount(template.entryByBasename("preview.bin")))
        val entry = styles.single()
        val original = source.entryByBasename(entry.basename)
        val kept = FaceRecordParser.scanWidgets(entry)
        assertEquals(listOf(WIDGET_SPRITE, WIDGET_SPRITE, WIDGET_SPRITE, WIDGET_SPRITE, WIDGET_STATIC),
            kept.map { it.widgetType })
        // The clock is the shipped clock: same records bar the colon's renumbered index,
        // and every raster left exactly where it was.
        val before = FaceRecordParser.scanWidgets(original).filter { it.globalIndex in listOf(0, 1, 2, 3, 6) }
        before.zip(kept).forEach { (b, a) ->
            assertEquals(b.widgetType, a.widgetType)
            assertEquals(b.sequenceId, a.sequenceId)
            assertEquals(b.x, a.x)
            assertEquals(b.y, a.y)
            assertEquals(b.words, a.words)
        }
        fun images(e: ContainerEntry) = e.data.copyOfRange(StyleHeader.parse(e).storedImageOffset, e.data.size)
        assertArrayEquals(images(original), images(entry))
        assertEquals(11, FaceRecordParser.scanImages(entry).size)
        // The first picker frame is the shipped one until the build redraws it.
        assertArrayEquals(
            source.entryByBasename("preview.bin").data.copyOfRange(0, PreviewStream.RECORD_STRIDE),
            template.entryByBasename("preview.bin").data,
        )
        // Always-on already is just the clock, and is not touched; nor is anything shared.
        listOf("aod.bin", "font_0.bin", "font_1.bin").forEach { name ->
            assertArrayEquals(name, source.entryByBasename(name).data, template.entryByBasename(name).data)
        }
        assertEquals(source.entries.map { it.path }.filterNot { Regex("style[123]\\.bin$").containsMatchIn(it) },
            template.entries.map { it.path })
        assertTrue(template.fileSize < source.fileSize / 2)
    }

    /**
     * The point of the template: room. Its style takes a full-panel background — the first
     * thing a blank canvas invites — with most of the watch's 4 MiB still free for imports.
     */
    @Test
    fun itTakesABackgroundWithRoomToSpare() {
        val template = CustomFaceTemplate.strip(face(CustomFaceTemplate.FACE_ID))
        val styles = FaceResources.selectableStyles(template).map { it.basename }
        val fit = StructuralEditor.backgroundStylesThatFit(template, styles, styles.first())
        assertEquals(listOf("style0.bin"), fit)
        val panel = FaceRecordParser.panelSize(template.entryByBasename(styles.first()))
        val withBackgrounds = StructuralEditor.addBackgrounds(template, styles, panel.width, panel.height,
            IntArray(panel.width * panel.height) { 0xFF336699.toInt() }).container
        assertTrue(WATCH_CONTAINER_BYTE_CEILING - withBackgrounds.fileSize > 3 * 1024 * 1024)
        assertEquals(emptyList<ValidationIssue>(), withBackgrounds.validate().errors)
    }

    /** A face that is not the one the recipe was written for is refused, not mangled. */
    @Test
    fun anyOtherShapeIsRefused() {
        val error = runCatching { CustomFaceTemplate.strip(face("00106")) }.exceptionOrNull()
        assertTrue("got $error", error is Fit3TemplateMismatchException)
    }
}

/**
 * Keeping the first styles of a face, which the template does and nothing else yet.
 *
 * Every one of the 99 catalogue containers has three counts agree — its `styleN.bin` entries,
 * `setting.bin +0x34`, and `preview.bin`'s frames — so all three move together, and the
 * styles that stay are byte for byte what they were.
 */
class KeepFirstStylesTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    @Test
    fun everyCountMovesTogetherAndTheKeptStylesAreUntouched() {
        listOf("00001" to 1, "00046" to 2, "00024" to 3, "00016" to 1).forEach { (id, count) ->
            val source = face(id)
            val kept = StructuralEditor.keepFirstStyles(source, count).container
            assertEquals("$id", emptyList<ValidationIssue>(), kept.validate().issues)
            val styles = FaceResources.selectableStyles(kept)
            assertEquals((0 until count).map { "style$it.bin" }, styles.map { it.basename })
            assertEquals(count, SettingRecord.parse(kept.entryByBasename("setting.bin")).styleCount)
            assertEquals(count, PreviewStream.recordCount(kept.entryByBasename("preview.bin")))
            styles.forEach { assertArrayEquals(source.entryByBasename(it.basename).data, it.data) }
            FaceResources.aodOrNull(source)?.let {
                assertArrayEquals(it.data, kept.entryByBasename(it.basename).data)
            }
            assertArrayEquals(kept.toByteArray(), Fit3Container.parse(kept.toByteArray()).toByteArray())
        }
    }

    @Test
    fun keepingNoneOrMoreThanThereAreIsRefused() {
        val source = face("00001")
        listOf(0, 5).forEach { count ->
            assertTrue(runCatching { StructuralEditor.keepFirstStyles(source, count) }.exceptionOrNull()
                is Fit3FormatException)
        }
    }
}
