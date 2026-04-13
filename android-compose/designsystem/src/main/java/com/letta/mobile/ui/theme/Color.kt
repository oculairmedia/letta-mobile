package com.letta.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.abs

data class HslColor(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
)

fun Color.toHslColor(): HslColor {
    val srgb = convert(ColorSpaces.Srgb)
    val r = srgb.red
    val g = srgb.green
    val b = srgb.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val lightness = (max + min) / 2f
    val saturation = if (delta == 0f) 0f else delta / (1f - abs(2f * lightness - 1f))
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta).mod(6f))
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return HslColor(
        hue = hue,
        saturation = saturation.coerceIn(0f, 1f),
        lightness = lightness.coerceIn(0f, 1f),
    )
}

fun Color.complementary(): Color {
    val hsl = toHslColor()
    return Color.hsl((hsl.hue + 180f).mod(360f), hsl.saturation, hsl.lightness, alpha)
}

val DarkSurface = Color(0xFF121212)
val DarkSurfaceVariant = Color(0xFF1E1E1E)
val DarkSurfaceContainer = Color(0xFF2A2A2A)
val DarkPrimary = Color(0xFF00BFA5)
val DarkPrimaryVariant = Color(0xFF009688)
val DarkOnSurface = Color(0xFFE0E0E0)
val DarkOnSurfaceVariant = Color(0xFFBDBDBD)
val DarkError = Color(0xFFCF6679)
val DarkOnError = Color(0xFF000000)
val DarkBackground = Color(0xFF0A0A0A)
val DarkOutline = Color(0xFF424242)

val LightSurface = Color(0xFFFAFAFA)
val LightSurfaceVariant = Color(0xFFEEEEEE)
val LightSurfaceContainer = Color(0xFFE0E0E0)
val LightPrimary = Color(0xFF00897B)
val LightPrimaryVariant = Color(0xFF00695C)
val LightOnSurface = Color(0xFF1A1A1A)
val LightOnSurfaceVariant = Color(0xFF424242)
val LightError = Color(0xFFB00020)
val LightOnError = Color(0xFFFFFFFF)
val LightBackground = Color(0xFFFFFFFF)
val LightOutline = Color(0xFFBDBDBD)

val TealAccent = Color(0xFF1DE9B6)
val CyanAccent = Color(0xFF00E5FF)
val AmberAccent = Color(0xFFFFD740)
