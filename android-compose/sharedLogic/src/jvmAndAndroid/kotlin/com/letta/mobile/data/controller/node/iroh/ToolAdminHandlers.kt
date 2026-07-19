package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object ToolAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("tool.list") { p ->
            api.get(
                adminProxyRequest("v1", "tools")
                    .query("limit", param(p, "limit"))
                    .query("offset", param(p, "offset"))
                    .build()
            )
        }
        router.register("tool.get") { p -> param(p, "tool_id")?.let { api.get("tools", it) } ?: adminError("tool_id required") }
        router.register("tool.create") { p -> api.put("tools", body = p?.toString() ?: "{}") }
        router.register("tool.update") { p -> param(p, "tool_id")?.let { api.patch("tools", it, body = p.toString()) } ?: adminError("tool_id required") }
        router.register("tool.delete") { p -> param(p, "tool_id")?.let { api.delete("tools", it) } ?: adminError("tool_id required") }
        router.register("tool.attach") { p ->
            val agentId = p.requireParam("agent_id")
            val toolId = p.requireParam("tool_id")
            api.patch("agents", agentId, "tools", "attach", toolId, body = "{}")
        }
        router.register("tool.detach") { p ->
            val agentId = p.requireParam("agent_id")
            val toolId = p.requireParam("tool_id")
            api.patch("agents", agentId, "tools", "detach", toolId, body = "{}")
        }
        router.register("block.list") { api.get(AdminPath.v1("blocks")) }
        router.register("block.get") { p ->
            val blockId = p.requireParam("block_id")
            api.get(AdminPath.v1("blocks", blockId))
        }
        router.register("block.create") { p -> api.post(AdminPath.v1("blocks"), body = p?.toString() ?: "{}") }
        router.register("block.update") { p ->
            val blockId = p.requireParam("block_id")
            api.patch(AdminPath.v1("blocks", blockId), body = p.toString())
        }
        router.register("block.delete") { p ->
            val blockId = p.requireParam("block_id")
            api.delete(AdminPath.v1("blocks", blockId))
        }
        router.register("block.attach") { p ->
            val agentId = p.requireParam("agent_id")
            val blockId = p.requireParam("block_id")
            api.patch(AdminPath.v1("agents", agentId, "core-memory", "blocks", "attach", blockId), body = "{}")
        }
        router.register("block.detach") { p ->
            val agentId = p.requireParam("agent_id")
            val blockId = p.requireParam("block_id")
            api.patch(AdminPath.v1("agents", agentId, "core-memory", "blocks", "detach", blockId), body = "{}")
        }
        router.register("block.update_agent") { p ->
            val agentId = p.requireParam("agent_id")
            val label = p.requireParam("label")
            api.patch(
                AdminPath.v1("agents", agentId, "core-memory", "blocks", label),
                body = passthroughBody(p, "agent_id", "label"),
            )
        }
    }
}
