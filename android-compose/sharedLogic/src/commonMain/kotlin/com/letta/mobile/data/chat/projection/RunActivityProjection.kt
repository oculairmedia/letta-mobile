package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.ToolReturnStatus
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.isCommand
import kotlin.time.Instant

enum class RunActivityState {
    Working,
    Thought,
    Worked,
}

/**
 * Platform-neutral summary derived from an already ordered [UiMessage] run.
 *
 * The projection carries no transport identity and never rewrites, merges, or
 * reorders canonical messages.
 */
data class RunActivityProjection(
    val state: RunActivityState,
    val durationMs: Long?,
    val toolCount: Int,
    val failureCount: Int,
) {
    val isActive: Boolean
        get() = state == RunActivityState.Working
}

/**
 * Projects one run's messages into disclosure state.
 *
 * [isActiveRunStreaming] must already be scoped to this run. A conversation-
 * wide streaming flag would incorrectly reactivate historical open-looking
 * runs while a later response is streaming.
 */
fun projectRunActivity(
    messages: List<UiMessage>,
    isActiveRunStreaming: Boolean,
): RunActivityProjection? {
    if (messages.isEmpty()) return null

    val toolCalls = messages.flatMap { it.toolCalls.orEmpty() }
    val active = messages.hasActiveWork(isActiveRunStreaming)

    return RunActivityProjection(
        state = messages.activityState(active),
        durationMs = if (active) null else messages.runDurationMs(toolCalls.mapNotNull { it.executionTimeMs }),
        toolCount = toolCalls.size,
        failureCount = messages.failureCount(),
    )
}

/**
 * Returns whether [renderItem] owns the currently streaming message.
 *
 * Keeping this decision beside the projection prevents a host from applying
 * conversation-wide activity to every historical run.
 */
fun isActiveStreamingRenderItem(
    renderItem: ChatRenderItem,
    conversationIsStreaming: Boolean,
    newestMessageId: String?,
): Boolean =
    conversationIsStreaming &&
        newestMessageId != null &&
        renderItem.containsMessageId(newestMessageId)

private fun List<UiMessage>.hasActiveWork(isActiveRunStreaming: Boolean): Boolean {
    if (any { it.isPending }) return true
    return isActiveRunStreaming
}

private fun List<UiMessage>.activityState(active: Boolean): RunActivityState = when {
    active -> RunActivityState.Working
    any { it.isReasoning } -> RunActivityState.Thought
    else -> RunActivityState.Worked
}

private fun List<UiMessage>.failureCount(): Int {
    val failedTools = sumOf { message ->
        message.toolCalls.orEmpty().count { ToolReturnStatus.isError(it.status) }
    }
    val failedMessagesWithoutFailedTools = count { message ->
        message.isError && message.toolCalls.orEmpty().none { ToolReturnStatus.isError(it.status) }
    }
    return failedTools + failedMessagesWithoutFailedTools
}

/**
 * How long the run worked: the turn's latency, else its first-to-last span, less the time it sat
 * waiting on a background command (see [backgroundCommandWaitMs]), so a run that spans a command's
 * report sums the working spans either side of it. Without timestamps, the sum of its tools' times.
 */
private fun List<UiMessage>.runDurationMs(toolDurations: List<Long>): Long? {
    val waited = backgroundCommandWaitMs()
    turnLatencyMs()?.minus(waited)?.takeIf { it >= 0L }?.let { return it }

    timestampDurationMs()?.minus(waited)?.takeIf { it > 0L }?.let { return it }

    return toolDurations
        .takeIf { it.isNotEmpty() }
        ?.fold(0L) { total, duration -> total + duration.coerceAtLeast(0L) }
}

private fun List<UiMessage>.turnLatencyMs(): Long? = asReversed()
    .firstOrNull { it.role == "assistant" && !it.isReasoning && it.latencyMs != null }
    ?.latencyMs
    ?.takeIf { it >= 0L }

/**
 * The idle time before each background command's report in the run: from when the work before the
 * report last finished (its message's time plus its longest tool's execution) to the report. A report
 * or neighbour without a timestamp counts no wait.
 */
private fun List<UiMessage>.backgroundCommandWaitMs(): Long = indices.sumOf { commandWaitBefore(it) }

private fun List<UiMessage>.commandWaitBefore(index: Int): Long {
    if (index == 0 || this[index].subagentNotification?.isCommand() != true) return 0L
    val reportedAt = parseTimestampEpochMillis(this[index].timestamp) ?: return 0L
    val workedUntil = subList(0, index).asReversed().firstNotNullOfOrNull { it.finishedAtEpochMillis() } ?: return 0L
    return (reportedAt - workedUntil).coerceAtLeast(0L)
}

/** When a message's work finished: its time, plus its longest tool call's execution. */
private fun UiMessage.finishedAtEpochMillis(): Long? {
    val at = parseTimestampEpochMillis(timestamp) ?: return null
    val longestTool = toolCalls.orEmpty().maxOfOrNull { it.executionTimeMs?.coerceAtLeast(0L) ?: 0L } ?: 0L
    return at + longestTool
}

private fun List<UiMessage>.timestampDurationMs(): Long? {
    val first = firstNotNullOfOrNull { parseTimestampEpochMillis(it.timestamp) } ?: return null
    val last = asReversed().firstNotNullOfOrNull { parseTimestampEpochMillis(it.timestamp) } ?: return null
    return (last - first).takeIf { it > 0L }
}

/**
 * Parses a wire timestamp without introducing a JVM-only date/time dependency.
 *
 * Hosts can format the returned epoch value using their locale and time-zone
 * APIs while sharing validation and ISO-8601 parsing semantics.
 */
fun parseTimestampEpochMillis(timestamp: String): Long? =
    runCatching { Instant.parse(timestamp).toEpochMilliseconds() }.getOrNull()
