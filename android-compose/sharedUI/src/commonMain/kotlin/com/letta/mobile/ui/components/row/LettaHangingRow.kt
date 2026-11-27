package com.letta.mobile.ui.components.row

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.letta.mobile.ui.components.foundation.LettaClickTarget
import com.letta.mobile.ui.components.foundation.lettaClickable
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens

/**
 * A hanging-indent row: [leading] (a status dot, an orb, a glyph) in its own column, and every line
 * of [content] (title, meta, detail lines, actions) stacked on one shared left edge after it, so
 * nothing below the title slides back under the leading mark.
 *
 * Put every line of the item in [content]; a line placed outside it starts at the row's edge and
 * breaks the indent. Actions inside it use [LettaHangingRowAction], whose label starts on the
 * content edge rather than inside a button's padding.
 */
@Composable
internal fun LettaHangingRow(
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(contentAlignment = Alignment.Center) { leading() }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(LettaSpaceTokens.xs),
            content = content,
        )
    }
}

/**
 * A text action on a [LettaHangingRow]'s content edge ("View conversation", "Dismiss"): primary ink,
 * a button to assistive technology, the platform's minimum touch size, and no horizontal inset, so
 * its first letter lines up with the title above it. A label narrower than the minimum touch width
 * keeps its start on the edge: the touch box grows to the right of it instead of centring the label.
 * [destructive] inks the label as the error colour instead of the primary one (the row's "Dismiss").
 */
@Composable
internal fun LettaHangingRowAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "",
    destructive: Boolean = false,
) {
    Box(
        modifier = modifier
            .lettaClickable(role = Role.Button, target = LettaClickTarget.Inline, onClick = onClick)
            .defaultMinSize(minWidth = LettaControlTokens.minTouchTarget, minHeight = LettaControlTokens.minTouchTarget)
            .then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}
