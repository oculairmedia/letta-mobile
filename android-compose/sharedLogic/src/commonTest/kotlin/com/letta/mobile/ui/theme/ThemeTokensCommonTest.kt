package com.letta.mobile.ui.theme

import com.letta.mobile.data.model.ThemePreset
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `default preset sits on the North Sea Buoy backgrounds`() {
        val tokens = LettaThemeTokens.preset(ThemePreset.DEFAULT)

        assertEquals(0xFFEFF3F6, tokens.light.backgroundArgb)
        assertEquals(0xFF0D1821, tokens.dark.backgroundArgb)
        assertEquals(0xFF10202C, LettaColorTokens.LIGHT_ON_SURFACE)
        assertEquals(0xFFE8EFF4, LettaColorTokens.DARK_ON_SURFACE)
    }

    @Test
    fun `amoled dark preset exposes black background and surface`() {
        val tokens = LettaThemeTokens.preset(ThemePreset.AMOLED_BLACK).dark

        assertEquals(0xFF000000, tokens.backgroundArgb)
        assertEquals(0xFF000000, tokens.surfaceArgb)
        assertEquals(0xFF000000, tokens.surfaceVariantArgb)
    }

    private fun assertPresetHasPalette(tokens: LettaThemePaletteTokens) {
        assertEquals(0xFF000000, tokens.primaryArgb and 0xFF000000)
        assertEquals(0xFF000000, tokens.primaryContainerArgb and 0xFF000000)
        assertEquals(0xFF000000, tokens.backgroundArgb and 0xFF000000)
        assertEquals(0xFF000000, tokens.surfaceArgb and 0xFF000000)
    }
}
