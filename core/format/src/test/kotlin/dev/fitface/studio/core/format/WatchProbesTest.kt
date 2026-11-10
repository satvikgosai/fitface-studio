package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import dev.fitface.studio.core.model.visualBounds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Builds the Stage 0 watch probes and checks each is a project the app will import and send.
 *
 * Every probe must validate, round-trip byte for byte, fit the 4 MiB ceiling, pass the
 * delivery checks `validatedBytes()` runs, and read back from its archive the way Import reads
 * it. Each also asserts the one thing it was built to change.
 *
 * With `-Pfit3.probeOut=<dir>` the archives are written there, one `.zip` a probe; see
 * `docs/development.md`. Without it nothing is written. The corpus supplies the faces, so the
 * whole class skips without one.
 */
class WatchProbesTest {
    private val corpus = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val output = System.getProperty("fit3.probeOut")?.takeIf(String::isNotBlank)?.let(Path::of)

    private class Probe(
        val id: String,
        val name: String,
        val storePackage: ByteArray,
        val container: Fit3Container,
        /** Variants left with no widget on purpose; Install warns about these, and sends. */
        val emptyVariants: Set<String> = emptySet(),
    )

    private fun storePackage(faceId: String): ByteArray {
        val packages = corpus.resolve("packages")
        assumeTrue("no packages under $corpus", Files.isDirectory(packages))
        val match = packages.listDirectoryEntries("*_${faceId.takeLast(4)}@*.apk").singleOrNull()
        assumeTrue("the package for $faceId is not in the corpus", match != null)
        return Files.readAllBytes(match!!)
    }

    private fun containerOf(storePackage: ByteArray) =
        Fit3Container.parse(Fit3Apk.parse(storePackage, retainMembers = false).binary)

    private fun guides(container: Fit3Container, variant: String = WatchProbes.STYLE) =
        FaceRecordParser.widgetGuides(container.entryByBasename(variant))

    private fun animation(
        container: Fit3Container,
        variant: String,
        x: Int,
        y: Int,
        rasters: List<ByteArray>,
        repeat: Int,
        periodMs: Int,
    ) = WatchProbes.append(
        container, variant,
        WatchProbes.animationRecord(x, y, rasters.size, repeat, periodMs),
        rasters, WatchProbes.animationPointers(rasters.size),
    )

    /** Four 64 px frames numbered 1 to 4, each with its own border colour so a skipped one shows. */
    private fun numberedFrames(): List<ByteArray> =
        listOf(0xFFE53935, 0xFF43A047, 0xFF1E88E5, 0xFFFDD835).mapIndexed { index, border ->
            WatchProbes.rgb565Raster(64, 64,
                WatchProbes.frame(64, "${index + 1}", 0xFF202020.toInt(), border.toInt(), 0xFFFFFFFF.toInt(), 6))
        }

    private fun solid(color: Int) = IntArray(256 * 402) { color }

