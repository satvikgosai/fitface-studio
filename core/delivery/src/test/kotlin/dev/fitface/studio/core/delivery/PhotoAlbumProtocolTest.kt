package dev.fitface.studio.core.delivery

import dev.fitface.studio.core.model.AlbumPhoto
import dev.fitface.studio.core.model.DirectInstallPayload
import dev.fitface.studio.core.model.PHOTO_ALBUM_FACE_ID
import dev.fitface.studio.core.model.PhotoAlbumRequest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The album commands, byte for byte, against what the stock plugin's builders produce for the
 * same input (assembled here from its `SAMessageData.Builder` rules: header byte, then
 * `<param> <value>` or `<param> <length> <bytes>`).
 */
class PhotoAlbumProtocolTest {
    private val a = "0123456789abcdef0123456789abcdef.png"
    private val b = "fedcba9876543210fedcba9876543210.png"
    private val default = "photo_clock_default_bg.png"

    @Test
    fun addAndDeleteImageListNameEachPhoto() {
        assertArrayEquals(
            bytes(0x0B, 0x04, 0xFE, 0x1B, 2) + named(0x1C, a) + named(0x1C, b),
            PhotoAlbumProtocol.addImageList(PHOTO_ALBUM_FACE_ID, listOf(a, b)),
        )
        // The plugin builds delete-image-list in its variable format, which sets 0x80.
        assertArrayEquals(
            bytes(0x8C, 0x04, 0xFE, 0x1B, 1) + named(0x1C, a),
            PhotoAlbumProtocol.deleteImageList(PHOTO_ALBUM_FACE_ID, listOf(a)),
        )
    }

    @Test
    fun editInfoListsEveryBackgroundAndMarksTheShownOne() {
        assertArrayEquals(
            bytes(0x07, 0x18, 2, 0x04, 0xFE, 0x1B, 3) +
                named(0x13, default) + bytes(0x1F, 0) +
                named(0x13, a) + bytes(0x1F, 0) +
                named(0x13, b) + bytes(0x1F, 1),
            PhotoAlbumProtocol.setEditInfo(PHOTO_ALBUM_FACE_ID, listOf(default, a, b), b),
        )
    }

    @Test
    fun theShortCommandsAreTheirFaceAndSampler() {
        assertArrayEquals(bytes(0x06, 0x04, 0xFE), PhotoAlbumProtocol.getEditInfo(PHOTO_ALBUM_FACE_ID))
        assertArrayEquals(bytes(0x03, 0x04, 0xFE, 0x1D, 0), PhotoAlbumProtocol.setCurrentFace(PHOTO_ALBUM_FACE_ID))
    }

    @Test
    fun anAdditionOpensThenAddsListsAndShowsTheFace() {
        val photo = AlbumPhoto(png())
        val request = PhotoAlbumRequest(PHOTO_ALBUM_FACE_ID, listOf(default), listOf(a, photo.fileName),
            photos = listOf(photo), selected = photo.fileName)
        assertEquals(listOf(hex(PhotoAlbumProtocol.getEditInfo(254))), PhotoAlbumProtocol.opening(request).map(::hex))
        assertEquals(
            listOf(
                PhotoAlbumProtocol.addImageList(254, listOf(photo.fileName)),
                PhotoAlbumProtocol.setEditInfo(254, listOf(default, a, photo.fileName), photo.fileName),
                PhotoAlbumProtocol.setCurrentFace(254),
            ).map(::hex),
            PhotoAlbumProtocol.closing(request).map(::hex),
        )
        assertArrayEquals(bytes(0x08, 0x04, 0xFE), PhotoAlbumProtocol.abandon(request))
    }

    @Test
    fun aRemovalDeletesThenListsWhatIsLeftWithoutChangingTheCurrentFace() {
        val request = PhotoAlbumRequest(PHOTO_ALBUM_FACE_ID, listOf(default), listOf(b),
            removed = listOf(a), selected = default)
        assertEquals(
            listOf(
                PhotoAlbumProtocol.deleteImageList(254, listOf(a)),
                PhotoAlbumProtocol.setEditInfo(254, listOf(default, b), default),
            ).map(::hex),
            PhotoAlbumProtocol.closing(request).map(::hex),
        )
    }

