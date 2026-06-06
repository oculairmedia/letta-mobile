package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Letta design system spacing scale. Extracted from ChatScreen as the
 * canonical spacing reference (letta-mobile-awbf.1).
 *
 * Chat surfaces use the full scale; other features may use a subset.
 * Prefer semantic names (screenHorizontal, cardGap) over raw scale
 * references where the intent is clear.
 */
object LettaSpacing {
    // Core scale
    val none: Dp = LettaSpacingTokens.none.dp
    val xxxs: Dp = LettaSpacingTokens.xxxs.dp
    val xxs: Dp = LettaSpacingTokens.xxs.dp
    val xs: Dp = LettaSpacingTokens.xs.dp
    val sm: Dp = LettaSpacingTokens.sm.dp
    val md: Dp = LettaSpacingTokens.md.dp
    val lg: Dp = LettaSpacingTokens.lg.dp
    val xl: Dp = LettaSpacingTokens.xl.dp
    val xxl: Dp = LettaSpacingTokens.xxl.dp
    val xxxl: Dp = LettaSpacingTokens.xxxl.dp

    // Semantic tokens (legacy, preserved for compat)
    val screenHorizontal: Dp = LettaSpacingTokens.screenHorizontal.dp
    val cardGap: Dp = LettaSpacingTokens.cardGap.dp
    val sectionGap: Dp = LettaSpacingTokens.sectionGap.dp
    val cardGroupItemGap: Dp = LettaSpacingTokens.cardGroupItemGap.dp
    val innerPadding: Dp = LettaSpacingTokens.innerPadding.dp
    val innerPaddingSmall: Dp = LettaSpacingTokens.innerPaddingSmall.dp
    val iconGap: Dp = LettaSpacingTokens.iconGap.dp
    val chipGap: Dp = LettaSpacingTokens.chipGap.dp

    // Chat-specific semantic tokens (awbf.1 extraction)
    val bubblePaddingHorizontal: Dp = LettaSpacingTokens.bubblePaddingHorizontal.dp
    val bubblePaddingVertical: Dp = LettaSpacingTokens.bubblePaddingVertical.dp
    val messageSpacing: Dp = LettaSpacingTokens.messageSpacing.dp
    val composerAttachIconSize: Dp = LettaSpacingTokens.composerAttachIconSize.dp
    val composerAttachButtonSize: Dp = LettaSpacingTokens.composerAttachButtonSize.dp

    // Corner radii (chat bubble and code blocks)
    val bubbleRadius: Dp = LettaSpacingTokens.bubbleRadius.dp
    val codeBlockRadius: Dp = LettaSpacingTokens.codeBlockRadius.dp

    // Avatar and icon sizing
    val avatarSize: Dp = LettaSpacingTokens.avatarSize.dp
    val iconSizeSmall: Dp = LettaSpacingTokens.iconSizeSmall.dp
    val borderWidthThin: Dp = LettaSpacingTokens.borderWidthThin.dp

    // Homogeneous chip sizing for active-subagent and tool chips.
    val chipMinHeight: Dp = LettaSpacingTokens.chipMinHeight.dp
    val chipPaddingVertical: Dp = LettaSpacingTokens.chipPaddingVertical.dp
    val chipPaddingHorizontal: Dp = LettaSpacingTokens.chipPaddingHorizontal.dp

    // Determinate progress ring drawn around chip icons.
    val chipRingSize: Dp = LettaSpacingTokens.chipRingSize.dp
    val chipRingStroke: Dp = LettaSpacingTokens.chipRingStroke.dp
}
