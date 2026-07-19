package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class AdminHandlerSupport(val proxy: AdminProxyClient) {
    fun get(vararg segments: String): JsonElement = proxy.get(adminProxyRequest("v1", *segments).build())
    fun get(request: AdminProxyRequest): JsonElement = proxy.get(request)
    fun post(vararg segments: String, body: String): JsonElement = proxy.post(adminProxyRequest("v1", *segments).build(), body)
    fun post(request: AdminProxyRequest, body: String): JsonElement = proxy.post(request, body)
    fun put(vararg segments: String, body: String): JsonElement = proxy.put(adminProxyRequest("v1", *segments).build(), body)
    fun put(request: AdminProxyRequest, body: String): JsonElement = proxy.put(request, body)
    fun patch(vararg segments: String, body: String): JsonElement = proxy.patch(adminProxyRequest("v1", *segments).build(), body)
    fun patch(request: AdminProxyRequest, body: String): JsonElement = proxy.patch(request, body)
    fun delete(vararg segments: String): JsonElement = proxy.delete(adminProxyRequest("v1", *segments).build())
    fun delete(request: AdminProxyRequest): JsonElement = proxy.delete(request)
}

internal fun param(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull

/** Prefer `identifier` when both alias keys are present (back-compat with legacy `project_id`). */
internal fun projectIdentifierParam(params: JsonObject?): String? =
    param(params, "identifier") ?: param(params, "project_id")

internal const val PROJECT_IDENTIFIER_REQUIRED = "identifier or project_id required"

internal fun passthroughBody(params: JsonObject?, vararg excludedKeys: String): String {
    if (params == null) return "{}"
    val excluded = excludedKeys.toSet()
    return buildJsonObject {
        params.forEach { (key, value) ->
            if (key !in excluded) put(key, value)
        }
    }.toString()
}
