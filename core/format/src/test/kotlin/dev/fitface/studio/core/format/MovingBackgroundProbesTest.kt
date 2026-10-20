package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.AnimationTiming
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.WidgetPlacement
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
 * Moving backgrounds made the way the app makes them — `AnimationTiming` for the table,
 * `MovingBackgroundEditor` for the record and Indexed8 frames — packed as project archives for
 * a watch session, as [WatchProbesTest] packs Stage 0's. `-Pfit3.probeOut=<dir>` writes them.
 *
 * What T1 did not cover and these do: a frame held for several periods (repeated pointers),
 * the record *under* the widgets rather than appended on top of them, full-panel Indexed8
 * frames with hundreds of colours quantised to a palette, one in a style and one in the
 * always-on display of the same face, and one slotted above a catalogue face's still
 * background with every widget renumbered above it.
 */
class MovingBackgroundProbesTest {
    private val corpus = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val output = System.getProperty("fit3.probeOut")?.takeIf(String::isNotBlank)?.let(Path::of)

    private class Probe(val id: String, val name: String, val storePackage: ByteArray, val container: Fit3Container)

    private fun storePackage(faceId: String): ByteArray {
        val packages = corpus.resolve("packages")
        assumeTrue("no packages under $corpus", Files.isDirectory(packages))
        val match = packages.listDirectoryEntries("*_${faceId.takeLast(4)}@*.apk").singleOrNull()
        assumeTrue("the package for $faceId is not in the corpus", match != null)
        return Files.readAllBytes(match!!)
    }

    private fun containerOf(storePackage: ByteArray) =
        Fit3Container.parse(Fit3Apk.parse(storePackage, retainMembers = false).binary)

    /** A full panel shading from [top] to [bottom] (hundreds of colours), with [digit] large and white. */
    private fun gradient(top: Int, bottom: Int, digit: String): IntArray {
        val pixels = IntArray(256 * 402) { index ->
            val t = index / 256 / 401.0
            fun mix(shift: Int) = ((top ushr shift and 0xFF) * (1 - t) + (bottom ushr shift and 0xFF) * t).toInt()
            (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
        WatchProbes.drawDigits(pixels, 256, 402, digit, 14, 0xFFFFFFFF.toInt())
        return pixels
    }

    /** Diagonal stripes in [colour] over near-black, shifted by [phase], with [digit] in the corner band. */
    private fun stripes(colour: Int, phase: Int, digit: String): IntArray {
        val pixels = IntArray(256 * 402) { index ->
            val x = index % 256
            val y = index / 256
            if ((x + y + phase) / 24 % 2 == 0) colour else 0xFF101010.toInt()
        }
        WatchProbes.drawDigits(pixels, 256, 402, digit, 6, 0xFFFFFFFF.toInt())
        return pixels
    }

    private fun probes(): List<Probe> {
        val info = storePackage("00006")
        // M1: a custom face as the app now makes one, under a number of its own, clear of the
        // 201/202 left on the watch by T3 and of the first numbers the app hands out.
        val template = FaceIdentity.renumber(CustomFaceTemplate.strip(containerOf(info)), "00210")
        val hues = listOf(0xFFB71C1C, 0xFFE65100, 0xFFF9A825, 0xFF2E7D32, 0xFF00838F, 0xFF1565C0, 0xFF4527A0, 0xFF6A1B9A)
            .map(Long::toInt)
        val styleFrames = hues.mapIndexed { index, hue -> gradient(hue, 0xFF000000.toInt(), "${index + 1}") }
        // Frame 1 held a second, the rest a quarter: period 250, frame 1 named by four entries.
        val stylePlan = AnimationTiming.plan(listOf(1000) + List(7) { 250 }, step = 1)
        assertEquals(250, stylePlan.periodMs)
        assertEquals(listOf(0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7), stylePlan.table)
        var m1 = MovingBackgroundEditor.insert(template, WatchProbes.STYLE, stylePlan.frames.map(styleFrames::get),
            stylePlan.table, stylePlan.periodMs).edit.container
        val aodFrames = (1..4).map { gradient(0xFF0D2B4E.toInt(), 0xFF000000.toInt(), "$it") }
        val aodPlan = AnimationTiming.plan(List(4) { 500 }, step = 1)
        m1 = MovingBackgroundEditor.insert(m1, WatchProbes.AOD, aodFrames, aodPlan.table, aodPlan.periodMs).edit.container

        // M2: above face 00046's still background, every widget renumbered above it. Its own ID,
        // so it installs over 00046 on the watch.
        val stock = storePackage("00046")
        val m2Plan = AnimationTiming.plan(List(6) { 200 }, step = 1)
        val m2Frames = (0 until 6).map { stripes(0xFF1E88E5.toInt(), it * 8, "${it + 1}") }
        val m2 = MovingBackgroundEditor.insert(containerOf(stock), WatchProbes.STYLE, m2Frames, m2Plan.table, m2Plan.periodMs)
        assertEquals("directly above 00046's still background", 1, m2.globalIndex)

        return listOf(
            Probe("M1-moving-custom-00210", "Probe M1 moving background", info, m1),
            Probe("M2-moving-over-still-00046", "Probe M2 moving over 00046", stock, m2.edit.container),
        )
    }

    @Test
    fun everyMovingBackgroundProbeIsAProjectTheAppWillImportAndSend() {
        val probes = probes()
        output?.let(Files::createDirectories)
        probes.forEach { probe ->
            val bytes = probe.container.toByteArray()
            assertTrue(probe.id, probe.container.validate().isValid)
            assertArrayEquals(probe.id, bytes, Fit3Container.parse(bytes).toByteArray())
            assertTrue("${probe.id}: ${bytes.size} bytes", bytes.size <= WATCH_CONTAINER_BYTE_CEILING)
            val faceId = SettingRecord.parse(requireNotNull(FaceResources.settingOrNull(probe.container))).faceId
            val delivery = DeliveryChecks.check(probe.container, faceId)
            assertTrue("${probe.id}: ${delivery.refusals}", delivery.canSend)
            assertTrue(probe.id, delivery.emptyVariants.isEmpty())
            // The moving background is a full-panel layer, never the still background.
            val style = probe.container.entryByBasename(WatchProbes.STYLE)
            val moving = requireNotNull(MovingBackgroundEditor.read(style))
            assertEquals(WidgetPlacement.BACKGROUND, FaceRecordParser.widgetGuides(style)[moving.globalIndex].placement)

            val archive = WatchProbes.archive(probe.storePackage, probe.container, probe.name)
            val apk = Fit3Apk.parse(archive, retainMembers = false)
            assertEquals(probe.id, faceId, apk.faceId)
            assertArrayEquals(probe.id, bytes, apk.binary)
            assertNull(probe.id, ProjectArchive.read(archive).editedContainer)
            output?.let { Files.write(it.resolve("${probe.id}.zip"), archive) }
            println("${probe.id}: face $faceId, ${bytes.size} bytes, style $moving" +
                (FaceResources.aodOrNull(probe.container)?.let { " aod ${MovingBackgroundEditor.read(it)}" } ?: ""))
        }
        assertNull("M2 keeps 00046's still background as the one a replacement edits",
            FaceRecordParser.backgroundImage(probes[1].container.entryByBasename(WatchProbes.STYLE))
                ?.takeIf { it.index != 0 })
    }
}
