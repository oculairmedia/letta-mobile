package com.letta.mobile.ui.canvas

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Editing hue, saturation or lightness must not change how transparent a colour is.
 *
 * The picker holds the colour in HSL while it is being edited, and HSL has no alpha channel. A
 * translucent highlight therefore came back fully opaque the moment its hue was nudged - the one
 * edit the person did not ask for.
 */
class CanvasColorAlphaTest {

    private fun assertAlpha(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 0.01f, "expected alpha $expected, got $actual")
    }

    @Test
    fun aTranslucentColourKeepsItsAlphaThroughTheRoundTrip() {
        val translucent = Color(red = 0.2f, green = 0.6f, blue = 0.9f, alpha = 0.4f)

        assertAlpha(0.4f, translucent.toHsl().toColor().alpha)
    }

    @Test
    fun editingHueLeavesAlphaAlone() {
        val hsl = Color(red = 0.2f, green = 0.6f, blue = 0.9f, alpha = 0.4f).toHsl()

        val hueTurned = hsl.copy(h = (hsl.h + 120f) % 360f).toColor()

        assertAlpha(0.4f, hueTurned.alpha)
    }

    @Test
    fun editingSaturationAndLightnessLeavesAlphaAlone() {
        val hsl = Color(red = 0.2f, green = 0.6f, blue = 0.9f, alpha = 0.4f).toHsl()

        assertAlpha(0.4f, hsl.copy(s = 0.1f).toColor().alpha)
        assertAlpha(0.4f, hsl.copy(l = 0.8f).toColor().alpha)
    }

    @Test
    fun anOpaqueColourStaysOpaque() {
        val opaque = Color(red = 0.2f, green = 0.6f, blue = 0.9f, alpha = 1f)

        assertAlpha(1f, opaque.toHsl().toColor().alpha)
        assertEquals(opaque.toHex(), opaque.toHsl().toColor().toHex())
    }

    @Test
    fun aGreyKeepsItsAlphaToo() {
        // Grey takes the early return in toHsl, where hue and saturation are undefined - and where
        // the alpha was being dropped as well.
        val grey = Color(red = 0.5f, green = 0.5f, blue = 0.5f, alpha = 0.25f)

        assertAlpha(0.25f, grey.toHsl().toColor().alpha)
    }

    @Test
    fun theHexTextAndTheColourAgreeOnAlpha() {
        val translucent = Color(red = 0.2f, green = 0.6f, blue = 0.9f, alpha = 0.4f)

        val hex = translucent.toHsl().toColor().toHex()
        val parsed = parseHexColor(hex)

        assertEquals(8, hex.removePrefix("#").length, "a translucent colour needs its alpha digits")
        assertAlpha(0.4f, parsed!!.alpha)
    }
}
