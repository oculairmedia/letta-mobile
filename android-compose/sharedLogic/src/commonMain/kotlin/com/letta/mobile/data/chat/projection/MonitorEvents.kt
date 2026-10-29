package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.model.MONITOR_EVENT_STATUS
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentNotification

// letta-mobile-a7ir6: letta-code's Monitor tool (task id `monitor_<uuid>`) reports every event as a
// `<task-notification>` with no status and a `<result><event>…</event></result>`, then one completed
// or failed notification when the watch ends. A long PR watch drew dozens of full-width rows, so the
// timeline folds each watch's events into one watcher chip at its newest event (a page at a time on
// the paged timeline, which reads no further than its page). The chip is an ordinary notification:
// its status is `running` until the watch ends, its summary the Monitor call's description, its
// result the newest `<event>` and its usage an `events: N` line, letta-code's own usage-line shape.

/** The event text of a Monitor event, or a watcher chip's newest event; null for any other notification. */
fun UiSubagentNotification.monitorEvent(): String? {
    if (!isMonitorTask()) return null
    return result?.let(EVENT_TAG::find)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}

/** Whether this notification stands for a whole watch (its watcher chip) rather than one event. */
fun UiSubagentNotification.isMonitorWatcher(): Boolean = status != MONITOR_EVENT_STATUS && monitorEvent() != null

