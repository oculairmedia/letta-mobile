package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ToolCall
import java.time.Instant

/** Wire-shape independent Client Mode stream events accepted by the timeline reducer. */
enum class ClientModeStreamEvent { ASSISTANT, REASONING, TOOL_CALL, TOOL_RESULT }

/**
 * Core representation of a Client Mode gateway chunk. The :app module adapts
 * BotStreamChunk into this type so timeline folding stays in :core without a
 * dependency on :bot.
 */
data class ClientModeStreamChunk(
    val event: ClientModeStreamEvent? = null,
    val text: String? = null,
    val uuid: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val isError: Boolean = false,
    val done: Boolean = false,
)

data class ClientModeStreamReduction(
    val timeline: Timeline,
    val localId: String?,
    val appended: Boolean,
)

/** Fold a Client Mode stream chunk into timeline Local events. */
fun Timeline.reduceClientModeStreamChunk(
    chunk: ClientModeStreamChunk,
    assistantMessageId: String,
    sentAt: Instant = Instant.now(),
): ClientModeStreamReduction {
    return when (chunk.event) {
        ClientModeStreamEvent.REASONING -> reduceClientModeReasoningChunk(chunk, assistantMessageId, sentAt)
        ClientModeStreamEvent.TOOL_CALL,
        ClientModeStreamEvent.TOOL_RESULT -> reduceClientModeToolChunk(chunk, sentAt)
        ClientModeStreamEvent.ASSISTANT,
        null -> reduceClientModeAssistantChunk(chunk, assistantMessageId, sentAt)
    }
}

private fun Timeline.reduceClientModeReasoningChunk(
    chunk: ClientModeStreamChunk,
    assistantMessageId: String,
    sentAt: Instant,
): ClientModeStreamReduction {
    val localId = "cm-reason-${chunk.uuid ?: assistantMessageId}"
    val delta = chunk.text.orEmpty()
    return reduceClientModeLocal(localId) {
        upsertClientModeLocal(
            otid = localId,
            build = {
                TimelineEvent.Local(
                    position = 0.0,
                    otid = localId,
                    content = "",
                    role = Role.ASSISTANT,
                    sentAt = sentAt,
                    deliveryState = DeliveryState.SENT,
                    source = MessageSource.CLIENT_MODE_HARNESS,
                    messageType = TimelineMessageType.REASONING,
                    reasoningContent = delta,
                )
            },
            transform = { existing ->
                val merged = if (delta.isEmpty()) existing.reasoningContent.orEmpty()
                    else existing.reasoningContent.orEmpty() + delta
                existing.copy(
                    reasoningContent = merged,
                    messageType = TimelineMessageType.REASONING,
                    sentAt = sentAt,
                )
            },
        )
    }
}

private fun Timeline.reduceClientModeAssistantChunk(
    chunk: ClientModeStreamChunk,
    assistantMessageId: String,
    sentAt: Instant,
): ClientModeStreamReduction {
    val delta = chunk.text?.takeIf { it.isNotEmpty() }
        ?: return ClientModeStreamReduction(this, localId = null, appended = false)
    val localId = clientModeAssistantLocalId(assistantMessageId, delta)
    return reduceClientModeLocal(localId) {
        upsertClientModeLocal(
            otid = localId,
            build = {
                TimelineEvent.Local(
                    position = 0.0,
                    otid = localId,
                    content = delta,
                    role = Role.ASSISTANT,
                    sentAt = sentAt,
                    deliveryState = DeliveryState.SENT,
                    source = MessageSource.CLIENT_MODE_HARNESS,
                    messageType = TimelineMessageType.ASSISTANT,
                )
            },
            transform = { existing ->
                val merged = if (chunk.done) {
                    when {
                        delta == existing.content -> existing.content
                        delta.startsWith(existing.content) -> delta
                        existing.content.startsWith(delta) -> existing.content
                        else -> existing.content + delta
                    }
                } else {
                    existing.content + delta
                }
                existing.copy(
                    content = merged,
                    messageType = TimelineMessageType.ASSISTANT,
                    sentAt = sentAt,
                )
            },
        )
    }
}

