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

val DarkSurface = Color(LettaColorTokens.DARK_SURFACE)
val DarkSurfaceVariant = Color(LettaColorTokens.DARK_SURFACE_VARIANT)
val DarkSurfaceContainer = Color(LettaColorTokens.DARK_SURFACE_CONTAINER)
val DarkPrimary = Color(LettaColorTokens.DARK_PRIMARY)
val DarkOnSurface = Color(LettaColorTokens.DARK_ON_SURFACE)
val DarkOnSurfaceVariant = Color(LettaColorTokens.DARK_ON_SURFACE_VARIANT)
val DarkError = Color(LettaColorTokens.DARK_ERROR)
val DarkOnError = Color(LettaColorTokens.DARK_ON_ERROR)
val DarkBackground = Color(LettaColorTokens.DARK_BACKGROUND)

val LightSurface = Color(LettaColorTokens.LIGHT_SURFACE)
val LightSurfaceVariant = Color(LettaColorTokens.LIGHT_SURFACE_VARIANT)
val LightSurfaceContainer = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER)
val LightPrimary = Color(LettaColorTokens.LIGHT_PRIMARY)
val LightOnSurface = Color(LettaColorTokens.LIGHT_ON_SURFACE)
val LightOnSurfaceVariant = Color(LettaColorTokens.LIGHT_ON_SURFACE_VARIANT)
val LightError = Color(LettaColorTokens.LIGHT_ERROR)
val LightOnError = Color(LettaColorTokens.LIGHT_ON_ERROR)
val LightBackground = Color(LettaColorTokens.LIGHT_BACKGROUND)
