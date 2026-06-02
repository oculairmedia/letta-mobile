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
    val none: Dp = 0.dp
    val xxxs: Dp = 2.dp
    val xxs: Dp = 4.dp
    val xs: Dp = 6.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 64.dp

    // Semantic tokens (legacy, preserved for compat)
    val screenHorizontal: Dp = md  // 12.dp
    val cardGap: Dp = sm  // 8.dp
    val sectionGap: Dp = lg  // 16.dp
    val cardGroupItemGap: Dp = xxxs  // 2.dp
    val innerPadding: Dp = lg  // 16.dp
    val innerPaddingSmall: Dp = md  // 12.dp
    val iconGap: Dp = md  // 12.dp
    val chipGap: Dp = sm  // 8.dp

    // Chat-specific semantic tokens (awbf.1 extraction)
    val bubblePaddingHorizontal: Dp = 10.dp  // asymmetric token (10 vs 12)
    val bubblePaddingVertical: Dp = 7.dp  // asymmetric token (7 vs 8)
    val messageSpacing: Dp = xxxs  // 2.dp - tight spacing for grouped messages
    val composerAttachIconSize: Dp = 18.dp
    val composerAttachButtonSize: Dp = 36.dp

    // Corner radii (chat bubble and code blocks)
    val bubbleRadius: Dp = md  // 12.dp
    val codeBlockRadius: Dp = sm  // 8.dp

    // Avatar and icon sizing
    val avatarSize: Dp = xl  // 24.dp
    val iconSizeSmall: Dp = 14.dp
    val borderWidthThin: Dp = 1.dp
}
