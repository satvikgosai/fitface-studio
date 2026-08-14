package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Which styles a background replacement lands on, over every container in the corpus.
 *
 * A style is not obliged to carry a full-panel raster — 14 of the 99 catalogue faces
 * have none in any style, and `00011` and `00108` have some styles with one and some
 * without. Refusing the whole edit whenever any style lacked one meant those two faces
 * could never have their background replaced, and the editor reported it as "this face
 * has no background image" while three of `00011`'s four styles plainly do.
 *
 * So the rule is the one the widget edits already follow: write every style that
 * carries the thing, leave the others exactly as they were, fail only when none does.
 * A bare style cannot be given a background — that needs an extra image record, and
 * the watch ignores a container whose image count changed.
 */
class BackgroundReplacementSweepTest {
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
    fun replacementWritesEveryStyleWithAPanelRasterAndTouchesNoOther() {
        val failures = mutableListOf<String>()
        var replaced = 0
        var refused = 0
        containers.forEach { path ->
            val face = path.fileName.toString()
            val source = Fit3Container.parse(Files.readAllBytes(path))
            val styles = styleEntries(source)
            val withBackground = styles.filter { FaceRecordParser.backgroundImage(it) != null }
            if (withBackground.isEmpty()) {
                refused++
                val error = runCatching {
                    val panel = FaceRecordParser.panelSize(styles.first())
                    FaceEditor.replaceBackgrounds(
                        source,
                        panel.width,
                        panel.height,
                        IntArray(panel.width * panel.height) { FLAT_FILL },
                    )
                }.exceptionOrNull()
                if (error !is Fit3FormatException) {
                    failures += "$face: no style carries a background but the edit " +
                        "returned ${error ?: "successfully"}"
                }
                return@forEach
            }
            replaced++
            val panel = FaceRecordParser.backgroundImage(withBackground.first())!!
            val edit = try {
                FaceEditor.replaceBackgrounds(
                    source,
                    panel.width,
                    panel.height,
                    IntArray(panel.width * panel.height) { FLAT_FILL },
                )
            } catch (error: Exception) {
                failures += "$face: ${error::class.simpleName} ${error.message}"
                return@forEach
            }
            if (edit.changedStyles != withBackground.map { it.basename }) {
                failures += "$face: wrote ${edit.changedStyles} for styles carrying a " +
                    "background: ${withBackground.map { it.basename }}"
            }
            if (edit.container.fileSize != source.fileSize) {
                failures += "$face: file size moved from ${source.fileSize} to " +
                    "${edit.container.fileSize}"
            }
            styles.forEach { style ->
                val after = edit.container.entryByBasename(style.basename)
                val hasBackground = style.basename in edit.changedStyles
                if (!hasBackground) {
                    if (!style.data.contentEquals(after.data)) {
                        failures += "$face/${style.basename}: a style with no background " +
                            "raster was rewritten"
                    }
                    return@forEach
                }
                // Every style that took the fill has to *show* it, and still hold the
                // same number of image records: a replacement is a payload rewrite in
                // place, never a record the watch could count differently.
                val raster = FaceRecordParser.backgroundImage(after)
                if (raster == null) {
                    failures += "$face/${style.basename}: lost its background raster"
                    return@forEach
                }
                val before = FaceRecordParser.decodeImage(
                    style,
                    FaceRecordParser.backgroundImage(style)!!,
                )
                val frame = FaceRecordParser.decodeImage(after, raster)
                val colours = frame.argb.map { it and 0x00FF_FFFF }.distinct()
                if (colours.size != 1) {
                    failures += "$face/${style.basename}: background is not the flat fill " +
                        "it was replaced with (${colours.size} colours)"
                }
                // The alpha plane of an `IMAGE_RGB565_ALPHA` background is the panel's
                // own rounded-corner mask — 656 of face 00003's 102,912 pixels, all of
                // them in the corner arcs — so a replacement writes colour only and
                // leaves it exactly as it was. Filling it in would square off the
                // corners of every face whose background carries one.
                //
                // The indexed path is the exception, and deliberately so: its whole
                // palette is re-emitted opaque, because a re-quantised background must
                // not become accidentally transparent. Face 00002 is the only indexed
                // background in the catalogue and it loses its corner mask this way.
                if (!raster.isIndexed) {
                    val staleAlpha = frame.argb.indices.count { index ->
                        (frame.argb[index] ushr 24) != (before.argb[index] ushr 24)
                    }
                    if (staleAlpha != 0) {
                        failures += "$face/${style.basename}: $staleAlpha pixels changed " +
                            "transparency; the panel mask has to survive a replacement"
                    }
                }
                assertEquals(
                    "$face/${style.basename} image record count",
                    FaceRecordParser.scanImages(style).size,
                    FaceRecordParser.scanImages(after).size,
                )
            }
            assertTrue("$face: edit does not validate", edit.container.validate().isValid)
            assertNotEquals("$face: edit changed nothing", 0, edit.changedPayloadBytes)
        }
        assertTrue(
            "${failures.size} of ${containers.size} containers failed:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
        // Both branches have to be exercised or this sweep proves only one of them.
        assertTrue("no corpus face carries a replaceable background", replaced > 0)
        assertTrue("no corpus face lacks one, so the refusal is unproven", refused > 0)
    }

    /**
     * `00011` style0 and `00108` styles 0–3 paint straight onto black while their
     * sibling styles carry a full-panel background. These are the faces the old
     * all-or-nothing rule locked out, so they are named rather than left to the sweep.
     */
    @Test
    fun aFaceWhoseFirstStyleIsBareStillTakesABackgroundOnTheRest() {
        val mixed = containers.mapNotNull { path ->
            val source = Fit3Container.parse(Files.readAllBytes(path))
            val styles = styleEntries(source)
            val bare = styles.filter { FaceRecordParser.backgroundImage(it) == null }
            val withBackground = styles.filter { FaceRecordParser.backgroundImage(it) != null }
            if (bare.isEmpty() || withBackground.isEmpty()) return@mapNotNull null
            Triple(path.fileName.toString(), source, withBackground.map { it.basename })
        }
        assumeTrue("corpus holds no partially backed face", mixed.isNotEmpty())

        mixed.forEach { (face, source, expected) ->
            val panel = FaceRecordParser.backgroundImage(
                source.entryByBasename(expected.first()),
            )!!
            val edit = FaceEditor.replaceBackgrounds(
                source,
                panel.width,
                panel.height,
                IntArray(panel.width * panel.height) { FLAT_FILL },
            )

            assertEquals(face, expected, edit.changedStyles)
            assertTrue(face, edit.container.validate().isValid)
            assertEquals(face, source.fileSize, edit.container.fileSize)
        }
    }

    @Test
    fun tintSkipsABareStyleInsteadOfRefusingTheWholeFace() {
        val mixed = containers.firstNotNullOfOrNull { path ->
            val source = Fit3Container.parse(Files.readAllBytes(path))
            val styles = styleEntries(source)
            val bare = styles.filter { FaceRecordParser.backgroundImage(it) == null }
            val withBackground = styles.filter { FaceRecordParser.backgroundImage(it) != null }
            if (bare.isEmpty() || withBackground.isEmpty()) return@firstNotNullOfOrNull null
            Triple(source, bare.map { it.basename }, withBackground.map { it.basename })
        }
        assumeTrue("corpus holds no partially backed face", mixed != null)
        val (source, bare, withBackground) = mixed!!

        val edit = FaceEditor.tintBackgrounds(source, 0, 255, 255)

        assertEquals(withBackground, edit.changedStyles)
        bare.forEach { name ->
            assertTrue(
                "$name was rewritten by a tint",
                source.entryByBasename(name).data
                    .contentEquals(edit.container.entryByBasename(name).data),
            )
        }
        assertTrue(edit.container.validate().isValid)
    }

    private fun styleEntries(source: Fit3Container): List<ContainerEntry> =
        source.entries.filter { it.basename.matches(Regex("""style\d+\.bin""")) }

    private companion object {
        /**
         * Flat so the assertion can be "every pixel of the background is the same
         * colour" — an indexed style re-quantises its palette and an RGB565 one rounds
         * each channel, so a gradient would not survive either path exactly.
         */
        const val FLAT_FILL = 0xFF1188CC.toInt()
    }
}
