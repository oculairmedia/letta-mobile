package com.letta.mobile.data.chat.projection

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.ToolReturnStatus
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.UiToolResultTruncation

/**
 * State of a tool call lifecycle in the timeline.
 */
enum class ToolTimelineState {
    AwaitingApproval,
    Running,
    Succeeded,
    Warning,
    Failed,
    Rejected,
}

/**
 * Immutable presentation model representing a single tool call within a timeline group.
 */
@Immutable
data class ToolTimelineCall(
    val key: String,
    val toolCallId: String?,
    val name: String,
    val arguments: String,
    val result: String?,
    val state: ToolTimelineState,
    val summary: String,
    val executionTimeMs: Long? = null,
    val approvalDecision: UiToolApprovalDecision? = null,
    val generatedImageAttachments: List<UiImageAttachment> = emptyList(),
    val subagentDispatch: UiSubagentDispatch? = null,
    val resultTruncation: UiToolResultTruncation? = null,
) {
    val isTerminal: Boolean
        get() = state == ToolTimelineState.Succeeded ||
            state == ToolTimelineState.Failed ||
            state == ToolTimelineState.Rejected
}

/**
 * Immutable presentation model representing a group of tool calls (e.g. from a message or run).
 */
@Immutable
data class ToolTimelineGroup(
    val key: String,
    val calls: List<ToolTimelineCall>,
    val state: ToolTimelineState,
    val messageId: String? = null,
    val runId: String? = null,
    val durationMs: Long? = null,
) {
    val toolCount: Int
        get() = calls.size

    val failureCount: Int
        get() = calls.count { it.state == ToolTimelineState.Failed }

    val isActive: Boolean
        get() = state == ToolTimelineState.Running || state == ToolTimelineState.AwaitingApproval
}

/**
 * Classifies the [ToolTimelineState] for a given [UiToolCall].
 */
fun classifyToolCallState(
    toolCall: UiToolCall,
    messageApprovalRequest: UiApprovalRequest? = null,
): ToolTimelineState {
    if (toolCall.approvalDecision == UiToolApprovalDecision.Rejected) {
        return ToolTimelineState.Rejected
    }
    if (toolCall.isAwaitingApproval(messageApprovalRequest)) {
        return ToolTimelineState.AwaitingApproval
    }

    val status = toolCall.status
        ?: return toolCall.settledStateWithoutStatus()

    return status.toTerminalState()
    // An unrecognised status is NOT a failure: fall back to whether a result landed.
        ?: toolCall.settledStateWithoutStatus()
}

/**
 * A call is awaiting approval only while it has no decision, no status and no result, and the
 * owning request actually references it (by id, or by name when the call carries no id).
 */
private fun UiToolCall.isAwaitingApproval(request: UiApprovalRequest?): Boolean {
    if (request == null) return false
    if (approvalDecision != null || result != null || status != null) return false
    if (toolCallId == null) return true
    return request.toolCalls.any { it.toolCallId == toolCallId || it.name == name }
}

/** Maps a recognised status string to its terminal state, or null when unrecognised. */
private fun String.toTerminalState(): ToolTimelineState? = when {
    ToolReturnStatus.isError(this) || equals("failed", ignoreCase = true) -> ToolTimelineState.Failed
    equals("warning", ignoreCase = true) -> ToolTimelineState.Warning
    equals(ToolReturnStatus.SUCCESS, ignoreCase = true) || equals("completed", ignoreCase = true) ->
        ToolTimelineState.Succeeded
    else -> null
}

/** With no usable status, a call is Succeeded once a result exists and Running until then. */
private fun UiToolCall.settledStateWithoutStatus(): ToolTimelineState =
    if (result != null) ToolTimelineState.Succeeded else ToolTimelineState.Running

/**
 * Derives a human-readable safe summary for a tool call without risking platform exceptions.
 */
fun deriveToolCallSummary(name: String, arguments: String): String {
    val cleanName = name.ifBlank { "Tool" }
    val trimmed = arguments.trim()
    if (trimmed.isEmpty() || trimmed == "{}") return cleanName

    val primaryArg = extractPrimaryJsonArg(trimmed)
    if (primaryArg != null) {
        return "$cleanName($primaryArg)"
    }

    val singleLine = trimmed.replace(WHITESPACE_RUN, " ")
    val preview = if (singleLine.length > 50) singleLine.take(47) + "..." else singleLine
    return "$cleanName($preview)"
}

// Compiled once at class-init rather than per call: this projection re-runs on every
// stream frame over every visible tool call, so building these inside the loop would
// compile ten regexes per call per frame.
private val WHITESPACE_RUN = Regex("\\s+")

private val PRIMARY_ARG_PATTERNS: List<Regex> =
    listOf("path", "command", "cmd", "query", "q", "url", "file", "prompt", "name")
        .map { key -> Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"") }

private fun extractPrimaryJsonArg(rawJson: String): String? {
    for (pattern in PRIMARY_ARG_PATTERNS) {
        val match = pattern.find(rawJson)
        if (match != null) {
            return match.groupValues[1]
        }
    }
    return null
}

/**
 * Projects a [UiToolCall] into a [ToolTimelineCall]. Reuses [previousCall] if content is equal.
 */
