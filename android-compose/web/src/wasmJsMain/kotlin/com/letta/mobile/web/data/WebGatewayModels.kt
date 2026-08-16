package com.letta.mobile.web.data

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface WebConnectionState {
    data object Unconfigured : WebConnectionState
    data object Connecting : WebConnectionState
    data class Connected(val transport: String) : WebConnectionState
    data class Failed(val message: String) : WebConnectionState
}

data class WebChatEntry(
    val id: String,
    val sender: String,
    val text: String,
    val isUser: Boolean,
    val attachments: List<MessageContentPart.Image> = emptyList(),
)

sealed interface WebConversationUpdate {
    data class Snapshot(val entries: List<WebChatEntry>) : WebConversationUpdate
    data class Upsert(val entry: WebChatEntry) : WebConversationUpdate
}

internal fun resolveWebSocketUrl(serverUrl: String): String {
    val trimmed = serverUrl.trim().removeSuffix("/")
    return when {
        trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> {
            val pathStart = trimmed.indexOf('/', startIndex = trimmed.indexOf("://") + 3)
            if (pathStart < 0) "$trimmed/ws" else trimmed
        }
        trimmed.startsWith("http://") -> "ws://${trimmed.removePrefix("http://")}/ws"
        trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}/ws"
        else -> error("Server URL must use iroh, http, https, ws, or wss")
    }
}

internal fun decodeWebAgents(elements: JsonArray): List<AgentItemState> = elements.map { element ->
    val agent = element.jsonObject
    AgentItemState(
        id = agent["id"]?.jsonPrimitive?.contentOrNull ?: error("Agent response is missing id"),
        name = agent["name"]?.jsonPrimitive?.contentOrNull ?: "Agent",
        description = agent["description"]?.jsonPrimitive?.contentOrNull,
        model = agent["model"]?.jsonPrimitive?.contentOrNull ?: "Unknown model",
        isOnline = true,
    )
}

internal fun decodeAssistantDelta(payload: RuntimeEventPayload.RemoteStreamFrame): String? {
    if (payload.messageType != "assistant_message") return null
    return runCatching {
        val raw = AppServerProtocol.json.parseToJsonElement(payload.body) as? JsonObject ?: return@runCatching null
        val delta = raw["delta"] as? JsonObject ?: raw
        AppServerProtocol.json
            .decodeFromJsonElement(LettaMessage.serializer(), delta)
            .let { it as? AssistantMessage }
            ?.content
    }.getOrNull()?.takeIf(String::isNotEmpty)
}

internal fun mergeAssistantText(existing: String, incoming: String): String = when {
    incoming.startsWith(existing) -> incoming
    existing.endsWith(incoming) -> existing
    else -> existing + incoming
}

internal fun decodeWebConversationUpdate(received: AppServerReceivedFrame): WebConversationUpdate? {
    val stream = received.frame as? AppServerInboundFrame.StreamDelta ?: return null
    val message = runCatching {
        AppServerProtocol.json.decodeFromJsonElement(LettaMessage.serializer(), stream.delta)
    }.getOrNull() ?: return null
    return message.toWebEntry()?.let(WebConversationUpdate::Upsert)
}

internal fun LettaMessage.toWebEntry(): WebChatEntry? = when (this) {
    is UserMessage -> WebChatEntry(id, "You", content, true, attachments)
    is AssistantMessage -> WebChatEntry(id, "Agent", content, false, attachments)
    else -> null
}
