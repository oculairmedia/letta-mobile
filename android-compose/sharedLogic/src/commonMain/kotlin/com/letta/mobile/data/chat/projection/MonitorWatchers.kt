package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.chat.display.MonitorEventWeight
import com.letta.mobile.data.chat.display.isMonitorTaskId
import com.letta.mobile.data.chat.display.isMonitorWatcher
import com.letta.mobile.data.chat.display.monitorEvent
import com.letta.mobile.data.chat.display.monitorEventResult
import com.letta.mobile.data.chat.display.monitorEventUsage
import com.letta.mobile.data.chat.display.monitorEventWeight
import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.model.MONITOR_EVENT_STATUS
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentNotification

// letta-mobile-a7ir6: a long PR watch drew dozens of full-width "Monitor event" rows. Each watch
// now draws one chip, keyed by its task id where its first event landed, so it updates in place;
// routine events only update the chip, and only actionable ones keep a row of their own.

/**
 * [messages] (chronological) with each Monitor watch's events folded into one watcher chip at its
 * first event's place. The chip shows the latest event, the event count and the watch's end once its
 * completed or failed notification arrives (that notification keeps its own row). An actionable
 * event (see monitorEventWeight) also keeps its row; a routine one only updates the chip. Returns
 * [messages] itself when it holds no Monitor event. Not idempotent: fold a list once.
 */
internal fun collapseMonitorEvents(messages: List<UiMessage>): List<UiMessage> {
    if (messages.none { it.subagentNotification?.rawMonitorEvent() != null }) return messages
    val watches = scanWatches(messages)
    val labels = monitorLabels(messages)
    val folded = ArrayList<UiMessage>(messages.size)
    messages.forEachIndexed { index, message ->
        val notification = message.subagentNotification
        val event = notification?.rawMonitorEvent()
        val taskId = notification?.taskId
        val watch = taskId?.let(watches::get)
        if (notification == null || event == null || taskId == null || watch == null) {
            folded += message
            return@forEachIndexed
        }
        if (index == watch.anchor) folded += watch.chip(message, notification, labels[taskId])
        if (monitorEventWeight(event) == MonitorEventWeight.Actionable) folded += message
    }
    return folded
}

/** The stable id of a watch's chip: it must not collide with its first event, which may keep a row too. */
internal fun monitorWatcherId(taskId: String): String = "monitor-watch-$taskId"

/** One watch's events so far. */
private class MonitorWatch {
    var anchor = -1
    var count = 0
    var latest = ""
    var endStatus: String? = null

    fun chip(anchor: UiMessage, notification: UiSubagentNotification, label: String?): UiMessage {
        val taskId = notification.taskId.orEmpty()
        return anchor.copy(
            id = monitorWatcherId(taskId),
            clientMessageId = null,
            subagentNotification = notification.copy(
                status = endStatus?.trim()?.lowercase()?.ifEmpty { null } ?: WATCHING_STATUS,
                summary = label,
                result = monitorEventResult(latest),
                usage = monitorEventUsage(count),
                transcriptUri = null,
                durationMs = null,
            ),
        )
    }
}

private fun scanWatches(messages: List<UiMessage>): Map<String, MonitorWatch> {
    val watches = LinkedHashMap<String, MonitorWatch>()
    messages.forEachIndexed { index, message ->
        val notification = message.subagentNotification ?: return@forEachIndexed
        val taskId = notification.taskId?.takeIf(::isMonitorTaskId) ?: return@forEachIndexed
        val event = notification.rawMonitorEvent()
        when {
            event != null -> watches.getOrPut(taskId, ::MonitorWatch).apply {
                if (anchor < 0) anchor = index
                count++
                latest = event
            }
            // The watch's own completed or failed notification: it ends the chip's watch.
            !notification.isMonitorWatcher() -> watches[taskId]?.endStatus = notification.status
        }
    }
    return watches
}

/** Each watch's description, from the Monitor call that started it ("Monitor started (task monitor_…"). */
private fun monitorLabels(messages: List<UiMessage>): Map<String, String> {
    val labels = HashMap<String, String>()
    for (call in messages.asSequence().flatMap { it.toolCalls.orEmpty() }) {
        if (!call.name.equals(MONITOR_TOOL, ignoreCase = true)) continue
        val taskId = call.result?.let(MONITOR_STARTED::find)?.groupValues?.get(1) ?: continue
        ToolCallFacts(call.name, call.arguments, call.result).string("description")?.let { labels[taskId] = it }
    }
    return labels
}

/** The event text of a notification that is one Monitor event, not yet folded into a chip. */
private fun UiSubagentNotification.rawMonitorEvent(): String? =
    if (status == MONITOR_EVENT_STATUS) monitorEvent() else null

private const val MONITOR_TOOL = "Monitor"
private const val WATCHING_STATUS = "running"
private val MONITOR_STARTED = Regex("Monitor started \\(task (monitor_[^,\\s)]+)")
