package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens

/**
 * The one switch row: a [LettaRowLabel] at the start, a Material [Switch] at the end, and the whole
 * row as the hit target (one node, role Switch, at least the platform's minimum touch target tall).
 * Replaces the hand-built switch rows that each chose their own label style and padding; Settings'
 * toggle rows are built on it.
 *
 * Use it for any on/off preference or option in a list. A disabled [label] greys the words and the
 * switch and ignores taps. [modifier] goes on the row (test tags).
 */
@Composable
fun LettaToggleRow(
    label: LettaRowLabel,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = Modifier.toggleable(value = checked, enabled = label.enabled, role = Role.Switch, onValueChange = onCheckedChange)
    LettaControlRow(label = label, modifier = modifier, interaction = interaction) {
        Switch(checked = checked, onCheckedChange = null, enabled = label.enabled)
    }
}

/**
 * The row anatomy under [LettaToggleRow] and Settings' rows, for a row whose trailing control is not
 * a switch (a value, a status, a chevron): the [label] at the start, [trailing] at the end, at least
 * the platform's minimum touch target tall.
 *
 * [interaction] is the row's `clickable`/`toggleable` modifier (or none); it goes on the whole row,
 * which merges its children into one node.
 */
@Composable
fun LettaControlRow(
    label: LettaRowLabel,
    modifier: Modifier = Modifier,
    interaction: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LettaControlTokens.minTouchTarget)
            .then(interaction)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = LettaSpaceTokens.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.md),
    ) {
        LettaRowWords(label, Modifier.weight(1f))
        trailing()
    }
}

/**
 * A row's label over its one-line helper, as [LettaControlRow] draws them; for a row that lays out
 * its own trailing part but should read the same.
 */
@Composable
fun LettaRowWords(label: LettaRowLabel, modifier: Modifier = Modifier) {
    val titleColor = when {
        label.destructive -> MaterialTheme.colorScheme.error
        label.enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LettaSpaceTokens.hair)) {
        Text(label.title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        label.supporting?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
