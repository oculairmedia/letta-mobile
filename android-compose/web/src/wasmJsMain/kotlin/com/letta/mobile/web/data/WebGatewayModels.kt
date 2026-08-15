package com.letta.mobile.web.data

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.JsonArray
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
)

internal fun resolveWebSocketUrl(serverUrl: String): String {
    val trimmed = serverUrl.trim().removeSuffix("/")
    return when {
        trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
        trimmed.startsWith("http://") -> "ws://${trimmed.removePrefix("http://")}/ws"
        trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}/ws"
        else -> error("Server URL must use iroh, http, https, ws, or wss")
    }
}

internal fun decodeWebAgents(elements: JsonArray): List<AgentItemState> = elements.map { element ->
    val agent = element.jsonObject
    AgentItemState(
        id = agent["id"]?.jsonPrimitive?.content ?: error("Agent response is missing id"),
        name = agent["name"]?.jsonPrimitive?.content ?: "Agent",
        description = agent["description"]?.jsonPrimitive?.content,
        model = agent["model"]?.jsonPrimitive?.content ?: "Unknown model",
        isOnline = true,
    )
}

internal fun decodeAssistantDelta(payload: RuntimeEventPayload.RemoteStreamFrame): String? {
    if (payload.messageType != "assistant_message") return null
    val raw = AppServerProtocol.json.parseToJsonElement(payload.body).jsonObject
    val delta = raw["delta"]?.jsonObject ?: raw
    return runCatching {
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

internal fun LettaMessage.toWebEntry(): WebChatEntry? = when (this) {
    is UserMessage -> WebChatEntry(id, "You", content, true)
    is AssistantMessage -> WebChatEntry(id, "Agent", content, false)
    else -> null
}
