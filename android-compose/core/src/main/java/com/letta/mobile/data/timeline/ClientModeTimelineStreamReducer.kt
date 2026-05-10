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
    val localId = "cm-assist-$assistantMessageId"
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
    val resultText = if (isResult) chunk.text else null
    val resultIsError = isResult && chunk.isError
    val resultByCallId = if (resultText != null) mapOf(rawToolCallId to resultText) else emptyMap()
    val resultErrorByCallId = if (isResult) mapOf(rawToolCallId to resultIsError) else emptyMap()
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
                    toolReturnContent = resultText,
                    toolReturnIsError = resultIsError,
                    toolReturnContentByCallId = resultByCallId,
                    toolReturnIsErrorByCallId = resultErrorByCallId,
                    toolStartedAtByCallId = startedAtByCallId,
                )
            },
            transform = { existing ->
                existing.copy(
                    messageType = TimelineMessageType.TOOL_CALL,
                    toolCalls = mergeToolCallSnapshots(existing.toolCalls, incomingToolCalls),
                    toolReturnContent = resultText ?: existing.toolReturnContent,
                    toolReturnIsError = if (isResult) resultIsError else existing.toolReturnIsError,
                    toolReturnContentByCallId = existing.toolReturnContentByCallId + resultByCallId,
                    toolReturnIsErrorByCallId = existing.toolReturnIsErrorByCallId + resultErrorByCallId,
                    toolStartedAtByCallId = existing.toolStartedAtByCallId + startedAtByCallId,
                    sentAt = sentAt,
                )
            },
        )
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
                    event.toolCalls.any { it.effectiveId == callId }
            )
    }
    ?.otid

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
