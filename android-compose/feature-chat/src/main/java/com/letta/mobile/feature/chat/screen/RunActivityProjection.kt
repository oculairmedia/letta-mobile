package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.model.ToolReturnStatus
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import java.time.Instant

internal enum class RunActivityState {
    Working,
    Thought,
    Worked,
}

/**
 * Presentation-only summary derived from the already ordered [UiMessage]
 * timeline. It deliberately carries no transport identity and never rewrites,
 * merges, or reorders canonical messages.
 */
internal data class RunActivityProjection(
    val state: RunActivityState,
    val durationMs: Long?,
    val toolCount: Int,
    val failureCount: Int,
) {
    val isActive: Boolean
        get() = state == RunActivityState.Working
}

internal fun projectRunActivity(
    messages: List<UiMessage>,
    isStreaming: Boolean,
): RunActivityProjection? {
    if (messages.isEmpty()) return null

    val toolCalls = messages.flatMap { it.toolCalls.orEmpty() }
    val active = messages.hasActiveWork(isStreaming)

    return RunActivityProjection(
        state = messages.activityState(active),
        durationMs = if (active) null else messages.runDurationMs(toolCalls.mapNotNull { it.executionTimeMs }),
        toolCount = toolCalls.size,
        failureCount = messages.failureCount(),
    )
}

private fun List<UiMessage>.hasActiveWork(isStreaming: Boolean): Boolean {
    if (any { it.isPending }) return true
    return isStreaming && last().hasOpenWork()
}

private fun UiMessage.hasOpenWork(): Boolean {
    if (isReasoning) return true
    if (approvalRequest != null) return true
    return toolCalls.orEmpty().any { it.isOpen() }
}

private fun UiToolCall.isOpen(): Boolean {
    if (result != null) return false
    if (ToolReturnStatus.isError(status)) return false
    return status != ToolReturnStatus.SUCCESS && status != "warning"
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
    val first = firstNotNullOfOrNull { it.timestamp.toEpochMillisOrNull() } ?: return null
    val last = asReversed().firstNotNullOfOrNull { it.timestamp.toEpochMillisOrNull() } ?: return null
    return (last - first).takeIf { it > 0L }
}

private fun String.toEpochMillisOrNull(): Long? =
    runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