    /** A photo placed again: its new PNG added, its old one deleted, then the list and the face. */
    @Test
    fun aReplacementAddsDeletesListsAndShows() {
        val photo = AlbumPhoto(png())
        val request = PhotoAlbumRequest(PHOTO_ALBUM_FACE_ID, listOf(default), listOf(photo.fileName, b),
            photos = listOf(photo), removed = listOf(a), selected = b)
        assertEquals(
            listOf(
                PhotoAlbumProtocol.addImageList(254, listOf(photo.fileName)),
                PhotoAlbumProtocol.deleteImageList(254, listOf(a)),
                PhotoAlbumProtocol.setEditInfo(254, listOf(default, photo.fileName, b), b),
                PhotoAlbumProtocol.setCurrentFace(254),
            ).map(::hex),
            PhotoAlbumProtocol.closing(request).map(::hex),
        )
    }

    /** Showing another photo sends no image list: only the face's settings, then the face. */
    @Test
    fun showingAnotherPhotoOnlySetsTheFace() {
        val request = PhotoAlbumRequest(PHOTO_ALBUM_FACE_ID, listOf(default), listOf(a, b), selected = b)
        assertEquals(
            listOf(
                PhotoAlbumProtocol.setEditInfo(254, listOf(default, a, b), b),
                PhotoAlbumProtocol.setCurrentFace(254),
            ).map(::hex),
            PhotoAlbumProtocol.closing(request).map(::hex),
        )
    }

    /** One file-info layout for both, and the album directory for a photo. */
    @Test
    fun aPhotoTravelsAsAPngIntoTheAlbumDirectory() {
        val photo = AlbumPhoto(png())
        assertEquals(
            "33png,/user/wf/album/${photo.fileName},${photo.size}",
            String(IdentityTransferProtocol.descriptor(photo), Charsets.US_ASCII),
        )
        val name = photo.fileName.toByteArray()
        val size = photo.size
        assertArrayEquals(
            bytes(0x01, 0x03, 0x06, 0x04, name.size) + name +
                bytes(0x05, size and 0xFF, size ushr 8 and 0xFF, size ushr 16 and 0xFF, size ushr 24 and 0xFF),
            IdentityTransferProtocol.fileInfo(photo),
        )
        val face = DirectInstallPayload.create(46, 0, "SM-R390_00046_256x402.bin", ByteArray(1234) { 7 })
        assertArrayEquals(IdentityTransferProtocol.descriptor(face.fileName, face.size), IdentityTransferProtocol.descriptor(face))
    }

    @Test
    fun aRefusalIsReadOnlyFromAReplyThatSaysSo() {
        assertEquals(
            "The watch's Photos face is full. Remove photos, then send again.",
            PhotoAlbumProtocol.refusal(bytes(0x4B, 0x04, 0xFE, 0x1A, 2) + named(0x1C, a)),
        )
        assertEquals(
            "The watch refused a photo's file name.",
            PhotoAlbumProtocol.refusal(bytes(0x4B, 0x04, 0xFE, 0x1A, 3)),
        )
        assertEquals(
            "The watch refused the Photos face's new settings.",
            PhotoAlbumProtocol.refusal(bytes(0x47, 0x1A, 0)),
        )
        assertNull(PhotoAlbumProtocol.refusal(bytes(0x4B, 0x04, 0xFE, 0x1A, 1)))
        assertNull(PhotoAlbumProtocol.refusal(bytes(0x47, 0x1A, 1)))
        // The request itself, echoed, and an install reply are not refusals.
        assertNull(PhotoAlbumProtocol.refusal(PhotoAlbumProtocol.addImageList(254, listOf(a))))
        assertNull(PhotoAlbumProtocol.refusal(bytes(0x44, 0x16, 4, 0x04, 46, 0x1D, 0)))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun named(param: Int, name: String) = bytes(param, name.length) + name.toByteArray()

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun png(): ByteArray {
        val raw = ByteArray(402 * (1 + 256 * 3))
        val deflater = Deflater().apply { setInput(raw); finish() }
        val idat = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) idat.write(buffer, 0, deflater.deflate(buffer))
        val header = ByteBuffer.allocate(13).putInt(256).putInt(402).put(8).put(2).put(0).put(0).put(0).array()
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            for ((type, data) in listOf("IHDR" to header, "IDAT" to idat.toByteArray(), "IEND" to ByteArray(0))) {
                val typed = type.toByteArray() + data
                write(ByteBuffer.allocate(4).putInt(data.size).array())
                write(typed)
                write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(typed) }.value.toInt()).array())
            }
        }.toByteArray()
    }
}
