package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.ToolReturnStatus
import com.letta.mobile.data.model.UiMessage
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

private fun List<UiMessage>.runDurationMs(toolDurations: List<Long>): Long? {
    maxOfOrNull { it.latencyMs ?: -1L }
        ?.takeIf { it >= 0L }
        ?.let { return it }

    timestampDurationMs()?.let { return it }

    return toolDurations
        .takeIf { it.isNotEmpty() }
        ?.fold(0L) { total, duration -> total + duration.coerceAtLeast(0L) }
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
