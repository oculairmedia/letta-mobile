@file:SuppressLint("RestrictedApi")

package com.letta.mobile.ui.theme

import android.annotation.SuppressLint
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
 *
 * Material ships HCT/Blend as `@RestrictTo(LIBRARY_GROUP)` helpers; suppress
 * RestrictedApi for this intentional theme-harmonization use.
 */
object HctColorHarmonizer {
    fun harmonize(
        stateColor: Color,
        seedColor: Color,
        strength: Float = DEFAULT_STRENGTH,
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
        minContrastRatio: Double = MIN_CONTENT_CONTRAST_RATIO,
        strength: Float = DEFAULT_STRENGTH,
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
        repeat(MAX_TONE_SEARCH_STEPS) {
            tone = (tone + direction).coerceIn(MIN_TONE, MAX_TONE)
            val candidate = Hct.from(hue, chroma, tone).toComposeColor()
            if (contrastRatio(contentColor, candidate) >= minContrastRatio) {
                return tone
            }
            if (tone == MIN_TONE || tone == MAX_TONE) return@repeat
        }
        return null
    }

    private fun contrastRatio(foreground: Color, background: Color): Double =
        ColorUtils.calculateContrast(foreground.toOpaqueArgb(), background.toOpaqueArgb())

    private fun Color.toOpaqueArgb(): Int = ColorUtils.setAlphaComponent(toArgb(), OpaqueAlpha)

    private fun Hct.toComposeColor(alpha: Float = 1f): Color = Color(toInt()).copy(alpha = alpha)

    private fun interpolateHue(from: Double, to: Double, fraction: Float): Double {
        val delta = ((to - from + HALF_CIRCLE_DEGREES).mod(FULL_CIRCLE_DEGREES)) - HALF_CIRCLE_DEGREES
        return (from + (delta * fraction)).mod(FULL_CIRCLE_DEGREES)
    }

    private const val DEFAULT_STRENGTH = 0.15f
    private const val MIN_CONTENT_CONTRAST_RATIO = 4.5
    private const val OpaqueAlpha = 255
    private const val MIN_TONE = 0.0
    private const val MAX_TONE = 100.0
    private const val MAX_TONE_SEARCH_STEPS = 100
    private const val HALF_CIRCLE_DEGREES = 180.0
    private const val FULL_CIRCLE_DEGREES = 360.0
}
