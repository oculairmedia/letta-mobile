package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

/** Stateless parsing, identity, and diagnostics support for the Iroh transport. */
internal object IrohTransportSupport {
    fun frameFlowContent(frame: ServerFrame): Triple<String, String, String>? = when (frame) {
        is ServerFrame.AssistantMessage -> Triple(frame.otid ?: frame.id, "assistant_message", frame.content)
        is ServerFrame.ReasoningMessage -> Triple(frame.id, "reasoning_message", frame.reasoning)
        else -> null
    }

    fun conversationIdFromMessageListPath(path: String): String? {
        val marker = "/v1/conversations/"
        val start = path.indexOf(marker)
        if (start < 0) return null
        return path.substring(start + marker.length).substringBefore('/').substringBefore('?').takeIf { it.isNotBlank() }
    }

    fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    fun observerTurnCommand(agentId: String, conversationId: String): TurnCommand = TurnCommand(
        backendId = BackendId("iroh-app-server"),
        runtimeId = RuntimeId("iroh-observer"),
        agentId = AgentId(agentId),
        conversationId = ConversationId(conversationId),
        input = TurnInput.UserMessage(localMessageId = "iroh-observer-$conversationId", text = ""),
    )

    fun frameMessageId(frame: ServerFrame): String? = when (frame) {
        is ServerFrame.AssistantMessage -> frame.id
        is ServerFrame.ReasoningMessage -> frame.id
        is ServerFrame.ToolCallMessage -> frame.id
        is ServerFrame.ToolReturnMessage -> frame.id
        is ServerFrame.UserMessage -> frame.id
        else -> null
    }

    fun frameConversationId(frame: ServerFrame): String? = when (frame) {
        is ServerFrame.AssistantMessage -> frame.conversationId
        is ServerFrame.ReasoningMessage -> frame.conversationId
        is ServerFrame.ToolCallMessage -> frame.conversationId
        is ServerFrame.ToolReturnMessage -> frame.conversationId
        is ServerFrame.UserMessage -> frame.conversationId
        else -> null
    }

    fun frameId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
    fun nowIso(): String = Instant.now().toString()

    fun otherActiveConversationsLabel(registry: IrohTurnRegistry, conversationId: String): String =
        registry.concurrentTurns(excludingConversationId = IrohConversationId(conversationId))
            .joinToString(",") { it.conversationId }
}
