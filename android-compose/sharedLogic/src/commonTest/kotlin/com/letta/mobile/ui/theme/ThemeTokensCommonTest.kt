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

        assertEquals(0xFF00897B, tokens.light.primaryArgb)
        assertEquals(0xFF00BFA5, tokens.dark.primaryArgb)
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
