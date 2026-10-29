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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
// Both are one composable: the web bundle budget has no room for two near-identical rows.

/**
 * One Monitor line that opens to the event in full and the task id. A [watcher] is the watch's
 * chip, labelled and counted; otherwise it is a single actionable event.
 */
@Composable
internal fun MonitorRow(notification: UiSubagentNotification, watcher: Boolean) {
    // A watch's chip keeps its state as new events replace its text; an event row is its event.
    var expanded by remember(notification.taskId, notification.result.takeUnless { watcher }) { mutableStateOf(false) }
    val event = notification.monitorEvent().orEmpty()
    val chip = if (watcher) monitorChipText(notification, event) else null
    val failed = notification.status.equals("failed", ignoreCase = true)
    val tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth()
            .testTag(monitorTag(watcher))
            .padding(vertical = LettaDimens.Space.xs),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = monitorBackground(watcher),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { expanded = !expanded }
                .semantics { chip?.let { contentDescription = it.third } },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                Icon(
                    imageVector = monitorIcon(watcher),
                    contentDescription = null,
                    modifier = Modifier.size(LettaDimens.Control.icon),
                    tint = tint,
                )
                chip?.let { OneLine(it.first, MaterialTheme.typography.labelMedium, Modifier.weight(1f, fill = false)) }
                OneLine(event, MaterialTheme.typography.bodySmall, Modifier.weight(1f, fill = watcher.not()))
                chip?.let { Text(text = it.second, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1) }
                DisclosureChevron(expanded = expanded, compact = true, contentDescription = null)
            }
        }
        if (expanded) MonitorDetails(event, notification)
    }
}

private fun monitorTag(watcher: Boolean) = if (watcher) ChatRowTestTags.MONITOR_WATCHER else ChatRowTestTags.MONITOR_EVENT

private fun monitorIcon(watcher: Boolean) = if (watcher) LettaIcons.Visibility else LettaIcons.Info

@Composable
private fun monitorBackground(watcher: Boolean): Color =
    if (watcher) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent

/** The chip's label, its trailing count and state, and its content description. */
@Composable
private fun monitorChipText(notification: UiSubagentNotification, event: String): Triple<String, String, String> {
    val label = notification.summary ?: stringResource(Res.string.rows_monitor_label)
    val state = stringResource(
        when {
            notification.status.equals("failed", ignoreCase = true) -> Res.string.rows_monitor_failed
            notification.status.equals("running", ignoreCase = true) -> Res.string.rows_monitor_watching
            else -> Res.string.rows_monitor_ended
        },
    )
    val count = notification.monitorEventCount()
    val events = pluralStringResource(Res.plurals.rows_monitor_events, count, count)
    return Triple(
        label,
        listOfNotNull(events.takeIf { count > 0 }, state).joinToString(" · "),
        stringResource(Res.string.rows_monitor_chip_description, label, state, event, events),
    )
}

@Composable
private fun OneLine(text: String, style: TextStyle, modifier: Modifier) {
    Text(text = text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
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
