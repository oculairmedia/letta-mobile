package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Shared helpers for admin RPC handlers.
 */
internal object AdminHandlerSupport {
    fun param(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull


    fun passthroughBody(params: JsonObject?, vararg excludedKeys: String): String {
        if (params == null) return "{}"
        val excluded = excludedKeys.toSet()
        return buildJsonObject {
            params.forEach { (key, value) ->
                if (key !in excluded) put(key, value)
            }
        }.toString()
    }
}

/**
 * A thin typed wrapper around AdminProxyClient to simplify typical proxy operations.
 */
internal class AdminHandlerProxy(val proxy: AdminProxyClient) {
    fun get(vararg segments: String, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement {
        val req = adminProxyRequest(*segments).apply(configure)
        return proxy.get(req.build())
    }

    fun post(vararg segments: String, body: String = "{}", configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement {
        val req = adminProxyRequest(*segments).apply(configure)
        return proxy.post(req.build(), body)
    }

    fun patch(vararg segments: String, body: String, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement {
        val req = adminProxyRequest(*segments).apply(configure)
        return proxy.patch(req.build(), body)
    }

    fun delete(vararg segments: String, configure: AdminProxyRequest.Builder.() -> Unit = {}): JsonElement {
        val req = adminProxyRequest(*segments).apply(configure)
        return proxy.delete(req.build())
    }
}
