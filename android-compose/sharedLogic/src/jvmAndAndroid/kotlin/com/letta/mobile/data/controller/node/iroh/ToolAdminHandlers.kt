package com.letta.mobile.data.controller.node.iroh

object ToolAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String?) {
        // lgns8.9: no admin-rest service injected -> capability-unavailable
        // (never a shim dial). Bounded admin adapter degrades gracefully.
        if (adminBaseUrl == null) {
            CapabilityUnavailable.register(router, METHODS, service = "admin_rest")
            return
        }
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("tool.list") { p ->
            api.get(AdminPath.v1("tools")) {
                query("limit", param(p, AdminParamKey("limit")))
                query("offset", param(p, AdminParamKey("offset")))
            }
        }
        router.register("tool.get") { p ->
            val toolId = p.requireParam(AdminParamKey("tool_id"))
            api.get(AdminPath.v1("tools", toolId))
        }
        router.register("tool.create") { p -> api.put(AdminPath.v1("tools"), body = p?.toString() ?: "{}") }
        router.register("tool.update") { p ->
            val toolId = p.requireParam(AdminParamKey("tool_id"))
            api.patch(AdminPath.v1("tools", toolId), body = p.toString())
        }
        router.register("tool.delete") { p ->
            val toolId = p.requireParam(AdminParamKey("tool_id"))
            api.delete(AdminPath.v1("tools", toolId))
        }
        router.register("tool.attach") { p ->
            val agentId = p.requireParam(AdminParamKey("agent_id"))
            val toolId = p.requireParam(AdminParamKey("tool_id"))
            api.patch(AdminPath.v1("agents", agentId, "tools", "attach", toolId), body = "{}")
        }
        router.register("tool.detach") { p ->
            val agentId = p.requireParam(AdminParamKey("agent_id"))
            val toolId = p.requireParam(AdminParamKey("tool_id"))
            api.patch(AdminPath.v1("agents", agentId, "tools", "detach", toolId), body = "{}")
        }
        router.register("block.list") { api.get(AdminPath.v1("blocks")) }
        router.register("block.get") { p ->
            val blockId = p.requireParam(AdminParamKey("block_id"))
            api.get(AdminPath.v1("blocks", blockId))
        }
        router.register("block.create") { p -> api.post(AdminPath.v1("blocks"), body = p?.toString() ?: "{}") }
        router.register("block.update") { p ->
            val blockId = p.requireParam(AdminParamKey("block_id"))
            api.patch(AdminPath.v1("blocks", blockId), body = p.toString())
        }
        router.register("block.delete") { p ->
            val blockId = p.requireParam(AdminParamKey("block_id"))
            api.delete(AdminPath.v1("blocks", blockId))
        }
        router.register("block.attach") { p ->
            val agentId = p.requireParam(AdminParamKey("agent_id"))
            val blockId = p.requireParam(AdminParamKey("block_id"))
            api.patch(AdminPath.v1("agents", agentId, "core-memory", "blocks", "attach", blockId), body = "{}")
        }
        router.register("block.detach") { p ->
            val agentId = p.requireParam(AdminParamKey("agent_id"))
            val blockId = p.requireParam(AdminParamKey("block_id"))
            api.patch(AdminPath.v1("agents", agentId, "core-memory", "blocks", "detach", blockId), body = "{}")
        }
        router.register("block.update_agent") { p ->
            val agentId = p.requireParam(AdminParamKey("agent_id"))
            val label = p.requireParam(AdminParamKey("label"))
            api.patch(
                AdminPath.v1("agents", agentId, "core-memory", "blocks", label),
                body = passthroughBody(p, listOf(AdminParamKey("agent_id"), AdminParamKey("label"))),
            )
        }
    }
    val METHODS: Set<String> = setOf(
        "tool.list",
        "tool.get",
        "tool.create",
        "tool.update",
        "tool.delete",
        "tool.attach",
        "tool.detach",
        "block.list",
        "block.get",
        "block.create",
        "block.update",
        "block.delete",
        "block.attach",
        "block.detach",
        "block.update_agent",
    )
}
