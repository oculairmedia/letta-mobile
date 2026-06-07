package com.letta.mobile.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatBackgroundTokensCommonTest {
    @Test
    fun `background presets preserve persisted keys`() {
        assertEquals("default", ChatBackgroundToken.Default.key)
        assertEquals("solid_charcoal", ChatBackgroundToken.solidPresets.first().key)
        assertEquals("gradient_night_sky", ChatBackgroundToken.gradientPresets.first().key)
        assertEquals(15, ChatBackgroundToken.allPresets.size)
    }

    @Test
    fun `fromKey returns matching preset or default`() {
        assertEquals(
            ChatBackgroundToken.solidPresets.first(),
            ChatBackgroundToken.fromKey("solid_charcoal"),
        )
        assertEquals(
            ChatBackgroundToken.gradientPresets.first(),
            ChatBackgroundToken.fromKey("gradient_night_sky"),
        )
        assertTrue(ChatBackgroundToken.fromKey("missing") is ChatBackgroundToken.Default)
    }

    @Test
    fun `gradient presets expose platform-neutral argb stops`() {
        val ocean = ChatBackgroundToken.fromKey("gradient_ocean")

        assertTrue(ocean is ChatBackgroundToken.Gradient)
        assertEquals(listOf(0xFF0F2027, 0xFF203A43, 0xFF2C5364), ocean.argbColors)
    }
}
