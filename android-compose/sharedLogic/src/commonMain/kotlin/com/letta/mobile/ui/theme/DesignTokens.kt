package com.letta.mobile.ui.theme

/**
 * Platform-neutral Letta spacing scale in density-independent pixels.
 *
 * Android adapts these values to Compose [Dp]; desktop targets can reuse the
 * same scalar contract without depending on Android or Compose UI classes.
 */
object LettaSpacingTokens {
    // Core scale
    const val none = 0f
    const val xxxs = 2f
    const val xxs = 4f
    const val xs = 6f
    const val sm = 8f
    const val md = 12f
    const val lg = 16f
    const val xl = 24f
    const val xxl = 32f
    const val xxxl = 64f

    // Semantic tokens
    const val screenHorizontal = md
    const val cardGap = sm
    const val sectionGap = lg
    const val cardGroupItemGap = xxxs
    const val innerPadding = lg
    const val innerPaddingSmall = md
    const val iconGap = md
    const val chipGap = sm

    // Chat-specific semantic tokens
    const val bubblePaddingHorizontal = 10f
    const val bubblePaddingVertical = 7f
    const val messageSpacing = xxxs
    const val composerAttachIconSize = 18f
    const val composerAttachButtonSize = 36f

    // Corner radii
    const val bubbleRadius = md
    const val codeBlockRadius = sm

    // Avatar and icon sizing
    const val avatarSize = xl
    const val iconSizeSmall = 14f
    const val borderWidthThin = 1f

    // Active-subagent and tool chip sizing
    const val chipMinHeight = 32f
    const val chipPaddingVertical = xs
    const val chipPaddingHorizontal = md
    const val chipRingSize = 20f
    const val chipRingStroke = 2f
}

object LettaSizingTokens {
    const val compactWidthBreakpoint = 600f
    const val readableDialogMaxWidth = 1000f
    const val diagramPreviewMinHeight = 120f
}

object LettaMotionTokens {
    const val StreamingSizeMillis = 60
    const val ContentSizeMillis = 220
    const val EnterMillis = 190
    const val ExitMillis = 130
    const val FastFadeInMillis = 120
    const val FastFadeOutMillis = 90
    const val ChipMillis = 150
}

object LettaShapeTokens {
    const val listRadius = LettaSpacingTokens.md
    const val prominentListRadius = LettaSpacingTokens.lg
    const val actionRadius = LettaSpacingTokens.sm
}

object LettaElevationTokens {
    const val none = 0f
    const val low = 1f
    const val actionSheetItemResting = 2f
    const val actionSheetItemPressed = 4f
    const val floatingBannerTonal = 3f
    const val floatingBannerShadow = 6f
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
        bubbleRadiusDp = LettaSpacingTokens.bubbleRadius,
        codeBlockRadiusDp = LettaSpacingTokens.codeBlockRadius,
    )

    val dimens = ChatDimensTokens(
        bubblePaddingHorizontalDp = LettaSpacingTokens.bubblePaddingHorizontal,
        bubblePaddingVerticalDp = LettaSpacingTokens.bubblePaddingVertical,
        bubbleMaxWidthFraction = 0.88f,
        messageSpacingDp = LettaSpacingTokens.messageSpacing,
        groupedMessageSpacingDp = LettaSpacingTokens.messageSpacing,
        ungroupedMessageSpacingDp = LettaSpacingTokens.xs,
        contentPaddingHorizontalDp = LettaSpacingTokens.screenHorizontal,
    )

    val typography = ChatTypographyTokens(
        roleLabelLetterSpacingSp = 0.4f,
        codeBlockFontSizeSp = 12f,
        codeBlockLineHeightSp = 16f,
    )
}
