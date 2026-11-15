package com.letta.mobile.ui.theme.tokens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Leaf values of the LettaDimens scale, like LettaRowDimens: LettaDimens.Space, .Control and .Stroke
 * read them from here. They live in this import-free package so the shared components in
 * ui.components.foundation can use the scale without importing ui.theme, which any page can then
 * import without closing a package cycle. Call sites keep using LettaDimens.
 */

/** The 4dp spacing grid; see LettaDimens.Space. */
object LettaSpaceTokens {
    val hair: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = LettaRowDimens.spacing
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Control sizes; see LettaDimens.Control. */
object LettaControlTokens {
    val iconButtonSm: Dp = 20.dp
    val iconButton: Dp = 28.dp
    val iconButtonLg: Dp = 34.dp
    val iconSm: Dp = 13.dp
    val icon: Dp = LettaRowDimens.icon
    val fieldHeight: Dp = 32.dp
    val actionButton: Dp = 38.dp
    val minTouchTarget: Dp = 48.dp
}

/** Strokes; see LettaDimens.Stroke. [hairline] is the only border width. */
object LettaStrokeTokens {
    val hairline: Dp = 1.dp
}