private fun Timeline.clientModeAssistantLocalId(
    assistantMessageId: String,
    delta: String,
): String {
    val baseId = "cm-assist-$assistantMessageId"
    val base = findByOtid(baseId)
    val latestToolPosition = events
        .asSequence()
        .filterIsInstance<TimelineEvent.Local>()
        .filter {
            it.source == MessageSource.CLIENT_MODE_HARNESS &&
                it.messageType == TimelineMessageType.TOOL_CALL
        }
        .maxOfOrNull { it.position }
        ?: return baseId

    if (base == null || base.position > latestToolPosition) return baseId

    // Keep punctuation-only continuation deltas (e.g. "...") attached to the
    // pre-tool assistant bubble. Full post-tool prose gets a separate segment
    // so final SSE fuzzy-collapse can absorb it without mutating the preamble.
    if (delta.isAssistantContinuationPunctuation()) return baseId

    val segment = events
        .asSequence()
        .filterIsInstance<TimelineEvent.Local>()
        .filter {
            it.source == MessageSource.CLIENT_MODE_HARNESS &&
                it.messageType == TimelineMessageType.TOOL_CALL &&
                it.position > base.position
        }
        .count()
        .coerceAtLeast(1)
    return "$baseId-after-tool-$segment"
}

private fun String.isAssistantContinuationPunctuation(): Boolean {
    val first = firstOrNull { !it.isWhitespace() } ?: return false
    return first in setOf('.', ',', '!', '?', ':', ';', ')', ']', '}')
}

