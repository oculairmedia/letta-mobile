package com.letta.mobile.data.transport

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage

/**
 * letta-mobile-qygvv.11: the ONE exact-event key shared by the transport ingest
 * seam ([IngestFrameDeduplicator]) and the coordinator safety net
 * (`BridgeEventDeduplicator`). Keeping a single definition is what lets the
 * coordinator's drop counter read zero once ingest has already collapsed the
 * duplicate: both layers agree on what "the same event" means.
 */
internal object TimelineEventKeys {
    /**
     * Coordinator key. `isReplay` is deliberately NOT part of it: a resume replay
     * re-delivers the turn_started the live connection already delivered;
     * including the flag guaranteed the two could never collide, which defeated
     * the only thing this key exists to do. (conversation, turn, run) identifies
     * the turn regardless of how it reached us.
     */
    fun key(event: WsTimelineEvent, fallbackConversationId: String?): String? = when (event) {
        is WsTimelineEvent.TurnStarted -> with(event) { "started|$conversationId|$turnId|$runId" }
        is WsTimelineEvent.MessageDelta -> messageKey(event, fallbackConversationId)
        is WsTimelineEvent.StopReason -> with(event) { "stop|$turnId|$runId|$stopReason" }
        is WsTimelineEvent.UsageStatistics -> with(event) {
            "usage|$turnId|$runId|$promptTokens|$completionTokens|$totalTokens"
        }
        is WsTimelineEvent.TurnDone -> with(event) { "done|$turnId|$runId|$status|$lossy|$dropCount" }
        is WsTimelineEvent.Error -> with(event) {
            "error|${conversationId.orEmpty()}|${turnId.orEmpty()}|${runId.orEmpty()}|$code|$message"
        }
        is WsTimelineEvent.UserActionOutcome -> with(event) {
            "action|$frameId|${actionId.orEmpty()}|$outcome|${reason.orEmpty()}"
        }
        else -> null
    }

    /**
     * Ingest key: the coordinator [key] with no coordinator-local fallback, made
     * strictly narrower for tool calls. The coordinator key names a tool call by
     * id+name only, but other subscribers (e.g. argument-streaming renderers)
     * still need a same-id frame whose ARGUMENTS changed, so at ingest the
     * arguments are part of the identity. Ingest therefore only drops frames the
     * coordinator would drop anyway — never one it would have let through.
     */
    fun ingestKey(event: WsTimelineEvent): String? {
        val base = key(event, fallbackConversationId = null) ?: return null
        val message = (event as? WsTimelineEvent.MessageDelta)?.message as? ToolCallMessage ?: return base
        return base + "|" + message.effectiveToolCalls.joinToString(separator = "|") { it.arguments.orEmpty() }
    }

    private fun messageKey(event: WsTimelineEvent.MessageDelta, fallbackConversationId: String?): String {
        val owner = event.conversationId ?: fallbackConversationId.orEmpty()
        val message = event.message
        return "message|$owner|${message.id}|${message.messageType}|${message.runId.orEmpty()}|${message.contentForDedupe()}"
    }

    private fun LettaMessage.contentForDedupe(): String = when (this) {
        is AssistantMessage -> content
        is UserMessage -> content
        is SystemMessage -> content
        is ReasoningMessage -> reasoning
        is ToolCallMessage -> effectiveToolCalls.joinToString(separator = "|") { it.effectiveId + ":" + (it.name ?: "") }
        is ToolReturnMessage -> toolCallId.orEmpty() + ":" + toolReturn.funcResponse.orEmpty()
        else -> date.orEmpty() + ":" + seqId.toString()
    }
}
