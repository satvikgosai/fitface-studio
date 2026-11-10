package dev.fitface.studio.core.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoAlbumTest {
    @Test
    fun aPhotoIsNamedByTheMd5OfItsOwnBytes() {
        val bytes = png()
        val photo = AlbumPhoto(bytes)
        val md5 = MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("$md5.png", photo.fileName)
        assertTrue(PhotoAlbumRequest.isPhotoName(photo.fileName))
        assertEquals(bytes.size, photo.size)
        assertTrue(photo.copyBytes().contentEquals(bytes))
    }

    @Test
    fun rgbaIsTakenAsWellAsRgb() {
        assertNull(AlbumPhotoFormat.refusal(png(colourType = 6)))
    }

    /** What T9d asks the watch about, refused here first. */
    @Test
    fun anythingButA256By402PngIsRefused() {
        assertEquals("Not a PNG", AlbumPhotoFormat.refusal(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0)))
        assertEquals("The PNG is 402x256, not 256x402", AlbumPhotoFormat.refusal(png(width = 402, height = 256)))
        assertEquals("The PNG is 255x402, not 256x402", AlbumPhotoFormat.refusal(png(width = 255)))
        assertEquals("The PNG is not 8-bit RGB or RGBA", AlbumPhotoFormat.refusal(png(colourType = 3)))
        assertEquals(
            "The PNG uses an unsupported compression, filter or interlace",
            AlbumPhotoFormat.refusal(png(interlace = 1)),
        )
    }

    @Test
    fun aDamagedOrHostilePngIsRefused() {
        val good = png()
        assertEquals("The PNG is truncated", AlbumPhotoFormat.refusal(good.copyOf(good.size - 3)))
        val badCrc = good.copyOf().also { it[29] = (it[29] + 1).toByte() }
        assertEquals("A PNG chunk's CRC is wrong", AlbumPhotoFormat.refusal(badCrc))
        assertEquals("The PNG does not end at IEND", AlbumPhotoFormat.refusal(good + byteArrayOf(0)))
        // A chunk claiming two gigabytes is caught by its length, not by reading it.
        val huge = good.copyOf().also { ByteBuffer.wrap(it, 33, 4).putInt(Int.MAX_VALUE) }
        assertEquals("A PNG chunk runs past the file", AlbumPhotoFormat.refusal(huge))
        assertThrows(IllegalArgumentException::class.java) { AlbumPhoto(good.copyOf(good.size - 3)) }
        assertThrows(IllegalArgumentException::class.java) {
            AlbumPhoto(ByteArray(MAX_ALBUM_PHOTO_BYTES + 1))
        }
    }

    @Test
    fun aRequestAddsOrRemovesAndListsWhatTheFaceShows() {
        val photo = AlbumPhoto(png())
        val earlier = "0123456789abcdef0123456789abcdef.png"
        val request = PhotoAlbumRequest(PHOTO_ALBUM_FACE_ID, listOf(DEFAULT), listOf(earlier, photo.fileName),
            photos = listOf(photo), selected = photo.fileName)
        assertEquals(listOf(DEFAULT, earlier, photo.fileName), request.backgrounds)
        val removal = PhotoAlbumRequest(PHOTO_ALBUM_FACE_ID, listOf(DEFAULT), emptyList(),
            removed = listOf(earlier), selected = DEFAULT)
        assertTrue(removal.isRemoval)
    }

    /**
     * A photo placed again is added and its old version deleted in one request; a request with
     * neither only changes the photo shown. Only a pure deletion leaves the current face alone.
     */
    @Test
    fun aRequestCanReplaceAPhotoOrOnlyChangeTheShownOne() {
        val photo = AlbumPhoto(png())
        val old = "0123456789abcdef0123456789abcdef.png"
        val replace = PhotoAlbumRequest(PHOTO_ALBUM_FACE_ID, listOf(DEFAULT), listOf(photo.fileName),
            photos = listOf(photo), removed = listOf(old), selected = photo.fileName)
        assertFalse(replace.isRemoval)
        assertFalse(replace.isSelection)
        val show = PhotoAlbumRequest(PHOTO_ALBUM_FACE_ID, listOf(DEFAULT), listOf(old), selected = old)
        assertTrue(show.isSelection)
        assertFalse(show.isRemoval)
        val remove = PhotoAlbumRequest(PHOTO_ALBUM_FACE_ID, listOf(DEFAULT), emptyList(), removed = listOf(old),
            selected = DEFAULT)
        assertTrue(remove.isRemoval)
        assertFalse(remove.isSelection)
    }

    @Test
    fun aRequestThatCouldConfuseTheWatchIsRefused() {
        val photo = AlbumPhoto(png())
        val names = (1..8).map { "%032x.png".format(it) }
        fun refused(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }
        // More than the plugin lets the face hold.
        refused { PhotoAlbumRequest(254, listOf(DEFAULT), names, removed = listOf(photo.fileName), selected = DEFAULT) }
        // A sent photo missing from the list, a removed one still in it, a name twice.
        refused { PhotoAlbumRequest(254, listOf(DEFAULT), emptyList(), listOf(photo), selected = DEFAULT) }
        refused { PhotoAlbumRequest(254, listOf(DEFAULT), listOf(names[0]), removed = listOf(names[0]), selected = DEFAULT) }
        refused { PhotoAlbumRequest(254, listOf(DEFAULT), listOf(photo.fileName, photo.fileName), listOf(photo), selected = DEFAULT) }
        // Names the commands could not carry, or a shown background not listed.
        refused { PhotoAlbumRequest(254, listOf("../x.png"), listOf(photo.fileName), listOf(photo), selected = DEFAULT) }
        refused { PhotoAlbumRequest(254, listOf(DEFAULT), listOf("photo.png"), removed = listOf(names[0]), selected = DEFAULT) }
        refused { PhotoAlbumRequest(254, listOf(DEFAULT), listOf(photo.fileName), listOf(photo), selected = names[1]) }
    }

    companion object {
        const val DEFAULT = "photo_clock_default_bg.png"

        /** A real, minimal PNG: grey rows, deflated, with correct CRCs. */
        fun png(
            width: Int = ALBUM_PHOTO_WIDTH,
            height: Int = ALBUM_PHOTO_HEIGHT,
            colourType: Int = 2,
            interlace: Int = 0,
        ): ByteArray {
            val channels = if (colourType == 6) 4 else 3
            val raw = ByteArrayOutputStream()
            repeat(height) { row ->
                raw.write(0)
                repeat(width * channels) { raw.write(row and 0xFF) }
            }
            val deflater = Deflater().apply { setInput(raw.toByteArray()); finish() }
            val idat = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) idat.write(buffer, 0, deflater.deflate(buffer))
            val header = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put(8).put(colourType.toByte()).put(0).put(0).put(interlace.toByte()).array()
            return ByteArrayOutputStream().apply {
                write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                chunk("IHDR", header)
                chunk("IDAT", idat.toByteArray())
                chunk("IEND", ByteArray(0))
            }.toByteArray()
        }

        private fun ByteArrayOutputStream.chunk(type: String, data: ByteArray) {
            val typed = type.toByteArray(Charsets.US_ASCII) + data
            write(ByteBuffer.allocate(4).putInt(data.size).array())
            write(typed)
            write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(typed) }.value.toInt()).array())
        }
    }
}
