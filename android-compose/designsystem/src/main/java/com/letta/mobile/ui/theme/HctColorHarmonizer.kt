package com.letta.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.utilities.Blend
import com.google.android.material.color.utilities.Hct

/**
 * Harmonizes state colors with the active Material seed in HCT color space.
 *
 * Material color utilities keep the source color recognizable by rotating hue
 * toward the seed while preserving tone and chroma as much as the sRGB gamut
 * allows. Container harmonization can additionally move tone just far enough to
 * keep the paired content color above the requested contrast floor.
 */
object HctColorHarmonizer {
    fun harmonize(
        stateColor: Color,
        seedColor: Color,
        strength: Float = DefaultStrength,
    ): Color {
        val clampedStrength = strength.coerceIn(0f, 1f)
        if (clampedStrength == 0f) return stateColor

        val stateHct = Hct.fromInt(stateColor.toOpaqueArgb())
        val materialHarmonized = Hct.fromInt(
            Blend.harmonize(stateColor.toOpaqueArgb(), seedColor.toOpaqueArgb()),
        )
        val harmonizedHue = interpolateHue(
            from = stateHct.hue,
            to = materialHarmonized.hue,
            fraction = clampedStrength,
        )

        return Hct.from(harmonizedHue, stateHct.chroma, stateHct.tone)
            .toComposeColor(alpha = stateColor.alpha)
    }

    fun harmonizeContainer(
        containerColor: Color,
        seedColor: Color,
        contentColor: Color,
        minContrastRatio: Double = MinContentContrastRatio,
        strength: Float = DefaultStrength,
    ): Color {
        val harmonized = harmonize(
            stateColor = containerColor,
            seedColor = seedColor,
            strength = strength,
        )
        if (contrastRatio(contentColor, harmonized) >= minContrastRatio) {
            return harmonized
        }

        val harmonizedHct = Hct.fromInt(harmonized.toOpaqueArgb())
        val contentTone = Hct.fromInt(contentColor.toOpaqueArgb()).tone
        val adjustedTone = findNearestContrastingTone(
            hue = harmonizedHct.hue,
            chroma = harmonizedHct.chroma,
            originalTone = harmonizedHct.tone,
            contentColor = contentColor,
            preferLighter = harmonizedHct.tone > contentTone,
            minContrastRatio = minContrastRatio,
        ) ?: return harmonized

        return Hct.from(harmonizedHct.hue, harmonizedHct.chroma, adjustedTone)
            .toComposeColor(alpha = containerColor.alpha)
    }

    private fun findNearestContrastingTone(
        hue: Double,
        chroma: Double,
        originalTone: Double,
        contentColor: Color,
        preferLighter: Boolean,
        minContrastRatio: Double,
    ): Double? {
        val direction = if (preferLighter) 1.0 else -1.0
        var tone = originalTone
        repeat(MaxToneSearchSteps) {
            tone = (tone + direction).coerceIn(MinTone, MaxTone)
            val candidate = Hct.from(hue, chroma, tone).toComposeColor()
            if (contrastRatio(contentColor, candidate) >= minContrastRatio) {
                return tone
            }
            if (tone == MinTone || tone == MaxTone) return@repeat
        }
        return null
    }

    private fun contrastRatio(foreground: Color, background: Color): Double =
        ColorUtils.calculateContrast(foreground.toOpaqueArgb(), background.toOpaqueArgb())

    private fun Color.toOpaqueArgb(): Int = ColorUtils.setAlphaComponent(toArgb(), OpaqueAlpha)

    private fun Hct.toComposeColor(alpha: Float = 1f): Color = Color(toInt()).copy(alpha = alpha)

    private fun interpolateHue(from: Double, to: Double, fraction: Float): Double {
        val delta = ((to - from + HalfCircleDegrees).mod(FullCircleDegrees)) - HalfCircleDegrees
        return (from + (delta * fraction)).mod(FullCircleDegrees)
    }

    private const val DefaultStrength = 0.15f
    private const val MinContentContrastRatio = 4.5
    private const val OpaqueAlpha = 255
    private const val MinTone = 0.0
    private const val MaxTone = 100.0
    private const val MaxToneSearchSteps = 100
    private const val HalfCircleDegrees = 180.0
    private const val FullCircleDegrees = 360.0
}
