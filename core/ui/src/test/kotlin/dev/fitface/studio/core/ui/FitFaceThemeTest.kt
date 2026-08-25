package dev.fitface.studio.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `FitFaceTheme` has to actually hand its semantic colours down.
 *
 * `LocalFitFaceSemanticColors` was declared and then never provided — there was no
 * `CompositionLocalProvider` anywhere in the repository — so `MaterialTheme.fitColors`
 * resolved to the composition local's own default in both themes. Giving the light scheme
 * its own values fixes nothing on its own; the provider is the half that carries them, and
 * this is the test that says so. `SemanticColorContrastTest` proves the values are legible,
 * and this proves they are the ones a composable actually reads.
 */
@RunWith(RobolectricTestRunner::class)
class FitFaceThemeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun semanticColorsUnder(darkTheme: Boolean): FitFaceSemanticColors {
        lateinit var seen: FitFaceSemanticColors
        compose.setContent {
            FitFaceTheme(darkTheme = darkTheme) {
                seen = MaterialTheme.fitColors
            }
        }
        return seen
    }

    @Test
    fun theDarkThemeReadsTheDarkSemanticColors() {
        assertEquals(DarkSemanticColors, semanticColorsUnder(darkTheme = true))
    }

    @Test
    fun theLightThemeReadsTheLightSemanticColors() {
        assertEquals(LightSemanticColors, semanticColorsUnder(darkTheme = false))
    }

    /**
     * The colour scheme was always switched correctly; it is only the semantic pair that was
     * not. Asserted so a regression in the provider cannot be mistaken for one here.
     */
    @Test
    fun theColorSchemeStillFollowsTheMode() {
        // Color is a value class, so these cannot be `lateinit`.
        var dark: Color? = null
        var light: Color? = null
        compose.setContent {
            FitFaceTheme(darkTheme = true) { dark = MaterialTheme.colorScheme.background }
            FitFaceTheme(darkTheme = false) { light = MaterialTheme.colorScheme.background }
        }
        assertEquals(DarkColors.background, dark)
        assertEquals(LightColors.background, light)
    }
}
