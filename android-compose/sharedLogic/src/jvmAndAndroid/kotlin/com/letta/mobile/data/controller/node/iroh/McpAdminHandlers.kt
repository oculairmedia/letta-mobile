package com.letta.mobile.data.controller.node.iroh

object McpAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("mcp.list") { api.get(AdminPath.v1("mcp", "servers")) }
        router.register("passage.list") { params ->
            val agentId = params.requireParam(AdminParamKey("agent_id"))
            api.get(AdminPath.v1("agents", agentId, "passages"))
        }
    }
}
