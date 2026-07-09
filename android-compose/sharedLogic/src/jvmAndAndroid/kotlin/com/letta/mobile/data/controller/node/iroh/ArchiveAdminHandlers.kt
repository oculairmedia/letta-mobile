package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object ArchiveAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("archive.list") { api.get("archives") }
        router.register("folder.list") { params ->
            val agentId = param(params, "agent_id")
            if (agentId != null) api.get("agents", agentId, "folders") else adminError("agent_id required")
        }
        router.register("passage.create") { params ->
            val agentId = param(params, "agent_id") ?: return@register adminError("agent_id required")
            api.post("agents", agentId, "archival-memory", body = passthroughBody(params, "agent_id"))
        }
        router.register("passage.delete") { params ->
            val agentId = param(params, "agent_id") ?: return@register adminError("agent_id required")
            val passageId = param(params, "passage_id") ?: return@register adminError("passage_id required")
            api.delete("agents", agentId, "archival-memory", passageId)
        }
        router.register("group.list") { api.get("groups") }
    }
}
