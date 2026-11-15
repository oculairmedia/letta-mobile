package com.letta.mobile.ui.components.foundation

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens

/**
 * The one horizontal rule: Material's [HorizontalDivider], a hairline in `outlineVariant` at full
 * strength. Replaces the hand-drawn `Box(Modifier.height(1.dp).background(outlineVariant.copy(alpha = 0.5f)))`
 * rules, which drew half as strong as the Material dividers beside them.
 *
 * It fills the width it is given; inset it with [modifier] padding.
 */
@Composable
fun LettaDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = LettaStrokeTokens.hairline, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * The one vertical rule, between controls in a bar or panes side by side: Material's [VerticalDivider]
 * in the same hairline and colour as [LettaDivider]. It fills the height it is given.
 */
@Composable
fun LettaVerticalDivider(modifier: Modifier = Modifier) {
    VerticalDivider(modifier = modifier, thickness = LettaStrokeTokens.hairline, color = MaterialTheme.colorScheme.outlineVariant)
}
