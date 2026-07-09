package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object ArchiveAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val proxy = AdminHandlerProxy(AdminProxyClient(adminBaseUrl))
        router.register("archive.list") { proxy.get("v1", "archives") }
        router.register("folder.list") { params ->
            val agentId = AdminHandlerSupport.param(params, "agent_id")
            if (agentId != null) proxy.get("v1", "agents", agentId, "folders") else adminError("agent_id required")
        }
        router.register("passage.create") { params ->
            val agentId = AdminHandlerSupport.param(params, "agent_id") ?: adminError("agent_id required")
            proxy.post("v1", "agents", agentId, "archival-memory", body = AdminHandlerSupport.passthroughBody(params, "agent_id"))
        }
        router.register("passage.delete") { params ->
            val agentId = AdminHandlerSupport.param(params, "agent_id") ?: adminError("agent_id required")
            val passageId = AdminHandlerSupport.param(params, "passage_id") ?: adminError("passage_id required")
            proxy.delete("v1", "agents", agentId, "archival-memory", passageId)
        }
        router.register("group.list") { proxy.get("v1", "groups") }
    }
}
