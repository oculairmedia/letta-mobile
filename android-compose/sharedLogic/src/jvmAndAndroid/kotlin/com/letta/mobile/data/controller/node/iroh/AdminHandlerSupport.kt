package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One path segment in an [AdminPath]. */
@JvmInline
internal value class AdminPathSegment(val value: String)

/**
 * Typed admin API path. Prefer over raw segment lists to keep prefix and
 * segment order explicit ([AdminPath.v1], [AdminPath.api], [AdminPath.shim]).
 */
internal class AdminPath private constructor(private val segments: List<AdminPathSegment>) {
    fun child(vararg segments: String): AdminPath =
        AdminPath(this.segments + segments.map(::AdminPathSegment))

    fun builder(): AdminProxyRequest.Builder =
        adminProxyRequest(*segments.map { it.value }.toTypedArray())

    fun build(): AdminProxyRequest = builder().build()

    companion object {
        fun v1(vararg segments: String): AdminPath =
            AdminPath(listOf(AdminPathSegment("v1")) + segments.map(::AdminPathSegment))

        fun api(vararg segments: String): AdminPath =
            AdminPath(listOf(AdminPathSegment("api")) + segments.map(::AdminPathSegment))

        fun shim(vararg segments: String): AdminPath =
            AdminPath(listOf(AdminPathSegment("shim")) + segments.map(::AdminPathSegment))

        fun segments(vararg parts: String): AdminPath =
            AdminPath(parts.map(::AdminPathSegment))
    }
}

/** Typed JSON body payload for admin proxy write methods. */
@JvmInline
internal value class AdminJsonBody(val value: String) {
    companion object {
        val Empty = AdminJsonBody("{}")
    }
}

/** Typed admin query/path parameter key. */
@JvmInline
internal value class AdminParamKey(val value: String)

/** Error message for a missing required admin param. */
@JvmInline
internal value class AdminParamError(val value: String)

internal class AdminHandlerSupport(val proxy: AdminProxyClient) {
    fun request(path: AdminPath): AdminProxyRequest.Builder = path.builder()

    fun get(path: AdminPath, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement =
        get(path.builder().apply(configure).build())

    fun get(request: AdminProxyRequest): JsonElement = proxy.get(request)

    fun post(
        path: AdminPath,
        body: AdminJsonBody,
        configure: AdminProxyRequest.Builder.() -> Unit = {},
    ): JsonElement = post(path.builder().apply(configure).build(), body)

    fun post(path: AdminPath, body: String, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement =
        post(path, AdminJsonBody(body), configure)

    fun post(request: AdminProxyRequest, body: AdminJsonBody): JsonElement =
        proxy.post(request, body.value)

    fun post(request: AdminProxyRequest, body: String): JsonElement = post(request, AdminJsonBody(body))

    fun put(
        path: AdminPath,
        body: AdminJsonBody,
        configure: AdminProxyRequest.Builder.() -> Unit = {},
    ): JsonElement = put(path.builder().apply(configure).build(), body)

    fun put(path: AdminPath, body: String, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement =
        put(path, AdminJsonBody(body), configure)

    fun put(request: AdminProxyRequest, body: AdminJsonBody): JsonElement =
        proxy.put(request, body.value)

    fun put(request: AdminProxyRequest, body: String): JsonElement = put(request, AdminJsonBody(body))

    fun patch(
        path: AdminPath,
        body: AdminJsonBody,
        configure: AdminProxyRequest.Builder.() -> Unit = {},
    ): JsonElement = patch(path.builder().apply(configure).build(), body)

    fun patch(path: AdminPath, body: String, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement =
        patch(path, AdminJsonBody(body), configure)

    fun patch(request: AdminProxyRequest, body: AdminJsonBody): JsonElement =
        proxy.patch(request, body.value)

    fun patch(request: AdminProxyRequest, body: String): JsonElement = patch(request, AdminJsonBody(body))

    fun delete(path: AdminPath, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement =
        delete(path.builder().apply(configure).build())

    fun delete(request: AdminProxyRequest): JsonElement = proxy.delete(request)
}

internal fun param(params: JsonObject?, key: AdminParamKey): String? =
    params?.get(key.value)?.jsonPrimitive?.contentOrNull

internal fun JsonObject?.requireParam(key: AdminParamKey): String =
    param(this, key) ?: adminError("${key.value} required")

internal fun JsonObject?.requireParam(key: AdminParamKey, error: AdminParamError): String =
    param(this, key) ?: adminError(error.value)

/** Prefer `identifier` when both alias keys are present (back-compat with legacy `project_id`). */
internal fun projectIdentifierParam(params: JsonObject?): String? =
    param(params, AdminParamKey("identifier")) ?: param(params, AdminParamKey("project_id"))

internal const val PROJECT_IDENTIFIER_REQUIRED = "identifier or project_id required"

internal fun passthroughBody(params: JsonObject?, excludedKeys: List<AdminParamKey>): String {
    if (params == null) return "{}"
    val excluded = excludedKeys.map { it.value }.toSet()
    return buildJsonObject {
        params.forEach { (key, value) ->
            if (key !in excluded) put(key, value)
        }
    }.toString()
}
