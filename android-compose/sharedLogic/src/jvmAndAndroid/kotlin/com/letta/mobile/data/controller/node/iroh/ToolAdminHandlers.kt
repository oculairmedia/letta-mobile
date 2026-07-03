package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object ToolAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = Api(AdminProxyClient(adminBaseUrl))
        router.register("tool.list") { api.get("tools") }
        router.register("tool.get") { p -> id(p, "tool_id")?.let { api.get("tools", it) } ?: jsonError("tool_id required") }
        router.register("tool.create") { p -> api.post("tools", body = p?.toString() ?: "{}") }
        router.register("tool.update") { p -> id(p, "tool_id")?.let { api.patch("tools", it, body = p.toString()) } ?: jsonError("tool_id required") }
        router.register("tool.delete") { p -> id(p, "tool_id")?.let { api.delete("tools", it) } ?: jsonError("tool_id required") }
        router.register("block.list") { api.get("blocks") }
        router.register("block.get") { p -> id(p, "block_id")?.let { api.get("blocks", it) } ?: jsonError("block_id required") }
        router.register("block.create") { p -> api.post("blocks", body = p?.toString() ?: "{}") }
        router.register("block.update") { p -> id(p, "block_id")?.let { api.patch("blocks", it, body = p.toString()) } ?: jsonError("block_id required") }
        router.register("block.delete") { p -> id(p, "block_id")?.let { api.delete("blocks", it) } ?: jsonError("block_id required") }
    }

    private class Api(private val proxy: AdminProxyClient) {
        fun get(vararg segments: String): JsonElement = proxy.get(adminProxyRequest("v1", *segments).build())
        fun post(vararg segments: String, body: String): JsonElement = proxy.post(adminProxyRequest("v1", *segments).build(), body)
        fun patch(vararg segments: String, body: String): JsonElement = proxy.patch(adminProxyRequest("v1", *segments).build(), body)
        fun delete(vararg segments: String): JsonElement = proxy.delete(adminProxyRequest("v1", *segments).build())
    }

    private fun id(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull
    private fun jsonError(message: String): JsonElement = buildJsonObject { put("_error", message) }
}
