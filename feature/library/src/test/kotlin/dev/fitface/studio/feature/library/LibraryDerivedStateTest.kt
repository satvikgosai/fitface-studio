package dev.fitface.studio.feature.library

import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.CatalogSort
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.ProjectSort
import dev.fitface.studio.core.model.ProjectSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filtering, ordering and button-state rules the two library pages are made of.
 *
 * None of this had any coverage: `visibleFaces` shipped untested, and everything the face
 * sheet now decides is new. All of it is pure, so none of it needs a screen.
 */
class LibraryDerivedStateTest {
    // -- Catalogue search ---------------------------------------------------

    @Test
    fun searchingTheCatalogueMatchesNameDescriptionFaceIdAndAppId() {
        val state = LibraryUiState(faces = faces)
        assertEquals(listOf("Aurora"), state.copy(query = "auro").visibleFaces.names())
        assertEquals(listOf("Aurora"), state.copy(query = "northern").visibleFaces.names())
        assertEquals(listOf("Zenith"), state.copy(query = "00112").visibleFaces.names())
        assertEquals(listOf("Zenith"), state.copy(query = "face112").visibleFaces.names())
    }

    @Test
    fun searchingTheCatalogueIgnoresCaseAndSurroundingSpace() {
        val state = LibraryUiState(faces = faces, query = "  AURORA  ")
        assertEquals(listOf("Aurora"), state.visibleFaces.names())
    }

    @Test
    fun reversingTheCatalogueSortReachesTheOtherEndOfTheList() {
        val state = LibraryUiState(faces = faces, sort = CatalogSort.NAME)
        assertEquals(listOf("Aurora", "Bold", "Zenith"), state.visibleFaces.names())
        assertEquals(
            listOf("Zenith", "Bold", "Aurora"),
            state.copy(sortReversed = true).visibleFaces.names(),
        )
    }

    // -- Projects search ----------------------------------------------------

    @Test
    fun searchingProjectsMatchesTheNameSomeoneGaveIt() {
        // The point of naming them: the face's own name is the same on every project
        // started from it, so it is the *project* name that has to be searchable.
        val state = LibraryUiState(
            projects = listOf(
                project(1, name = "Night shift", faceName = "Black and white"),
                project(2, name = "Bold hours", faceName = "Black and white"),
            ),
        )
        assertEquals(listOf("Night shift"), state.copy(projectQuery = "night").visibleProjects.map { it.name })
        // The face name still matches, and matches both.
        assertEquals(2, state.copy(projectQuery = "black").visibleProjects.size)
    }

    @Test
    fun searchingProjectsMatchesTheFaceNumber() {
        val state = LibraryUiState(
            projects = listOf(project(1, faceId = "00112"), project(2, faceId = "00022")),
        )
        assertEquals(listOf(1L), state.copy(projectQuery = "00112").visibleProjects.map { it.id })
    }

    @Test
    fun reversingTheProjectSortReachesTheOtherEndOfTheList() {
        val state = LibraryUiState(
            projects = listOf(
                project(1, name = "Older", updatedAt = 100),
                project(2, name = "Newer", updatedAt = 900),
            ),
            projectSort = ProjectSort.RECENT,
        )
        assertEquals(listOf("Newer", "Older"), state.visibleProjects.map { it.name })
        assertEquals(
            listOf("Older", "Newer"),
            state.copy(projectSortReversed = true).visibleProjects.map { it.name },
        )
    }

    // -- The face sheet's own list ------------------------------------------

    @Test
    fun theSheetShowsOnlyThisFacesProjectsMostRecentlyEditedFirst() {
        val state = LibraryUiState(
            projects = listOf(
                project(1, name = "Other face", faceId = "00022", updatedAt = 999),
                project(2, name = "Older", faceId = "00112", updatedAt = 100),
                project(3, name = "Newer", faceId = "00112", updatedAt = 500),
            ),
        )
        assertEquals(listOf("Newer", "Older"), state.projectsFor("00112").map { it.name })
    }

    @Test
    fun theSheetsOrderIgnoresWhateverTheProjectsPageIsSortedBy() {
        // Someone arrives at the sheet to carry on where they left off, not to read an
        // index. The Projects page's chosen order is about that page.
        val state = LibraryUiState(
            projects = listOf(
                project(1, name = "Aaa", faceId = "00112", updatedAt = 100),
                project(2, name = "Zzz", faceId = "00112", updatedAt = 900),
            ),
            projectSort = ProjectSort.NAME,
            projectSortReversed = true,
        )
        assertEquals(listOf("Zzz", "Aaa"), state.projectsFor("00112").map { it.name })
    }

