package com.letta.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import kotlin.math.abs

@Tag("unit")
class ColorTest {

    // ── toHslColor ──────────────────────────────────────────

    @Test
    fun `toHslColor converts pure red correctly`() {
        val hsl = Color.Red.toHslColor()
        assertEquals(0f, hsl.hue, 0.5f)
        assertTrue(hsl.saturation > 0.9f)
        assertTrue(hsl.lightness > 0.4f)
    }

    @Test
    fun `toHslColor converts pure green correctly`() {
        val hsl = Color.Green.toHslColor()
        assertEquals(120f, hsl.hue, 1f)
        assertTrue(hsl.saturation > 0.9f)
    }

    @Test
    fun `toHslColor converts pure blue correctly`() {
        val hsl = Color.Blue.toHslColor()
        assertEquals(240f, hsl.hue, 1f)
        assertTrue(hsl.saturation > 0.9f)
    }

    @Test
    fun `toHslColor converts white to zero saturation`() {
        val hsl = Color.White.toHslColor()
        assertEquals(0f, hsl.saturation, 0.01f)
        assertTrue(hsl.lightness > 0.95f)
    }

    @Test
    fun `toHslColor converts black to zero saturation`() {
        val hsl = Color.Black.toHslColor()
        assertEquals(0f, hsl.saturation, 0.01f)
        assertTrue(hsl.lightness < 0.05f)
    }

    @Test
    fun `toHslColor converts middle gray correctly`() {
        val hsl = Color.Gray.toHslColor()
        assertEquals(0f, hsl.saturation, 0.01f)
        assertTrue(hsl.lightness > 0.4f && hsl.lightness < 0.6f)
    }

    @Test
    fun `toHslColor converts cyan to hue near 180`() {
        val hsl = Color.Cyan.toHslColor()
        assertEquals(180f, hsl.hue, 1f)
    }

    @Test
    fun `toHslColor converts magenta to hue near 300`() {
        val hsl = Color.Magenta.toHslColor()
        assertEquals(300f, hsl.hue, 1f)
    }

    @Test
    fun `toHslColor converts yellow to hue near 60`() {
        val hsl = Color.Yellow.toHslColor()
        assertEquals(60f, hsl.hue, 1f)
    }

    @Test
    fun `toHslColor saturation bounded between 0 and 1`() {
        val testColors = listOf(Color.Red, Color.Green, Color.Blue, Color.White, Color.Black,
            Color(0xFF123456), Color(0xFFABCDEF), Color(0xFF789012))
        for (c in testColors) {
            val hsl = c.toHslColor()
            assertTrue("saturation out of bounds for $c: ${hsl.saturation}", hsl.saturation in 0f..1f)
            assertTrue("lightness out of bounds for $c: ${hsl.lightness}", hsl.lightness in 0f..1f)
        }
    }

    @Test
    fun `toHslColor is deterministic`() {
        val color = Color(0xFF3A7B92)
        val hsl1 = color.toHslColor()
        val hsl2 = color.toHslColor()
        assertEquals(hsl1.hue, hsl2.hue, 0f)
        assertEquals(hsl1.saturation, hsl2.saturation, 0f)
        assertEquals(hsl1.lightness, hsl2.lightness, 0f)
    }

    // ── complementary ────────────────────────────────────────

    @Test
    fun `complementary of red is cyan`() {
        val comp = Color.Red.complementary()
        val hsl = comp.toHslColor()
        assertEquals(180f, hsl.hue, 5f)
    }

    @Test
    fun `complementary of green is magenta-ish`() {
        val comp = Color.Green.complementary()
        val hsl = comp.toHslColor()
        // 120 + 180 = 300
        assertEquals(300f, hsl.hue, 5f)
    }

    @Test
    fun `complementary of blue is yellow-ish`() {
        val comp = Color.Blue.complementary()
        val hsl = comp.toHslColor()
        // 240 + 180 = 420 → 60 (mod 360)
        assertEquals(60f, hsl.hue, 5f)
    }

    @Test
    fun `complementary preserves alpha`() {
        val semiTransparent = Color.Red.copy(alpha = 0.5f)
        val comp = semiTransparent.complementary()
        assertEquals(0.5f, comp.alpha, 0.01f)
    }

    @Test
    fun `complementary of complementary returns original hue`() {
        val original = Color(0xFF3A7B92)
        val doubleComp = original.complementary().complementary()
        val origHsl = original.toHslColor()
        val doubleCompHsl = doubleComp.toHslColor()
        assertEquals(origHsl.hue, doubleCompHsl.hue, 1f)
    }

    @Test
    fun `complementary of black stays black`() {
        val comp = Color.Black.complementary()
        val hsl = comp.toHslColor()
        // Black has no hue, complementary should also be low-saturation
        assertTrue(hsl.saturation < 0.05f)
    }

    @Test
    fun `complementary of white stays white`() {
        val comp = Color.White.complementary()
        val hsl = comp.toHslColor()
        assertTrue(hsl.saturation < 0.05f)
    }

    // ── HslColor data class ──────────────────────────────────

    @Test
    fun `HslColor equality works`() {
        val a = HslColor(120f, 0.5f, 0.5f)
        val b = HslColor(120f, 0.5f, 0.5f)
        assertEquals(a, b)
    }
}
