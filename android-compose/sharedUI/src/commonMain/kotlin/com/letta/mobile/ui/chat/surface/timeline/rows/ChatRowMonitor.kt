package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.chat.projection.monitorEvent
import com.letta.mobile.data.chat.projection.monitorEventCount
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_monitor_chip_description
import com.letta.mobile.sharedui.resources.rows_monitor_ended
import com.letta.mobile.sharedui.resources.rows_monitor_events
import com.letta.mobile.sharedui.resources.rows_monitor_failed
import com.letta.mobile.sharedui.resources.rows_monitor_label
import com.letta.mobile.sharedui.resources.rows_monitor_watching
import com.letta.mobile.sharedui.resources.rows_task_id
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// letta-mobile-a7ir6: a Monitor watch recedes to one chip (its newest event, the event count and
// whether it still watches); an actionable event is one line. The raw task id shows only expanded.

/** A Monitor watch's chip: one line that opens to the newest event in full and the task id. */
@Composable
internal fun MonitorWatcherChip(notification: UiSubagentNotification) {
    var expanded by remember(notification.taskId) { mutableStateOf(false) }
    val event = notification.monitorEvent().orEmpty()
    val label = notification.summary ?: stringResource(Res.string.rows_monitor_label)
    val failed = notification.status.equals("failed", ignoreCase = true)
    val state = stringResource(
        when {
            failed -> Res.string.rows_monitor_failed
            notification.status.equals("running", ignoreCase = true) -> Res.string.rows_monitor_watching
            else -> Res.string.rows_monitor_ended
        },
    )
    val count = notification.monitorEventCount()
    val description = stringResource(Res.string.rows_monitor_chip_description, label, state, event)
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.MONITOR_WATCHER).padding(vertical = LettaDimens.Space.xs),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { expanded = !expanded }.semantics { contentDescription = description },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                Icon(
                    imageVector = LettaIcons.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(LettaDimens.Control.icon),
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Text(
                    text = event,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = listOfNotNull(pluralStringResource(Res.plurals.rows_monitor_events, count, count).takeIf { count > 0 }, state)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                DisclosureChevron(expanded = expanded, compact = true, contentDescription = null)
            }
        }
        if (expanded) MonitorDetails(event, notification)
    }
}

/** An actionable Monitor event: one line that opens to its full text and the task id. */
@Composable
internal fun MonitorEventRow(notification: UiSubagentNotification) {
    var expanded by remember(notification.taskId, notification.result) { mutableStateOf(false) }
    val event = notification.monitorEvent().orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.MONITOR_EVENT).padding(vertical = LettaDimens.Space.xs),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Icon(
                imageVector = LettaIcons.Info,
                contentDescription = null,
                modifier = Modifier.size(LettaDimens.Control.icon),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = event,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DisclosureChevron(expanded = expanded, compact = true, contentDescription = null)
        }
        if (expanded) MonitorDetails(event, notification)
    }
}

@Composable
private fun MonitorDetails(event: String, notification: UiSubagentNotification) {
    SelectionContainer {
        Text(text = event, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    notification.taskId?.let {
        Text(
            text = stringResource(Res.string.rows_task_id, it),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(ChatRowAlpha.deliveryTime),
        )
    }
}
