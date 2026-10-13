package com.letta.mobile.web.data

import com.letta.mobile.data.model.isIrohBackendUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Auth capability the browser Iroh binding honours by reassembling `frame_part` sequences. */
internal const val FRAME_PART_CAPABILITY = "frame_part"

/**
 * The transport capabilities to advertise on auth for [serverUrl] (letta-mobile-yhq16). Over Iroh a
 * reply larger than 1 MiB, such as a full agent list, only arrives when the server may split it into
 * parts, which the browser binding reassembles. The WebSocket transport has no such framing.
 */
internal fun authCapabilitiesFor(serverUrl: String): List<String>? =
    if (isIrohBackendUrl(serverUrl)) listOf(FRAME_PART_CAPABILITY) else null

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
