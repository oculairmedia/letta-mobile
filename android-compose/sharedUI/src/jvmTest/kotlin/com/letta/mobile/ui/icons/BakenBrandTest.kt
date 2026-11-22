package com.letta.mobile.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.VectorPath
import com.letta.mobile.ui.theme.lettaColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-0sb6o: Home is drawn as the Baken mark (the kit's two basalt stones on a 200 viewport), and text on the
 * vermilion primary is white in both themes. The oracles are the kit's own numbers, not the production helpers.
 */
class BakenBrandTest {
    @Test
    fun homeIsTheBakenMarkFromTheKit() {
        val home = LettaIcons.Home

        assertEquals("BakenMark", home.name)
        assertEquals(KIT_VIEWPORT, home.viewportWidth)
        assertEquals(KIT_VIEWPORT, home.viewportHeight)
        assertEquals(KIT_PATHS, home.root.filterIsInstance<VectorPath>().size)
    }

    @Test
    fun textOnTheVermilionPrimaryIsWhiteInBothThemes() {
        assertEquals(Color(0xFFEE5A3F), lettaColorScheme(dark = true).primary)
        assertEquals(Color(0xFFE04A32), lettaColorScheme(dark = false).primary)
        assertEquals(Color.White, lettaColorScheme(dark = true).onPrimary)
        assertEquals(Color.White, lettaColorScheme(dark = false).onPrimary)
    }

    @Test
    fun theSchemeSitsOnTheNorthSeaBuoyBackgrounds() {
        assertEquals(Color(0xFF0D1821), lettaColorScheme(dark = true).background)
        assertEquals(Color(0xFFEFF3F6), lettaColorScheme(dark = false).background)
        assertEquals(Color(0xFFE8EFF4), lettaColorScheme(dark = true).onSurface)
        assertEquals(Color(0xFF10202C), lettaColorScheme(dark = false).onSurface)
    }

    private companion object {
        const val KIT_VIEWPORT = 200f
        const val KIT_PATHS = 2
    }
}
