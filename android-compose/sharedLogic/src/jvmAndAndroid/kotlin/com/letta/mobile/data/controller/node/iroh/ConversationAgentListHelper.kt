package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object ConversationAgentListHelper {
    private const val MAX_AGENT_LIST_FETCH = 500

    fun listAgentConversations(
        params: JsonObject?,
        context: AdminRpcRequestContext,
        tiers: NativeReadTiers,
        agentId: String,
        scopeFunc: (JsonElement, AdminRpcRequestContext) -> JsonElement,
    ): JsonElement {
        val store = tiers.localBackendStore
            ?: return adminError("capability_unavailable: conversation.list_agent requires the local backend store")
        if (!store.agentExists(agentId)) {
            Telemetry.event(
                "IrohNode", "conversation.list_agent.agent_missing",
                "agentId" to agentId,
                level = Telemetry.Level.WARN,
            )
            return JsonArray(emptyList())
        }
        val limit = (param(params, AdminParamKey("limit"))?.toIntOrNull() ?: 200)
            .coerceIn(1, MAX_AGENT_LIST_FETCH)
        val archiveStatus = param(params, AdminParamKey("archive_status")) ?: "active"
        val rows = store.listConversationsProjected(
            agentId = agentId,
            archiveStatus = archiveStatus,
            limit = limit,
            offset = 0,
        ) ?: return adminError("local_backend_error: listConversationsProjected returned null")
        val decoded = rows.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            decodeConversationObject(obj, agentId)
        }
        return scopeFunc(JsonArray(decoded), context)
    }

    private fun decodeConversationObject(obj: JsonObject, fallbackAgentId: String): JsonObject {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return obj
        val agentId = obj["agent_id"]?.jsonPrimitive?.contentOrNull ?: fallbackAgentId
        val updated = obj.toMutableMap()
        updated["id"] = JsonPrimitive(id)
        updated["agent_id"] = JsonPrimitive(agentId)
        return JsonObject(updated)
    }
}