    private fun probes(): List<Probe> {
        val infoPackage = storePackage("00006")
        val template = CustomFaceTemplate.strip(containerOf(infoPackage))
        val probes = mutableListOf<Probe>()

        // T0: the control. If this does not install, nothing below means anything.
        probes += Probe("T0-control", "Probe T0 control", infoPackage, template)

        // T1a/b: does an appended Animation play, at what speed, and does it stop?
        probes += Probe("T1a-animation-period-1000", "Probe T1a animation 1000",
            infoPackage, animation(template, WatchProbes.STYLE, 96, 200, numberedFrames(), 255, 1000))
        probes += Probe("T1b-animation-repeat-3", "Probe T1b animation repeat 3",
            infoPackage, animation(template, WatchProbes.STYLE, 96, 200, numberedFrames(), 3, 1000))

        // T1c: twelve full-panel frames at period 100, the heaviest load a GIF background makes.
        val hues = intArrayOf(0xFFB71C1C.toInt(), 0xFF880E4F.toInt(), 0xFF4A148C.toInt(), 0xFF1A237E.toInt(),
            0xFF0D47A1.toInt(), 0xFF006064.toInt(), 0xFF004D40.toInt(), 0xFF1B5E20.toInt(),
            0xFF827717.toInt(), 0xFFE65100.toInt(), 0xFF3E2723.toInt(), 0xFF263238.toInt())
        val panels = hues.mapIndexed { index, hue ->
            WatchProbes.rgb565Raster(256, 402, solid(hue).also {
                WatchProbes.drawDigits(it, 256, 402, "${index + 1}", 16, 0xFFFFFFFF.toInt())
            })
        }
        probes += Probe("T1c-animation-full-panel", "Probe T1c full panel frames",
            infoPackage, animation(template, WatchProbes.STYLE, 0, 0, panels, 255, 100))

        // T1d: Indexed8 frames whose palette entry 0 is clear, over a teal background.
        val teal = StructuralEditor.addBackgrounds(template, listOf(WatchProbes.STYLE), 256, 402,
            solid(0xFF00897B.toInt())).container
        val palette = IntArray(INDEXED_PALETTE_ENTRIES) { 0xFFFFFFFF.toInt() }.also {
            it[0] = 0x00000000
            it[2] = 0xFFFF6F00.toInt()
        }
        val indexedFrames = (1..4).map { number ->
            val pixels = WatchProbes.frame(64, "$number", 0, 2, 1, 6)
            WatchProbes.indexed8Raster(64, 64, palette, ByteArray(pixels.size) { pixels[it].toByte() })
        }
        probes += Probe("T1d-animation-indexed8-alpha", "Probe T1d indexed8 alpha",
            infoPackage, animation(teal, WatchProbes.STYLE, 96, 200, indexedFrames, 255, 1000))

        // T1e: the T1a animation on the always-on display.
        probes += Probe("T1e-animation-aod", "Probe T1e animation on AOD",
            infoPackage, animation(template, WatchProbes.AOD, 96, 200, numberedFrames(), 255, 1000))

        // T2: half of a picture, a sprite, a text value and a turned text composite off the face.
        val photoPackage = storePackage("00112")
        val black = containerOf(photoPackage)
        val composite = guides(black).single { it.globalIndex == 12 }
        val turned = FaceEditor.rotateWidget(black, listOf(WatchProbes.STYLE), 12, composite.sequenceId,
            composite.x, composite.y, 300, pristine = black).container
        fun half(container: Fit3Container, index: Int, edge: Char): Fit3Container {
            val guide = guides(container).single { it.globalIndex == index }
            val bounds = guide.visualBounds
            return when (edge) {
                'L' -> WatchProbes.place(container, index, -bounds.width / 2, null)
                'R' -> WatchProbes.place(container, index, 256 - bounds.width / 2, null)
                'T' -> WatchProbes.place(container, index, null, -bounds.height / 2)
                else -> WatchProbes.place(container, index, null, 402 - bounds.height / 2)
            }
        }
        // Static #7, Sprite #1, Value #8 and the turned date Composite #12, each to a different
        // edge, then each to another edge in the second probe.
        probes += Probe("T2a-overhang-half", "Probe T2a overhang half",
            photoPackage, half(half(half(half(turned, 7, 'L'), 1, 'R'), 8, 'T'), 12, 'B'))
        probes += Probe("T2b-overhang-half", "Probe T2b overhang half",
            photoPackage, half(half(half(half(turned, 7, 'B'), 1, 'T'), 8, 'L'), 12, 'R'))

        // T3: the custom face under two IDs outside the catalogue, told apart by background.
        probes += Probe("T3a-face-id-00201", "Probe T3a face 00201", infoPackage,
            FaceIdentity.renumber(StructuralEditor.addBackgrounds(template, listOf(WatchProbes.STYLE), 256, 402,
                solid(0xFF1B5E20.toInt())).container, "00201"))
        probes += Probe("T3b-face-id-00202", "Probe T3b face 00202", infoPackage,
            FaceIdentity.renumber(StructuralEditor.addBackgrounds(template, listOf(WatchProbes.STYLE), 256, 402,
                solid(0xFFB71C1C.toInt())).container, "00202"))

        // T4: two MiSans-24 Values given Roboto Bold 40 and LED Digital 32.
        val fontPackage = storePackage("00106")
        probes += Probe("T4-font-binding", "Probe T4 fonts", fontPackage,
            WatchProbes.rebindValues(containerOf(fontPackage), linkedMapOf(
                15 to WatchProbes.binding(family = 2, size = 40, role = "WF_PROBE_RB40"),
                16 to WatchProbes.binding(family = 15, size = 32, role = "WF_PROBE_LED32"),
            )))

        // T4b: which characters each family carries at a size. T4 showed letters turning into
        // boxes outside MiSans, and the catalogue only ever draws digits and % - / ° in the
        // other families. The four live numbers (Roboto Medium 40) and the four word labels
        // (MiSans 24) are each given a different family and size; the label sizes are picked
        // so each word still fits its box.
        probes += Probe("T4b-digits", "Probe T4b digits", fontPackage,
            WatchProbes.rebindValues(containerOf(fontPackage), linkedMapOf(
                1 to WatchProbes.binding(family = 2, size = 40, role = "WF_PROBE_RB40"),
                2 to WatchProbes.binding(family = 15, size = 32, role = "WF_PROBE_LED32"),
                3 to WatchProbes.binding(family = 0, size = 40, role = "WF_PROBE_MI40"),
                4 to WatchProbes.binding(family = 1, size = 40, role = "WF_PROBE_RR40"),
            )))
        probes += Probe("T4b-letters", "Probe T4b letters", fontPackage,
            WatchProbes.rebindValues(containerOf(fontPackage), linkedMapOf(
                15 to WatchProbes.binding(family = 0, size = 16, role = "WF_PROBE_MI16"),
                16 to WatchProbes.binding(family = 0, size = 40, role = "WF_PROBE_MI40"),
                17 to WatchProbes.binding(family = 1, size = 24, role = "WF_PROBE_RR24"),
                18 to WatchProbes.binding(family = 2, size = 24, role = "WF_PROBE_RB24"),
            )))

        // T5: a style with no widget at all. Removed top first so each index still names its record.
        var empty = template
        guides(template).sortedByDescending { it.globalIndex }.forEach { guide ->
            empty = StructuralEditor.removeWidget(empty, listOf(WatchProbes.STYLE), guide.globalIndex, guide.type,
                guide.sequenceId, guide.x, guide.y, requireFinal = false).container
        }
        probes += Probe("T5-empty-style", "Probe T5 empty style", infoPackage, empty,
            emptyVariants = setOf(WatchProbes.STYLE))

        // T6: numeric day and month swapped in every date Composite, every style and AOD.
        val datePackage = storePackage("00003")
        probes += Probe("T6-date-order-swap", "Probe T6 day month swap", datePackage,
            WatchProbes.swapCompositeSources(containerOf(datePackage), 18, 21))

        // T7: a soft-edged picture over a gradient, and a second over the clock's digits.
        val gradient = IntArray(256 * 402) { index ->
            val t = index / 256 * 255 / 401
            (0xFF shl 24) or ((40 + t / 3) shl 16) or (30 shl 8) or (120 - t / 3)
        }
        var sticker = StructuralEditor.addBackgrounds(template, listOf(WatchProbes.STYLE), 256, 402, gradient).container
        listOf(80 to 200, 150 to 60).forEach { (x, y) ->
            sticker = WatchProbes.append(sticker, WatchProbes.STYLE, WatchProbes.staticRecord(x, y),
                listOf(WatchProbes.rgb565AlphaRaster(96, 96, WatchProbes.softDisc(96, 0xFFFF9800.toInt(), 24))),
                listOf(0x20))
        }
        probes += Probe("T7-soft-alpha-picture", "Probe T7 soft alpha picture", infoPackage, sticker)
        return probes
    }

