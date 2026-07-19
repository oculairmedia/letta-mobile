package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Typed admin API path. Prefer over raw segment varargs to keep prefix and
 * segment order explicit ([AdminPath.v1], [AdminPath.api], [AdminPath.shim]).
 */
internal class AdminPath private constructor(private val segments: List<String>) {
    fun child(vararg segments: String): AdminPath = AdminPath(this.segments + segments)

    fun builder(): AdminProxyRequest.Builder = adminProxyRequest(*segments.toTypedArray())

    fun build(): AdminProxyRequest = builder().build()

    companion object {
        fun v1(vararg segments: String): AdminPath = AdminPath(listOf("v1", *segments))

        fun api(vararg segments: String): AdminPath = AdminPath(listOf("api", *segments))

        fun shim(vararg segments: String): AdminPath = AdminPath(listOf("shim", *segments))

        fun segments(vararg segments: String): AdminPath = AdminPath(segments.toList())
    }
}

internal class AdminHandlerSupport(val proxy: AdminProxyClient) {
    fun request(path: AdminPath): AdminProxyRequest.Builder = path.builder()

    fun get(path: AdminPath, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement =
        get(path.builder().apply(configure).build())

    fun get(request: AdminProxyRequest): JsonElement = proxy.get(request)

    fun get(vararg segments: String): JsonElement = get(AdminPath.v1(*segments))

    fun post(path: AdminPath, body: String, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement =
        post(path.builder().apply(configure).build(), body)

    fun post(request: AdminProxyRequest, body: String): JsonElement = proxy.post(request, body)

    fun post(vararg segments: String, body: String): JsonElement = post(AdminPath.v1(*segments), body)

    fun put(path: AdminPath, body: String, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement =
        put(path.builder().apply(configure).build(), body)

    fun put(request: AdminProxyRequest, body: String): JsonElement = proxy.put(request, body)

    fun put(vararg segments: String, body: String): JsonElement = put(AdminPath.v1(*segments), body)

    fun patch(path: AdminPath, body: String, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement =
        patch(path.builder().apply(configure).build(), body)

    fun patch(request: AdminProxyRequest, body: String): JsonElement = proxy.patch(request, body)

    fun patch(vararg segments: String, body: String): JsonElement = patch(AdminPath.v1(*segments), body)

    fun delete(path: AdminPath, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement =
        delete(path.builder().apply(configure).build())

    fun delete(request: AdminProxyRequest): JsonElement = proxy.delete(request)

    fun delete(vararg segments: String): JsonElement = delete(AdminPath.v1(*segments))
}

internal fun param(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull

internal fun JsonObject?.requireParam(key: String): String = param(this, key) ?: adminError("$key required")

internal fun JsonObject?.requireParam(key: String, message: String): String =
    param(this, key) ?: adminError(message)

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
