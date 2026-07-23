package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object SkillAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String, nativeClient: AppServerClient? = null) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("skill.list") { api.get(AdminPath.v1("skills")) }
        router.register("skill.list_agent") { params ->
            val agentId = params.requireParam(AdminParamKey("agent_id"))
            api.get(AdminPath.v1("agents", agentId, "skills"))
        }
        router.register("skill.install") { params ->
            // lgns8.8: the native command is skill_enable(skill_path) — a
            // filesystem enable, not the shim's agent-scoped install. Use the
            // native path only when the caller provides skill_path (semantic
            // parity verified per matrix note); otherwise keep the shim route.
            val skillPath = param(params, AdminParamKey("skill_path"))
            if (skillPath != null) {
                NativeAdmin.attempt(nativeClient, "skill.install") { c ->
                    val response = c.skillEnable(
                        AppServerCommand.SkillEnable(requestId = NativeAdmin.requestId(), skillPath = skillPath),
                    )
                    if (response.success) {
                        buildJsonObject { put("enabled", true); response.skillName?.let { put("skill_name", it) } }
                    } else {
                        null
                    }
                } ?: adminError("skill_enable failed and no shim fallback applies to skill_path installs")
            } else {
                val agentId = params.requireParam(AdminParamKey("agent_id"))
                api.post(AdminPath.v1("agents", agentId, "skills"), body = passthroughBody(params, listOf(AdminParamKey("agent_id"))))
            }
        }
        router.register("skill.uninstall") { params ->
            val skillName = params.requireParam(AdminParamKey("name"))
            val agentId = param(params, AdminParamKey("agent_id"))
            if (agentId == null) {
                NativeAdmin.attempt(nativeClient, "skill.uninstall") { c ->
                    val response = c.skillDisable(
                        AppServerCommand.SkillDisable(requestId = NativeAdmin.requestId(), name = skillName),
                    )
                    if (response.success) buildJsonObject { put("disabled", true) } else null
                } ?: adminError("skill_disable failed and no agent_id was provided for the shim route")
            } else {
                api.delete(AdminPath.v1("agents", agentId, "skills", skillName))
            }
        }
    }
}
