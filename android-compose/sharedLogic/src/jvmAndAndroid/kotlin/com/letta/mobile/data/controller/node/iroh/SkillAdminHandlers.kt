package com.letta.mobile.data.controller.node.iroh

object SkillAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("skill.list") { api.get(AdminPath.v1("skills")) }
        router.register("skill.list_agent") { params ->
            val agentId = params.requireParam(AdminParamKey("agent_id"))
            api.get(AdminPath.v1("agents", agentId, "skills"))
        }
        router.register("skill.install") { params ->
            val agentId = params.requireParam(AdminParamKey("agent_id"))
            api.post(AdminPath.v1("agents", agentId, "skills"), body = passthroughBody(params, listOf(AdminParamKey("agent_id"))))
        }
        router.register("skill.uninstall") { params ->
            val agentId = params.requireParam(AdminParamKey("agent_id"))
            val skillName = params.requireParam(AdminParamKey("name"))
            api.delete(AdminPath.v1("agents", agentId, "skills", skillName))
        }
    }
}
