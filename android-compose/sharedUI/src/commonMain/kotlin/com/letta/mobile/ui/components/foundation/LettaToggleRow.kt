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
 * The one switch row: [title] over an optional one-line [supporting] helper, a Material [Switch] at
 * the end, and the whole row as the hit target (one node, role Switch, at least the platform's minimum
 * touch target tall). Replaces the hand-built switch rows that each chose their own label style and
 * padding; Settings' toggle rows are built on it.
 *
 * Use it for any on/off preference or option in a list. [enabled] false greys the words and the
 * switch and ignores taps. [modifier] goes on the row (test tags).
 */
@Composable
fun LettaToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    LettaControlRow(
        title = title,
        modifier = modifier,
        supporting = supporting,
        enabled = enabled,
        interaction = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * The row anatomy under [LettaToggleRow] and Settings' rows, for a row whose trailing control is not
 * a switch (a value, a status, a chevron): [title] over an optional one-line [supporting] helper at
 * the start, [trailing] at the end, at least the platform's minimum touch target tall.
 *
 * [interaction] is the row's `clickable`/`toggleable` modifier (or none); it goes on the whole row,
 * which merges its children into one node. [destructive] draws the title in the error colour.
 */
@Composable
fun LettaControlRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
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
        LettaRowWords(title, supporting, enabled, Modifier.weight(1f), destructive)
        trailing()
    }
}

/**
 * A row's label over its one-line helper, as [LettaControlRow] draws them; for a row that lays out
 * its own trailing part but should read the same.
 */
@Composable
fun LettaRowWords(
    title: String,
    supporting: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val titleColor = when {
        destructive -> MaterialTheme.colorScheme.error
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LettaSpaceTokens.hair)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        supporting?.let {
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
