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
        router.register("tool.attach") { p ->
            val agentId = id(p, "agent_id") ?: return@register jsonError("agent_id required")
            val toolId = id(p, "tool_id") ?: return@register jsonError("tool_id required")
            api.patch("agents", agentId, "tools", "attach", toolId, body = "{}")
        }
        router.register("tool.detach") { p ->
            val agentId = id(p, "agent_id") ?: return@register jsonError("agent_id required")
            val toolId = id(p, "tool_id") ?: return@register jsonError("tool_id required")
            api.patch("agents", agentId, "tools", "detach", toolId, body = "{}")
        }
        router.register("block.list") { api.get("blocks") }
        router.register("block.get") { p -> id(p, "block_id")?.let { api.get("blocks", it) } ?: jsonError("block_id required") }
        router.register("block.create") { p -> api.post("blocks", body = p?.toString() ?: "{}") }
        router.register("block.update") { p -> id(p, "block_id")?.let { api.patch("blocks", it, body = p.toString()) } ?: jsonError("block_id required") }
        router.register("block.delete") { p -> id(p, "block_id")?.let { api.delete("blocks", it) } ?: jsonError("block_id required") }
        router.register("block.attach") { p ->
            val agentId = id(p, "agent_id") ?: return@register jsonError("agent_id required")
            val blockId = id(p, "block_id") ?: return@register jsonError("block_id required")
            api.patch("agents", agentId, "core-memory", "blocks", "attach", blockId, body = "{}")
        }
        router.register("block.detach") { p ->
            val agentId = id(p, "agent_id") ?: return@register jsonError("agent_id required")
            val blockId = id(p, "block_id") ?: return@register jsonError("block_id required")
            api.patch("agents", agentId, "core-memory", "blocks", "detach", blockId, body = "{}")
        }
        router.register("block.update_agent") { p ->
            val agentId = id(p, "agent_id") ?: return@register jsonError("agent_id required")
            val label = id(p, "label") ?: return@register jsonError("label required")
            api.patch("agents", agentId, "core-memory", "blocks", label, body = passthroughBody(p, "agent_id", "label"))
        }
    }

    private class Api(private val proxy: AdminProxyClient) {
        fun get(vararg segments: String): JsonElement = proxy.get(adminProxyRequest("v1", *segments).build())
        fun post(vararg segments: String, body: String): JsonElement = proxy.post(adminProxyRequest("v1", *segments).build(), body)
        fun patch(vararg segments: String, body: String): JsonElement = proxy.patch(adminProxyRequest("v1", *segments).build(), body)
        fun delete(vararg segments: String): JsonElement = proxy.delete(adminProxyRequest("v1", *segments).build())
    }

    private fun id(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull
    private fun passthroughBody(params: JsonObject?, vararg excludedKeys: String): String {
        if (params == null) return "{}"
        val excluded = excludedKeys.toSet()
        return buildJsonObject {
            params.forEach { (key, value) ->
                if (key !in excluded) put(key, value)
            }
        }.toString()
    }
    private fun jsonError(message: String): JsonElement = buildJsonObject { put("_error", message) }
}
