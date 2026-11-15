package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.chat.projection.monitorEvent
import com.letta.mobile.data.chat.projection.monitorDisplayLines
import com.letta.mobile.data.chat.projection.monitorEventCount
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_monitor_chip_description
import com.letta.mobile.sharedui.resources.rows_monitor_ended
import com.letta.mobile.sharedui.resources.rows_monitor_events
import com.letta.mobile.sharedui.resources.rows_monitor_failed
import com.letta.mobile.sharedui.resources.rows_monitor_label
import com.letta.mobile.sharedui.resources.rows_monitor_watching
import com.letta.mobile.sharedui.resources.rows_monitor_show_raw
import com.letta.mobile.sharedui.resources.rows_monitor_hide_raw
import com.letta.mobile.sharedui.resources.rows_task_id
import com.letta.mobile.ui.components.SummaryLine
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.components.line.LineSpec
import com.letta.mobile.ui.components.line.LineTags
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// letta-mobile-a7ir6: a Monitor watch recedes to one chip (its newest event, the event count and
// whether it still watches); an actionable event is one line. The raw task id shows only expanded.
// Both are one composable: the web bundle budget has no room for two near-identical rows.

/**
 * One Monitor line that opens to the event in full and the task id. A [watcher] is the watch's
 * chip, labelled and counted; otherwise it is a single actionable event. Either is the shared
 * [SummaryLine] on the header rail: a Monitor event is a sibling of a run's header, never one of its
 * work steps, so it keeps the header's glyph, label and chevron column wherever it is drawn.
 */
@Composable
internal fun MonitorRow(notification: UiSubagentNotification, watcher: Boolean) {
    // A watch's chip keeps its state as new events replace its text; an event row is its event.
    var expanded by remember(notification.taskId, notification.result.takeUnless { watcher }) { mutableStateOf(false) }
    val raw = notification.monitorEvent().orEmpty()
    val lines = remember(raw) { monitorDisplayLines(raw) }
    val event = lines.firstOrNull().orEmpty()
    val chip = if (watcher) monitorChipText(notification, event) else null
    Column(
        modifier = Modifier.fillMaxWidth().testTag(monitorTag(watcher)),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        SummaryLine(
            spec = LineSpec(
                title = chip?.first ?: event,
                tone = monitorTone(notification, watcher),
                tags = LineTags(row = monitorLineTag(watcher), glyph = ChatRowTestTags.MONITOR_GLYPH),
            ),
            disclosure = LineDisclosure(expanded) { expanded = !expanded },
            modifier = monitorChrome(watcher).semantics { chip?.let { contentDescription = it.third } },
            detail = event.takeIf { watcher },
            meta = chip?.second,
        )
        if (expanded) MonitorDetails(raw, lines, notification)
    }
}

/** A watch's chip keeps its tonal fill (on the line's own bounds, so its column does not move); an event has none. */
@Composable
private fun monitorChrome(watcher: Boolean): Modifier =
    if (watcher) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small) else Modifier

private fun monitorTag(watcher: Boolean) = if (watcher) ChatRowTestTags.MONITOR_WATCHER else ChatRowTestTags.MONITOR_EVENT

private fun monitorLineTag(watcher: Boolean) = if (watcher) ChatRowTestTags.MONITOR_WATCHER_LINE else ChatRowTestTags.MONITOR_EVENT_LINE

/** A failed watch or event in the error tone; a watch's eye; an event's info mark. */
private fun monitorTone(notification: UiSubagentNotification, watcher: Boolean): StatusTone = when {
    notification.status.equals("failed", ignoreCase = true) -> StatusTone.Failed
    watcher -> StatusTone.Watching
    else -> StatusTone.Info
}

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
private fun MonitorDetails(raw: String, lines: List<String>, notification: UiSubagentNotification) {
    var showRaw by remember(raw) { mutableStateOf(false) }
    SelectionContainer {
        Text(
            text = if (showRaw) raw else lines.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = stringResource(if (showRaw) Res.string.rows_monitor_hide_raw else Res.string.rows_monitor_show_raw),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clickable(role = Role.Button) { showRaw = !showRaw }.padding(LettaDimens.Space.xs),
    )
    notification.taskId?.let {
        Text(
            text = stringResource(Res.string.rows_task_id, it),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(ChatRowAlpha.deliveryTime),
        )
    }
}
