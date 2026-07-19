package com.letta.mobile.data.controller.node.iroh

object SkillAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("skill.list") { api.get("skills") }
        router.register("skill.list_agent") { params ->
            val agentId = param(params, "agent_id") ?: return@register adminError("agent_id required")
            api.get("agents", agentId, "skills")
        }
        router.register("skill.install") { params ->
            val agentId = param(params, "agent_id") ?: return@register adminError("agent_id required")
            api.post("agents", agentId, "skills", body = passthroughBody(params, "agent_id"))
        }
        router.register("skill.uninstall") { params ->
            val agentId = param(params, "agent_id") ?: return@register adminError("agent_id required")
            val skillName = param(params, "name") ?: return@register adminError("name required")
            api.delete("agents", agentId, "skills", skillName)
        }
    }
}
