package dev.fitface.studio.feature.library

import android.os.Looper
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DownloadProgress
import dev.fitface.studio.core.model.ExportedProject
import dev.fitface.studio.core.model.FaceCatalog
import dev.fitface.studio.core.model.FaceCatalogRepository
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.ImportedProject
import dev.fitface.studio.core.model.ProjectSummary
import dev.fitface.studio.core.model.WatchFaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Export and import from the projects list, from the tap to the notice.
 *
 * The archive itself is tested in `:core:data`; what is worth pinning here is the part a
 * screen cannot show: the name the picker is armed with, a cancelled picker leaving nothing
 * armed, and every outcome reaching the state that reports it.
 *
 * Robolectric for `viewModelScope`, like `LibraryViewModelTest`: the ViewModel launches from
 * its own `init` and `Dispatchers.Main` needs a looper to dispatch onto.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectTransferWiringTest {
    private val project = ProjectSummary(
        id = 12,
        displayName = "Face 00046",
        sourceUri = "${FacePackage.SOURCE_SCHEME}dev.fitface.face00046/4/0",
        faceId = "00046",
        faceName = "Aurora",
        importedAtEpochMillis = 1_700_000_000_000,
        name = "Aurora 2",
    )

    private val repository = mockk<WatchFaceRepository>(relaxed = true) {
        every { observeProjects() } returns emptyFlow()
    }

    private fun viewModel(): LibraryViewModel =
        LibraryViewModel(repository, FakeCatalog(), DiagnosticsLog(), mockk(relaxed = true))
            .also { settle() }

    /**
     * The tap arms the picker with the name it will suggest.
     *
     * The name is derived in the state rather than in the composable so that this, the one
     * part the harness cannot see, is still pinned: `CreateDocument` takes it at launch.
     */
    @Test
    fun exportingArmsThePickerWithTheNameItWillSuggest() {
        val viewModel = viewModel()

        viewModel.startExport(project)
        settle()

        assertNotNull(viewModel.state.value.exporting)
        assertEquals("SM-R390_00046_Aurora-2.zip", viewModel.state.value.exportFileName)
    }

    /**
     * A cancelled picker disarms the request rather than leaving it standing.
     *
     * The effect that opens the picker keys on the request, so one left armed would mean the
     * next export of the same project never reopened it — the tap would do nothing at all.
     */
    @Test
    fun cancellingThePickerDisarmsTheRequest() {
        val viewModel = viewModel()
        viewModel.startExport(project)
        settle()

        viewModel.finishExport(null)
        settle()

        assertNull(viewModel.state.value.exporting)
        coVerify(exactly = 0) { repository.exportProject(any(), any()) }
    }

    /** A written file is reported by name and size, because nothing else on screen says so. */
    @Test
    fun aFinishedExportIsReported() {
        coEvery { repository.exportProject(12, "content://out") } returns
            ExportedProject("Aurora 2", 1_310_720)
        val viewModel = viewModel()
        viewModel.startExport(project)
        settle()

        viewModel.finishExport("content://out")
        settle()

        val notice = viewModel.state.value.exported
        assertEquals("Aurora 2", notice?.name)
        assertEquals(1_310_720L, notice?.byteCount)
        assertNull("the request stayed armed", viewModel.state.value.exporting)
    }

    /** An import reports the name the project was given here. */
    @Test
    fun aFinishedImportIsReported() {
        coEvery { repository.importProject("content://in") } returns ImportedProject(31, "Aurora 3")
        val viewModel = viewModel()

        viewModel.importProject("content://in")
        settle()

        assertEquals("Aurora 3", viewModel.state.value.imported?.name)
    }

    /** A cancelled picker returns null, which is not an import and not an error. */
    @Test
    fun aCancelledImportPickerDoesNothing() {
        val viewModel = viewModel()

        viewModel.importProject(null)
        settle()

        coVerify(exactly = 0) { repository.importProject(any()) }
        assertNull(viewModel.state.value.imported)
        assertNull(viewModel.state.value.error)
    }

    /** A failed import says why, on the snackbar every other library failure uses. */
    @Test
    fun aFailedImportIsReportedRatherThanSwallowed() {
        coEvery { repository.importProject(any()) } throws
            IllegalStateException("that file holds no exported project")
        val viewModel = viewModel()

        viewModel.importProject("content://in")
        settle()

        assertEquals(
            "that file holds no exported project",
            viewModel.state.value.error?.text,
        )
        assertNull(viewModel.state.value.imported)
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** Enough of a catalogue for the ViewModel's `init` to finish. */
    private class FakeCatalog : FaceCatalogRepository {
        override suspend fun cachedCatalog(): FaceCatalog? = null

        override suspend fun loadCatalog(forceRefresh: Boolean): FaceCatalog =
            FaceCatalog(faces = emptyList(), styleCount = 0, fetchedAtEpochMillis = 0)

        override suspend fun uneditableAppIds(): Set<String> = emptySet()

        override suspend fun markUneditable(appId: String) = Unit

        override suspend fun isPackageCached(face: CatalogFace) = false

        override suspend fun downloadPackage(
            face: CatalogFace,
            styleId: Int,
            onProgress: (DownloadProgress) -> Unit,
        ): FacePackage = error("not used")
    }
}
