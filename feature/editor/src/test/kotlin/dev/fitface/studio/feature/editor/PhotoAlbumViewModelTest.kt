package dev.fitface.studio.feature.editor

import dev.fitface.studio.core.delivery.AlbumProgress
import dev.fitface.studio.core.delivery.DirectInstallPhase
import dev.fitface.studio.core.delivery.DirectInstallState
import dev.fitface.studio.core.delivery.Fit3DirectInstaller
import dev.fitface.studio.core.model.AlbumPhoto
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DownloadProgress
import dev.fitface.studio.core.model.FaceCatalog
import dev.fitface.studio.core.model.FaceCatalogRepository
import dev.fitface.studio.core.model.FaceStyleOption
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.DraftPhoto
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.KeptPhoto
import dev.fitface.studio.core.model.SentPhoto
import dev.fitface.studio.core.model.PhotoAlbum
import dev.fitface.studio.core.model.PhotoAlbumFace
import dev.fitface.studio.core.model.PhotoAlbumRepository
import dev.fitface.studio.core.model.PhotoAlbumRequest
import dev.fitface.studio.core.model.PreviewFrame
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoAlbumViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val earlier = "0123456789abcdef0123456789abcdef.png"
    private val default = "photo_clock_default_bg.png"

    @Before
    fun installDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun restoreDispatcher() = Dispatchers.resetMain()

    private val delivery = MutableStateFlow(DirectInstallState(phase = DirectInstallPhase.READY))
    private val requests = mutableListOf<PhotoAlbumRequest>()
    private val installer = mockk<Fit3DirectInstaller>(relaxed = true) {
        every { state } returns delivery
        every { sendAlbum(any()) } answers {
            val request = firstArg<PhotoAlbumRequest>()
            requests += request
            delivery.value = delivery.value.copy(phase = DirectInstallPhase.VERIFYING,
                album = AlbumProgress(request.photos.size, removing = request.isRemoval, showing = request.isSelection))
        }
    }

    /**
     * Photos named in [sent] are kept with a source and a placement when listed in [sources];
     * the rest are as if sent before copies were kept.
     */
    private class Repository(
        sent: List<String>,
        var shown: String? = null,
        val sources: Map<String, PreviewFrame> = emptyMap(),
        val kept: Map<String, AlbumPhoto> = emptyMap(),
    ) : PhotoAlbumRepository {
        var photos = sent.map { name ->
            SentPhoto(name, sources[name], ImagePlacement(fit = ImageFit.CONTAIN, zoom = 2f).takeIf { name in sources })
        }
        /** Each encode: the picked file, or null for a photo placed again from its kept original. */
        val encoded = mutableListOf<Pair<String?, ImagePlacement>>()
        var recorded: List<String>? = null
        var keptRecorded: List<KeptPhoto> = emptyList()
        var drafted: List<DraftPhoto> = emptyList()
        var draftSaves = 0

        override suspend fun draft(faceId: Int) = drafted

        override fun saveDraft(faceId: Int, photos: List<DraftPhoto>) {
            drafted = photos
            draftSaves++
        }

        override suspend fun album(face: CatalogFace, onProgress: (DownloadProgress) -> Unit) =
            PhotoAlbum(PhotoAlbumFace(254, listOf("photo_clock_default_bg.png")), photos, shown)

        override suspend fun preview(uri: String) = PreviewFrame(4, 6, IntArray(24))

        override suspend fun encode(uri: String, placement: ImagePlacement): AlbumPhoto {
            encoded += uri to placement
            return AlbumPhoto(png(uri.hashCode()))
        }

        override suspend fun encode(source: PreviewFrame, placement: ImagePlacement): AlbumPhoto {
            encoded += null to placement
            return AlbumPhoto(png(placement.hashCode()))
        }

        override suspend fun keptSource(faceId: Int, name: String) = sources[name]

        override suspend fun keptPhoto(faceId: Int, name: String) = kept[name]

        override suspend fun recordAlbum(faceId: Int, sent: List<String>, shown: String?, keep: List<KeptPhoto>): List<SentPhoto> {
            recorded = sent
            this.shown = shown
            keptRecorded = keep
            photos = sent.map { name -> photos.firstOrNull { it.name == name } ?: SentPhoto(name, null, null) }
            return photos
        }
    }

    private val catalog = object : FaceCatalogRepository {
        val face = CatalogFace("p", "00254", "Photos", "", "app", "1.0.0", 1, 1,
            listOf(FaceStyleOption(0, "https://example.invalid/0.png")))
        override suspend fun cachedCatalog() = FaceCatalog(listOf(face), 1)
        override suspend fun loadCatalog(forceRefresh: Boolean) = FaceCatalog(listOf(face), 1)
        override suspend fun uneditableAppIds() = emptySet<String>()
        override suspend fun markUneditable(appId: String) = Unit
        override suspend fun isPackageCached(face: CatalogFace) = true
        override suspend fun downloadPackage(face: CatalogFace, styleId: Int, onProgress: (DownloadProgress) -> Unit) =
            error("unused")
    }

    private fun viewModel(repository: Repository) =
        PhotoAlbumViewModel(repository, catalog, installer, DiagnosticsLog()).also { settle() }

    @Test
    fun photosPastTheFacesRoomAreLeftOutAndSaidSo() {
        val repository = Repository(sent = List(5) { "%032x.png".format(it) })
        val viewModel = viewModel(repository)
        assertEquals(2, viewModel.state.value.room)
        viewModel.pick(listOf("content://a", "content://b", "content://c"))
        settle()
        val state = viewModel.state.value
        assertEquals(listOf("content://a", "content://b"), state.photos.map { it.uri })
        assertEquals(1, state.dropped)
        assertEquals(0, state.room)
    }

    /**
     * Each photo is encoded where it was placed; the request keeps what this app sent before,
     * adds the new ones and shows the first; the album is recorded only once the request has
     * been handed over.
     */
    @Test
    fun sendingListsEarlierPhotosShowsTheFirstAndRecordsOnlyWhenSent() {
        val repository = Repository(sent = listOf(earlier))
        val viewModel = viewModel(repository)
        viewModel.pick(listOf("content://a", "content://b"))
        settle()
        viewModel.select(viewModel.state.value.photos.first().id)
        viewModel.setFit(ImageFit.CONTAIN)
        viewModel.send()
        settle()

        assertEquals(listOf(ImageFit.CONTAIN, ImageFit.COVER), repository.encoded.map { it.second.fit })
        val request = requests.single()
        val names = request.photos.map { it.fileName }
        assertEquals(listOf(default), request.preloaded)
        assertEquals(listOf(earlier) + names, request.album)
        assertEquals(names.first(), request.selected)
        assertNull(repository.recorded)

        delivery.value = delivery.value.copy(phase = DirectInstallPhase.COMPLETE)
        settle()
        assertEquals(listOf(earlier) + names, repository.recorded)
        assertEquals(listOf(earlier) + names, viewModel.state.value.album?.names)
        assertEquals(names.first(), repository.shown)
        assertEquals(names, repository.keptRecorded.map { it.photo.fileName })
        assertTrue(viewModel.state.value.photos.isEmpty())
    }

    /** The Background page's colour: kept through a new fit and Reset, and sent with the photo. */
    @Test
    fun aPhotosColourStaysThroughFitAndResetAndIsSentWithIt() {
        val repository = Repository(sent = emptyList())
        val viewModel = viewModel(repository)
        viewModel.pick(listOf("content://a"))
        settle()
        viewModel.setTint(0xFF0000FF.toInt())
        viewModel.transform(1.5f, 0.1f, 0f)
        viewModel.setFit(ImageFit.CONTAIN)
        viewModel.resetPlacement()
        val placement = viewModel.state.value.photos.single().placement
        assertEquals(ImagePlacement(fit = ImageFit.CONTAIN, tint = 0xFF0000FF.toInt()), placement)
        viewModel.send()
        settle()
        assertEquals(0xFF0000FF.toInt(), repository.encoded.single().second.tint)
    }

    @Test
    fun aFailedSendRecordsNothing() {
        val repository = Repository(sent = emptyList())
        val viewModel = viewModel(repository)
        viewModel.pick(listOf("content://a"))
        settle()
        viewModel.send()
        settle()
        delivery.value = delivery.value.copy(phase = DirectInstallPhase.FAILED, failure = "x")
        settle()
        delivery.value = delivery.value.copy(phase = DirectInstallPhase.COMPLETE)
        settle()
        assertNull(repository.recorded)
        assertEquals(1, viewModel.state.value.photos.size)
    }

    @Test
    fun removingAsksForEveryPhotoThisAppSentAndRecordsAnEmptyAlbum() {
        val repository = Repository(sent = listOf(earlier))
        val viewModel = viewModel(repository)
        viewModel.removeSent()
        settle()
        val request = requests.single()
        assertEquals(listOf(earlier), request.removed)
        assertEquals(emptyList<String>(), request.album)
        assertEquals(default, request.selected)
        delivery.value = delivery.value.copy(phase = DirectInstallPhase.COMPLETE)
        settle()
        assertEquals(emptyList<String>(), repository.recorded)
        assertEquals(7, viewModel.state.value.room)
    }

    /**
     * A sent photo brought back to Background is placed again on its kept original, keeps its
     * place in the album and what was shown stays shown; its old version is deleted.
     */
    @Test
    fun aChangedSentPhotoTakesItsOldPlaceAndReplacesIt() {
        val other = "fedcba9876543210fedcba9876543210.png"
        val source = PreviewFrame(8, 4, IntArray(32))
        val repository = Repository(sent = listOf(earlier, other), shown = other, sources = mapOf(earlier to source))
        val viewModel = viewModel(repository)
        viewModel.changeSent(earlier)
        settle()
        val pick = viewModel.state.value.photos.single()
        assertEquals(earlier, pick.replaces)
        assertEquals(ImagePlacement(fit = ImageFit.CONTAIN, zoom = 2f), pick.placement)
        assertEquals(7 - 2, viewModel.state.value.room)
        viewModel.setTint(0xFFFF0000.toInt())
        viewModel.send()
        settle()

        val request = requests.single()
        val renewed = request.photos.single().fileName
        assertEquals(listOf(renewed, other), request.album)
        assertEquals(listOf(earlier), request.removed)
        assertEquals(other, request.selected)
        assertEquals(null to 0xFFFF0000.toInt(), repository.encoded.single().let { it.first to it.second.tint })
        delivery.value = delivery.value.copy(phase = DirectInstallPhase.COMPLETE)
        settle()
        assertEquals(listOf(renewed, other), repository.recorded)
        assertSame(source, repository.keptRecorded.single().source)
        assertTrue(viewModel.state.value.photos.isEmpty())
    }

    @Test
    fun aPhotoSentBeforeCopiesWereKeptCannotBeChanged() {
        val viewModel = viewModel(Repository(sent = listOf(earlier)))
        viewModel.changeSent(earlier)
        settle()
        assertTrue(viewModel.state.value.photos.isEmpty())
        assertEquals("This photo was sent before copies were kept. Pick it again to change it.",
            viewModel.state.value.error?.text)
    }

    /** Showing another sent photo transfers nothing and records only which one is shown. */
    @Test
    fun showingAnotherSentPhotoOnlyChangesWhichIsShown() {
        val other = "fedcba9876543210fedcba9876543210.png"
        val repository = Repository(sent = listOf(earlier, other), shown = earlier)
        val viewModel = viewModel(repository)
        viewModel.showSent(other)
        settle()
        val request = requests.single()
        assertTrue(request.isSelection)
        assertEquals(other, request.selected)
        delivery.value = delivery.value.copy(phase = DirectInstallPhase.COMPLETE)
        settle()
        assertEquals(other, repository.shown)
        assertEquals(listOf(earlier, other), repository.recorded)
        assertEquals(other, viewModel.state.value.album?.shown)
    }

    /** Removing one keeps the shown photo shown; removing the shown one shows the next. */
    @Test
    fun removingOneSentPhotoKeepsTheRest() {
        val other = "fedcba9876543210fedcba9876543210.png"
        val viewModel = viewModel(Repository(sent = listOf(earlier, other), shown = other))
        viewModel.removeSentPhoto(earlier)
        settle()
        requests.single().let {
            assertEquals(listOf(earlier), it.removed)
            assertEquals(listOf(other), it.album)
            assertEquals(other, it.selected)
            assertTrue(it.isRemoval)
        }
        requests.clear()
        delivery.value = DirectInstallState(phase = DirectInstallPhase.READY)
        settle()
        viewModel.removeSentPhoto(other)
        settle()
        assertEquals(earlier, requests.single().selected)
    }

    /** Sending again uses the kept copies as they were sent, in their places, the same one shown. */
    @Test
    fun sendingAgainUsesTheKeptCopies() {
        val other = "fedcba9876543210fedcba9876543210.png"
        val copy = AlbumPhoto(png(41))
        val repository = Repository(sent = listOf(copy.fileName, other), shown = other, kept = mapOf(copy.fileName to copy))
        val viewModel = viewModel(repository)
        viewModel.resendAll()
        settle()
        val request = requests.single()
        assertEquals(listOf(copy.fileName), request.photos.map { it.fileName })
        assertEquals(listOf(copy.fileName, other), request.album)
        assertEquals(other, request.selected)
        assertTrue(repository.encoded.isEmpty())
    }

    /**
     * Picks are saved as a draft once they settle, and a page opened later starts with them,
     * placed and coloured as they were; sending clears the draft.
     */
    @Test
    fun picksSurviveLeavingThePageUntilTheyAreSent() {
        val repository = Repository(sent = emptyList())
        val first = viewModel(repository)
        first.pick(listOf("content://a", "content://b"))
        settle()
        first.setTint(0xFF00FF00.toInt())
        first.transform(1f, 0.1f, 0f)
        first.transform(1f, 0.1f, 0f)
        settle()
        assertEquals(2, repository.drafted.size)
        val saves = repository.draftSaves
        assertTrue("a drag's frames are saved once, when they settle", saves <= 3)

        val reopened = viewModel(repository)
        val photos = reopened.state.value.photos
        assertEquals(repository.drafted.map { it.key }, photos.map { it.key })
        assertTrue(photos.all { it.uri == null })
        assertEquals(0xFF00FF00.toInt(), photos.last().placement.tint)
        assertEquals(0.2f, photos.last().placement.offsetX, 0.0001f)
        assertEquals(5, reopened.state.value.room)

        reopened.send()
        settle()
        assertEquals(listOf(null, null), repository.encoded.map { it.first })
        delivery.value = delivery.value.copy(phase = DirectInstallPhase.COMPLETE)
        settle()
        assertTrue(repository.drafted.isEmpty())
    }

    /** A drafted change of a photo that is no longer on the watch has nothing to replace. */
    @Test
    fun aDraftedChangeOfARemovedPhotoIsDropped() {
        val repository = Repository(sent = emptyList()).apply {
            drafted = listOf(DraftPhoto("0f0e0d0c-0b0a-4908-8706-050403020100", PreviewFrame(4, 6, IntArray(24)),
                ImagePlacement(), replaces = earlier))
        }
        assertTrue(viewModel(repository).state.value.photos.isEmpty())
    }

    private fun settle() = scope.testScheduler.advanceUntilIdle()

    companion object {
        /** A valid 256 × 402 PNG whose bytes, and so whose name, depend on [seed]. */
        fun png(seed: Int): ByteArray {
            val raw = ByteArray(402 * (1 + 256 * 3)) { (seed + it / 997).toByte() }
            for (row in 0 until 402) raw[row * (1 + 256 * 3)] = 0
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
}
