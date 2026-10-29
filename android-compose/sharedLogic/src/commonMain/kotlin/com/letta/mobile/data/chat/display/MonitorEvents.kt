package com.letta.mobile.data.chat.display

import com.letta.mobile.data.model.MONITOR_EVENT_STATUS
import com.letta.mobile.data.model.UiSubagentNotification

// letta-mobile-a7ir6: letta-code's Monitor tool (task id `monitor_<uuid>`) reports every event as a
// `<task-notification>` with no status and a `<result><event>…</event></result>`, then one
// completed or failed notification when the watch ends. The timeline folds a watch into one
// watcher chip (collapseMonitorEvents) carried on an ordinary notification: its status is
// `running` until the watch ends, its summary the Monitor call's description, its result the
// latest `<event>`, and its usage an `events: N` line, letta-code's own usage-line shape.

/** The event text of a Monitor event, or a watcher chip's latest event; null for any other notification. */
fun UiSubagentNotification.monitorEvent(): String? {
    if (taskId?.startsWith(MONITOR_TASK_PREFIX) != true) return null
    return result?.let(EVENT_TAG::find)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}

/** Whether this notification stands for a whole watch (its timeline chip) rather than one event. */
fun UiSubagentNotification.isMonitorWatcher(): Boolean = status != MONITOR_EVENT_STATUS && monitorEvent() != null

/** How many events a watcher chip stands for; 0 for anything that is not one. */
fun UiSubagentNotification.monitorEventCount(): Int {
    if (!isMonitorWatcher()) return 0
    return usage?.let(EVENTS_LINE::find)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/** Whether a Monitor event only updates its watcher chip or also earns a one-line timeline row. */
internal enum class MonitorEventWeight { Routine, Actionable }

/**
 * Sorts an event by its words, since the watcher script decides what it says. Recovery, rate-limit,
 * edit, poll and passing-check news is routine even when it names a failure ("recovered after 1
 * failed poll"); a failure, merge, review, conflict or finished task is actionable; anything else
 * (progress such as "CI 6/11") only updates the chip.
 */
internal fun monitorEventWeight(event: String): MonitorEventWeight = when {
    ROUTINE_WORDS.containsMatchIn(event) -> MonitorEventWeight.Routine
    ACTIONABLE_WORDS.containsMatchIn(event) -> MonitorEventWeight.Actionable
    else -> MonitorEventWeight.Routine
}

/** Whether [taskId] names a Monitor watch. */
internal fun isMonitorTaskId(taskId: String?): Boolean = taskId?.startsWith(MONITOR_TASK_PREFIX) == true

/** The `<result>` a watcher chip carries for its latest [event]. */
internal fun monitorEventResult(event: String): String = "<event>$event</event>"

/** The usage line a watcher chip carries for its [count] of events. */
internal fun monitorEventUsage(count: Int): String = "events: $count"

private const val MONITOR_TASK_PREFIX = "monitor_"

// Greedy: an event's own text may contain the closing tag once the parser has decoded it.
private val EVENT_TAG = Regex("<event>([\\s\\S]*)</event>", RegexOption.IGNORE_CASE)
private val EVENTS_LINE = Regex("(?m)^\\s*events:\\s*(\\d+)\\s*$")

private val ROUTINE_WORDS = Regex(
    "\\b(recover(ed|ing|y)?|rate[- ]?limit(ed|s)?|throttl\\w*|edit(ed|s)?|poll(ed|ing|s)?|retr(y|ying|ied|ies)|" +
        "heartbeat|still|unchanged|no change|pending|queued|in[ _-]?progress|running|pass(ed|es|ing)?|" +
        "succeeded|success(ful)?|advisory|started|watching)\\b",
    RegexOption.IGNORE_CASE,
)
private val ACTIONABLE_WORDS = Regex(
    "\\b(fail(ed|ing|ure|ures|s)?|errors?|errored|broken|conflicts?|conflicted|merged|closed|reopened|" +
        "changes requested|approved|reviews?|reviewed|comments?|commented|blocked|timed out|timeout|" +
        "cancell?ed|action required|completed|finished|done)\\b",
    RegexOption.IGNORE_CASE,
)