private fun Timeline.reduceClientModeToolChunk(
    chunk: ClientModeStreamChunk,
    sentAt: Instant,
): ClientModeStreamReduction {
    val incomingToolCalls = chunk.effectiveToolCalls()
    val rawToolCallId = chunk.toolFrameId(incomingToolCalls)
        ?: return ClientModeStreamReduction(this, localId = null, appended = false)
    val isResult = chunk.event == ClientModeStreamEvent.TOOL_RESULT
    val localId = if (isResult) {
        findClientModeToolLocalIdForCall(rawToolCallId) ?: "cm-tool-$rawToolCallId"
    } else {
        "cm-tool-$rawToolCallId"
    }
    val callIds = incomingToolCalls.effectiveIds() + rawToolCallId
    val pendingResultLocals = if (isResult) {
        emptyList()
    } else {
        findPendingClientModeToolResultLocals(
            callIds = callIds,
            targetLocalId = localId,
        )
    }
    val pendingResultLocalIds = pendingResultLocals.map { it.otid }.toSet()
    val pendingResultByCallId = pendingResultLocals.flatMap { it.toolReturnContentByCallId.entries }
        .associate { it.key to it.value }
    val pendingResultErrorByCallId = pendingResultLocals.flatMap { it.toolReturnIsErrorByCallId.entries }
        .associate { it.key to it.value }
    val pendingCompletedAtByCallId = pendingResultLocals.flatMap { it.toolCompletedAtByCallId.entries }
        .associate { it.key to it.value }
    val pendingStartedAtByCallId = pendingResultLocals.flatMap { it.toolStartedAtByCallId.entries }
        .associate { it.key to it.value }
    val pendingBatchIdByCallId = pendingResultLocals.flatMap { it.toolBatchIdByCallId.entries }
        .associate { it.key to it.value }

    val resultText = if (isResult) chunk.text else null
    val resultIsError = isResult && chunk.isError
    val resultByCallId = if (resultText != null) mapOf(rawToolCallId to resultText) else emptyMap()
    val resultErrorByCallId = if (isResult) mapOf(rawToolCallId to resultIsError) else emptyMap()
    val completedAtByCallId = if (isResult) mapOf(rawToolCallId to sentAt) else emptyMap()
    val startedAtByCallId = if (chunk.event == ClientModeStreamEvent.TOOL_CALL) {
        buildMap {
            put(rawToolCallId, sentAt)
            incomingToolCalls.forEach { call ->
                call.effectiveId.takeIf { it.isNotBlank() }?.let { put(it, sentAt) }
            }
        }
    } else {
        emptyMap()
    }
    val batchIdByCallId = if (chunk.event == ClientModeStreamEvent.TOOL_CALL) {
        buildMap {
            put(rawToolCallId, rawToolCallId)
            incomingToolCalls.forEach { call ->
                call.effectiveId.takeIf { it.isNotBlank() }?.let { put(it, rawToolCallId) }
            }
        }
    } else {
        emptyMap()
    }
    val mergedInitialResultByCallId = pendingResultByCallId + resultByCallId
    val mergedInitialErrorByCallId = pendingResultErrorByCallId + resultErrorByCallId
    val mergedInitialCompletedAtByCallId = pendingCompletedAtByCallId + completedAtByCallId
    val mergedInitialStartedAtByCallId = mergePreservingExisting(
        existing = pendingStartedAtByCallId,
        incoming = startedAtByCallId,
    )
    val mergedInitialBatchIdByCallId = pendingBatchIdByCallId + batchIdByCallId
    val initialToolReturnContent = resultText ?: primaryToolReturnContent(
        calls = incomingToolCalls,
        returnsByCallId = mergedInitialResultByCallId,
    )
    val initialToolReturnIsError = primaryToolReturnIsError(
        calls = incomingToolCalls,
        returnsByCallId = mergedInitialResultByCallId,
        errorsByCallId = mergedInitialErrorByCallId,
    ) ?: resultIsError

    return reduceClientModeLocal(localId) {
        upsertClientModeLocal(
            otid = localId,
            build = {
                TimelineEvent.Local(
                    position = 0.0,
                    otid = localId,
                    content = "",
                    role = Role.ASSISTANT,
                    sentAt = sentAt,
                    deliveryState = DeliveryState.SENT,
                    source = MessageSource.CLIENT_MODE_HARNESS,
                    messageType = TimelineMessageType.TOOL_CALL,
                    toolCalls = incomingToolCalls,
                    toolReturnContent = initialToolReturnContent,
                    toolReturnIsError = initialToolReturnIsError,
                    toolReturnContentByCallId = mergedInitialResultByCallId,
                    toolReturnIsErrorByCallId = mergedInitialErrorByCallId,
                    toolStartedAtByCallId = mergedInitialStartedAtByCallId,
                    toolCompletedAtByCallId = mergedInitialCompletedAtByCallId,
                    toolBatchIdByCallId = mergedInitialBatchIdByCallId,
                )
            },
            transform = { existing ->
                val mergedToolCalls = mergeToolCallSnapshots(existing.toolCalls, incomingToolCalls)
                val mergedResultByCallId = existing.toolReturnContentByCallId + pendingResultByCallId + resultByCallId
                val mergedErrorByCallId = existing.toolReturnIsErrorByCallId + pendingResultErrorByCallId + resultErrorByCallId
                val mergedCompletedAtByCallId = existing.toolCompletedAtByCallId + pendingCompletedAtByCallId + completedAtByCallId
                val mergedStartedAtByCallId = mergePreservingExisting(
                    existing = mergePreservingExisting(existing.toolStartedAtByCallId, pendingStartedAtByCallId),
                    incoming = startedAtByCallId,
                )
                val mergedBatchIdByCallId = existing.toolBatchIdByCallId + pendingBatchIdByCallId + batchIdByCallId
                val nextToolReturnContent = resultText
                    ?: existing.toolReturnContent
                    ?: primaryToolReturnContent(mergedToolCalls, mergedResultByCallId)
                val nextToolReturnIsError = if (isResult) {
                    resultIsError
                } else {
                    primaryToolReturnIsError(mergedToolCalls, mergedResultByCallId, mergedErrorByCallId)
                        ?: existing.toolReturnIsError
                }
                existing.copy(
                    messageType = TimelineMessageType.TOOL_CALL,
                    toolCalls = mergedToolCalls,
                    toolReturnContent = nextToolReturnContent,
                    toolReturnIsError = nextToolReturnIsError,
                    toolReturnContentByCallId = mergedResultByCallId,
                    toolReturnIsErrorByCallId = mergedErrorByCallId,
                    toolStartedAtByCallId = mergedStartedAtByCallId,
                    toolCompletedAtByCallId = mergedCompletedAtByCallId,
                    toolBatchIdByCallId = mergedBatchIdByCallId,
                    sentAt = sentAt,
                )
            },
        )
    }.let { reduction ->
        if (pendingResultLocalIds.isEmpty()) {
            reduction
        } else {
            reduction.copy(timeline = reduction.timeline.removeClientModeToolLocals(pendingResultLocalIds))
        }
    }
}

private inline fun Timeline.reduceClientModeLocal(
    localId: String,
    reduce: Timeline.() -> Timeline,
): ClientModeStreamReduction {
    val existed = findByOtid(localId) is TimelineEvent.Local
    val next = reduce()
    val appended = !existed && next.findByOtid(localId) is TimelineEvent.Local
    return ClientModeStreamReduction(next, localId, appended)
}

private fun Timeline.findClientModeToolLocalIdForCall(callId: String): String? = events
    .asSequence()
    .filterIsInstance<TimelineEvent.Local>()
    .firstOrNull { event ->
        event.source == MessageSource.CLIENT_MODE_HARNESS &&
            event.messageType == TimelineMessageType.TOOL_CALL &&
            (
                event.otid == "cm-tool-$callId" ||
                    event.toolReturnContentByCallId.containsKey(callId) ||
                    event.toolBatchIdByCallId.containsKey(callId) ||
                    event.toolCalls.any { it.effectiveId == callId }
            )
    }
    ?.otid