    @Test
    fun everyProbeIsAProjectTheAppWillImportAndSend() {
        val probes = probes()
        assertEquals(probes.size, probes.map { it.id }.toSet().size)
        output?.let(Files::createDirectories)
        probes.forEach { probe ->
            val bytes = probe.container.toByteArray()
            val report = probe.container.validate()
            assertTrue("${probe.id}: ${report.errors}", report.isValid)
            assertArrayEquals(probe.id, bytes, Fit3Container.parse(bytes).toByteArray())
            assertTrue("${probe.id}: ${bytes.size} bytes", bytes.size <= WATCH_CONTAINER_BYTE_CEILING)
            val faceId = SettingRecord.parse(requireNotNull(FaceResources.settingOrNull(probe.container))).faceId
            val delivery = DeliveryChecks.check(probe.container, faceId)
            assertTrue("${probe.id}: ${delivery.refusals}", delivery.canSend)
            assertEquals(probe.id, probe.emptyVariants, delivery.emptyVariants.toSet())

            val archive = WatchProbes.archive(probe.storePackage, probe.container, probe.name)
            val contents = ProjectArchive.read(archive)
            val apk = Fit3Apk.parse(archive, retainMembers = false)
            assertEquals(probe.id, faceId, apk.faceId)
            assertEquals(probe.id, faceId, contents.manifest.faceId)
            assertArrayEquals(probe.id, bytes, apk.binary)
            assertNull(probe.id, contents.editedContainer)
            output?.let { Files.write(it.resolve("${probe.id}.zip"), archive) }
        }
    }

