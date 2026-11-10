package dev.fitface.studio.core.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * A camera photo stored on its side with an EXIF orientation tag arrives upright.
 *
 * `BitmapFactory` ignores the tag, and phone cameras commonly write a portrait photo as a
 * landscape frame tagged orientation 6, so such a photo used to become a sideways background.
 * Native graphics, because the legacy shadows do not draw pixels the assertions can read.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageOrientationTest {
    /** 20 wide by 10 tall, left half red and right half blue, tagged with [orientation]. */
    private fun taggedJpeg(orientation: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        for (x in 0 until 20) for (y in 0 until 10) {
            bitmap.setPixel(x, y, if (x < 10) Color.RED else Color.BLUE)
        }
        val file = File.createTempFile("oriented", ".jpg")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
            bitmap.recycle()
        }
    }

    private fun source(bytes: ByteArray) = AndroidImageSource(
        mockk<ContentResolver>().also { resolver ->
            every { resolver.openInputStream(any()) } answers { ByteArrayInputStream(bytes) }
        },
    )

    private fun isRed(argb: Int) = Color.red(argb) > 180 && Color.blue(argb) < 80
    private fun isBlue(argb: Int) = Color.blue(argb) > 180 && Color.red(argb) < 80

    @Test
    fun aPortraitPhotoStoredSidewaysPreviewsUpright() {
        val preview = source(taggedJpeg(ExifInterface.ORIENTATION_ROTATE_90)).preview("content://fitface/photo.jpg")

        // Turned a quarter clockwise: the stored left half is now the top.
        assertEquals(10, preview.width)
        assertEquals(20, preview.height)
        assertTrue(isRed(preview.argb[2 * preview.width + 5]))
        assertTrue(isBlue(preview.argb[17 * preview.width + 5]))
    }

    @Test
    fun aSidewaysPhotoFillsAPortraitTargetUpright() {
        val pixels = source(taggedJpeg(ExifInterface.ORIENTATION_ROTATE_90)).decode(
            uri = "content://fitface/photo.jpg",
            width = 10,
            height = 20,
            placement = ImagePlacement(fit = ImageFit.STRETCH),
        )

        assertTrue(isRed(pixels[2 * 10 + 5]))
        assertTrue(isBlue(pixels[17 * 10 + 5]))
    }

    @Test
    fun anUntaggedPhotoIsLeftAsStored() {
        val preview = source(taggedJpeg(ExifInterface.ORIENTATION_NORMAL)).preview("content://fitface/photo.jpg")

        assertEquals(20, preview.width)
        assertEquals(10, preview.height)
        assertTrue(isRed(preview.argb[5 * preview.width + 2]))
        assertTrue(isBlue(preview.argb[5 * preview.width + 17]))
    }
}
