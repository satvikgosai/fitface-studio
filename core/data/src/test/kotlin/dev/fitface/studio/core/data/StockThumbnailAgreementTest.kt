package dev.fitface.studio.core.data

import android.graphics.Bitmap
import dev.fitface.studio.core.format.FaceRecordParser
import dev.fitface.studio.core.format.FaceResources
import dev.fitface.studio.core.format.Fit3Container
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every stock style, drawn from its records, against the face picker's own thumbnail of it.
 *
 * `ResourceCanvasCorpusTest` proves every drawable record draws. This catches the other way a
 * canvas can be missing something: artwork the thumbnail shows that our render has nothing
 * near. Sample values differ (a bar's fill, a step count), so the measure is the share of the
 * thumbnail lit where our render is dark within 3 px, and the bound sits well above the
 * sample-value noise (at most 4.3% across the 99 faces, on `00089`'s long bars).
 *
 * `00011` style 0 ("Big info") is the one exception: its thumbnail shows three activity rings
 * in a heart, and `style0.bin` holds no record or raster for them — only the time and date,
 * the same six records as its AOD. Whatever draws that heart on the watch is not in the
 * container, so the canvas cannot show it or edit it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StockThumbnailAgreementTest {
    @Test fun everyStockStyleDrawsWhatItsPickerThumbnailShows() {
        val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot"))).resolve("SM_R390")
        assumeTrue(Files.isDirectory(root))
        val files = Files.walk(root, 3).use { stream -> stream.asSequence()
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".bin") }.sorted().toList() }
        assumeTrue(files.isNotEmpty())
        val failures = mutableListOf<String>()
        var styles = 0
        for (file in files) {
            val container = Fit3Container.parse(Files.readAllBytes(file))
            val preview = FaceResources.previewOrNull(container) ?: continue
            val thumbnails = FaceRecordParser.scanImages(preview)
            FaceResources.selectableStyles(container).forEachIndexed { index, style ->
                val thumbnail = thumbnails.getOrNull(index)?.let { FaceRecordParser.decodeImage(preview, it) }
                    ?: return@forEachIndexed
                val name = "${file.fileName}/${style.basename}"
                val share = missingShare(thumbnail.argb, render(style, container, thumbnail.width, thumbnail.height),
                    thumbnail.width, thumbnail.height)
                styles++
                val firmwareDrawn = name.contains("_00011_") && style.basename == "style0.bin"
                if (firmwareDrawn) {
                    if (share < MISSING_LIMIT) failures += "$name now draws its heart: drop the exception"
                } else if (share >= MISSING_LIMIT) {
                    failures += "$name: %.1f%% of its picker thumbnail has nothing of ours near it".format(share * 100)
                }
            }
        }
        println("StockThumbnailAgreementTest: $styles styles")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun render(style: dev.fitface.studio.core.format.ContainerEntry, container: Fit3Container,
        width: Int, height: Int): IntArray {
        val composed = WidgetPreviewComposer.compose(style, container.entries, WidgetTextRasterizer::render).composed
        val full = Bitmap.createBitmap(composed.argb, composed.width, composed.height, Bitmap.Config.ARGB_8888)
        val small = Bitmap.createScaledBitmap(full, width, height, true)
        return IntArray(width * height).also { small.getPixels(it, 0, width, 0, 0, width, height) }
    }

    private fun missingShare(stock: IntArray, ours: IntArray, width: Int, height: Int): Double {
        fun luma(color: Int) = ((color shr 16 and 255) * 3 + (color shr 8 and 255) * 6 + (color and 255)) / 10
        var missing = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (luma(stock[y * width + x]) < 60) continue
            val near = (-3..3).any { dy -> (-3..3).any { dx ->
                val nx = x + dx
                val ny = y + dy
                nx in 0 until width && ny in 0 until height && luma(ours[ny * width + nx]) >= 40
            } }
            if (!near) missing++
        }
        return missing.toDouble() / (width * height)
    }

    private companion object {
        const val MISSING_LIMIT = 0.08
    }
}
