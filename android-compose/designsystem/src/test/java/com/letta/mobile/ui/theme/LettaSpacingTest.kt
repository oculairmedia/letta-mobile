package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class LettaSpacingTest {

    @Test
    fun `screenHorizontal is 12 dp`() {
        assertEquals(12.dp, LettaSpacing.screenHorizontal)
    }

    @Test
    fun `cardGap is 8 dp`() {
        assertEquals(8.dp, LettaSpacing.cardGap)
    }

    @Test
    fun `sectionGap is 16 dp`() {
        assertEquals(16.dp, LettaSpacing.sectionGap)
    }

    @Test
    fun `cardGroupItemGap is 2 dp`() {
        assertEquals(2.dp, LettaSpacing.cardGroupItemGap)
    }

    @Test
    fun `innerPadding is 16 dp`() {
        assertEquals(16.dp, LettaSpacing.innerPadding)
    }

    @Test
    fun `innerPaddingSmall is 12 dp`() {
        assertEquals(12.dp, LettaSpacing.innerPaddingSmall)
    }

    @Test
    fun `iconGap is 12 dp`() {
        assertEquals(12.dp, LettaSpacing.iconGap)
    }

    @Test
    fun `chipGap is 8 dp`() {
        assertEquals(8.dp, LettaSpacing.chipGap)
    }
}
