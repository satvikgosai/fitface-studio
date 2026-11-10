package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.AnimationSource
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.PixelBounds
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.WidgetPlacement
import dev.fitface.studio.core.model.animationAddedBytes
import dev.fitface.studio.core.model.animationStepThatFits
import dev.fitface.studio.core.model.movingBackgroundBounds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Watch probes for storing moving frames in less room, packed as project archives the way
 * [MovingBackgroundProbesTest] packs its own. `-Pfit3.probeOut=<dir>` writes them; the Bad
 * Apple ones also need `FIT3_PROBE_GIF` naming a GIF.
 *
 * - **P1** (custom face `00240`): a moving background smaller than the panel, stored at the
 *   rectangle it covers with `BKGD` in the Animation's unread `+0x1C`, under the time; its
 *   table names frames out of order and more than once (1 2 3 4 5 4 3 2), so one raster is
 *   drawn from two places in the loop. The AOD has one too, elsewhere.
 * - **P2** (`00046`): the same small background above `00046`'s still background, which is
 *   left as it is, under its hands: the layer order with a partial frame.
 * - **P3/P4** (`00241`, `00242`): the GIF as the app now adds it — NATIVE, every frame; and
 *   CONTAIN across the width at the smallest step that fits.
 * - **T1** (`00243`): 24 Animations, a 4 × 6 grid of tiles, each 30 frames at 100 ms, drawing
 *   one picture between them: a frame number across the middle four tiles, a bar sweeping
 *   down and one sweeping across. A tile that is black, or the same as another, is one shared
 *   raster. If the 24 timers stay in step the bars stay straight and the number whole.
 * - **T2–T5** (`00245`–`00248`): the T1 sweep as 6 full-width strips, as T1's 24 tiles
 *   written in column order, as 48 tiles, and as one full-panel Animation, to find what puts
 *   tiles out of step after the face is entered from the picker, and the real frame period.
 *
 * What one watch showed (2026-11-05) is recorded in `docs/bin-format.md` under adding a moving
 * background: smaller frames work; several Animations drift apart, so tiles are not used.
 */
class FramePackingProbesTest {
    private val corpus = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val output = System.getProperty("fit3.probeOut")?.takeIf(String::isNotBlank)?.let(Path::of)
    private val gifPath = System.getenv("FIT3_PROBE_GIF")?.takeIf(String::isNotBlank)?.let(Path::of)

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

    private fun customFace(info: ByteArray, id: String) =
        FaceIdentity.renumber(CustomFaceTemplate.strip(containerOf(info)), id)

