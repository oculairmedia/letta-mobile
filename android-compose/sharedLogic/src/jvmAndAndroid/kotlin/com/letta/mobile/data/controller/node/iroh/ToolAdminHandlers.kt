package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object ToolAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val proxy = AdminHandlerProxy(AdminProxyClient(adminBaseUrl))
        router.register("tool.list") { proxy.get("v1", "tools") }
        router.register("tool.get") { p -> AdminHandlerSupport.param(p, "tool_id")?.let { proxy.get("v1", "tools", it) } ?: adminError("tool_id required") }
        router.register("tool.create") { p -> proxy.post("v1", "tools", body = p?.toString() ?: "{}") }
        router.register("tool.update") { p -> AdminHandlerSupport.param(p, "tool_id")?.let { proxy.patch("v1", "tools", it, body = p.toString()) } ?: adminError("tool_id required") }
        router.register("tool.delete") { p -> AdminHandlerSupport.param(p, "tool_id")?.let { proxy.delete("v1", "tools", it) } ?: adminError("tool_id required") }
        router.register("tool.attach") { p ->
            val agentId = AdminHandlerSupport.param(p, "agent_id") ?: return@register adminError("agent_id required")
            val toolId = AdminHandlerSupport.param(p, "tool_id") ?: return@register adminError("tool_id required")
            proxy.patch("v1", "agents", agentId, "tools", "attach", toolId, body = "{}")
        }
        router.register("tool.detach") { p ->
            val agentId = AdminHandlerSupport.param(p, "agent_id") ?: return@register adminError("agent_id required")
            val toolId = AdminHandlerSupport.param(p, "tool_id") ?: return@register adminError("tool_id required")
            proxy.patch("v1", "agents", agentId, "tools", "detach", toolId, body = "{}")
        }
        router.register("block.list") { proxy.get("v1", "blocks") }
        router.register("block.get") { p -> AdminHandlerSupport.param(p, "block_id")?.let { proxy.get("v1", "blocks", it) } ?: adminError("block_id required") }
        router.register("block.create") { p -> proxy.post("v1", "blocks", body = p?.toString() ?: "{}") }
        router.register("block.update") { p -> AdminHandlerSupport.param(p, "block_id")?.let { proxy.patch("v1", "blocks", it, body = p.toString()) } ?: adminError("block_id required") }
        router.register("block.delete") { p -> AdminHandlerSupport.param(p, "block_id")?.let { proxy.delete("v1", "blocks", it) } ?: adminError("block_id required") }
        router.register("block.attach") { p ->
            val agentId = AdminHandlerSupport.param(p, "agent_id") ?: return@register adminError("agent_id required")
            val blockId = AdminHandlerSupport.param(p, "block_id") ?: return@register adminError("block_id required")
            proxy.patch("v1", "agents", agentId, "core-memory", "blocks", "attach", blockId, body = "{}")
        }
        router.register("block.detach") { p ->
            val agentId = AdminHandlerSupport.param(p, "agent_id") ?: return@register adminError("agent_id required")
            val blockId = AdminHandlerSupport.param(p, "block_id") ?: return@register adminError("block_id required")
            proxy.patch("v1", "agents", agentId, "core-memory", "blocks", "detach", blockId, body = "{}")
        }
        router.register("block.update_agent") { p ->
            val agentId = AdminHandlerSupport.param(p, "agent_id") ?: return@register adminError("agent_id required")
            val label = AdminHandlerSupport.param(p, "label") ?: return@register adminError("label required")
            proxy.patch("v1", "agents", agentId, "core-memory", "blocks", label, body = AdminHandlerSupport.passthroughBody(p, "agent_id", "label"))
        }
    }
}
