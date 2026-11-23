package com.letta.mobile.ui.theme

import com.letta.mobile.data.model.ThemePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ThemeTokensCommonTest {
    @Test
    fun `theme tokens expose every configured preset`() {
        ThemePreset.entries.forEach { preset ->
            val tokens = LettaThemeTokens.preset(preset)

            assertPresetHasPalette(tokens.light)
            assertPresetHasPalette(tokens.dark)
        }
    }

    @Test
    fun `default preset preserves primary brand colors`() {
        val tokens = LettaThemeTokens.preset(ThemePreset.DEFAULT)

        assertEquals(0xFFE04A32, tokens.light.primaryArgb)
        assertEquals(0xFFEE5A3F, tokens.dark.primaryArgb)
    }

    @Test
    fun `default preset sits on the neutral grey backgrounds`() {
        val tokens = LettaThemeTokens.preset(ThemePreset.DEFAULT)

        assertEquals(0xFFF4F5F6, tokens.light.backgroundArgb)
        assertEquals(0xFF101214, tokens.dark.backgroundArgb)
        assertEquals(0xFF15191D, LettaColorTokens.LIGHT_ON_SURFACE)
        assertEquals(0xFFE8EBEE, LettaColorTokens.DARK_ON_SURFACE)
    }

    @Test
    fun `amoled dark preset exposes black background and surface`() {
        val tokens = LettaThemeTokens.preset(ThemePreset.AMOLED_BLACK).dark

        assertEquals(0xFF000000, tokens.backgroundArgb)
        assertEquals(0xFF000000, tokens.surfaceArgb)
        assertEquals(0xFF000000, tokens.surfaceVariantArgb)
    }

    @Test
    fun `default preset's secondary and tertiary are slate and steel greys and never teal or cyan`() {
        val tokens = LettaThemeTokens.preset(ThemePreset.DEFAULT)

        // The Baken kit's petrol slate (secondary) and steel mist (tertiary), per theme.
        assertEquals(0xFF8C98A3, tokens.dark.secondaryArgb)
        assertEquals(0xFFA9B3BC, tokens.dark.tertiaryArgb)
        assertEquals(0xFF4C5761, tokens.light.secondaryArgb)
        assertEquals(0xFF5A6670, tokens.light.tertiaryArgb)
        listOf(tokens.dark.secondaryArgb, tokens.dark.tertiaryArgb, tokens.light.secondaryArgb, tokens.light.tertiaryArgb)
            .forEach { argb -> assertFalse(isTealOrCyan(argb), "0x${argb.toString(HEX)} reads as teal/cyan") }
    }

    @Test
    fun `the persona category is a brand-family colour and never teal`() {
        assertFalse(isTealOrCyan(LettaColorTokens.DARK_CATEGORY_PERSONA))
    }

    /** Hue in degrees from the ARGB channels (independent of any production colour helper). */
    private fun hue(argb: Long): Double {
        val r = (argb shr 16 and 0xFF) / 255.0
        val g = (argb shr 8 and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        if (delta == 0.0) return 0.0
        val sector = when (max) {
            r -> ((g - b) / delta).mod(6.0)
            g -> (b - r) / delta + 2
            else -> (r - g) / delta + 4
        }
        return sector * 60
    }

    private fun saturation(argb: Long): Double {
        val channels = listOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF).map { it / 255.0 }
        val max = channels.max()
        val min = channels.min()
        val lightness = (max + min) / 2
        return if (max == min) 0.0 else (max - min) / (1 - kotlin.math.abs(2 * lightness - 1))
    }

    /** Teal/cyan: a hue between green-teal and cyan with enough saturation to read as a colour. */
    private fun isTealOrCyan(argb: Long): Boolean = hue(argb) in TEAL_HUES && saturation(argb) > VIVID_SATURATION

    private fun assertPresetHasPalette(tokens: LettaThemePaletteTokens) {
        assertEquals(0xFF000000, tokens.primaryArgb and 0xFF000000)
        assertEquals(0xFF000000, tokens.primaryContainerArgb and 0xFF000000)
        assertEquals(0xFF000000, tokens.backgroundArgb and 0xFF000000)
        assertEquals(0xFF000000, tokens.surfaceArgb and 0xFF000000)
    }

    private companion object {
        val TEAL_HUES = 150.0..200.0
        const val VIVID_SATURATION = 0.35
        const val HEX = 16
    }
}
