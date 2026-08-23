package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Bounded exact-event deduplication for fanout from shared bridge collectors. */
internal class BridgeEventDeduplicator {
    private val eventLock = SynchronizedObject()
    private val eventKeys = ArrayDeque<String>()
    private val eventKeySet = mutableSetOf<String>()

    fun isDuplicate(event: WsTimelineEvent, fallbackConversationId: String?): Boolean {
        val key = event.key(fallbackConversationId) ?: return false
        val duplicate = if (event is WsTimelineEvent.MessageDelta) {
            synchronized(sharedMessageEventLock) {
                rememberBounded(key, sharedMessageEventKeys, sharedMessageEventKeySet)
            }
        } else {
            synchronized(eventLock) {
                rememberBounded(key, eventKeys, eventKeySet)
            }
        }
        if (duplicate) {
            Telemetry.event(
                "AdminChatVM", "ws.event.exactDuplicateDropped",
                "eventType" to (event::class.simpleName ?: ""),
                "keyHash" to key.hashCode().toString(),
            )
        }
        return duplicate
    }

    private fun WsTimelineEvent.key(fallbackConversationId: String?): String? = when (this) {
        is WsTimelineEvent.TurnStarted -> "started|$conversationId|$turnId|$runId|$isReplay"
        is WsTimelineEvent.MessageDelta -> {
            val owner = conversationId ?: fallbackConversationId.orEmpty()
            "message|$owner|${message.id}|${message.messageType}|${message.runId.orEmpty()}|${message.contentForDedupe()}"
        }
        is WsTimelineEvent.StopReason -> "stop|$turnId|$runId|$stopReason"
        is WsTimelineEvent.UsageStatistics -> "usage|$turnId|$runId|$promptTokens|$completionTokens|$totalTokens"
        is WsTimelineEvent.TurnDone -> "done|$turnId|$runId|$status|$lossy|$dropCount"
        is WsTimelineEvent.Error -> "error|${conversationId.orEmpty()}|${turnId.orEmpty()}|${runId.orEmpty()}|$code|$message"
        is WsTimelineEvent.UserActionOutcome -> "action|$frameId|${actionId.orEmpty()}|$outcome|${reason.orEmpty()}"
        else -> null
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

    private fun rememberBounded(
        key: String,
        keys: ArrayDeque<String>,
        keySet: MutableSet<String>,
    ): Boolean {
        if (key in keySet) return true
        keySet += key
        keys.addLast(key)
        while (keys.size > MAX_SEEN_EVENTS) {
            keySet.remove(keys.removeFirst())
        }
        return false
    }

    private companion object {
        private const val MAX_SEEN_EVENTS = 512

        // Message frames are fanned out to multiple per-agent coordinators from
        // one bridge flow, so their exact-dedupe window remains process-wide.
        private val sharedMessageEventLock = SynchronizedObject()
        private val sharedMessageEventKeys = ArrayDeque<String>()
        private val sharedMessageEventKeySet = mutableSetOf<String>()
    }
}
