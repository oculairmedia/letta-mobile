package com.letta.mobile.ui.theme

/**
 * Platform-neutral Letta spacing scale in density-independent pixels.
 *
 * Android adapts these values to Compose [Dp]; desktop targets can reuse the
 * same scalar contract without depending on Android or Compose UI classes.
 */
object LettaSpacingTokens {
    // Core scale
    const val NONE = 0f
    const val XXXS = 2f
    const val XXS = 4f
    const val XS = 6f
    const val SM = 8f
    const val MD = 12f
    const val LG = 16f
    const val XL = 24f
    const val XXL = 32f
    const val XXXL = 64f

    // Semantic tokens
    const val SCREEN_HORIZONTAL = MD
    const val CARD_GAP = SM
    const val SECTION_GAP = LG
    const val CARD_GROUP_ITEM_GAP = XXXS
    const val INNER_PADDING = LG
    const val INNER_PADDING_SMALL = MD
    const val ICON_GAP = MD
    const val CHIP_GAP = SM

    // Chat-specific semantic tokens
    const val BUBBLE_PADDING_HORIZONTAL = 10f
    const val BUBBLE_PADDING_VERTICAL = 7f
    // Editorial inter-message rhythm (see docs/design/editorial-prose.md §3):
    // tighter WITHIN a turn, looser BETWEEN turns. MESSAGE_SPACING is the beat
    // between consecutive bubbles from the same speaker; UNGROUPED_MESSAGE_SPACING
    // is the editorial section break between speakers / run boundaries.
    const val MESSAGE_SPACING = SM
    const val UNGROUPED_MESSAGE_SPACING = XL
    const val COMPOSER_ATTACH_ICON_SIZE = 18f
    const val COMPOSER_ATTACH_BUTTON_SIZE = 36f

    // Corner radii
    const val BUBBLE_RADIUS = MD
    const val CODE_BLOCK_RADIUS = SM

    // Avatar and icon sizing
    const val AVATAR_SIZE = XL
    const val ICON_SIZE_SMALL = 14f
    const val BORDER_WIDTH_THIN = 1f

    // Active-subagent and tool chip sizing
    const val CHIP_MIN_HEIGHT = 32f
    const val CHIP_PADDING_VERTICAL = XS
    const val CHIP_PADDING_HORIZONTAL = MD
    const val CHIP_RING_SIZE = 20f
    const val CHIP_RING_STROKE = 2f
}

object LettaSizingTokens {
    const val COMPACT_WIDTH_BREAKPOINT = 600f
    const val READABLE_DIALOG_MAX_WIDTH = 1000f
    const val DIAGRAM_PREVIEW_MIN_HEIGHT = 120f
}

object LettaMotionTokens {
    const val STREAMING_SIZE_MILLIS = 60
    const val CONTENT_SIZE_MILLIS = 220
    const val ENTER_MILLIS = 190
    const val EXIT_MILLIS = 130
    const val FAST_FADE_IN_MILLIS = 120
    const val FAST_FADE_OUT_MILLIS = 90
    const val CHIP_MILLIS = 150
}

object LettaShapeTokens {
    const val LIST_RADIUS = LettaSpacingTokens.MD
    const val PROMINENT_LIST_RADIUS = LettaSpacingTokens.LG
    const val ACTION_RADIUS = LettaSpacingTokens.SM
}

object LettaElevationTokens {
    const val NONE = 0f
    const val LOW = 1f
    const val ACTION_SHEET_ITEM_RESTING = 2f
    const val ACTION_SHEET_ITEM_PRESSED = 4f
    const val FLOATING_BANNER_TONAL = 3f
    const val FLOATING_BANNER_SHADOW = 6f
}

data class ChatShapeTokens(
    val bubbleRadiusDp: Float,
    val codeBlockRadiusDp: Float,
)

data class ChatDimensTokens(
    val bubblePaddingHorizontalDp: Float,
    val bubblePaddingVerticalDp: Float,
    val bubbleMaxWidthFraction: Float,
    val messageSpacingDp: Float,
    val groupedMessageSpacingDp: Float,
    val ungroupedMessageSpacingDp: Float,
    val contentPaddingHorizontalDp: Float,
)

data class ChatTypographyTokens(
    val roleLabelLetterSpacingSp: Float,
    val codeBlockFontSizeSp: Float,
    val codeBlockLineHeightSp: Float,
)

object LettaChatTokens {
    val shapes = ChatShapeTokens(
        bubbleRadiusDp = LettaSpacingTokens.BUBBLE_RADIUS,
        codeBlockRadiusDp = LettaSpacingTokens.CODE_BLOCK_RADIUS,
    )

    val dimens = ChatDimensTokens(
        bubblePaddingHorizontalDp = LettaSpacingTokens.BUBBLE_PADDING_HORIZONTAL,
        bubblePaddingVerticalDp = LettaSpacingTokens.BUBBLE_PADDING_VERTICAL,
        bubbleMaxWidthFraction = 0.88f,
        messageSpacingDp = LettaSpacingTokens.MESSAGE_SPACING,
        groupedMessageSpacingDp = LettaSpacingTokens.MESSAGE_SPACING,
        ungroupedMessageSpacingDp = LettaSpacingTokens.UNGROUPED_MESSAGE_SPACING,
        contentPaddingHorizontalDp = LettaSpacingTokens.SCREEN_HORIZONTAL,
    )

    val typography = ChatTypographyTokens(
        roleLabelLetterSpacingSp = 0.4f,
        codeBlockFontSizeSp = 12f,
        codeBlockLineHeightSp = 16f,
    )
}
