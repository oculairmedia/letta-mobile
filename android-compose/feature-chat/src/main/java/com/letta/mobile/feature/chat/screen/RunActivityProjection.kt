package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.model.ToolReturnStatus
import com.letta.mobile.data.model.UiMessage
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
    val failedToolCount = toolCalls.count { ToolReturnStatus.isError(it.status) }
    val failedMessageCount = messages.count { message ->
        message.isError && message.toolCalls.orEmpty().none { ToolReturnStatus.isError(it.status) }
    }
    val hasReasoning = messages.any { it.isReasoning }
    val latestMessage = messages.last()
    val latestWorkIsOpen = latestMessage.isReasoning ||
        latestMessage.toolCalls.orEmpty().any { toolCall ->
            toolCall.result == null &&
                !ToolReturnStatus.isError(toolCall.status) &&
                toolCall.status != ToolReturnStatus.SUCCESS &&
                toolCall.status != "warning"
        } ||
        latestMessage.approvalRequest != null
    val active = messages.any { it.isPending } || (isStreaming && latestWorkIsOpen)

    return RunActivityProjection(
        state = when {
            active -> RunActivityState.Working
            hasReasoning -> RunActivityState.Thought
            else -> RunActivityState.Worked
        },
        durationMs = if (active) null else messages.runDurationMs(toolCalls.mapNotNull { it.executionTimeMs }),
        toolCount = toolCalls.size,
        failureCount = failedToolCount + failedMessageCount,
    )
}

private fun List<UiMessage>.runDurationMs(toolDurations: List<Long>): Long? {
    maxOfOrNull { it.latencyMs ?: -1L }
        ?.takeIf { it >= 0L }
        ?.let { return it }

    val first = firstNotNullOfOrNull { it.timestamp.toEpochMillisOrNull() }
    val last = asReversed().firstNotNullOfOrNull { it.timestamp.toEpochMillisOrNull() }
    if (first != null && last != null && last > first) {
        return last - first
    }

    return toolDurations
        .takeIf { it.isNotEmpty() }
        ?.fold(0L) { total, duration -> total + duration.coerceAtLeast(0L) }
}

private fun String.toEpochMillisOrNull(): Long? =
    runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
