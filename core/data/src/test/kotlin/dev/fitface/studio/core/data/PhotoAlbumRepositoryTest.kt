package dev.fitface.studio.core.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.DownloadProgress
import dev.fitface.studio.core.model.FaceCatalog
import dev.fitface.studio.core.model.FaceCatalogRepository
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.FaceStyleOption
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.DraftPhoto
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.KeptPhoto
import dev.fitface.studio.core.model.PhotoAlbumFace
import dev.fitface.studio.core.model.WatchFaceException
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Native graphics: the photo is really decoded, placed and encoded as a PNG. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoAlbumRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aPlacedPhotoBecomesThe256By402RgbPngTheWatchTakes() = runBlocking {
        // Twice as wide as tall, left red and right blue: covering the tall face crops it to
        // the middle, so the face's left edge is red and its right edge is blue.
        val photo = repository(jpeg(800, 400)).encode("content://fitface/photo.jpg", ImagePlacement(fit = ImageFit.COVER))
        val bytes = photo.copyBytes()
        assertEquals(2, bytes[25].toInt())
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("$md5.png", photo.fileName)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(256, decoded.width)
        assertEquals(402, decoded.height)
        assertTrue(Color.red(decoded.getPixel(5, 200)) > 180)
        assertTrue(Color.blue(decoded.getPixel(250, 200)) > 180)
    }

    /** One colour rule with the Background page: a white photo coloured blue is sent blue. */
    @Test
    fun aColouredPhotoIsSentInItsColour() = runBlocking {
        val photo = repository(jpeg(256, 402, Color.WHITE)).encode("content://fitface/white.jpg",
            ImagePlacement(fit = ImageFit.STRETCH, tint = 0xFF0000FF.toInt()))
        val bytes = photo.copyBytes()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val centre = decoded.getPixel(128, 201)
        assertTrue(Color.blue(centre) > 230 && Color.red(centre) < 30 && Color.green(centre) < 30)
    }

    @Test
    fun anUnreadablePhotoSaysSo() {
        val error = assertThrows(WatchFaceException::class.java) {
            runBlocking { repository("not an image".toByteArray()).encode("content://fitface/x", ImagePlacement()) }
        }
        assertEquals("This photo could not be read. Choose a PNG or JPEG.", error.userMessage)
    }

    /**
     * A sent photo is kept as it was sent, beside its reduced original and where it was placed,
     * and comes back with the album: its copy for the thumbnail and for sending again, its
     * original for placing it again.
     */
    @Test
    fun aSentPhotoIsKeptAndComesBackWithTheAlbum() = runBlocking {
        val repository = repository(jpeg(800, 400))
        assertEquals(PhotoAlbumFace(254, listOf("photo_clock_default_bg.png")), repository.album(face).face)
        val placement = ImagePlacement(fit = ImageFit.CONTAIN, zoom = 1.5f, offsetX = 0.1f, tint = 0xFF00FF00.toInt())
        val photo = repository.encode("content://fitface/photo.jpg", placement)
        val source = repository.preview("content://fitface/photo.jpg")
        val recorded = repository.recordAlbum(254, listOf(photo.fileName), photo.fileName,
            listOf(KeptPhoto(photo, source, placement)))
        assertEquals(listOf(photo.fileName), recorded.map { it.name })

        val album = repository.album(face)
        val sent = album.sent.single()
        assertEquals(photo.fileName, album.shown)
        assertEquals(placement, sent.placement)
        assertEquals(256 to 402, sent.preview?.width to sent.preview?.height)
        assertTrue(sent.kept && sent.changeable)
        assertEquals(6, album.room)
        assertArrayEquals(photo.copyBytes(), repository.keptPhoto(254, photo.fileName)?.copyBytes())
        val kept = requireNotNull(repository.keptSource(254, photo.fileName))
        assertEquals(source.width to source.height, kept.width to kept.height)
    }

    /** Placed again from the kept original: drawn the same way a picked file is. */
    @Test
    fun aPhotoPlacedAgainIsDrawnFromItsKeptOriginal() = runBlocking {
        val repository = repository(jpeg(800, 400))
        val source = repository.preview("content://fitface/photo.jpg")
        val again = repository.encode(source, ImagePlacement(fit = ImageFit.COVER))
        val decoded = BitmapFactory.decodeByteArray(again.copyBytes(), 0, again.size)
        assertTrue(Color.red(decoded.getPixel(5, 200)) > 180)
        assertTrue(Color.blue(decoded.getPixel(250, 200)) > 180)
    }

    /** A photo no longer listed loses its copies; scratch files a killed write left go too. */
    @Test
    fun copiesOfPhotosNoLongerListedAreDeleted() = runBlocking {
        val repository = repository(jpeg(800, 400))
        val source = repository.preview("content://fitface/photo.jpg")
        val first = repository.encode("content://fitface/photo.jpg", ImagePlacement(fit = ImageFit.COVER))
        val second = repository.encode("content://fitface/photo.jpg", ImagePlacement(fit = ImageFit.CONTAIN))
        repository.recordAlbum(254, listOf(first.fileName, second.fileName), first.fileName, listOf(
            KeptPhoto(first, source, ImagePlacement(fit = ImageFit.COVER)),
            KeptPhoto(second, source, ImagePlacement(fit = ImageFit.CONTAIN)),
        ))
        val folder = File(context.filesDir, "photo-album/254")
        File(folder, ".left-behind.tmp").writeText("x")
        repository.recordAlbum(254, listOf(second.fileName), second.fileName)
        assertEquals(
            setOf("album.json", second.fileName, second.fileName.removeSuffix(".png") + ".source.jpg"),
            folder.list()!!.toSet(),
        )
        assertEquals(listOf(second.fileName), repository.album(face).names)
        assertNull(repository.keptPhoto(254, first.fileName))
        repository.recordAlbum(254, emptyList(), null)
        assertEquals(emptyList<String>(), repository.album(face).names)
    }

    /**
     * Photos recorded before copies were kept, by name alone in the preferences, are still
     * listed: shown and removable, but neither changeable nor sent again.
     */
    @Test
    fun photosSentBeforeCopiesWereKeptAreListedByName() = runBlocking {
        val repository = repository(ByteArray(0))
        val names = listOf("0123456789abcdef0123456789abcdef.png", "fedcba9876543210fedcba9876543210.png")
        repository.recordAlbum(254, names, null)
        // As an older build leaves it: the preferences hold the names, and there is no album file.
        File(context.filesDir, "photo-album/254/album.json").delete()
        val album = repository.album(face)
        assertEquals(names, album.names)
        assertTrue(album.sent.none { it.kept || it.changeable })
        assertNull(repository.keptPhoto(254, names[0]))
        assertEquals(5, album.room)
    }

    /**
     * Picks not yet sent survive leaving the page: the draft comes back with each original,
     * placement and replaced photo. A change of place rewrites only the list, an empty draft
     * clears the folder, and recording a sent album leaves the draft alone.
     */
    @Test
    fun aDraftIsSavedAndComesBack() = runBlocking {
        val repository = repository(jpeg(800, 400))
        val source = repository.preview("content://fitface/photo.jpg")
        val key = "0f0e0d0c-0b0a-4908-8706-050403020100"
        val replaced = "fedcba9876543210fedcba9876543210.png"
        val placement = ImagePlacement(fit = ImageFit.STRETCH, zoom = 1.25f, offsetY = -0.2f, tint = 0xFFFF0000.toInt())
        repository.saveDraft(254, listOf(DraftPhoto(key, source, placement, replaced)))
        repository.draftWrite?.join()
        val original = File(context.filesDir, "photo-album/254/draft/$key.jpg")
        val written = original.readBytes()

        val back = repository.draft(254).single()
        assertEquals(key, back.key)
        assertEquals(placement, back.placement)
        assertEquals(replaced, back.replaces)
        assertEquals(source.width to source.height, back.source.width to back.source.height)

        repository.saveDraft(254, listOf(DraftPhoto(key, back.source, placement.copy(zoom = 2f), null)))
        repository.draftWrite?.join()
        assertArrayEquals("the original is written once", written, original.readBytes())
        assertEquals(2f, repository.draft(254).single().placement.zoom)

        repository.recordAlbum(254, emptyList(), null)
        assertEquals(1, repository.draft(254).size)
        repository.saveDraft(254, emptyList())
        repository.draftWrite?.join()
        assertTrue(repository.draft(254).isEmpty())
        assertTrue(!File(context.filesDir, "photo-album/254/draft").exists())
    }

    /** A kept copy that is not the photo of that name is not sent as it. */
    @Test
    fun aKeptCopyThatNoLongerMatchesItsNameIsNotSent() = runBlocking {
        val repository = repository(jpeg(800, 400))
        val source = repository.preview("content://fitface/photo.jpg")
        val photo = repository.encode("content://fitface/photo.jpg", ImagePlacement(fit = ImageFit.COVER))
        val other = repository.encode("content://fitface/photo.jpg", ImagePlacement(fit = ImageFit.CONTAIN))
        repository.recordAlbum(254, listOf(photo.fileName), photo.fileName,
            listOf(KeptPhoto(photo, source, ImagePlacement(fit = ImageFit.COVER))))
        File(context.filesDir, "photo-album/254/${photo.fileName}").writeBytes(other.copyBytes())
        assertNull(repository.keptPhoto(254, photo.fileName))
    }

    @Test
    fun aPackageThatDeclaresNoAlbumIsRefused() {
        val error = assertThrows(WatchFaceException::class.java) {
            runBlocking { repository(ByteArray(0), metadata = false).album(face) }
        }
        assertEquals("This face's package does not say it takes photos, so none can be sent to it.", error.userMessage)
    }

    private val face = CatalogFace("p254", "00254", "My Photo", "", "com.samsung.fit3watchface.sm_r390_0254",
        "1.0.0", 10000, 1, listOf(FaceStyleOption(0, "https://example.invalid/s.png")))

    private fun repository(image: ByteArray, metadata: Boolean = true) = PhotoAlbumRepositoryImpl(
        context,
        object : FaceCatalogRepository {
            override suspend fun cachedCatalog(): FaceCatalog? = null
            override suspend fun loadCatalog(forceRefresh: Boolean): FaceCatalog = error("unused")
            override suspend fun uneditableAppIds(): Set<String> = emptySet()
            override suspend fun markUneditable(appId: String) = Unit
            override suspend fun isPackageCached(face: CatalogFace) = true
            override suspend fun downloadPackage(face: CatalogFace, styleId: Int, onProgress: (DownloadProgress) -> Unit) =
                FacePackage(FacePackage.sourceKey(face.productId, face.versionCode, styleId), face.name,
                    face.faceId, styleId, face.versionCode, apk(metadata))
        },
        AndroidImageSource(mockk<ContentResolver>().also { resolver ->
            every { resolver.openInputStream(any()) } answers { ByteArrayInputStream(image) }
        }),
    )

    private fun apk(album: Boolean): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("assets/bandface_info_myPhoto.json"))
            val gallery = if (album) "yes" else "no"
            zip.write(
                """{"info": {"wf_unique": "SM-R390_00254", "Settings": {"setting_type": "layer_background",
                    "BackgroundImageSelections": {"gallery_menu": "$gallery", "BackgroundImageSelection":
                    [{"ImageName": "photo_clock_default_bg.png", "type": "preloaded"}]}}}}""".toByteArray(),
            )
            zip.closeEntry()
        }
    }.toByteArray()

    private fun jpeg(width: Int, height: Int, solid: Int? = null): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) for (y in 0 until height) {
            bitmap.setPixel(x, y, solid ?: if (x < width / 2) Color.RED else Color.BLUE)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }
}
