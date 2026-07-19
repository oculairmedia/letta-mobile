package com.letta.mobile.data.controller.node.iroh

object ArchiveAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("archive.list") { api.get(AdminPath.v1("archives")) }
        router.register("folder.list") { params ->
            val agentId = params.requireParam(AdminParamKey("agent_id"))
            api.get(AdminPath.v1("agents", agentId, "folders"))
        }
        router.register("passage.create") { params ->
            val agentId = params.requireParam(AdminParamKey("agent_id"))
            api.post(AdminPath.v1("agents", agentId, "archival-memory"), body = passthroughBody(params, listOf(AdminParamKey("agent_id"))))
        }
        router.register("passage.delete") { params ->
            val agentId = params.requireParam(AdminParamKey("agent_id"))
            val passageId = params.requireParam(AdminParamKey("passage_id"))
            api.delete(AdminPath.v1("agents", agentId, "archival-memory", passageId))
        }
        router.register("group.list") { api.get(AdminPath.v1("groups")) }
    }
}
