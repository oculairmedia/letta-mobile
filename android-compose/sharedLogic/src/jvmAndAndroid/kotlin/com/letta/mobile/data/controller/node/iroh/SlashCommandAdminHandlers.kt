package com.letta.mobile.data.controller.node.iroh

object SlashCommandAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        // Global builtins (e.g. /goal). GET /v1/slash-commands.
        router.register("slash_command.list") {
            api.get(AdminPath.v1("slash-commands"))
        }
        // Per-agent list (builtins + installed skills). GET
        // /v1/agents/{id}/slash-commands. Routed over admin_rpc so the composer's
        // slash-command autocomplete populates in iroh:// mode.
        router.register("slash_command.list_agent") { params ->
            val agentId = params.requireParam("agent_id")
            api.get(AdminPath.v1("agents", agentId, "slash-commands"))
        }
    }
}
