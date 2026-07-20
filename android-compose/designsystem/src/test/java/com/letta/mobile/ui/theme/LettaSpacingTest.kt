package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class LettaSpacingTest {

    @Test
    fun `screenHorizontal is 12 dp`() {
        assertEquals(12.dp, LettaSpacing.SCREEN_HORIZONTAL)
    }

    @Test
    fun `cardGap is 8 dp`() {
        assertEquals(8.dp, LettaSpacing.CARD_GAP)
    }

    @Test
    fun `sectionGap is 16 dp`() {
        assertEquals(16.dp, LettaSpacing.SECTION_GAP)
    }

    @Test
    fun `cardGroupItemGap is 2 dp`() {
        assertEquals(2.dp, LettaSpacing.CARD_GROUP_ITEM_GAP)
    }

    @Test
    fun `innerPadding is 16 dp`() {
        assertEquals(16.dp, LettaSpacing.INNER_PADDING)
    }

    @Test
    fun `innerPaddingSmall is 12 dp`() {
        assertEquals(12.dp, LettaSpacing.INNER_PADDING_SMALL)
    }

    @Test
    fun `iconGap is 12 dp`() {
        assertEquals(12.dp, LettaSpacing.ICON_GAP)
    }

    @Test
    fun `chipGap is 8 dp`() {
        assertEquals(8.dp, LettaSpacing.CHIP_GAP)
    }
}
