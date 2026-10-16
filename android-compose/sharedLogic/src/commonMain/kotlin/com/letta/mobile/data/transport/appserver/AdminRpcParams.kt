package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The `admin_rpc` params an App Server reads for a legacy `(method, path, body)` call: the
 * conversation and message ids a `/v1/conversations/{id}/messages/{id}` path names, its query
 * parameters, then the JSON body's fields. letta-mobile-ckmub.17.1: one shape for the JVM Iroh
 * transport and the browser's AppServerClientChannel, so both send the same request.
 */
fun appServerAdminRpcParams(path: String, body: String?): JsonObject = buildJsonObject {
    adminRpcPathParams(path).forEach { (key, value) -> put(key, value) }
    if (body != null) {
        runCatching { AppServerProtocol.json.parseToJsonElement(body).jsonObject }
            .getOrNull()
            ?.forEach { (key, value) -> put(key, value) }
    }
}

private fun adminRpcPathParams(path: String): Map<String, String> {
    val segments = path.substringBefore('?').trim('/').split('/').filter { it.isNotBlank() }
    val values = mutableMapOf<String, String>()
    conversationIdIn(segments)?.let { values["conversation_id"] = it }
    messageIdIn(segments)?.let { values["message_id"] = it }
    path.substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .filter { it.isNotBlank() }
        .forEach { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', missingDelimiterValue = "")
            if (key.isNotBlank()) values[key] = value
        }
    return values
}

/** The {id} of a /v1/conversations/{id}/... path, or null for any other path. */
private fun conversationIdIn(segments: List<String>): String? =
    segments.getOrNull(2)?.takeIf { segments.take(2) == CONVERSATIONS_PREFIX }

/** The message {id} of a /v1/conversations/{conversation}/messages/{id} path. */
private fun messageIdIn(segments: List<String>): String? =
    segments.getOrNull(4)?.takeIf { conversationIdIn(segments) != null && segments[3] == "messages" }

private val CONVERSATIONS_PREFIX = listOf("v1", "conversations")