    /** A [width] × [height] frame: [fill] inside a white border, [text] large in the middle. */
    private fun numbered(width: Int, height: Int, fill: Int, text: String): IntArray {
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x < 4 || y < 4 || x >= width - 4 || y >= height - 4) 0xFFFFFFFF.toInt() else fill
        }
        WatchProbes.drawDigits(pixels, width, height, text, minOf(width, height) / 12, 0xFFFFFFFF.toInt())
        return pixels
    }

    private val hues = listOf(0xFFB71C1C, 0xFFE65100, 0xFF2E7D32, 0xFF1565C0, 0xFF6A1B9A).map(Long::toInt)

    // ---------------------------------------------------------------------------------------
    // P: a moving background smaller than the panel
    // ---------------------------------------------------------------------------------------

    private fun pingPong(variant: String, container: Fit3Container, bounds: PixelBounds): Fit3Container {
        val frames = hues.mapIndexed { index, hue -> numbered(bounds.width, bounds.height, hue, "${index + 1}") }
        val table = listOf(0, 1, 2, 3, 4, 3, 2, 1)
        return MovingBackgroundEditor.insert(container, variant, frames, table, 250, bounds).edit.container
    }

    // ---------------------------------------------------------------------------------------
    // The GIF as the app adds it
    // ---------------------------------------------------------------------------------------

    private class Gif(val width: Int, val height: Int, val frames: List<IntArray>, val delays: List<Int>)

    private fun gif(): Gif? = gifPath?.takeIf(Files::isRegularFile)?.let { path ->
        val decoded = GifDecoder.decode(Files.readAllBytes(path))
        Gif(decoded.width, decoded.height, decoded.frames.map { it.argb }, decoded.frames.map { it.delayMs })
    }

    /** Bilinear, as near as a test needs to the app's filtered draw: [source] at [width] × [height]. */
    private fun scaled(source: IntArray, sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): IntArray =
        IntArray(width * height) { index ->
            val fx = ((index % width) + 0.5) * sourceWidth / width - 0.5
            val fy = ((index / width) + 0.5) * sourceHeight / height - 0.5
            val x0 = fx.toInt().coerceIn(0, sourceWidth - 1)
            val y0 = fy.toInt().coerceIn(0, sourceHeight - 1)
            val x1 = minOf(x0 + 1, sourceWidth - 1)
            val y1 = minOf(y0 + 1, sourceHeight - 1)
            val tx = (fx - x0).coerceIn(0.0, 1.0)
            val ty = (fy - y0).coerceIn(0.0, 1.0)
            fun channel(shift: Int): Int {
                fun at(x: Int, y: Int) = source[y * sourceWidth + x] ushr shift and 0xFF
                val top = at(x0, y0) * (1 - tx) + at(x1, y0) * tx
                val bottom = at(x0, y1) * (1 - tx) + at(x1, y1) * tx
                return (top * (1 - ty) + bottom * ty).toInt().coerceIn(0, 255)
            }
            (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

    /** [gif] added as a moving background placed with [fit], as the app's repository adds one. */
    private fun movingGif(container: Fit3Container, gif: Gif, fit: ImageFit): Fit3Container {
        val bounds = requireNotNull(movingBackgroundBounds(gif.width, gif.height, 256, 402, ImagePlacement(fit = fit)))
        val room = WATCH_CONTAINER_BYTE_CEILING - container.fileSize.toLong()
        val source = AnimationSource("", gif.width, gif.height,
            gif.frames.map { PreviewFrame(gif.width, gif.height, it) }, gif.delays)
        val step = requireNotNull(animationStepThatFits(source, room, bounds.width, bounds.height))
        val plan = source.plan(step)
        val frames = plan.frames.map { scaled(gif.frames[it], gif.width, gif.height, bounds.width, bounds.height) }
        println("$fit: ${bounds.width}x${bounds.height} at ${bounds.x},${bounds.y}, every ${step}th frame, " +
            "${plan.frames.size} frames, ${plan.table.size} entries of ${plan.periodMs} ms, " +
            "${animationAddedBytes(plan.frames.size, plan.table.size, bounds.width, bounds.height)} bytes")
        return MovingBackgroundEditor.insert(container, WatchProbes.STYLE, frames, plan.table, plan.periodMs, bounds)
            .edit.container
    }

    // ---------------------------------------------------------------------------------------
    // T: tiles sharing rasters
    // ---------------------------------------------------------------------------------------

    /**
     * [tables] — one per Animation at [positions], each naming rasters by index into [rasters] —
     * appended to [variant] with every raster stored once, whoever draws it.
     */
    private fun appendShared(
        container: Fit3Container,
        variant: String,
        positions: List<Pair<Int, Int>>,
        tables: List<List<Int>>,
        rasters: List<ByteArray>,
        periodMs: Int,
    ): Fit3Container {
        val entry = container.entryByBasename(variant)
        val header = StyleHeader.parse(entry)
        check(header.storedImageOffset + header.imageBytes == entry.data.size)
        val existing = FaceRecordParser.scanWidgets(entry).size
        val offsets = rasters.runningFold(header.imageBytes.toLong()) { at, raster -> at + raster.size }
        val records = tables.mapIndexed { index, table ->
            val (x, y) = positions[index]
            WatchProbes.animationRecord(x, y, table.size, 255, periodMs).also { record ->
                record.putU32(0x0C, ((existing + index).toLong() shl 16) or record.size.toLong())
                table.forEachIndexed { slot, raster -> record.putU32(0x28 + slot * 4, offsets[raster]) }
            }
        }
        val widgets = entry.data.copyOfRange(STYLE_HEADER_SIZE, header.storedImageOffset) +
            records.fold(ByteArray(0)) { all, record -> all + record }
        val images = entry.data.copyOfRange(header.storedImageOffset, entry.data.size) +
            rasters.fold(ByteArray(0)) { all, raster -> all + raster }
        val data = WidgetImporter.styleBytes(entry.data, widgets, existing + records.size, images, header.fontBindingCount)
        return StructuralEditor.rebuild(container, mapOf(entry.index to data)).container
    }

    /** The full-panel picture T1 draws for frame [frame] of [count]. */
    private fun sweep(frame: Int, count: Int): IntArray {
        val pixels = IntArray(256 * 402) { 0xFF000000.toInt() }
        val barY = frame * 402 / count
        for (y in barY until minOf(402, barY + 10)) for (x in 0 until 256) pixels[y * 256 + x] = 0xFFFFD600.toInt()
        val barX = frame * 256 / count
        for (x in barX until minOf(256, barX + 8)) for (y in 0 until 402) pixels[y * 256 + x] = 0xFF00E5FF.toInt()
        WatchProbes.drawDigits(pixels, 256, 402, "${frame + 1}".padStart(2, '0'), 12, 0xFFFFFFFF.toInt())
        return pixels
    }

    /**
     * The sweep cut into [columns] × [rows] tiles, one Animation each, written in row order —
     * or column order when [columnOrder], so a break that follows record order runs down the
     * face instead of across it.
     */
    private fun tiledSweep(
        container: Fit3Container,
        columns: Int = 4,
        rows: Int = 6,
        columnOrder: Boolean = false,
    ): Fit3Container {
        val count = 30
        val xs = (0..columns).map { it * 256 / columns }
        val ys = (0..rows).map { it * 402 / rows }
        val pictures = (0 until count).map { sweep(it, count) }
        val unique = LinkedHashMap<String, Int>()
        val rasters = mutableListOf<ByteArray>()
        val positions = mutableListOf<Pair<Int, Int>>()
        val tables = mutableListOf<List<Int>>()
        val cells = if (columnOrder) {
            (0 until columns).flatMap { column -> (0 until rows).map { row -> row to column } }
        } else {
            (0 until rows).flatMap { row -> (0 until columns).map { column -> row to column } }
        }
        for ((row, column) in cells) {
            val left = xs[column]
            val top = ys[row]
            val width = xs[column + 1] - left
            val height = ys[row + 1] - top
            positions += left to top
            tables += pictures.map { picture ->
                val tile = IntArray(width * height) { picture[(top + it / width) * 256 + left + it % width] }
                val key = "${width}x$height:" + tile.contentHashCode() + ":" + tile.joinToString(",").hashCode()
                unique.getOrPut(key) {
                    rasters += MovingBackgroundEditor.frameRecord(width, height, tile)
                    rasters.size - 1
                }
            }
        }
        val plain = count * MovingBackgroundEditor.frameRecord(256, 402, pictures[0]).size
        println("T1: ${rasters.size} shared tile rasters, ${rasters.sumOf { it.size }} bytes, against $plain " +
            "for $count full-panel frames")
        return appendShared(container, WatchProbes.STYLE, positions, tables, rasters, 100)
    }

    private fun probes(): List<Probe> {
        val info = storePackage("00006")
        var p1 = pingPong(WatchProbes.STYLE, customFace(info, "00240"), PixelBounds(28, 126, 200, 150))
        p1 = pingPong(WatchProbes.AOD, p1, PixelBounds(64, 40, 128, 96))
        val stock = storePackage("00046")
        val p2 = pingPong(WatchProbes.STYLE, containerOf(stock), PixelBounds(48, 236, 160, 120))
        val probes = mutableListOf(
            Probe("P1-small-moving-background-00240", "Probe P1 small moving background", info, p1),
            Probe("P2-small-moving-over-00046", "Probe P2 small moving over 00046", stock, p2),
        )
        gif()?.let { gif ->
            probes += Probe("P3-gif-native-00241", "Probe P3 GIF native", info,
                movingGif(customFace(info, "00241"), gif, ImageFit.NATIVE))
            probes += Probe("P4-gif-contain-00242", "Probe P4 GIF contain", info,
                movingGif(customFace(info, "00242"), gif, ImageFit.CONTAIN))
        }
        probes += Probe("T1-tiles-in-step-00243", "Probe T1 tiles in step", info, tiledSweep(customFace(info, "00243")))
        probes += Probe("T2-six-strips-00245", "Probe T2 six strips", info,
            tiledSweep(customFace(info, "00245"), columns = 1, rows = 6))
        probes += Probe("T3-column-order-00246", "Probe T3 column order", info,
            tiledSweep(customFace(info, "00246"), columnOrder = true))
        probes += Probe("T4-48-tiles-00247", "Probe T4 48 tiles", info,
            tiledSweep(customFace(info, "00247"), columns = 8, rows = 6))
        probes += Probe("T5-one-animation-00248", "Probe T5 one animation", info,
            tiledSweep(customFace(info, "00248"), columns = 1, rows = 1))
        return probes
    }

    @Test
    fun everyFramePackingProbeIsAProjectTheAppWillImportAndSend() {
        val probes = probes()
        output?.let(Files::createDirectories)
        probes.forEach { probe ->
            val bytes = probe.container.toByteArray()
            assertTrue("${probe.id}: ${probe.container.validate()}", probe.container.validate().isValid)
            assertArrayEquals(probe.id, bytes, Fit3Container.parse(bytes).toByteArray())
            assertTrue("${probe.id}: ${bytes.size} bytes", bytes.size <= WATCH_CONTAINER_BYTE_CEILING)
            val faceId = SettingRecord.parse(requireNotNull(FaceResources.settingOrNull(probe.container))).faceId
            val delivery = DeliveryChecks.check(probe.container, faceId)
            assertTrue("${probe.id}: ${delivery.refusals}", delivery.canSend)
            val style = probe.container.entryByBasename(WatchProbes.STYLE)
            MovingBackgroundEditor.read(style)?.let { moving ->
                assertEquals(probe.id, WidgetPlacement.BACKGROUND,
                    FaceRecordParser.widgetGuides(style)[moving.globalIndex].placement)
            }
            val archive = WatchProbes.archive(probe.storePackage, probe.container, probe.name)
            assertArrayEquals(probe.id, bytes, Fit3Apk.parse(archive, retainMembers = false).binary)
            output?.let { Files.write(it.resolve("${probe.id}.zip"), archive) }
            println("${probe.id}: face $faceId, ${bytes.size} bytes, moving background ${MovingBackgroundEditor.read(style)}")
        }
    }
}
