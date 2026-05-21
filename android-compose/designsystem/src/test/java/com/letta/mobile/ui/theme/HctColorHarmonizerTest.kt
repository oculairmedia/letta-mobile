package com.letta.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class HctColorHarmonizerTest {

    @Test
    fun `zero strength returns original color`() {
        val stateColor = Color.hsl(5f, 0.7f, 0.45f, alpha = 0.72f)

        val harmonized = HctColorHarmonizer.harmonize(
            stateColor = stateColor,
            seedColor = Color.hsl(170f, 0.8f, 0.5f),
            strength = 0f,
        )

        assertEquals(stateColor, harmonized)
    }

    @Test
    fun `hue moves along shortest path across zero degree boundary`() {
        val harmonized = HctColorHarmonizer.harmonize(
            stateColor = Color.hsl(350f, 0.7f, 0.5f),
            seedColor = Color.hsl(10f, 0.7f, 0.5f),
            strength = 0.5f,
        )

        val hue = harmonized.toHslColor().hue
        assertTrue("Expected hue near 0°, was $hue", hue < 2f || hue > 358f)
    }

    @Test
    fun `harmonization preserves state saturation lightness and alpha`() {
        val stateColor = Color.hsl(20f, 0.62f, 0.41f, alpha = 0.65f)

        val harmonized = HctColorHarmonizer.harmonize(
            stateColor = stateColor,
            seedColor = Color.hsl(180f, 0.9f, 0.8f),
            strength = 0.25f,
        )

        val originalHsl = stateColor.toHslColor()
        val harmonizedHsl = harmonized.toHslColor()
        assertEquals(originalHsl.saturation, harmonizedHsl.saturation, HslTolerance)
        assertEquals(originalHsl.lightness, harmonizedHsl.lightness, HslTolerance)
        assertEquals(stateColor.alpha, harmonized.alpha, AlphaTolerance)
    }

    private companion object {
        const val HslTolerance = 0.01f
        const val AlphaTolerance = 0.001f
    }
}