/** How many events a watcher chip stands for; 0 for anything that is not one. */
fun UiSubagentNotification.monitorEventCount(): Int {
    if (!isMonitorWatcher()) return 0
    return usage?.let(EVENTS_LINE::find)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/**
 * Whether a Monitor event earns a one-line row besides updating its chip. The watcher script decides
 * what an event says, so this goes by its words. A merge, reopen, conflict or change request is
 * always actionable ("PR merged; checks passed"). Otherwise recovery, rate-limit, edit, poll and
 * passing-check news is routine even when it names a failure ("recovered after 1 failed poll"); a
 * failure, review or finished task is actionable; anything else (progress such as "CI 6/11") is routine.
 */
internal fun isActionableMonitorEvent(event: String): Boolean =
    DECISIVE_WORDS.containsMatchIn(event) ||
        (!ROUTINE_WORDS.containsMatchIn(event) && ACTIONABLE_WORDS.containsMatchIn(event))

/**
 * [messages] (chronological) with each Monitor watch's events folded: its newest event becomes the
 * watch's chip, an older actionable event keeps its row, and an older routine one is left out.
 * Returns [messages] itself when it holds no Monitor event. Fold a list once: a folded list's
 * remaining actionable events would fold again.
 */
internal fun collapseMonitorEvents(messages: List<UiMessage>): List<UiMessage> {
    val folded = foldMonitorEvents(messages)
    return if (folded === messages) messages else folded.filterNotNull()
}

/**
 * [collapseMonitorEvents] in place, for a list whose positions matter (a page's records): each slot
 * keeps its message, becomes its watch's chip, or is null when the event is left out.
 */
internal fun foldMonitorEvents(messages: List<UiMessage?>): List<UiMessage?> {
    val watches = MonitorWatches()
    messages.forEachIndexed(watches::record)
    if (watches.isEmpty()) return messages
    val labels = monitorLabels(messages)
    return messages.mapIndexed { index, message -> watches.fold(index, message, labels) }
}

/** Each watch's newest event, event count and end status, as [foldMonitorEvents] reads them. */
private class MonitorWatches {
    private val newest = HashMap<String, Int>()
    private val counts = HashMap<String, Int>()
    private val ends = HashMap<String, String>()

    fun isEmpty(): Boolean = newest.isEmpty()

    fun record(index: Int, message: UiMessage?) {
        val notification = message?.subagentNotification ?: return
        val taskId = notification.taskId?.takeIf { notification.isMonitorTask() } ?: return
        if (notification.rawMonitorEvent() != null) {
            newest[taskId] = index
            counts[taskId] = (counts[taskId] ?: 0) + 1
        } else if (!notification.isMonitorWatcher()) {
            // The watch's own completed or failed notification, which keeps its row.
            ends[taskId] = notification.status
        }
    }

    /** [message] as it stays, as its watch's chip, or null when it is left out. */
    fun fold(index: Int, message: UiMessage?, labels: Map<String, String>): UiMessage? {
        val notification = message?.subagentNotification
        val event = notification?.rawMonitorEvent()
        val taskId = notification?.taskId
        return when {
            message == null || notification == null || event == null || taskId == null -> message
            newest[taskId] == index -> message.copy(subagentNotification = chip(notification, event, labels[taskId]))
            isActionableMonitorEvent(event) -> message
            else -> null
        }
    }

    /** [notification] (one of the watch's events) as the watch's chip. */
    private fun chip(notification: UiSubagentNotification, event: String, label: String?): UiSubagentNotification {
        val taskId = notification.taskId.orEmpty()
        return notification.copy(
            status = ends[taskId]?.trim()?.lowercase()?.ifEmpty { null } ?: WATCHING_STATUS,
            summary = label,
            result = "<event>$event</event>",
            usage = "events: ${counts[taskId] ?: 1}",
            transcriptUri = null,
            durationMs = null,
        )
    }
}

/** Each watch's description, from the Monitor call that started it ("Monitor started (task monitor_…"). */
private fun monitorLabels(messages: List<UiMessage?>): Map<String, String> {
    val labels = HashMap<String, String>()
    for (call in messages.asSequence().flatMap { it?.toolCalls.orEmpty() }) {
        if (!call.name.equals(MONITOR_TOOL, ignoreCase = true)) continue
        val taskId = call.result?.let(MONITOR_STARTED::find)?.groupValues?.get(1) ?: continue
        ToolCallFacts(call.name, call.arguments, call.result).string("description")?.let { labels[taskId] = it }
    }
    return labels
}

/** The event text of a notification that is one Monitor event, not yet folded into a chip. */
private fun UiSubagentNotification.rawMonitorEvent(): String? =
    if (status == MONITOR_EVENT_STATUS) monitorEvent() else null

private fun UiSubagentNotification.isMonitorTask(): Boolean = taskId?.startsWith(MONITOR_TASK_PREFIX) == true

private const val MONITOR_TASK_PREFIX = "monitor_"
private const val MONITOR_TOOL = "Monitor"
private const val WATCHING_STATUS = "running"
private val MONITOR_STARTED = Regex("Monitor started \\(task (monitor_[^,\\s)]+)")

// Greedy: an event's own text may contain the closing tag once the parser has decoded it.
private val EVENT_TAG = Regex("<event>([\\s\\S]*)</event>", RegexOption.IGNORE_CASE)
private val EVENTS_LINE = Regex("(?m)^\\s*events:\\s*(\\d+)\\s*$")

private val ROUTINE_WORDS = Regex(
    "\\b(recover(ed|ing|y)?|rate[- ]?limit(ed|s)?|throttl\\w*|edit(ed|s)?|poll(ed|ing|s)?|retr(y|ying|ied|ies)|" +
        "heartbeat|still|unchanged|no change|pending|queued|in[ _-]?progress|running|pass(ed|es|ing)?|" +
        "succeeded|success(ful)?|advisory|started|watching)\\b",
    RegexOption.IGNORE_CASE,
)
private val DECISIVE_WORDS = Regex(
    "\\b(merged|reopened|conflicts?|conflicted|changes requested)\\b",
    RegexOption.IGNORE_CASE,
)
private val ACTIONABLE_WORDS = Regex(
    "\\b(fail(ed|ing|ure|ures|s)?|errors?|errored|broken|conflicts?|conflicted|merged|closed|reopened|" +
        "changes requested|approved|reviews?|reviewed|comments?|commented|blocked|timed out|timeout|" +
        "cancell?ed|action required|completed|finished|done)\\b",
    RegexOption.IGNORE_CASE,
)
