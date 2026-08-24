package dev.fitface.studio.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Every semantic colour has to be readable on the theme it is shown in.
 *
 * `FitFaceSemanticColors` used to hold one pair of values, tuned for the near-black
 * surfaces, and `FitFaceTheme` never provided the composition local at all — so the light
 * scheme silently got the dark colours. Amber `#F2C879` on the light background measures
 * **1.50:1** and violet `#C8B6FF` **1.73:1**, against a 4.5:1 floor, and the two of them
 * carry nine call sites between them: the OPAQUE banner on the inspector, the EDITED badge,
 * the "Report crash" action, the device-status badge, the transfer phase dot. On a light-mode
 * phone all of it was washed out to the point of being unreadable, and nothing in the build
 * said so.
 *
 * This is deliberately a plain JVM test. `Color` is a value class over a packed Long and the
 * WCAG formula is arithmetic, so pinning the palette needs no Android runtime, no
 * Robolectric, and no rendering — which means it cannot flake and it runs in milliseconds.
 */
class SemanticColorContrastTest {

    /**
     * WCAG 2.1 relative luminance, then the contrast ratio between two opaque colours.
     *
     * Kept in the test rather than in production code because nothing in the app needs to
     * compute this at runtime — the palette is a constant, so the check belongs at build
     * time.
     */
    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Every surface a semantic colour is actually drawn as text on. */
    private fun surfacesOf(scheme: ColorScheme) = mapOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
    )

    private fun assertReadable(
        themeName: String,
        scheme: ColorScheme,
        semantic: FitFaceSemanticColors,
    ) {
        val colors = mapOf(
            "warning" to semantic.warning,
            "experimental" to semantic.experimental,
        )
        for ((colorName, color) in colors) {
            for ((surfaceName, surface) in surfacesOf(scheme)) {
                val ratio = contrastRatio(color, surface)
                assertTrue(
                    "$themeName $colorName on $surfaceName is %.2f:1, under the 4.5:1 floor"
                        .format(ratio),
                    ratio >= MINIMUM_CONTRAST,
                )
            }
        }
    }

    @Test
    fun darkSemanticColorsAreReadableOnDarkSurfaces() {
        assertReadable("dark", DarkColors, DarkSemanticColors)
    }

    /** The half that was broken: these were the dark values until the theme provided a set. */
    @Test
    fun lightSemanticColorsAreReadableOnLightSurfaces() {
        assertReadable("light", LightColors, LightSemanticColors)
    }

    /**
     * The dark values on a light surface are the exact defect this class exists for, so the
     * numbers are asserted rather than described. If a future palette change makes the dark
     * amber legible on white, this test has stopped guarding anything and should be revisited
     * rather than deleted.
     */
    @Test
    fun theDarkValuesWouldFailOnTheLightScheme() {
        val amberOnLight = contrastRatio(DarkSemanticColors.warning, LightColors.background)
        val violetOnLight =
            contrastRatio(DarkSemanticColors.experimental, LightColors.background)
        assertTrue(
            "dark amber on the light background is %.2f:1, expected under the floor"
                .format(amberOnLight),
            amberOnLight < MINIMUM_CONTRAST,
        )
        assertTrue(
            "dark violet on the light background is %.2f:1, expected under the floor"
                .format(violetOnLight),
            violetOnLight < MINIMUM_CONTRAST,
        )
    }

    /** `fitSemanticColorsFor` is the seam the theme uses; it must not hand back one set. */
    @Test
    fun theThemeSelectsADifferentSetPerMode() {
        assertTrue(
            "light and dark resolve to the same semantic colours",
            fitSemanticColorsFor(darkTheme = true) != fitSemanticColorsFor(darkTheme = false),
        )
    }

    private companion object {
        /** WCAG AA for body text. The banners and badges are small, so AA is the floor. */
        const val MINIMUM_CONTRAST = 4.5
    }
}
