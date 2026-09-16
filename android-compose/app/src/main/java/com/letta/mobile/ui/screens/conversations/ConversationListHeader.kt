package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.oculair.meridian.R
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons

/**
 * The row above the list: where the conversations come from on the left, and on the right the
 * filter as a small "All ⌄" control that opens the status menu - All, Working, Completed, then
 * Archived below a divider. The archive is a filter here, not a separate mode with its own chips.
 */
@Composable
internal fun ConversationListHeader(
    label: String?,
    filter: ConversationFilter,
    onFilterChange: (ConversationFilter) -> Unit,
    onLabelClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label ?: stringResource(R.string.common_conversations),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (onLabelClick != null) Modifier.clickable(onClick = onLabelClick) else Modifier,
        )
        ConversationFilterMenu(filter = filter, onFilterChange = onFilterChange)
    }
}

@Composable
private fun ConversationFilterMenu(
    filter: ConversationFilter,
    onFilterChange: (ConversationFilter) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(filter.labelRes), style = MaterialTheme.typography.labelLarge)
                Icon(LettaIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(LettaIconSizing.Inline))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ConversationFilter.entries.forEach { entry ->
                if (entry == ConversationFilter.ARCHIVED) HorizontalDivider()
                val selected = entry == filter
                val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(entry.labelRes),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    leadingIcon = { Icon(entry.icon, contentDescription = null, tint = tint) },
                    trailingIcon = if (selected) {
                        { Icon(LettaIcons.Check, contentDescription = null, tint = tint) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onFilterChange(entry)
                    },
                )
            }
        }
    }
}

internal val ConversationFilter.labelRes: Int
    get() = when (this) {
        ConversationFilter.ALL -> R.string.screen_conversations_filter_all
        ConversationFilter.WORKING -> R.string.screen_conversations_filter_working
        ConversationFilter.COMPLETED -> R.string.screen_conversations_filter_completed
        ConversationFilter.ARCHIVED -> R.string.screen_conversations_filter_archived
    }

private val ConversationFilter.icon: ImageVector
    get() = when (this) {
        ConversationFilter.ALL -> LettaIcons.ListFilter
        ConversationFilter.WORKING -> LettaIcons.Loader
        ConversationFilter.COMPLETED -> LettaIcons.Check
        ConversationFilter.ARCHIVED -> LettaIcons.Archive
    }
