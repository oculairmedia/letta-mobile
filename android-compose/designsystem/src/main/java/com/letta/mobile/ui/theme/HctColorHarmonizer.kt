package com.letta.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Harmonizes state colors with the active Material seed while preserving their tone.
 *
 * The implementation intentionally uses AndroidX HSL primitives rather than RGB
 * blending: only hue moves toward the seed color, while saturation/lightness stay
 * anchored to the semantic state color so error, warning, and success remain legible.
 */
object HctColorHarmonizer {
    fun harmonize(
        stateColor: Color,
        seedColor: Color,
        strength: Float = DefaultStrength,
    ): Color {
        val clampedStrength = strength.coerceIn(0f, 1f)
        if (clampedStrength == 0f) return stateColor

        val stateHsl = stateColor.toColorUtilsHsl()
        val seedHsl = seedColor.toColorUtilsHsl()
        stateHsl[HueIndex] = interpolateHue(
            from = stateHsl[HueIndex],
            to = seedHsl[HueIndex],
            fraction = clampedStrength,
        )

        return Color(ColorUtils.HSLToColor(stateHsl)).copy(alpha = stateColor.alpha)
    }

    private fun Color.toColorUtilsHsl(): FloatArray {
        val hsl = FloatArray(HslSize)
        ColorUtils.colorToHSL(toArgb(), hsl)
        return hsl
    }

    private fun interpolateHue(from: Float, to: Float, fraction: Float): Float {
        val delta = ((to - from + HalfCircleDegrees).mod(FullCircleDegrees)) - HalfCircleDegrees
        return (from + (delta * fraction)).mod(FullCircleDegrees)
    }

    private const val DefaultStrength = 0.15f
    private const val HueIndex = 0
    private const val HslSize = 3
    private const val HalfCircleDegrees = 180f
    private const val FullCircleDegrees = 360f
}