    /** An appended Animation names its own frames, in order, and nothing before it moves. */
    @Test
    fun anAppendedAnimationPointsAtItsFramesAndMovesNothing() {
        val template = CustomFaceTemplate.strip(containerOf(storePackage("00006")))
        val frames = numberedFrames()
        val result = animation(template, WatchProbes.STYLE, 96, 200, frames, 3, 1000)

        val before = template.entryByBasename(WatchProbes.STYLE)
        val after = result.entryByBasename(WatchProbes.STYLE)
        val records = FaceRecordParser.scanWidgets(after)
        val record = records.last()
        assertEquals(WIDGET_ANIMATION, record.widgetType)
        assertEquals(FaceRecordParser.scanWidgets(before).size, record.globalIndex)
        assertEquals(4, after.data[record.recordOffset + 0x24].toInt())
        assertEquals(3, after.data[record.recordOffset + 0x25].toInt())
        assertEquals(1000, after.data.u16(record.recordOffset + 0x26))
        val pointed = FaceRecordParser.imagePointerFields(record, FaceRecordParser.imagesByRelativeOffset(after))
        assertEquals(4, pointed.size)
        pointed.zip(frames).forEach { (field, raster) ->
            assertArrayEquals(raster, after.data.copyOfRange(field.image.recordOffset, field.image.pixelOffset + field.image.dataSize))
        }
        // Every record and raster that was there is byte for byte where it was.
        val oldHeader = StyleHeader.parse(before)
        val newHeader = StyleHeader.parse(after)
        assertArrayEquals(before.data.copyOfRange(STYLE_HEADER_SIZE, oldHeader.storedImageOffset),
            after.data.copyOfRange(STYLE_HEADER_SIZE, oldHeader.storedImageOffset))
        assertArrayEquals(before.data.copyOfRange(oldHeader.storedImageOffset, before.data.size),
            after.data.copyOfRange(newHeader.storedImageOffset, newHeader.storedImageOffset + oldHeader.imageBytes))
        assertArrayEquals(template.entryByBasename(WatchProbes.AOD).data, result.entryByBasename(WatchProbes.AOD).data)
    }

    /** Each rebound Value names its own new binding, which asks for exactly its family and size. */
    @Test
    fun reboundValuesNameNewBindingsAndNativeOnesStayPut() {
        val original = containerOf(storePackage("00106"))
        val result = WatchProbes.rebindValues(original, linkedMapOf(
            15 to WatchProbes.binding(2, 40, "A"),
            16 to WatchProbes.binding(15, 32, "B"),
        ))

        val records = FaceRecordParser.scanWidgets(result.entryByBasename(WatchProbes.STYLE)).associateBy { it.globalIndex }
        assertEquals(4, records.getValue(15).fontBindingIndex)
        assertEquals(5, records.getValue(16).fontBindingIndex)
        val bindings = FaceResources.fontBindings(result)
        assertEquals(6, bindings.size)
        assertEquals(2, bindings[4].data[1].toInt())
        assertEquals(40L, bindings[4].data.u32(0x58))
        assertEquals(15, bindings[5].data[1].toInt())
        assertEquals(32L, bindings[5].data.u32(0x58))
        FaceResources.fontBindings(original).forEachIndexed { index, binding ->
            assertArrayEquals(binding.data, bindings[index].data)
        }
        FaceResources.variantEntries(result).forEach { assertEquals(6, StyleHeader.parse(it).fontBindingCount) }
    }

    /** Half of each widget leaves the face; the first T2 probe puts each past a different edge. */
    @Test
    fun theOverhangProbeHangsHalfOfEachWidgetOffItsEdge() {
        val probe = probes().single { it.id == "T2a-overhang-half" }
        val placed = guides(probe.container).associateBy { it.globalIndex }
        fun visual(index: Int) = placed.getValue(index).let { guide ->
            val bounds = guide.visualBounds
            listOf(guide.drawLeft + bounds.left, guide.drawTop + bounds.top, bounds.width, bounds.height)
        }
        val (staticLeft, _, staticWidth) = visual(7)
        assertEquals(-staticWidth / 2, staticLeft)
        val (spriteLeft, _, spriteWidth) = visual(1)
        assertEquals(256 - spriteWidth / 2, spriteLeft)
        val (_, valueTop, _, valueHeight) = visual(8)
        assertEquals(-valueHeight / 2, valueTop)
        val (_, compositeTop, _, compositeHeight) = visual(12)
        assertEquals(402 - compositeHeight / 2, compositeTop)
        assertEquals(300, placed.getValue(12).rotationTenths)
    }

    /** Every date Composite reads the day where it read the month, and the reverse, in every variant. */
    @Test
    fun theDateProbeSwapsDayAndMonthEverywhere() {
        val original = containerOf(storePackage("00003"))
        val result = WatchProbes.swapCompositeSources(original, 18, 21)
        var swapped = 0
        FaceResources.variantEntries(original).forEach { before ->
            val after = result.entryByBasename(before.basename)
            FaceRecordParser.scanWidgets(before).filter { it.widgetType == WIDGET_COMP }.forEach { record ->
                listOf(0x24, 0x30, 0x3C, 0x48).forEach { program ->
                    val was = before.data.u16(record.recordOffset + program)
                    val now = after.data.u16(record.recordOffset + program)
                    when (was) {
                        18 -> { assertEquals(21, now); swapped++ }
                        21 -> { assertEquals(18, now); swapped++ }
                        else -> assertEquals(was, now)
                    }
                }
            }
        }
        assertTrue("found $swapped date parts", swapped >= 2)
    }
}
