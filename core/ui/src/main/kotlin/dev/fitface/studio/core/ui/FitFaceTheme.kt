package dev.fitface.studio.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFFE6EDEA)
private val Teal = Color(0xFF7FE3D2)
private val DeepTeal = Color(0xFF00504A)
private val Coral = Color(0xFFFFAF9F)
private val Amber = Color(0xFFF2C879)
private val Violet = Color(0xFFC8B6FF)

// Internal rather than private so the contrast test can pair each scheme with the
// semantic colours that sit on it. Nothing outside this module reads them; the theme is
// still the only way to apply one.
internal val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF062E29),
    primaryContainer = DeepTeal,
    onPrimaryContainer = Color(0xFFC2FFF4),
    secondary = Amber,
    onSecondary = Color(0xFF3E2E00),
    secondaryContainer = Color(0xFF3B321D),
    onSecondaryContainer = Color(0xFFFFE4A8),
    tertiary = Violet,
    onTertiary = Color(0xFF30265A),
    tertiaryContainer = Color(0xFF453A70),
    onTertiaryContainer = Color(0xFFE9DDFF),
    background = Color(0xFF0A0E0E),
    onBackground = Ink,
    surface = Color(0xFF0A0E0E),
    surfaceVariant = Color(0xFF18201F),
    surfaceContainerLowest = Color(0xFF080B0B),
    surfaceContainerLow = Color(0xFF101615),
    surfaceContainer = Color(0xFF18201F),
    surfaceContainerHigh = Color(0xFF222B29),
    surfaceContainerHighest = Color(0xFF2B3533),
    onSurface = Ink,
    onSurfaceVariant = Color(0xFF99A5A1),
    outline = Color(0xFF596460),
    outlineVariant = Color(0xFF2C3533),
    error = Coral,
    onError = Color(0xFF51130B),
    errorContainer = Color(0xFF6B2118),
    onErrorContainer = Color(0xFFFFDAD3),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF006B60),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC0F3E9),
    onPrimaryContainer = Color(0xFF003731),
    secondary = Color(0xFF775B16),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6A9),
    onSecondaryContainer = Color(0xFF251A00),
    tertiary = Color(0xFF62558B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8DDFF),
    onTertiaryContainer = Color(0xFF1E143F),
    background = Color(0xFFF7FAF9),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF7FAF9),
    surfaceVariant = Color(0xFFE4ECE9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F3),
    surfaceContainer = Color(0xFFEAEFED),
    surfaceContainerHigh = Color(0xFFE1E8E5),
    surfaceContainerHighest = Color(0xFFD8E0DD),
    onSurface = Color(0xFF171D1B),
    onSurfaceVariant = Color(0xFF4A5551),
    outline = Color(0xFF75817D),
    outlineVariant = Color(0xFFC5CECB),
    error = Color(0xFFA73A2E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF3F0501),
)

private val FitTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp,
    ),
)

private val FitShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

/**
 * The two meanings the Material scheme has no slot for.
 *
 * `warning` is not `error`: it marks a face the watch will still accept but render
 * differently from the preview — an opaque backdrop, an edit not yet installed — where
 * `error` means the container is refused. `experimental` is reserved for the direct-install
 * path, which is proven on one watch model and nothing else.
 *
 * These are per-theme because the dark values are tuned for a near-black surface: amber
 * `#F2C879` measures 1.50:1 on the light background and violet `#C8B6FF` 1.73:1, both far
 * under the 4.5:1 floor. `SemanticColorContrastTest` holds the line for both schemes.
 */
@Immutable
data class FitFaceSemanticColors(
    val warning: Color,
    val experimental: Color,
)

internal val DarkSemanticColors = FitFaceSemanticColors(
    warning = Amber,
    experimental = Violet,
)

