package dev.fitface.studio.core.data

import android.content.ContentResolver
import android.graphics.Bitmap
import dev.fitface.studio.core.model.ImagePlacement
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidImageSourceTest {
    @Test
    fun previewAcceptsAValidImageAfterBoundsDecode() {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream(png)
        }

        val preview = AndroidImageSource(resolver).preview("content://fitface/test.png")

        assertEquals(1, preview.width)
        assertEquals(1, preview.height)
        assertEquals(1, preview.argb.size)
    }

    @Test
    fun decodeSamplesAndProducesOnlyTheRequestedDestination() {
        val source = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        source.recycle()
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream(encoded)
        }

        val pixels = AndroidImageSource(resolver).decode(
            uri = "content://fitface/large.png",
            width = 256,
            height = 402,
            placement = ImagePlacement(),
        )

        assertEquals(256 * 402, pixels.size)
    }
}
