package dev.fitface.studio.feature.library

import android.os.Looper
import dev.fitface.studio.core.data.DiagnosticsReporter
import dev.fitface.studio.core.model.CUSTOM_FACE_TEMPLATE_FACE_ID
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DownloadProgress
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.FaceCatalog
import dev.fitface.studio.core.model.FaceCatalogRepository
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.FaceStyleOption
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.WatchFaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Starting a custom face from the Projects page.
 *
 * Nothing ships with the app, so the face it is built from is downloaded on the tap, like any
 * other — and while that and the build run, they hold the repository's one editing session,
 * so nothing else may open a project. What the card promises before the tap (a download, or
 * none) is checked against the package cache, never inferred.
 */
@RunWith(RobolectricTestRunner::class)
class CustomFaceLibraryTest {
    private val template = face(CUSTOM_FACE_TEMPLATE_FACE_ID)
    private val other = face("00001")
    private val repository = mockk<WatchFaceRepository>(relaxed = true) {
        every { observeProjects() } returns emptyFlow()
    }
    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun opened(catalog: FakeCatalog): LibraryViewModel =
        LibraryViewModel(repository, catalog, DiagnosticsLog(), mockk<DiagnosticsReporter>(relaxed = true))
            .also { settle() }

    @Test
    fun theCustomFaceIsBuiltFromTheTemplateFaceAndOpensInTheEditor() {
        val catalog = FakeCatalog(listOf(other, template))
        val built = mockk<EditorSnapshot>(relaxed = true) { every { projectId } returns 42 }
        coEvery { repository.openTemplate(any(), "Custom face") } returns built
        val viewModel = opened(catalog)
        assertEquals(template, viewModel.state.value.customFaceSource)
        val events = mutableListOf<LibraryEvent>()
        CoroutineScope(Dispatchers.Main).launch { viewModel.events.collect { events += it } }
        viewModel.startCustomFace("Custom face")
        settle()
        assertEquals(listOf(template), catalog.downloaded)
        catalog.download.complete(pkg())
        settle()
        coVerify(exactly = 1) { repository.openTemplate(any(), "Custom face") }
        assertEquals(listOf<LibraryEvent>(LibraryEvent.OpenEditor(42)), events)
        assertNull(viewModel.state.value.customFaceProgress)
    }

    /** The session is single, so while one is being made nothing else may open. */
    @Test
    fun whileOneIsBeingMadeNothingElseOpens() {
        val catalog = FakeCatalog(listOf(other, template))
        val viewModel = opened(catalog)
        viewModel.startCustomFace("Custom face")
        settle()
        val state = viewModel.state.value
        assertNotNull(state.customFaceProgress)
        assertFalse(state.customFaceProgress!!.building)
        assertTrue(state.isWorking)
        assertFalse(state.canSelectFace)
        viewModel.startCustomFace("Custom face")
        settle()
        assertEquals(1, catalog.downloaded.size)
    }

    @Test
    fun withoutItsFaceInTheCatalogueNothingStarts() {
        val catalog = FakeCatalog(listOf(other))
        val viewModel = opened(catalog)
        assertNull(viewModel.state.value.customFaceSource)
        viewModel.startCustomFace("Custom face")
        settle()
        assertTrue(catalog.downloaded.isEmpty())
        assertNull(viewModel.state.value.customFaceProgress)
    }

    /** A build the store's newer face refuses says why, and lets the card go again. */
    @Test
    fun aRefusedBuildSaysWhyAndLetsGo() {
        val catalog = FakeCatalog(listOf(template))
        coEvery { repository.openTemplate(any(), any()) } throws
            WatchFaceException("Info_4 has changed in the store.")
        val viewModel = opened(catalog)
        viewModel.startCustomFace("Custom face")
        settle()
        catalog.download.complete(pkg())
        settle()
        assertNull(viewModel.state.value.customFaceProgress)
        assertEquals("Info_4 has changed in the store.", viewModel.state.value.error?.text)
    }

    /** "No download needed" is only said once the cache has been asked. */
    @Test
    fun whetherItDownloadsIsCheckedNotAssumed() {
        assertFalse(opened(FakeCatalog(listOf(template), cached = false)).state.value.customFaceCached)
        assertTrue(opened(FakeCatalog(listOf(template), cached = true)).state.value.customFaceCached)
    }

    private fun face(faceId: String) = CatalogFace(
        productId = "p$faceId", faceId = faceId, name = "Face $faceId", description = "",
        appId = "app.$faceId", versionName = "1", versionCode = 4, packageSize = 2_700_000,
        styles = listOf(FaceStyleOption(id = 0, previewUrl = "https://example/$faceId.png")),
    )

    private fun pkg() = FacePackage(FacePackage.sourceKey("p00006", 4, 0), "SM-R390_00006.apk",
        CUSTOM_FACE_TEMPLATE_FACE_ID, 0, 4, byteArrayOf(1))

    private class FakeCatalog(faces: List<CatalogFace>, private val cached: Boolean = false) : FaceCatalogRepository {
        private val catalogue = FaceCatalog(faces, faces.size, 1)
        val download = CompletableDeferred<FacePackage>()
        val downloaded = mutableListOf<CatalogFace>()
        override suspend fun cachedCatalog(): FaceCatalog? = catalogue
        override suspend fun loadCatalog(forceRefresh: Boolean): FaceCatalog = catalogue
        override suspend fun uneditableAppIds(): Set<String> = emptySet()
        override suspend fun markUneditable(appId: String) = Unit
        override suspend fun isPackageCached(face: CatalogFace): Boolean = cached
        override suspend fun downloadPackage(
            face: CatalogFace, styleId: Int, onProgress: (DownloadProgress) -> Unit,
        ): FacePackage {
            downloaded += face
            return download.await()
        }
    }
}