    @Test
    fun aFaceWithNoProjectsHasAnEmptySheetList() {
        assertTrue(LibraryUiState(projects = listOf(project(1, faceId = "00022"))).projectsFor("00112").isEmpty())
    }

    // -- The one button -----------------------------------------------------

    @Test
    fun aFaceWithNoProjectsOffersADownload() {
        assertEquals(FaceAction.DOWNLOAD, action(projects = emptyList()))
    }

    @Test
    fun aFaceThatAlreadyHasAProjectDoesNotOfferToDownloadItAgain() {
        // The complaint this whole change starts from.
        assertEquals(FaceAction.NEW_PROJECT, action(projects = listOf(project(1, versionCode = 40001))))
    }

    @Test
    fun aNewerStoreVersionTurnsTheButtonIntoAnUpdate() {
        assertEquals(
            FaceAction.UPDATE,
            action(projects = listOf(project(1, versionCode = 40000)), storeVersionCode = 40001),
        )
    }

    @Test
    fun oneOutdatedProjectIsEnoughToOfferTheUpdate() {
        assertEquals(
            FaceAction.UPDATE,
            action(
                projects = listOf(project(1, versionCode = 40001), project(2, versionCode = 40000)),
                storeVersionCode = 40001,
            ),
        )
    }

    @Test
    fun aProjectOfUnknownVersionNeverOffersAnUpdate() {
        assertEquals(
            FaceAction.NEW_PROJECT,
            action(projects = listOf(project(1, versionCode = null)), storeVersionCode = 40001),
        )
    }

    @Test
    fun notEditableBeatsEveryOfferButTheOneInFlight() {
        assertEquals(
            FaceAction.NOT_EDITABLE,
            action(uneditable = true, projects = listOf(project(1, versionCode = 40000)), storeVersionCode = 40001),
        )
        assertEquals(
            FaceAction.DOWNLOADING,
            action(downloading = true, uneditable = true, projects = emptyList()),
        )
    }

    @Test
    fun aCachedPackageIsOpened_notDownloaded() {
        // "Downloading…" above a caption saying nothing would be downloaded is the same
        // dishonesty this screen exists to fix.
        assertEquals(
            FaceAction.OPENING,
            action(downloading = true, packageOnDevice = true, projects = emptyList()),
        )
        assertEquals(
            FaceAction.DOWNLOADING,
            action(downloading = true, packageOnDevice = false, projects = emptyList()),
        )
    }

    // -- Gating -------------------------------------------------------------

    @Test
    fun theSheetOpensDuringTheLaunchWindowButNotDuringADownload() {
        assertTrue(LibraryUiState(isLoadingCatalog = true).canSelectFace)
        assertFalse(LibraryUiState(downloadingProductId = "p").canSelectFace)
        assertFalse(LibraryUiState(isOpeningProject = true).canSelectFace)
    }

    private fun action(
        downloading: Boolean = false,
        uneditable: Boolean = false,
        packageOnDevice: Boolean = false,
        projects: List<ProjectSummary>,
        storeVersionCode: Long = 40001,
    ) = faceAction(downloading, uneditable, packageOnDevice, projects, storeVersionCode)

    private fun List<CatalogFace>.names() = map(CatalogFace::name)

    private val faces = listOf(
        face("Zenith", "00112", "A plain readout"),
        face("Aurora", "00022", "Northern lights"),
        face("Bold", "00030", "Large numerals"),
    )

    private fun face(name: String, faceId: String, description: String) = CatalogFace(
        productId = "product-$faceId",
        faceId = faceId,
        name = name,
        description = description,
        appId = "dev.fitface.face${faceId.trimStart('0')}",
        versionName = "4.0.1",
        versionCode = 40001,
        packageSize = 1,
        styles = emptyList(),
    )

    private fun project(
        id: Long,
        name: String = "Project $id",
        faceId: String = "00112",
        faceName: String? = "Black and white",
        updatedAt: Long = 0,
        versionCode: Long? = 40001,
    ) = ProjectSummary(
        id = id,
        displayName = "$faceName.apk",
        sourceUri = "${FacePackage.SOURCE_SCHEME}product-$faceId/$versionCode/0",
        faceId = faceId,
        faceName = faceName,
        importedAtEpochMillis = updatedAt,
        name = name,
        styleId = 0,
        packageVersionCode = versionCode,
        updatedAtEpochMillis = updatedAt,
    )
}
