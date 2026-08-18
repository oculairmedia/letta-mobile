package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class AgentListRequest(
    val params: JsonObject?,
    val context: AdminRpcRequestContext,
    val tiers: NativeReadTiers,
    val agentId: String,
)

internal object ConversationAgentListHelper {
    private const val MAX_AGENT_LIST_FETCH = 500

    fun listAgentConversations(
        request: AgentListRequest,
        scopeFunc: (JsonElement, AdminRpcRequestContext) -> JsonElement,
    ): JsonElement {
        val store = request.tiers.localBackendStore
            ?: return adminError("capability_unavailable: conversation.list_agent requires the local backend store")
        if (!store.agentExists(request.agentId)) {
            Telemetry.event(
                "IrohNode", "conversation.list_agent.agent_missing",
                "agentId" to request.agentId,
                level = Telemetry.Level.WARN,
            )
            return JsonArray(emptyList())
        }
        val limit = (param(request.params, AdminParamKey("limit"))?.toIntOrNull() ?: 200)
            .coerceIn(1, MAX_AGENT_LIST_FETCH)
        val archiveStatus = param(request.params, AdminParamKey("archive_status")) ?: "active"
        val rows = store.listConversationsProjected(
            agentId = request.agentId,
            archiveStatus = archiveStatus,
            limit = limit,
            offset = 0,
        ) ?: return adminError("local_backend_error: listConversationsProjected returned null")
        val decoded = rows.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            decodeConversationObject(obj, request.agentId)
        }
        return scopeFunc(JsonArray(decoded), request.context)
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
