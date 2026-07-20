package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Letta design system spacing scale. Extracted from ChatScreen as the
 * canonical spacing reference (letta-mobile-awbf.1).
 *
 * Chat surfaces use the full scale; other features may use a subset.
 * Prefer semantic names (SCREEN_HORIZONTAL, CARD_GAP) over raw scale
 * references where the intent is clear.
 */
object LettaSpacing {
    // Core scale
    val NONE: Dp = LettaSpacingTokens.NONE.dp
    val XXXS: Dp = LettaSpacingTokens.XXXS.dp
    val XXS: Dp = LettaSpacingTokens.XXS.dp
    val XS: Dp = LettaSpacingTokens.XS.dp
    val SM: Dp = LettaSpacingTokens.SM.dp
    val MD: Dp = LettaSpacingTokens.MD.dp
    val LG: Dp = LettaSpacingTokens.LG.dp
    val XL: Dp = LettaSpacingTokens.XL.dp
    val XXL: Dp = LettaSpacingTokens.XXL.dp
    val XXXL: Dp = LettaSpacingTokens.XXXL.dp

    // Semantic tokens (legacy, preserved for compat)
    val SCREEN_HORIZONTAL: Dp = LettaSpacingTokens.SCREEN_HORIZONTAL.dp
    val CARD_GAP: Dp = LettaSpacingTokens.CARD_GAP.dp
    val SECTION_GAP: Dp = LettaSpacingTokens.SECTION_GAP.dp
    val CARD_GROUP_ITEM_GAP: Dp = LettaSpacingTokens.CARD_GROUP_ITEM_GAP.dp
    val INNER_PADDING: Dp = LettaSpacingTokens.INNER_PADDING.dp
    val INNER_PADDING_SMALL: Dp = LettaSpacingTokens.INNER_PADDING_SMALL.dp
    val ICON_GAP: Dp = LettaSpacingTokens.ICON_GAP.dp
    val CHIP_GAP: Dp = LettaSpacingTokens.CHIP_GAP.dp

    // Chat-specific semantic tokens (awbf.1 extraction)
    val BUBBLE_PADDING_HORIZONTAL: Dp = LettaSpacingTokens.BUBBLE_PADDING_HORIZONTAL.dp
    val BUBBLE_PADDING_VERTICAL: Dp = LettaSpacingTokens.BUBBLE_PADDING_VERTICAL.dp
    val MESSAGE_SPACING: Dp = LettaSpacingTokens.MESSAGE_SPACING.dp
    val COMPOSER_ATTACH_ICON_SIZE: Dp = LettaSpacingTokens.COMPOSER_ATTACH_ICON_SIZE.dp
    val COMPOSER_ATTACH_BUTTON_SIZE: Dp = LettaSpacingTokens.COMPOSER_ATTACH_BUTTON_SIZE.dp

    // Corner radii (chat bubble and code blocks)
    val BUBBLE_RADIUS: Dp = LettaSpacingTokens.BUBBLE_RADIUS.dp
    val CODE_BLOCK_RADIUS: Dp = LettaSpacingTokens.CODE_BLOCK_RADIUS.dp

    // Avatar and icon sizing
    val AVATAR_SIZE: Dp = LettaSpacingTokens.AVATAR_SIZE.dp
    val ICON_SIZE_SMALL: Dp = LettaSpacingTokens.ICON_SIZE_SMALL.dp
    val BORDER_WIDTH_THIN: Dp = LettaSpacingTokens.BORDER_WIDTH_THIN.dp

    // Homogeneous chip sizing for active-subagent and tool chips.
    val CHIP_MIN_HEIGHT: Dp = LettaSpacingTokens.CHIP_MIN_HEIGHT.dp
    val CHIP_PADDING_VERTICAL: Dp = LettaSpacingTokens.CHIP_PADDING_VERTICAL.dp
    val CHIP_PADDING_HORIZONTAL: Dp = LettaSpacingTokens.CHIP_PADDING_HORIZONTAL.dp

    // Determinate progress ring drawn around chip icons.
    val CHIP_RING_SIZE: Dp = LettaSpacingTokens.CHIP_RING_SIZE.dp
    val CHIP_RING_STROKE: Dp = LettaSpacingTokens.CHIP_RING_STROKE.dp
}
