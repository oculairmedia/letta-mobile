package com.letta.mobile.ui.components.foundation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaControlTokens

/**
 * The trailing chevron on a row that drills into another page or pane ("Providers ›"): a right
 * chevron at the scale's glyph size in `onSurfaceVariant`. Replaces the hand-built right chevrons at
 * 13, 18 and 24 dp.
 *
 * It is decorative (no content description): the row it sits on carries the words and the click. For
 * a chevron that expands or collapses content in place, use DisclosureChevron.
 */
@Composable
fun LettaNavChevron(modifier: Modifier = Modifier) {
    LettaIcon(
        LettaIcons.ChevronRight,
        contentDescription = null,
        modifier = modifier,
        size = LettaControlTokens.icon,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
