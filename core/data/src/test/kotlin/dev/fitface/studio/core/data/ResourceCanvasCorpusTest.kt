package dev.fitface.studio.core.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import dev.fitface.studio.core.format.*
import dev.fitface.studio.core.model.PreviewFrame
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResourceCanvasCorpusTest {
    @Test fun nativeTextHasTransparentSurroundingsAndUsesTheBindingSize() {
        val text = WidgetText("28", FontBinding(ByteArray(72), "date", 20), 0xFFFF0000.toInt())
        val frame = WidgetTextRasterizer.render(text, 60, 40)
        assertTrue(frame.argb.any { it ushr 24 != 0 })
        assertTrue(frame.argb.any { it == 0 })
        assertTrue(frame.argb.filter { it ushr 24 != 0 }.all { it and 0xFFFFFF == 0xFF0000 })
        val largerBox = WidgetTextRasterizer.render(text, 120, 80)
        assertEquals("resizing the box must not scale the font", frame.argb.count { it != 0 },
            largerBox.argb.count { it != 0 })
    }

    @Test fun everyVariantUsesOnlyCurrentResourcesAndKeepsEveryDrawableRecord() {
        val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot"))).resolve("SM_R390")
        assumeTrue(Files.isDirectory(root))
        val files = Files.walk(root, 3).use { stream -> stream.asSequence()
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".bin") }.sorted().toList() }
        assumeTrue(files.isNotEmpty())
        val failures = mutableListOf<String>()
        val examples = mutableListOf<Pair<String, List<PreviewFrame>>>()
        var variants = 0
        var layers = 0
        for (file in files) {
            val container = Fit3Container.parse(Files.readAllBytes(file))
            for (entry in FaceResources.variantEntries(container)) {
                val records = FaceRecordParser.scanWidgets(entry)
                val preview = WidgetPreviewComposer.compose(entry, container.entries, WidgetTextRasterizer::render)
                variants++; layers += preview.widgetImageLayers.size
                val indices = preview.widgetImageLayers.map { it.globalIndex }.toSet()
                for (record in records) {
                    if (record.widgetType in setOf(1, 2, 3, 4, 6, 7, 16, 17) && record.globalIndex !in indices) {
                        failures += "${file.fileName}/${entry.basename}: omitted type ${record.widgetType} " +
                            "source ${record.sourceId} #${record.globalIndex}"
                    }
                    if (record.widgetType == 3) {
                        val images = FaceRecordParser.resourceImages(entry, record)
                        val expected = FaceRecordParser.decodeImage(entry, images[
                            WidgetPreviewSample.spriteFrame(record.sourceId, images.size)])
                        val actual = preview.widgetImageLayers.single { it.globalIndex == record.globalIndex }.frame
                        assertEquals(expected.width, actual.width)
                        assertEquals(expected.height, actual.height)
                        assertArrayEquals("sprite must be its own full-resolution frame", expected.argb, actual.argb)
                    }
                    val textSources = if (record.widgetType == 13) record.compositePartSources
                        else listOf(record.sourceId)
                    if (record.widgetType in setOf(5, 13) && record.globalIndex !in indices &&
                        textSources.all { WidgetPreviewSample.value(it) != null }) {
                        failures += "${file.fileName}/${entry.basename}: unavailable text " +
                            "#${record.globalIndex}, sources $textSources"
                    }
                }
                // The stock picture may be absent or arbitrary: neither changes a single pixel.
                val noPreview = container.entries.filterNot { it.basename == "preview.bin" }
                val second = WidgetPreviewComposer.compose(entry, noPreview, WidgetTextRasterizer::render)
                assertArrayEquals(preview.composed.argb, second.composed.argb)
            }
            if (listOf("00003", "00015", "00046", "00100", "00106", "00108")
                .any { file.fileName.toString().contains(it) }) {
                val style = FaceResources.selectableStyles(container).first()
                val frames = mutableListOf<PreviewFrame>()
                FaceResources.previewOrNull(container)?.let { p -> FaceRecordParser.scanImages(p)
                    .firstOrNull()?.let { frames += FaceRecordParser.decodeImage(p, it) } }
                frames += WidgetPreviewComposer.compose(style, container.entries, WidgetTextRasterizer::render).composed
                FaceResources.aodOrNull(container)?.let { frames += WidgetPreviewComposer.compose(it,
                    container.entries, WidgetTextRasterizer::render).composed }
                examples += file.fileName.toString() to frames
            }
        }
        // Opt-in local visual evidence. Never writes into the corpus or modifies package bytes.
        System.getenv("FITFACE_CANVAS_CONTACT_SHEET")?.let { renderSheet(File(it), examples) }
        println("ResourceCanvasCorpusTest: ${files.size} faces, $variants variants, $layers resource layers")
        assertTrue("$variants variants / $layers layers\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun renderSheet(file: File, examples: List<Pair<String, List<PreviewFrame>>>) {
        val bitmap = Bitmap.createBitmap(570, examples.size * 314 + 28, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF202028.toInt())
            val paint = Paint().apply { color = -1; textSize = 13f }
            canvas.drawText("Stock (unedited)       Resource style       Resource AOD", 8f, 19f, paint)
            examples.forEachIndexed { row, (name, frames) ->
                val y = row * 314 + 28
                canvas.drawText(name, 8f, y + 16f, paint)
                frames.forEachIndexed { col, frame ->
                    val image = Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
                    try { canvas.drawBitmap(image, null, Rect(col * 190 + 6, y + 25,
                        col * 190 + 184, y + 305), null) } finally { image.recycle() }
                }
            }
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
