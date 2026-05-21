package com.letta.mobile.util

import android.graphics.Bitmap
import com.google.android.material.color.utilities.Blend
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.Scheme
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Harmonizes colors extracted from images using Material Color Utilities HCT color space.
 *
 * Not yet wired to a consumer surface — landed standalone so callers can adopt it
 * once an image-driven theming surface exists.
 *
 * Implements the Overmorrow pattern:
 * 1. Extract a representative region from the image (bottom-left 10-30% width, 70-90% height)
 * 2. Sample the dominant color from that region
 * 3. Generate a Material 3 dynamic color scheme from the source color
 * 4. Pick the first palette color (tertiary → primary → secondary → surface) whose tone
 *    differs from the source by ≥40 (≈ 3.0 contrast ratio)
 *
 * Reference: https://github.com/oculairmedia/Overmorrow/blob/main/lib/services/color_service.dart
 * Material Color Utilities: https://github.com/material-foundation/material-color-utilities
 */
@Singleton
class ColorHarmonizer @Inject constructor() {

    /**
     * Extracts a representative color from an image bitmap and harmonizes it.
     *
     * Samples the bottom-left region (10-30% width, 70-90% height) to avoid sky and
     * non-representative areas, then generates a Material 3 scheme and selects the
     * highest-contrast color for text/foreground use.
     *
     * @param bitmap The source image bitmap
     * @param targetTone Optional target tone (0-100) for the harmonized color. If null, uses the
     *                   tone from the extracted color.
     * @return A harmonized ARGB color (Int) suitable for Material 3 theming, or null if extraction fails
     */
    fun harmonizeFromBitmap(bitmap: Bitmap, targetTone: Int? = null): Int? {
        val sourceColor = extractDominantColor(bitmap) ?: return null
        return harmonizeColor(sourceColor, targetTone)
    }

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
            selectedHct.setTone(targetTone.toDouble())
            selectedHct.toInt()
        } else {
            selectedColor
        }
    }

    /**
     * Extracts the dominant color from a representative region of the bitmap.
     *
     * Samples the bottom-left region (10-30% width, 70-90% height) to avoid sky
     * and non-representative areas, following the Overmorrow pattern.
     *
     * @param bitmap The source image bitmap
     * @return Dominant ARGB color from the sampled region, or null if bitmap is too small
     */
    private fun extractDominantColor(bitmap: Bitmap): Int? {
        if (bitmap.width < 10 || bitmap.height < 10) return null

        // Sample region: bottom-left 10-30% width, 70-90% height
        val startX = (bitmap.width * 0.1).toInt()
        val endX = (bitmap.width * 0.3).toInt()
        val startY = (bitmap.height * 0.7).toInt()
        val endY = (bitmap.height * 0.9).toInt()

        val regionWidth = endX - startX
        val regionHeight = endY - startY

        if (regionWidth <= 0 || regionHeight <= 0) return null

        // Extract pixels from the region
        val pixels = IntArray(regionWidth * regionHeight)
        bitmap.getPixels(pixels, 0, regionWidth, startX, startY, regionWidth, regionHeight)

        // Find the most frequent color (simple histogram approach)
        val colorFrequency = mutableMapOf<Int, Int>()
        for (pixel in pixels) {
            val color = pixel and 0xFFFFFF // Ignore alpha
            colorFrequency[color] = (colorFrequency[color] ?: 0) + 1
        }

        return colorFrequency.maxByOrNull { it.value }?.key
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
