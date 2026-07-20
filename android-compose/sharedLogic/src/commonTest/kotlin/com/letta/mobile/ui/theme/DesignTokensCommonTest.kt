package com.letta.mobile.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class DesignTokensCommonTest {
    @Test
    fun `spacing tokens preserve extracted design scale`() {
        assertEquals(12f, LettaSpacingTokens.SCREEN_HORIZONTAL)
        assertEquals(8f, LettaSpacingTokens.CARD_GAP)
        assertEquals(16f, LettaSpacingTokens.SECTION_GAP)
        assertEquals(2f, LettaSpacingTokens.CARD_GROUP_ITEM_GAP)
        assertEquals(10f, LettaSpacingTokens.BUBBLE_PADDING_HORIZONTAL)
        assertEquals(7f, LettaSpacingTokens.BUBBLE_PADDING_VERTICAL)
        assertEquals(36f, LettaSpacingTokens.COMPOSER_ATTACH_BUTTON_SIZE)
    }

    @Test
    fun `editorial chat rhythm tokens are tight within a turn and loose between turns`() {
        // docs/design/editorial-prose.md §3: grouped beat 8dp, ungrouped break 24dp.
        // The contract that matters is ungrouped > grouped so the section
        // boundary between speakers / run collections is unmistakable.
        assertEquals(8f, LettaSpacingTokens.MESSAGE_SPACING)
        assertEquals(24f, LettaSpacingTokens.UNGROUPED_MESSAGE_SPACING)
        assertEquals(
            true,
            LettaSpacingTokens.UNGROUPED_MESSAGE_SPACING > LettaSpacingTokens.MESSAGE_SPACING,
        )
    }

    @Test
    fun `chat tokens expose dimensions and typography without Compose units`() {
        assertEquals(12f, LettaChatTokens.shapes.bubbleRadiusDp)
        assertEquals(8f, LettaChatTokens.shapes.codeBlockRadiusDp)
        assertEquals(0.88f, LettaChatTokens.dimens.bubbleMaxWidthFraction)
        // Editorial rhythm (docs/design/editorial-prose.md §3): grouped 8dp,
        // ungrouped 24dp.
        assertEquals(8f, LettaChatTokens.dimens.groupedMessageSpacingDp)
        assertEquals(24f, LettaChatTokens.dimens.ungroupedMessageSpacingDp)
        assertEquals(0.4f, LettaChatTokens.typography.roleLabelLetterSpacingSp)
        assertEquals(12f, LettaChatTokens.typography.codeBlockFontSizeSp)
        assertEquals(16f, LettaChatTokens.typography.codeBlockLineHeightSp)
    }

    @Test
    fun `motion and surface tokens preserve Android design-system values`() {
        assertEquals(60, LettaMotionTokens.STREAMING_SIZE_MILLIS)
        assertEquals(220, LettaMotionTokens.CONTENT_SIZE_MILLIS)
        assertEquals(150, LettaMotionTokens.CHIP_MILLIS)
        assertEquals(12f, LettaShapeTokens.LIST_RADIUS)
        assertEquals(8f, LettaShapeTokens.ACTION_RADIUS)
        assertEquals(2f, LettaElevationTokens.ACTION_SHEET_ITEM_RESTING)
        assertEquals(4f, LettaElevationTokens.ACTION_SHEET_ITEM_PRESSED)
    }
}
