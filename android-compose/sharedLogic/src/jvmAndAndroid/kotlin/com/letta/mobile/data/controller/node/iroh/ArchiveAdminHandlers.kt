package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object ArchiveAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = Api(AdminProxyClient(adminBaseUrl))
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

    private class Api(private val proxy: AdminProxyClient) {
        fun get(vararg segments: String): JsonElement = proxy.get(adminProxyRequest("v1", *segments).build())
        fun post(vararg segments: String, body: String): JsonElement = proxy.post(adminProxyRequest("v1", *segments).build(), body)
        fun delete(vararg segments: String): JsonElement = proxy.delete(adminProxyRequest("v1", *segments).build())
    }

    private fun param(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull
    private fun passthroughBody(params: JsonObject?, vararg excludedKeys: String): String {
        if (params == null) return "{}"
        val excluded = excludedKeys.toSet()
        return buildJsonObject {
            params.forEach { (key, value) ->
                if (key !in excluded) put(key, value)
            }
        }.toString()
    }

}
