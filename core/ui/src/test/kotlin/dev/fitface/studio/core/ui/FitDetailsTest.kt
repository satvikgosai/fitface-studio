package dev.fitface.studio.core.ui

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h640dp-xhdpi")
class FitDetailsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun helpOpensByClickAnnouncesItsStateAndSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            FitFaceTheme(darkTheme = true) {
                FitDetails(label = "Preview details") { Text("Full preview explanation") }
            }
        }
        val heading = compose.onNodeWithText("PREVIEW DETAILS")
        heading.assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithText("Full preview explanation").assertDoesNotExist()

        heading.performClick()
        heading.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithText("Full preview explanation").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Full preview explanation").assertIsDisplayed()
        heading.performClick()
        compose.onNodeWithText("Full preview explanation").assertDoesNotExist()
    }
}
