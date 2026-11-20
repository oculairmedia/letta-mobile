package com.letta.mobile.ui.components.row

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import com.letta.mobile.ui.theme.LettaDimens

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
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(contentAlignment = Alignment.Center) { leading() }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            content = content,
        )
    }
}

/**
 * A text action on a [LettaHangingRow]'s content edge ("View conversation", "Dismiss"): primary ink,
 * a button to assistive technology, the platform's minimum touch height, and no horizontal inset,
 * so its first letter lines up with the title above it.
 */
@Composable
internal fun LettaHangingRowAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String = "") {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = modifier
            .lettaClickable(role = Role.Button, target = LettaClickTarget.Standalone, onClick = onClick)
            .then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag)),
    )
}
