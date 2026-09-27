package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.PreviewFrame
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Styles page shows the face-picker thumbnail twice — as it is, and as **Update
 * thumbnail** would leave it — and both pictures have to be the stored pixels, not an
 * approximation. The "after" one is only worth showing if it is exactly what the update
 * writes, so this reads the update back and compares every pixel.
 */
class ThumbnailPreviewTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    /** Something no stored thumbnail already is: a hard-edged diagonal gradient. */
    private val composed = PreviewFrame(256, 402, IntArray(256 * 402) { index ->
        val x = index % 256
        val y = index / 256
        (0xFF shl 24) or ((x * 255 / 255) shl 16) or (((x + y) % 256) shl 8) or ((y * 255 / 401))
    })

    @Test
    fun theAfterPictureIsExactlyWhatTheUpdateWrites() {
        listOf("00001", "00016", "00046", "00106").forEach { id ->
            val source = face(id)
            FaceResources.selectableStyles(source).indices.forEach { style ->
                val now = FaceEditor.previewThumbnail(source, style)
                assertNotNull("$id style $style has no readable thumbnail", now)
                val after = FaceEditor.renderedThumbnail(source, style, composed)!!
                assertEquals(now!!.width, after.width)
                assertEquals(now.height, after.height)
                val updated = FaceEditor.replacePreviewThumbnail(source, style, composed)!!.container
                val stored = FaceEditor.previewThumbnail(updated, style)!!
                assertArrayEquals("$id style $style", after.argb, stored.argb)
                // And no other style's picture moved.
                FaceResources.selectableStyles(source).indices.filter { it != style }.forEach { other ->
                    assertArrayEquals(FaceEditor.previewThumbnail(source, other)!!.argb,
                        FaceEditor.previewThumbnail(updated, other)!!.argb)
                }
            }
        }
    }

    @Test
    fun aStyleWithNoThumbnailIsNullNotAnError() {
        val source = face("00001")
        assertNull(FaceEditor.previewThumbnail(source, 999))
        assertNull(FaceEditor.renderedThumbnail(source, 999, composed))
    }
}
