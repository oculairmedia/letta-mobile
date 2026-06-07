package com.letta.mobile.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class DesignTokensCommonTest {
    @Test
    fun `spacing tokens preserve extracted design scale`() {
        assertEquals(12f, LettaSpacingTokens.screenHorizontal)
        assertEquals(8f, LettaSpacingTokens.cardGap)
        assertEquals(16f, LettaSpacingTokens.sectionGap)
        assertEquals(2f, LettaSpacingTokens.cardGroupItemGap)
        assertEquals(10f, LettaSpacingTokens.bubblePaddingHorizontal)
        assertEquals(7f, LettaSpacingTokens.bubblePaddingVertical)
        assertEquals(36f, LettaSpacingTokens.composerAttachButtonSize)
    }

    @Test
    fun `chat tokens expose dimensions and typography without Compose units`() {
        assertEquals(12f, LettaChatTokens.shapes.bubbleRadiusDp)
        assertEquals(8f, LettaChatTokens.shapes.codeBlockRadiusDp)
        assertEquals(0.88f, LettaChatTokens.dimens.bubbleMaxWidthFraction)
        assertEquals(6f, LettaChatTokens.dimens.ungroupedMessageSpacingDp)
        assertEquals(0.4f, LettaChatTokens.typography.roleLabelLetterSpacingSp)
        assertEquals(12f, LettaChatTokens.typography.codeBlockFontSizeSp)
        assertEquals(16f, LettaChatTokens.typography.codeBlockLineHeightSp)
    }

    @Test
    fun `motion and surface tokens preserve Android design-system values`() {
        assertEquals(60, LettaMotionTokens.StreamingSizeMillis)
        assertEquals(220, LettaMotionTokens.ContentSizeMillis)
        assertEquals(150, LettaMotionTokens.ChipMillis)
        assertEquals(12f, LettaShapeTokens.listRadius)
        assertEquals(8f, LettaShapeTokens.actionRadius)
        assertEquals(2f, LettaElevationTokens.actionSheetItemResting)
        assertEquals(4f, LettaElevationTokens.actionSheetItemPressed)
    }
}
