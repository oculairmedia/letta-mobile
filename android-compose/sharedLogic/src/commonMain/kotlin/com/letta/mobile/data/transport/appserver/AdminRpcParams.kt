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
    val withoutPrefix = path.substringBefore('?').trim('/').split('/').filter { it.isNotBlank() }
    val values = mutableMapOf<String, String>()
    if (withoutPrefix.size >= 3 && withoutPrefix[0] == "v1" && withoutPrefix[1] == "conversations") {
        values["conversation_id"] = withoutPrefix[2]
        if (withoutPrefix.size >= 5 && withoutPrefix[3] == "messages") {
            values["message_id"] = withoutPrefix[4]
        }
    }
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