/**
 * The light counterparts, taken from `LightColors`' own `secondary` and `tertiary`.
 *
 * They are the M3-generated light forms of the same two hues, so the light theme stays one
 * palette rather than gaining two invented colours, and both clear the contrast floor with
 * room to spare (6.07:1 and 6.27:1 on `#F7FAF9`).
 */
internal val LightSemanticColors = FitFaceSemanticColors(
    warning = Color(0xFF775B16),
    experimental = Color(0xFF62558B),
)

internal fun fitSemanticColorsFor(darkTheme: Boolean): FitFaceSemanticColors =
    if (darkTheme) DarkSemanticColors else LightSemanticColors

/**
 * The two quiet tiers of text, as colours rather than as an alpha to apply by hand.
 *
 * They exist because the app used to reach for `onSurfaceVariant.copy(alpha = .68f)` — and
 * .72f, and .66f, and .48f — at nineteen call sites. `onSurfaceVariant` **is already the dim
 * role**, so multiplying it dimmed the text twice, and on the smaller styles that pushed it
 * under the readable floor: `MicroLabel` at 9.5sp measured 4.08:1 in the dark theme and
 * 3.38:1 in the light one, and a project's timestamp reached 2.58:1. Someone reading the
 * editor described its small text as almost unreadable, which those numbers bear out.
 *
 * So there is no alpha to choose at a call site any more. Use [secondary] for meta lines,
 * subtitles and section headings, and [tertiary] for the quietest line in a stack of three.
 * `SmallTextContrastTest` holds both to 4.5:1 in both schemes; nothing else may be used to
 * dim text.
 *
 * Disabled controls are a different matter and keep their own alphas — a control that cannot
 * be operated is exempt from the contrast floor and needs to look inert.
 */
@Immutable
data class FitFaceTextColors(
    val secondary: Color,
    val tertiary: Color,
)

/**
 * Chosen for headroom, not for the boundary. .82f is the lowest alpha that clears 4.5:1 in
 * both schemes and it lands on 4.52:1 in the light one, close enough that a future palette
 * tweak would silently fail it; .90f gives 5.49:1 there and 6.05:1 in the dark.
 */
internal const val TertiaryTextAlpha = .90f

internal fun fitTextColorsFor(scheme: ColorScheme) = FitFaceTextColors(
    secondary = scheme.onSurfaceVariant,
    tertiary = scheme.onSurfaceVariant.copy(alpha = TertiaryTextAlpha),
)

val MaterialTheme.fitText: FitFaceTextColors
    @Composable get() = fitTextColorsFor(MaterialTheme.colorScheme)

/**
 * The mono slots Material's ramp has no room for.
 *
 * The Material scale is entirely proportional, and in this app the numerals *are* the
 * content — a coordinate, a byte count, a sequence id. A proportional digit changes width
 * as it changes value, so a figure being nudged jitters while you hold the button. Anything
 * quantitative goes in one of these, or in `labelMedium`/`labelSmall`, which are mono for
 * the same reason.
 */
object FitFaceType {
    val numeric = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    val micro = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.3.sp,
    )

    /**
     * A figure large enough to be the subject of its own card — the inspector's X and Y, a
     * sprite's size. `headlineSmall` used to do this job, and being proportional it was the
     * exact case the paragraph above describes: the nudge buttons sit directly under the
     * number they change.
     */
    val readout = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    )
}

val LocalFitFaceSemanticColors = staticCompositionLocalOf { DarkSemanticColors }

val MaterialTheme.fitColors: FitFaceSemanticColors
    @Composable get() = LocalFitFaceSemanticColors.current

@Composable
fun FitFaceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The semantic pair is not part of a Material scheme, so it has to be provided
    // alongside one. Without this the composition local falls back to its own default and
    // every warning in the app is amber-on-white in the light theme; see
    // FitFaceThemeTest and SemanticColorContrastTest.
    CompositionLocalProvider(
        LocalFitFaceSemanticColors provides fitSemanticColorsFor(darkTheme),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = FitTypography,
            shapes = FitShapes,
            content = content,
        )
    }
}
