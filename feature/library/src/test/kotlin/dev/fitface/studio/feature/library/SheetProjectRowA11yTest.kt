package dev.fitface.studio.feature.library

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import dev.fitface.studio.core.model.ProjectSummary
import dev.fitface.studio.core.ui.FitFaceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * That a row in the face sheet says everything it shows.
 *
 * The row sets its own `contentDescription`, which **replaces** the label a screen reader
 * would otherwise assemble from the text inside it. So the OUTDATED badge — the one thing
 * distinguishing this row from the siblings above and below it, all of which carry the same
 * face and often near-identical names — was drawn and never announced.
 *
 * Asserted on the semantics rather than on the pixels for the reason `FitTopBarLayoutTest`
 * gives about Robolectric's font metrics: what is in the accessibility tree is exact, where
 * what fits on a line is not.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class SheetProjectRowA11yTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun anOutdatedRowSaysSoToAScreenReader() {
        compose.setContent {
            FitFaceTheme {
                SheetProjectRow(project = project(), outdated = true, enabled = true, onOpen = {})
            }
        }

        compose.onNodeWithContentDescription("outdated", substring = true, ignoreCase = true)
            .assertExists()
    }

    @Test
    fun aCurrentRowDoesNotClaimToBeOutdated() {
        compose.setContent {
            FitFaceTheme {
                SheetProjectRow(project = project(), outdated = false, enabled = true, onOpen = {})
            }
        }

        val tree = compose.onRoot().printToString()
        assertTrue(
            "a project on the newest version must not be described as outdated:\n$tree",
            !tree.contains("outdated", ignoreCase = true),
        )
    }

    /**
     * The parts that were already there have to survive the extra clause, or the fix has
     * traded one missing fact for another.
     */
    @Test
    fun theDescriptionStillCarriesTheNameTheStyleAndTheAge() {
        compose.setContent {
            FitFaceTheme {
                SheetProjectRow(project = project(), outdated = true, enabled = true, onOpen = {})
            }
        }

        compose.onNodeWithContentDescription("Night mode", substring = true)
            .assertContentDescriptionContains("outdated", substring = true, ignoreCase = true)
        compose.onNodeWithContentDescription("face 00112", substring = true).assertExists()
        // styleId 2 is shown as "style 03" — the sheet's thumbnails are one-based.
        compose.onNodeWithContentDescription("style 03", substring = true).assertExists()
    }

    private fun project() = ProjectSummary(
        id = 7,
        displayName = "Black and white.apk",
        sourceUri = "fit3-catalog://000007255362/40001/2",
        faceId = "00112",
        faceName = "Black or white",
        importedAtEpochMillis = 1_000L,
        // Null on purpose: a path would send the thumbnail through Coil, which has no
        // business being loaded to assert a string.
        previewImagePath = null,
        name = "Night mode",
        styleId = 2,
        packageVersionCode = 40_000,
        updatedAtEpochMillis = 1_000L,
    )
}
