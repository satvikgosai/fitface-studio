package dev.fitface.studio.core.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A widget colour as the editor handles it: opaque RGB, written as `#RRGGBB`.
 *
 * Alpha is not part of it. The watch never reads a colour word's alpha byte, and every
 * colour word in the catalogue stores 0xFF, so a picker that offered transparency would
 * offer a control that does nothing.
 */
fun colorHex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

/** An opaque colour from `#RRGGBB` or `RRGGBB`, or null for anything else. */
fun parseColorHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6 || digits.any { it.digitToIntOrNull(16) == null }) return null
    return digits.toInt(16) or OPAQUE
}

/** Hue in degrees `[0, 360)`, saturation and brightness in `[0, 1]`. */
data class Hsv(val hue: Float, val saturation: Float, val value: Float)

fun hsvOf(argb: Int): Hsv {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val delta = max - minOf(r, g, b)
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta).mod(6f))
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    return Hsv(hue, if (max == 0f) 0f else delta / max, max)
}

fun colorOf(hsv: Hsv): Int {
    val hue = hsv.hue.mod(360f)
    val saturation = hsv.saturation.coerceIn(0f, 1f)
    val value = hsv.value.coerceIn(0f, 1f)
    val chroma = value * saturation
    val x = chroma * (1 - abs((hue / 60f).mod(2f) - 1))
    val (r, g, b) = when ((hue / 60f).toInt()) {
        0 -> Triple(chroma, x, 0f)
        1 -> Triple(x, chroma, 0f)
        2 -> Triple(0f, chroma, x)
        3 -> Triple(0f, x, chroma)
        4 -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val m = value - chroma
    fun channel(c: Float) = ((c + m) * 255).roundToInt().coerceIn(0, 255)
    return OPAQUE or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

private const val OPAQUE = 0xFF00_0000.toInt()
