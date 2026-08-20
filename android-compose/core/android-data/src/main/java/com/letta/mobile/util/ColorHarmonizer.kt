@file:SuppressLint("RestrictedApi")

package com.letta.mobile.util

import android.annotation.SuppressLint
import com.google.android.material.color.utilities.Blend
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.Scheme
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Harmonizes ARGB colors using Material Color Utilities HCT color space.
 *
 * Generates a Material 3 dynamic color scheme from a source color and picks the
 * first palette color (tertiary → primary → secondary → surface) whose tone
 * differs from the source by ≥40 (≈ 3.0 contrast ratio).
 *
 * Material ships HCT/Scheme/Blend as `@RestrictTo(LIBRARY_GROUP)` helpers. We intentionally
 * call them here (same pattern as Material's own dynamic-color samples); suppress RestrictedApi
 * for this file only rather than reimplement HCT.
 */
@Singleton
class ColorHarmonizer @Inject constructor() {

    /**
     * Harmonizes a given ARGB color using Material 3 dynamic color scheme.
     *
     * Generates a scheme from the source color and selects the first candidate
     * (tertiary → primary → secondary → surface) whose tone differs from the
     * source by ≥40. Falls back to tertiary if no candidate meets the threshold.
     *
     * @param sourceColor ARGB color to harmonize
     * @param targetTone Optional target tone (0-100). If null, uses the tone from sourceColor.
     * @return Harmonized ARGB color suitable for Material 3 theming
     */
    fun harmonizeColor(sourceColor: Int, targetTone: Int? = null): Int {
        val sourceHct = Hct.fromInt(sourceColor)
        val scheme = Scheme.light(sourceColor)

        // Candidate colors in priority order (matches Overmorrow strategy)
        val candidates = listOf(
            scheme.tertiary,
            scheme.primary,
            scheme.secondary,
            scheme.surface
        )

        // Find the first color with contrast ≥1.75 (tone difference ≥40)
        val selectedColor = candidates.firstOrNull { candidate ->
            val candidateHct = Hct.fromInt(candidate)
            val toneDifference = kotlin.math.abs(candidateHct.tone - sourceHct.tone)
            toneDifference >= 40 // Δ40 tone ≈ 3.0 contrast ratio
        } ?: candidates.first() // Fallback to tertiary if no candidate meets threshold

        // Apply target tone if specified
        return if (targetTone != null) {
            val selectedHct = Hct.fromInt(selectedColor)
            selectedHct.tone = targetTone.toDouble()
            selectedHct.toInt()
        } else {
            selectedColor
        }
    }

    /**
     * Blends two colors in HCT space, optionally rotating hue.
     *
     * Useful for creating harmonious color variations. The blend respects Material's
     * 15° maximum hue rotation constraint.
     *
     * @param color1 First ARGB color
     * @param color2 Second ARGB color
     * @param t Blend factor (0.0 = color1, 1.0 = color2)
     * @return Blended ARGB color
     */
    fun blendColors(color1: Int, color2: Int, t: Double = 0.5): Int {
        val hct1 = Hct.fromInt(color1)
        val hct2 = Hct.fromInt(color2)

        val blendedHue = hct1.hue + (hct2.hue - hct1.hue) * t
        val blendedChroma = hct1.chroma + (hct2.chroma - hct1.chroma) * t
        val blendedTone = hct1.tone + (hct2.tone - hct1.tone) * t

        return Hct.from(blendedHue, blendedChroma, blendedTone).toInt()
    }

    /**
     * Harmonizes a color by rotating its hue within Material's 15° constraint.
     *
     * Useful for creating accessible color variations that maintain semantic meaning
     * while improving contrast or visual distinction.
     *
     * @param sourceColor ARGB color to harmonize
     * @param targetColor ARGB color to harmonize towards (hue source)
     * @return Harmonized ARGB color with hue rotated up to 15° towards targetColor
     */
    fun harmonizeHue(sourceColor: Int, targetColor: Int): Int {
        val harmonized = Blend.harmonize(sourceColor, targetColor)
        return harmonized
    }
}
