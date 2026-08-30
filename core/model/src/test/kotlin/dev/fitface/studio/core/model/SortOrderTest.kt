package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SortOrderTest {
    @Test
    fun newestIsWhateverOrderTheCatalogueServed() {
        assertEquals(catalogue, CatalogSort.RECENT.apply(catalogue))
    }

    @Test
    fun newestReversedIsNotANoOp() {
        // The whole point of the reversible chip. `RECENT` has no key to sort on — the
        // store already serves newest first — so reading "nothing to do" in both
        // directions would leave the chip inert with no way to tell.
        assertEquals(catalogue.reversed(), CatalogSort.RECENT.apply(catalogue, reversed = true))
    }

    @Test
    fun nameSortsAToZAndBack() {
        assertEquals(
            listOf("Aurora", "aurora dusk", "Bold", "Zenith"),
            CatalogSort.NAME.apply(catalogue).map(CatalogFace::name),
        )
        assertEquals(
            listOf("Zenith", "Bold", "aurora dusk", "Aurora"),
            CatalogSort.NAME.apply(catalogue, reversed = true).map(CatalogFace::name),
        )
    }

    @Test
    fun theFaceNumberTiebreakDoesNotFlipWithTheName() {
        // Reverse the comparator, never the sorted list. Reversing the result would flip
        // the tiebreak too, so faces sharing a name would swap places for a reason nothing
        // on screen explains.
        val sameName = listOf(face("Twin", 40), face("Twin", 10), face("Twin", 25))
        assertEquals(
            listOf(10, 25, 40),
            CatalogSort.NAME.apply(sameName).map(CatalogFace::faceNumber),
        )
        assertEquals(
            listOf(10, 25, 40),
            CatalogSort.NAME.apply(sameName, reversed = true).map(CatalogFace::faceNumber),
        )
    }

    @Test
    fun faceNumberSortsBothWays() {
        assertEquals(
            listOf(1, 22, 30, 112),
            CatalogSort.NUMBER.apply(catalogue).map(CatalogFace::faceNumber),
        )
        assertEquals(
            listOf(112, 30, 22, 1),
            CatalogSort.NUMBER.apply(catalogue, reversed = true).map(CatalogFace::faceNumber),
        )
    }

    @Test
    fun everyCatalogueSortKeepsEveryFace() {
        for (sort in CatalogSort.entries) {
            for (reversed in listOf(false, true)) {
                assertEquals(
                    "$sort reversed=$reversed",
                    catalogue.toSet(),
                    sort.apply(catalogue, reversed).toSet(),
                )
            }
        }
    }

    @Test
    fun projectsSortOnWhenTheyWereEditedNotWhenTheyWereOpened() {
        // `importedAtEpochMillis` is bumped by merely opening a project, which is what made
        // two projects on one face trade places every time either was looked at.
        val projects = listOf(
            project(id = 1, name = "Older edit", updatedAt = 100, importedAt = 900),
            project(id = 2, name = "Newer edit", updatedAt = 500, importedAt = 200),
        )
        assertEquals(
            listOf("Newer edit", "Older edit"),
            ProjectSort.RECENT.apply(projects).map(ProjectSummary::name),
        )
        assertEquals(
            listOf("Older edit", "Newer edit"),
            ProjectSort.RECENT.apply(projects, reversed = true).map(ProjectSummary::name),
        )
    }

    @Test
    fun projectsSortByNameAndFaceNumberBothWays() {
        val projects = listOf(
            project(id = 1, name = "Zenith", faceId = "00112"),
            project(id = 2, name = "Aurora", faceId = "00022"),
        )
        assertEquals(
            listOf("Aurora", "Zenith"),
            ProjectSort.NAME.apply(projects).map(ProjectSummary::name),
        )
        assertEquals(
            listOf("Zenith", "Aurora"),
            ProjectSort.NAME.apply(projects, reversed = true).map(ProjectSummary::name),
        )
        assertEquals(
            listOf("00022", "00112"),
            ProjectSort.NUMBER.apply(projects).map(ProjectSummary::faceId),
        )
        assertEquals(
            listOf("00112", "00022"),
            ProjectSort.NUMBER.apply(projects, reversed = true).map(ProjectSummary::faceId),
        )
    }

    @Test
    fun twoProjectsWithTheSameNameOnTheSameFaceKeepAStableOrder() {
        // Legacy rows the schema 5 backfill could not name can still collide. An unstable
        // order would make them swap on every recomposition.
        val projects = listOf(
            project(id = 2, name = "Twin", faceId = "00112", updatedAt = 0, importedAt = 0),
            project(id = 1, name = "Twin", faceId = "00112", updatedAt = 0, importedAt = 0),
        )
        for (sort in ProjectSort.entries) {
            assertEquals(
                "$sort",
                sort.apply(projects).map(ProjectSummary::id),
                sort.apply(projects).map(ProjectSummary::id),
            )
        }
        assertEquals(listOf(1L, 2L), ProjectSort.NAME.apply(projects).map(ProjectSummary::id))
    }

    @Test
    fun anUnknownPackageVersionIsNeverOutOfDate() {
        // A project imported before the app recorded a version has nothing to compare, and
        // badging it would send someone to re-download a face that is already current.
        assertFalse(project(id = 1, versionCode = null).isOutdated(40002))
        assertTrue(project(id = 1, versionCode = 40001).isOutdated(40002))
        assertFalse(project(id = 1, versionCode = 40002).isOutdated(40002))
    }

    @Test
    fun aStaleCatalogueDoesNotMakeANewerProjectLookOutOfDate() {
        assertFalse(project(id = 1, versionCode = 40003).isOutdated(40002))
    }

    private val catalogue = listOf(
        face("Zenith", 112),
        face("Aurora", 22),
        face("Bold", 30),
        face("aurora dusk", 1),
    )

    private fun face(name: String, number: Int) = CatalogFace(
        productId = "product-$number",
        faceId = number.toString().padStart(5, '0'),
        name = name,
        description = "",
        appId = "dev.fitface.face$number",
        versionName = "1.0.0",
        versionCode = 1,
        packageSize = 0,
        styles = emptyList(),
    )

    private fun project(
        id: Long,
        name: String = "Project $id",
        faceId: String = "00112",
        updatedAt: Long = 0,
        importedAt: Long = 0,
        versionCode: Long? = 40001,
    ) = ProjectSummary(
        id = id,
        displayName = "$name.apk",
        sourceUri = "${FacePackage.SOURCE_SCHEME}product/$versionCode/0",
        faceId = faceId,
        faceName = name,
        importedAtEpochMillis = importedAt,
        name = name,
        packageVersionCode = versionCode,
        updatedAtEpochMillis = updatedAt,
    )
}
