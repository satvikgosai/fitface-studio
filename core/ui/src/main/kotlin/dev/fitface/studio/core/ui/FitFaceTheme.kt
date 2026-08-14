package dev.fitface.studio.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFFE6EDEA)
private val Teal = Color(0xFF7FE3D2)
private val DeepTeal = Color(0xFF00504A)
private val Coral = Color(0xFFFFAF9F)
private val Amber = Color(0xFFF2C879)
private val Violet = Color(0xFFC8B6FF)

private val DarkColors = darkColorScheme(
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

private val LightColors = lightColorScheme(
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

@Immutable
data class FitFaceSpacing(
    val hair: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 28.dp,
    val gutter: Dp = 40.dp,
)

@Immutable
data class FitFaceSemanticColors(
    val warning: Color = Amber,
    val experimental: Color = Violet,
)

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
}

object FitFaceElevation {
    val base = 0.dp
    val raised = 1.dp
    val card = 2.dp
    val sheet = 4.dp
}

val LocalFitFaceSpacing = staticCompositionLocalOf { FitFaceSpacing() }
val LocalFitFaceSemanticColors = staticCompositionLocalOf { FitFaceSemanticColors() }

val MaterialTheme.fitSpacing: FitFaceSpacing
    @Composable get() = LocalFitFaceSpacing.current

val MaterialTheme.fitColors: FitFaceSemanticColors
    @Composable get() = LocalFitFaceSemanticColors.current

@Composable
fun FitFaceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = FitTypography,
        shapes = FitShapes,
        content = content,
    )
}