private fun Timeline.findPendingClientModeToolResultLocals(
    callIds: Set<String>,
    targetLocalId: String,
): List<TimelineEvent.Local> {
    if (callIds.isEmpty()) return emptyList()
    return events
        .asSequence()
        .filterIsInstance<TimelineEvent.Local>()
        .filter { event ->
            event.otid != targetLocalId &&
                event.source == MessageSource.CLIENT_MODE_HARNESS &&
                event.messageType == TimelineMessageType.TOOL_CALL &&
                callIds.any { callId ->
                    event.otid == "cm-tool-$callId" ||
                        event.toolReturnContentByCallId.containsKey(callId) ||
                        event.toolCalls.any { it.effectiveId == callId }
                }
        }
        .toList()
}

private fun Timeline.removeClientModeToolLocals(otids: Set<String>): Timeline {
    if (otids.isEmpty()) return this
    return copy(events = events.filterNot { event ->
        event is TimelineEvent.Local &&
            event.source == MessageSource.CLIENT_MODE_HARNESS &&
            event.messageType == TimelineMessageType.TOOL_CALL &&
            event.otid in otids
    })
}

private fun ClientModeStreamChunk.effectiveToolCalls(): List<ToolCall> {
    val batchedCalls = toolCalls.filter { call ->
        call.effectiveId.isNotBlank() ||
            !call.name.isNullOrBlank() ||
            !call.arguments.isNullOrBlank()
    }
    if (batchedCalls.isNotEmpty()) return batchedCalls
    if (toolCallId == null && uuid == null && toolName == null && toolInput == null) return emptyList()
    return listOf(
        ToolCall(
            id = toolCallId ?: uuid,
            name = toolName ?: "tool",
            arguments = toolInput.orEmpty(),
        )
    )
}

private fun ClientModeStreamChunk.toolFrameId(calls: List<ToolCall>): String? {
    return toolCallId
        ?: uuid
        ?: calls.firstNotNullOfOrNull { call -> call.effectiveId.takeIf { it.isNotBlank() } }
        ?: calls.takeIf { it.isNotEmpty() }?.hashCode()?.toString()
}

private fun List<ToolCall>.effectiveIds(): Set<String> = mapNotNullTo(mutableSetOf()) { call ->
    call.effectiveId.takeIf { it.isNotBlank() }
}

private fun primaryToolReturnContent(
    calls: List<ToolCall>,
    returnsByCallId: Map<String, String>,
): String? = calls
    .asSequence()
    .mapNotNull { call -> returnsByCallId[call.effectiveId] }
    .firstOrNull()
    ?: returnsByCallId.values.firstOrNull()

private fun primaryToolReturnIsError(
    calls: List<ToolCall>,
    returnsByCallId: Map<String, String>,
    errorsByCallId: Map<String, Boolean>,
): Boolean? {
    calls.forEach { call ->
        val callId = call.effectiveId
        if (returnsByCallId.containsKey(callId)) return errorsByCallId[callId] ?: false
    }
    val firstReturnedId = returnsByCallId.keys.firstOrNull() ?: return null
    return errorsByCallId[firstReturnedId] ?: false
}

private fun <T> mergePreservingExisting(
    existing: Map<String, T>,
    incoming: Map<String, T>,
): Map<String, T> = incoming + existing

private fun mergeToolCallSnapshots(
    existing: List<ToolCall>,
    incoming: List<ToolCall>,
): List<ToolCall> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming

    val merged = existing.toMutableList()
    incoming.forEach { incomingCall ->
        val incomingId = incomingCall.effectiveId.takeIf { it.isNotBlank() }
        val index = if (incomingId != null) {
            merged.indexOfFirst { it.effectiveId == incomingId }
        } else {
            -1
        }
        if (index >= 0) {
            merged[index] = merged[index].mergeWith(incomingCall)
        } else {
            merged.add(incomingCall)
        }
    }
    return merged
}

private fun ToolCall.mergeWith(incoming: ToolCall): ToolCall = ToolCall(
    id = incoming.id ?: id,
    toolCallId = incoming.toolCallId ?: toolCallId,
    name = incoming.name?.takeIf {
        it.isNotBlank() && (it != "tool" || name.isNullOrBlank() || name == "tool")
    } ?: name,
    arguments = incoming.arguments?.takeIf { it.isNotBlank() } ?: arguments,
    type = incoming.type,
)