fun projectToolTimelineCall(
    toolCall: UiToolCall,
    messageApprovalRequest: UiApprovalRequest? = null,
    keyOverride: String? = null,
    fallbackIndex: Int = 0,
    previousCall: ToolTimelineCall? = null,
): ToolTimelineCall {
    val rawId = toolCall.toolCallId
    val key = keyOverride ?: if (!rawId.isNullOrBlank()) {
        "call:$rawId"
    } else {
        "call::$fallbackIndex"
    }

    val state = classifyToolCallState(toolCall, messageApprovalRequest)
    val summary = deriveToolCallSummary(toolCall.name, toolCall.arguments)

    val fresh = ToolTimelineCall(
        key = key,
        toolCallId = rawId,
        name = toolCall.name,
        arguments = toolCall.arguments,
        result = toolCall.result,
        state = state,
        summary = summary,
        executionTimeMs = toolCall.executionTimeMs,
        approvalDecision = toolCall.approvalDecision,
        generatedImageAttachments = toolCall.generatedImageAttachments,
        subagentDispatch = toolCall.subagentDispatch,
        resultTruncation = toolCall.resultTruncation,
    )

    return when {
        previousCall === fresh -> fresh
        previousCall == fresh -> previousCall
        else -> fresh
    }
}

/**
 * Projects a [UiMessage] into a [ToolTimelineGroup] if it contains tool calls.
 * Reuses unchanged [previousGroup] or unchanged individual calls inside the group.
 */
fun projectToolTimelineGroup(
    message: UiMessage,
    groupKeyOverride: String? = null,
    fallbackIndex: Int = 0,
    previousGroup: ToolTimelineGroup? = null,
): ToolTimelineGroup? {
    val callsSource = message.toolCalls
    if (callsSource.isNullOrEmpty()) return null

    val groupKey = groupKeyOverride ?: if (message.id.isNotBlank()) {
        "group:${message.id}"
    } else {
        "group::$fallbackIndex"
    }

    val previousCallsByKey = previousGroup?.calls?.associateBy { it.key }.orEmpty()

    val seenCallKeys = HashSet<String>(callsSource.size)
    val projectedCalls = ArrayList<ToolTimelineCall>(callsSource.size)

    for ((index, toolCall) in callsSource.withIndex()) {
        val rawCallKey = if (!toolCall.toolCallId.isNullOrBlank()) {
            "call:${toolCall.toolCallId}"
        } else {
            "call::${message.id}-$index"
        }

        var candidateKey = rawCallKey
        var dupIndex = 1
        while (!seenCallKeys.add(candidateKey)) {
            candidateKey = "$rawCallKey#${dupIndex++}"
        }

        val prevCall = previousCallsByKey[candidateKey]
        val call = projectToolTimelineCall(
            toolCall = toolCall,
            messageApprovalRequest = message.approvalRequest,
            keyOverride = candidateKey,
            fallbackIndex = index,
            previousCall = prevCall,
        )
        projectedCalls.add(call)
    }

    val overallState = aggregateGroupState(projectedCalls)
    val totalDuration = projectedCalls.mapNotNull { it.executionTimeMs }.takeIf { it.isNotEmpty() }?.sum()

    val freshGroup = ToolTimelineGroup(
        key = groupKey,
        calls = projectedCalls,
        state = overallState,
        messageId = message.id,
        runId = message.runId,
        durationMs = totalDuration,
    )

    return when {
        previousGroup === freshGroup -> freshGroup
        previousGroup == freshGroup -> previousGroup
        else -> freshGroup
    }
}

private fun aggregateGroupState(calls: List<ToolTimelineCall>): ToolTimelineState = when {
    calls.isEmpty() -> ToolTimelineState.Running
    calls.any { it.state == ToolTimelineState.Failed } -> ToolTimelineState.Failed
    calls.any { it.state == ToolTimelineState.Rejected } -> ToolTimelineState.Rejected
    calls.any { it.state == ToolTimelineState.AwaitingApproval } -> ToolTimelineState.AwaitingApproval
    calls.any { it.state == ToolTimelineState.Warning } -> ToolTimelineState.Warning
    calls.any { it.state == ToolTimelineState.Running } -> ToolTimelineState.Running
    else -> ToolTimelineState.Succeeded
}

/**
 * Projects a list of [UiMessage]s into a list of [ToolTimelineGroup]s,
 * deduplicating group keys and preserving referential identity against [previousGroups].
 */
fun projectToolTimelineGroups(
    messages: List<UiMessage>,
    previousGroups: List<ToolTimelineGroup> = emptyList(),
): List<ToolTimelineGroup> {
    if (messages.isEmpty()) return emptyList()

    val previousGroupByKey = previousGroups.associateBy { it.key }
    val seenGroupKeys = HashSet<String>()
    val result = ArrayList<ToolTimelineGroup>()
    var reusedAll = previousGroups.isNotEmpty() && previousGroups.size == messages.count { !it.toolCalls.isNullOrEmpty() }

    var groupIndex = 0
    for (message in messages) {
        if (message.toolCalls.isNullOrEmpty()) continue

        val baseKey = if (message.id.isNotBlank()) "group:${message.id}" else "group::$groupIndex"
        var candidateKey = baseKey
        var dupCount = 1
        while (!seenGroupKeys.add(candidateKey)) {
            candidateKey = "$baseKey#${dupCount++}"
        }

        val prevGroup = previousGroupByKey[candidateKey]
        val group = projectToolTimelineGroup(
            message = message,
            groupKeyOverride = candidateKey,
            fallbackIndex = groupIndex,
            previousGroup = prevGroup,
        )

        if (group != null) {
            if (prevGroup !== group) {
                reusedAll = false
            }
            result.add(group)
            groupIndex++
        }
    }

    if (reusedAll && result.size == previousGroups.size) {
        return previousGroups
    }

    return result
}

/**
 * Stateful projector that holds cached timeline groups across updates to enforce referential identity.
 */
class ToolTimelineProjector {
    private var cachedGroups: List<ToolTimelineGroup> = emptyList()

    fun project(messages: List<UiMessage>): List<ToolTimelineGroup> {
        val next = projectToolTimelineGroups(messages, cachedGroups)
        cachedGroups = next
        return next
    }

    fun clear() {
        cachedGroups = emptyList()
    }
}
