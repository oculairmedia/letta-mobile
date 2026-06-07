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

val DarkSurface = Color(LettaColorTokens.darkSurface)
val DarkSurfaceVariant = Color(LettaColorTokens.darkSurfaceVariant)
val DarkSurfaceContainer = Color(LettaColorTokens.darkSurfaceContainer)
val DarkPrimary = Color(LettaColorTokens.darkPrimary)
val DarkPrimaryVariant = Color(LettaColorTokens.darkPrimaryVariant)
val DarkOnSurface = Color(LettaColorTokens.darkOnSurface)
val DarkOnSurfaceVariant = Color(LettaColorTokens.darkOnSurfaceVariant)
val DarkError = Color(LettaColorTokens.darkError)
val DarkOnError = Color(LettaColorTokens.darkOnError)
val DarkBackground = Color(LettaColorTokens.darkBackground)
val DarkOutline = Color(LettaColorTokens.darkOutline)

val LightSurface = Color(LettaColorTokens.lightSurface)
val LightSurfaceVariant = Color(LettaColorTokens.lightSurfaceVariant)
val LightSurfaceContainer = Color(LettaColorTokens.lightSurfaceContainer)
val LightPrimary = Color(LettaColorTokens.lightPrimary)
val LightPrimaryVariant = Color(LettaColorTokens.lightPrimaryVariant)
val LightOnSurface = Color(LettaColorTokens.lightOnSurface)
val LightOnSurfaceVariant = Color(LettaColorTokens.lightOnSurfaceVariant)
val LightError = Color(LettaColorTokens.lightError)
val LightOnError = Color(LettaColorTokens.lightOnError)
val LightBackground = Color(LettaColorTokens.lightBackground)
val LightOutline = Color(LettaColorTokens.lightOutline)

val TealAccent = Color(LettaColorTokens.tealAccent)
val CyanAccent = Color(LettaColorTokens.cyanAccent)
val AmberAccent = Color(LettaColorTokens.amberAccent)
