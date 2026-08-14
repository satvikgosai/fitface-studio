package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The "give this face a background" path, swept over every corpus face that has none.
 *
 * Confirmed on an SM-R390: a container that gains a panel background and the Static that
 * draws it installs and renders. What these assertions add is that the bytes are sound
 * for the other 15 faces too — each parses, validates, round-trips, keeps every widget
 * drawing the same artwork it drew before, and ends up looking like a style that shipped
 * with a background.
 */
class AddBackgroundTest {
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

    private fun styles(container: Fit3Container) =
        container.entries.filter { it.basename.matches(Regex("""style\d+\.bin""")) }

    private fun fill(width: Int, height: Int) = IntArray(width * height) { index ->
        // A gradient, so a raster written at the wrong offset cannot pass by looking flat.
        val x = index % width
        val y = index / width
        (0xFF shl 24) or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or 0x40
    }

    @Test
    fun everyBackgroundlessFaceCanBeGivenOneWithoutDisturbingWhatItAlreadyDraws() {
        var faces = 0
        var trimmed = 0
        var refused = 0
        val failures = mutableListOf<String>()
        containers.forEach { path ->
            val face = path.fileName.toString()
            val source = Fit3Container.parse(Files.readAllBytes(path))
            val allBare = styles(source).filter { FaceRecordParser.backgroundImage(it) == null }
            if (allBare.isEmpty()) return@forEach
            faces++
            // Not every backgroundless face has room for a raster in every style — see
            // WATCH_CONTAINER_BYTE_CEILING. The edit is offered for the styles that fit.
            val targets = StructuralEditor.backgroundStylesThatFit(
                source = source,
                entryBasenames = allBare.map { it.basename },
            )
            if (targets.size < allBare.size) trimmed++
            if (targets.isEmpty()) {
                refused++
                return@forEach
            }
            val bare = allBare.filter { it.basename in targets }
            val panel = FaceRecordParser.panelSize(bare.first())
            val edit = try {
                StructuralEditor.addBackgrounds(
                    source = source,
                    entryBasenames = bare.map { it.basename },
                    width = panel.width,
                    height = panel.height,
                    argb = fill(panel.width, panel.height),
                )
            } catch (error: Exception) {
                failures += "$face: ${error::class.simpleName} ${error.message}"
                return@forEach
            }
            if (edit.changedStyles != bare.map { it.basename }) {
                failures += "$face: wrote ${edit.changedStyles}, expected ${bare.map { it.basename }}"
            }
            // One raster and one Static per style, and nothing else.
            val expectedGrowth = bare.size * (IMAGE_HEADER_SIZE + panel.width * panel.height * 2 + 4 + 40)
            if (edit.sizeDelta != expectedGrowth) {
                failures += "$face: grew by ${edit.sizeDelta}, expected $expectedGrowth"
            }
            if (edit.container.fileSize > WATCH_CONTAINER_BYTE_CEILING) {
                failures += "$face: ${edit.container.fileSize} bytes is over the ceiling"
            }
            if (!edit.container.validate().isValid) {
                failures += "$face: edited container does not validate"
            }
            // Round trip: the parser has to agree with itself about the new layout.
            val reparsed = Fit3Container.parse(edit.container.toByteArray())
            if (!reparsed.toByteArray().contentEquals(edit.container.toByteArray())) {
                failures += "$face: does not re-emit byte-identically"
            }
            bare.forEach { original ->
                val after = reparsed.entryByBasename(original.basename)
                val background = FaceRecordParser.backgroundImage(after)
                if (background == null) {
                    failures += "$face/${original.basename}: has no background afterwards"
                    return@forEach
                }
                // Appended, so it is the last record and every original raster is
                // exactly where it was.
                val imagesAfter = FaceRecordParser.scanImages(after)
                if (background.index != imagesAfter.lastIndex ||
                    background.format != IMAGE_RGB565 ||
                    background.width != panel.width ||
                    background.height != panel.height
                ) {
                    failures += "$face/${original.basename}: the background is not the " +
                        "panel-sized RGB565 raster at the end of the section"
                }
                val imagesBefore = FaceRecordParser.scanImages(original)
                imagesBefore.forEach { was ->
                    val now = imagesAfter[was.index]
                    if (was.recordOffset - imagesBefore.first().recordOffset !=
                        now.recordOffset - imagesAfter.first().recordOffset ||
                        !original.data.copyOfRange(was.pixelOffset, was.pixelOffset + was.dataSize)
                            .contentEquals(
                                after.data.copyOfRange(now.pixelOffset, now.pixelOffset + now.dataSize),
                            )
                    ) {
                        failures += "$face/${original.basename}: raster ${was.index} moved " +
                            "or changed"
                    }
                }
                val widgetsBefore = FaceRecordParser.scanWidgets(original)
                val widgetsAfter = FaceRecordParser.scanWidgets(after)
                if (widgetsAfter.size != widgetsBefore.size + 1) {
                    failures += "$face/${original.basename}: widget table did not grow by one"
                    return@forEach
                }
                val guides = FaceRecordParser.widgetGuides(after)
                if (guides.first().placement != dev.fitface.studio.core.model.WidgetPlacement.BACKGROUND) {
                    failures += "$face/${original.basename}: widget 0 is not the background layer"
                }
                // Everything that was drawable still is, at the same rectangle.
                val drawableBefore = FaceRecordParser.widgetGuides(original)
                    .map { "${it.type}:${it.sequenceId}:${it.x}:${it.y}:${it.width}x${it.height}" }
                val drawableAfter = guides.drop(1)
                    .map { "${it.type}:${it.sequenceId}:${it.x}:${it.y}:${it.width}x${it.height}" }
                if (drawableBefore != drawableAfter) {
                    failures += "$face/${original.basename}: widget geometry changed"
                }
            }
            // Styles that already had a background — and styles the size ceiling made the
            // edit skip — are not this path's business.
            styles(source).filterNot { it in bare }.forEach { untouched ->
                if (!untouched.data.contentEquals(
                        reparsed.entryByBasename(untouched.basename).data,
                    )
                ) {
                    failures += "$face/${untouched.basename}: an untouched style was rewritten"
                }
            }
        }
        assertTrue(
            "${failures.size} failures:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
        // 14 faces have no background in any style, 00011 and 00108 in some of theirs.
        assertEquals(16, faces)
        // 00007, 00019, 00021, 00024 and 00104 lose some styles to the size ceiling and
        // 00022 loses all three, which is what a refusal here means.
        assertEquals(6, trimmed)
        assertEquals(1, refused)
    }

    /**
     * Face `00008` rather than `00022`: `00022` is 4,117,664 bytes and has no room for a
     * raster in any style, which [aFaceWithNoRoomForABackgroundIsRefused] is about.
     */
    @Test
    fun theAddedBackgroundIsTheImageThatWasHandedIn() {
        val path = containers.single { it.fileName.toString().contains("00008") }
        val source = Fit3Container.parse(Files.readAllBytes(path))
        val panel = FaceRecordParser.panelSize(source.entryByBasename("style0.bin"))
        val argb = fill(panel.width, panel.height)

        val edit = StructuralEditor.addBackgrounds(
            source = source,
            entryBasenames = listOf("style0.bin"),
            width = panel.width,
            height = panel.height,
            argb = argb,
        )

        val entry = edit.container.entryByBasename("style0.bin")
        val images = FaceRecordParser.scanImages(entry)
        val background = requireNotNull(FaceRecordParser.backgroundImage(entry))
        val decoded = FaceRecordParser.decodeImage(entry, background)
        assertEquals(images.lastIndex, background.index)
        assertEquals(panel.width, decoded.width)
        assertEquals(panel.height, decoded.height)
        // RGB565 keeps 5/6/5 bits, so a pixel comes back rounded — 0x40 blue decodes as
        // 0x42. Compare the codes the encoder produces, which is the precision the
        // format actually carries.
        fun code(color: Int): Int {
            val red = ((color ushr 16 and 0xFF) * 31 + 127) / 255
            val green = ((color ushr 8 and 0xFF) * 63 + 127) / 255
            val blue = ((color and 0xFF) * 31 + 127) / 255
            return (red shl 11) or (green shl 5) or blue
        }
        val mismatches = argb.indices.count { code(argb[it]) != code(decoded.argb[it]) }
        assertTrue("$mismatches pixels differ beyond RGB565 rounding", mismatches == 0)
        // No alpha to invent: an RGB565 background is opaque everywhere.
        assertTrue(decoded.argb.all { it ushr 24 == 0xFF })
    }

    /**
     * Relative offset `0x0` must still name the raster it always named.
     *
     * Face `00019` draws its day of week and its date with two Value widgets, and both
     * hold `words[3] = words[4] = 0`. Zero is also image 0's own relative offset, so when
     * the background went in at index 0 those words started naming a 256×402 panel raster
     * instead of the 102×132 digit they had always named — and on the watch one of the two
     * stopped drawing while the other kept working. Whether the firmware reads those words
     * as pointers is still unknown, and this test is why it no longer matters: appending
     * the raster leaves every existing offset naming exactly what it named before.
     */
    @Test
    fun everyOriginalOffsetStillNamesTheSameRaster() {
        val path = containers.single { it.fileName.toString().contains("00019") }
        val source = Fit3Container.parse(Files.readAllBytes(path))
        // Two of this face's three styles; the third has no room under the size ceiling.
        val targets = StructuralEditor.backgroundStylesThatFit(
            source = source,
            entryBasenames = styles(source).map { it.basename },
        )
        assertEquals(listOf("style0.bin", "style1.bin"), targets)
        val bare = styles(source).filter { it.basename in targets }
        val panel = FaceRecordParser.panelSize(bare.first())

        val edit = StructuralEditor.addBackgrounds(
            source = source,
            entryBasenames = bare.map { it.basename },
            width = panel.width,
            height = panel.height,
            argb = fill(panel.width, panel.height),
        )

        bare.forEach { original ->
            val after = edit.container.entryByBasename(original.basename)
            fun offsets(entry: ContainerEntry): Map<Long, String> {
                val images = FaceRecordParser.scanImages(entry)
                val start = images.first().recordOffset
                return images.associate { image ->
                    (image.recordOffset - start).toLong() to
                        "${image.width}x${image.height}:${image.format}:" +
                        Crc16.ccittFalse(
                            entry.data,
                            image.pixelOffset,
                            image.pixelOffset + image.dataSize,
                        )
                }
            }
            val was = offsets(original)
            val now = offsets(after)
            was.forEach { (offset, signature) ->
                assertEquals(
                    "${original.basename}: offset $offset changed what it names",
                    signature,
                    now[offset],
                )
            }
            // Only the appended raster is new, and it is the only added offset.
            assertEquals(was.size + 1, now.size)
            // The zero-word Value widgets are byte-identical apart from their index.
            val valuesBefore = FaceRecordParser.scanWidgets(original).filter { it.widgetType == 5 }
            val valuesAfter = FaceRecordParser.scanWidgets(after).filter { it.widgetType == 5 }
            assertEquals(valuesBefore.size, valuesAfter.size)
            valuesBefore.forEachIndexed { index, before ->
                val after2 = valuesAfter[index]
                assertEquals(before.sequenceId, after2.sequenceId)
                assertEquals(before.words, after2.words)
                assertEquals(before.unknown20, after2.unknown20)
                assertEquals(before.globalIndex + 1, after2.globalIndex)
            }
        }
    }

    @Test
    fun aStyleThatAlreadyHasABackgroundIsRefused() {
        val path = containers.first { it.fileName.toString().contains("00046") }
        val source = Fit3Container.parse(Files.readAllBytes(path))
        val panel = FaceRecordParser.panelSize(source.entryByBasename("style0.bin"))

        assertThrows(Fit3FormatException::class.java) {
            StructuralEditor.addBackgrounds(
                source = source,
                entryBasenames = listOf("style0.bin"),
                width = panel.width,
                height = panel.height,
                argb = fill(panel.width, panel.height),
            )
        }
    }

    @Test
    fun aBackgroundThatIsNotPanelSizedIsRefused() {
        val path = containers.single { it.fileName.toString().contains("00008") }
        val source = Fit3Container.parse(Files.readAllBytes(path))

        assertThrows(Fit3FormatException::class.java) {
            StructuralEditor.addBackgrounds(
                source = source,
                entryBasenames = listOf("style0.bin"),
                width = 200,
                height = 300,
                argb = fill(200, 300),
            )
        }
    }

    /**
     * The added record is what the firmware is suspected to object to, so the test says
     * so out loud: before the edit the style has no panel raster, after it the image
     * count is one higher. Nothing in the app may claim this is proven.
     */
    @Test
    fun theEditDeliberatelyChangesTheImageRecordCount() {
        val path = containers.single { it.fileName.toString().contains("00008") }
        val source = Fit3Container.parse(Files.readAllBytes(path))
        val before = source.entryByBasename("style0.bin")
        val panel = FaceRecordParser.panelSize(before)
        assertNull(FaceRecordParser.backgroundImage(before))

        val edit = StructuralEditor.addBackgrounds(
            source = source,
            entryBasenames = listOf("style0.bin"),
            width = panel.width,
            height = panel.height,
            argb = fill(panel.width, panel.height),
        )

        val after = edit.container.entryByBasename("style0.bin")
        assertEquals(
            FaceRecordParser.scanImages(before).size + 1,
            FaceRecordParser.scanImages(after).size,
        )
    }

    /**
     * The size rule, on the two faces that taught it.
     *
     * Face `00019` is 3,747,987 bytes. A background in all three of its styles takes it to
     * 4,365,627 — over [WATCH_CONTAINER_BYTE_CEILING] — and on an SM-R390 that container
     * transferred, was accepted, and left the watch showing the old face. Two styles fit,
     * and that edit is the one the app offers. Nothing about the refused bytes is
     * malformed, which is why this is a size check and not a validation error.
     */
    @Test
    fun aBackgroundInMoreStylesThanFitIsRefused() {
        val path = containers.single { it.fileName.toString().contains("00019") }
        val source = Fit3Container.parse(Files.readAllBytes(path))
        val all = styles(source).map { it.basename }
        val panel = FaceRecordParser.panelSize(source.entryByBasename(all.first()))

        val refusal = assertThrows(Fit3FormatException::class.java) {
            StructuralEditor.addBackgrounds(
                source = source,
                entryBasenames = all,
                width = panel.width,
                height = panel.height,
                argb = fill(panel.width, panel.height),
            )
        }
        assertTrue(refusal.message!!.contains("over the"))

        val fitting = StructuralEditor.backgroundStylesThatFit(source, all)
        assertEquals(2, fitting.size)
        val edit = StructuralEditor.addBackgrounds(
            source = source,
            entryBasenames = fitting,
            width = panel.width,
            height = panel.height,
            argb = fill(panel.width, panel.height),
        )
        assertTrue(edit.container.fileSize <= WATCH_CONTAINER_BYTE_CEILING)
        // One more style would not have fitted, which is why it was left out.
        assertTrue(
            edit.container.fileSize + StructuralEditor.addedBackgroundBytes(
                panel.width,
                panel.height,
            ) > WATCH_CONTAINER_BYTE_CEILING,
        )
    }

    /** Face `00022` is 4,117,664 bytes: there is no room for a raster in any style. */
    @Test
    fun aFaceWithNoRoomForABackgroundIsRefused() {
        val path = containers.single { it.fileName.toString().contains("00022") }
        val source = Fit3Container.parse(Files.readAllBytes(path))
        val panel = FaceRecordParser.panelSize(source.entryByBasename("style0.bin"))

        assertEquals(
            emptyList<String>(),
            StructuralEditor.backgroundStylesThatFit(
                source,
                styles(source).map { it.basename },
            ),
        )
        assertThrows(Fit3FormatException::class.java) {
            StructuralEditor.addBackgrounds(
                source = source,
                entryBasenames = listOf("style0.bin"),
                width = panel.width,
                height = panel.height,
                argb = fill(panel.width, panel.height),
            )
        }
    }

    /**
     * The style being edited is the one that gets the background when only one fits: it is
     * the style the install activates and the only one the canvas shows, so a face too
     * large for three is still editable rather than refused.
     */
    @Test
    fun theSelectedStyleIsTheOneThatGetsTheBackgroundWhenOnlyOneFits() {
        val path = containers.single { it.fileName.toString().contains("00021") }
        val source = Fit3Container.parse(Files.readAllBytes(path))
        val all = styles(source).map { it.basename }

        assertEquals(listOf("style0.bin"), StructuralEditor.backgroundStylesThatFit(source, all))
        assertEquals(
            listOf("style2.bin"),
            StructuralEditor.backgroundStylesThatFit(source, all, preferred = "style2.bin"),
        )
    }

    /** A style that already has one is never a candidate: it takes a replacement instead. */
    @Test
    fun aStyleThatAlreadyHasABackgroundIsNotACandidate() {
        val path = containers.first { it.fileName.toString().contains("00108") }
        val source = Fit3Container.parse(Files.readAllBytes(path))
        val all = styles(source).map { it.basename }
        val withBackground = styles(source)
            .filter { FaceRecordParser.backgroundImage(it) != null }
            .map { it.basename }

        assertTrue(withBackground.isNotEmpty())
        val fitting = StructuralEditor.backgroundStylesThatFit(source, all)
        assertTrue(fitting.isNotEmpty())
        assertTrue(fitting.none { it in withBackground })
    }
}
