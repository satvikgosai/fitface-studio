package dev.fitface.studio.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h640dp-xhdpi")
class FitInfoTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun infoOpensByClickAsALabelledButtonAndHidesUntilThen() {
        compose.setContent {
            FitFaceTheme(darkTheme = true) {
                FitHeading("Size", info = { InfoText("Why the size cannot change") })
            }
        }
        compose.onNodeWithText("Why the size cannot change").assertDoesNotExist()
        val glyph = compose.onNodeWithContentDescription("About Size")
        glyph.assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

        glyph.performClick()
        compose.onNodeWithText("Why the size cannot change").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Size")).assertExists()
    }

    @Test
    fun aTagOpensTheSameBubble() {
        compose.setContent {
            FitFaceTheme(darkTheme = false) {
                FitTag("OPAQUE", MaterialTheme.fitColors.warning) { InfoText("Its whole box draws") }
            }
        }
        compose.onNodeWithContentDescription("About OPAQUE").performClick()
        compose.onNodeWithText("Its whole box draws").assertExists()
    }

    @Test
    fun bubbleSitsAboveItsTriggerWhenItFitsAndBelowWhenItDoesNot() {
        val provider = BubblePositionProvider(gap = 10, margin = 30)
        val window = IntSize(600, 1200)
        val bubble = IntSize(400, 200)
        val low = provider.calculatePosition(IntRect(280, 700, 320, 740), window, LayoutDirection.Ltr, bubble)
        assertEquals(700 - 10 - 200, low.y)
        assertEquals(100, low.x)
        val high = provider.calculatePosition(IntRect(10, 40, 50, 80), window, LayoutDirection.Ltr, bubble)
        assertEquals(80 + 10, high.y)
        // Centred on a trigger at the edge would hang off the window; it is kept inside.
        assertEquals(30, high.x)
    }
}
