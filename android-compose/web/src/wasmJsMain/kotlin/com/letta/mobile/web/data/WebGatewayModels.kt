package com.letta.mobile.web.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface WebConnectionState {
    data object Unconfigured : WebConnectionState
    data object Connecting : WebConnectionState
    data class Connected(val transport: String) : WebConnectionState
    data class Failed(val message: String) : WebConnectionState
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
