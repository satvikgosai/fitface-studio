package dev.fitface.studio.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The quiet tiers of text have to stay readable, alpha included.
 *
 * `onSurfaceVariant` is already the dim role, and the app used to dim it *again* — nineteen
 * call sites reaching for `.copy(alpha = .68f)`, or .72f, or .66f, or .48f, each chosen by
 * hand. On the small styles the second dimming took the text under the floor: `MicroLabel` at
 * 9.5sp measured **4.08:1** in the dark theme and **3.38:1** in the light one, a project's
 * timestamp **2.58:1**, and none of those sizes are large enough for the 3:1 allowance, which
 * starts around 24sp. A reader called the editor's small text almost unreadable.
 *
 * `FitFaceTextColors` replaced every one of those with two named tiers, so this test only has
 * to hold two values per scheme rather than chase call sites. What it cannot do is notice a
 * *new* hand-rolled alpha appearing somewhere, so the rule lives in the KDoc on
 * `FitFaceTextColors` as well: nothing else may be used to dim text.
 *
 * Plain JVM, like `SemanticColorContrastTest` — `Color` is a value class and the WCAG formula
 * is arithmetic, so this needs no Android runtime and cannot flake.
 */
class SmallTextContrastTest {

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /**
     * Text colours here carry an alpha, so they have to be composited onto the surface before
     * being measured — the ratio of a translucent colour is meaningless on its own, and taking
     * it at face value is how these values passed for so long.
     */
    private fun composite(foreground: Color, surface: Color): Color {
        val a = foreground.alpha
        return Color(
            red = foreground.red * a + surface.red * (1 - a),
            green = foreground.green * a + surface.green * (1 - a),
            blue = foreground.blue * a + surface.blue * (1 - a),
        )
    }

    private fun contrastRatio(foreground: Color, surface: Color): Double {
        val la = relativeLuminance(composite(foreground, surface))
        val lb = relativeLuminance(surface)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Every surface the app actually sets quiet text on. */
    private fun surfacesOf(scheme: ColorScheme) = mapOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
    )

    private fun assertReadable(themeName: String, scheme: ColorScheme) {
        val text = fitTextColorsFor(scheme)
        val tiers = mapOf("secondary" to text.secondary, "tertiary" to text.tertiary)
        for ((tierName, color) in tiers) {
            for ((surfaceName, surface) in surfacesOf(scheme)) {
                val ratio = contrastRatio(color, surface)
                assertTrue(
                    "$themeName $tierName on $surfaceName is %.2f:1, under the 4.5:1 floor"
                        .format(ratio),
                    ratio >= MINIMUM_CONTRAST,
                )
            }
        }
    }

    @Test
    fun darkQuietTextIsReadable() = assertReadable("dark", DarkColors)

    @Test
    fun lightQuietTextIsReadable() = assertReadable("light", LightColors)

    /**
     * The tiers have to stay visibly different, or the hierarchy they exist for is gone and
     * someone will reintroduce a hand-rolled alpha to get it back.
     */
    @Test
    fun theTwoTiersAreDistinguishable() {
        for (scheme in listOf(DarkColors, LightColors)) {
            val text = fitTextColorsFor(scheme)
            assertTrue(
                "the two text tiers resolved to the same colour",
                text.secondary != text.tertiary,
            )
        }
    }

    /**
     * Pins the choice of `TertiaryTextAlpha` rather than only its consequence. .82f is the
     * lowest value that clears the floor in both schemes and it lands on 4.52:1 in the light
     * one — so close that a small palette change would fail it silently. This asserts the
     * chosen alpha keeps real headroom.
     */
    @Test
    fun theTertiaryTierKeepsHeadroomAboveTheFloor() {
        for ((name, scheme) in listOf("dark" to DarkColors, "light" to LightColors)) {
            val worst = surfacesOf(scheme).values.minOf { surface ->
                contrastRatio(fitTextColorsFor(scheme).tertiary, surface)
            }
            assertTrue(
                "$name tertiary text is only %.2f:1 at its worst, too near the 4.5:1 floor"
                    .format(worst),
                worst >= COMFORTABLE_CONTRAST,
            )
        }
    }

    private companion object {
        /** WCAG AA for text below the large-text threshold, which is all of this. */
        const val MINIMUM_CONTRAST = 4.5

        /** Enough margin that a palette tweak shows up as a failure, not as a near miss. */
        const val COMFORTABLE_CONTRAST = 5.0
    }
}
