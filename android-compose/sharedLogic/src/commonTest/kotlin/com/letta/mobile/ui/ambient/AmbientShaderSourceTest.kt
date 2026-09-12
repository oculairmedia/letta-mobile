package com.letta.mobile.ui.ambient

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmbientShaderSourceTest {
    /**
     * The wrap is only seamless if the field is actually periodic at the wrap point, and
     * it is only periodic if every frequency is a whole number of cycles per period.
     * One stray decimal here and the glow jumps once per wrap instead of never.
     */
    @Test
    fun everyTimeFrequencyIsAWholeMultipleOfTheWrapBase() {
        assertContains(AMBIENT_GLOW_SHADER_SOURCE, "const float F = 1.0 / 1024.0;")
        assertEquals(1024f, AmbientMotion.PHASE_WRAP_TURNS, "the wrap must be 1/F")

        val multiples = Regex("""([0-9]+\.[0-9]+)\s*\*\s*F\b""")
            .findAll(AMBIENT_GLOW_SHADER_SOURCE)
            .map { it.groupValues[1].toFloat() }
            .toList()
        assertTrue(multiples.isNotEmpty(), "the shader must express its rates as multiples of F")
        multiples.forEach { multiple ->
            assertEquals(
                multiple.toInt().toFloat(),
                multiple,
                "$multiple cycles per period is not a whole number, so the wrap would seam",
            )
        }
    }

    /**
     * The five drift rates are written as one expression over the loop index; spelling
     * them out here is the readable record of what that expression produces.
     */
    @Test
    fun theFiveFieldsDriftAtWholeCycleRates() {
        assertContains(AMBIENT_GLOW_SHADER_SOURCE, "F * (5.0 + fi + step(1.5, fi) + step(3.5, fi))")
        val rates = (0..4).map { i -> 5 + i + (if (i > 1) 1 else 0) + (if (i > 3) 1 else 0) }
        assertEquals(listOf(5, 6, 8, 9, 11), rates)
    }

    /**
     * Stream energy may brighten the glow, never hurry it. A live signal on the rate
     * pops the motion when it moves, and leaves the period unknowable, which is what
     * made a seamless wrap impossible before.
     */
    @Test
    fun streamEnergyScalesAmplitudeNeverRate() {
        assertContains(AMBIENT_GLOW_SHADER_SOURCE, "float t = uTime;")
        listOf("0.09 + 0.009 * uStreamEnergy", "1.0 + 0.10 * uStreamEnergy").forEach { coupling ->
            assertTrue(
                !AMBIENT_GLOW_SHADER_SOURCE.contains(coupling),
                "stream energy must not scale time: found \"$coupling\"",
            )
        }
    }

    /** The palette is pulled toward the tint's hue, so the field cannot speak a foreign palette. */
    @Test
    fun thePaletteIsRotatedTowardTheTintHue() {
        assertContains(AMBIENT_GLOW_SHADER_SOURCE, "uniform float uPalettePull;")
        assertContains(AMBIENT_GLOW_SHADER_SOURCE, "towardTintHue(clamp(palRaw(t), 0.0, 1.0), uColor.rgb, uPalettePull)")
        assertTrue(AmbientMotion.PALETTE_HUE_PULL in 0f..1f, "pull is a fraction")
    }
}
