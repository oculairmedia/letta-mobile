package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Per-peer agent scope (letta-mobile-dpv7m.1): a device paired with an invite
 * that named agents may only address those agents.
 *
 * Enforcement is centralized here so it cannot be forgotten by a handler:
 *  - requests: any admin_rpc params / runtime frame naming an out-of-scope
 *    `agent_id` (top level or under `runtime`) are denied before dispatch;
 *  - results: listings (`agent.list`, `conversation.list`, …) drop rows that
 *    belong to out-of-scope agents.
 *
 * Residual (tracked as a follow-up): reads addressed ONLY by a conversation
 * id do not resolve the conversation's owning agent here; scoped peers cannot
 * discover such ids through filtered listings, and conversation-content scope
 * (lgns8.12) still applies.
 */
object AgentScopeGuard {
    const val DENIED = "forbidden: agent not in this device's scope"

    private val AGENT_ID_KEYS = listOf("agent_id", "agentId")
    private val LIST_KEYS = listOf("agents", "conversations", "items", "data")

    /** The agent a runtime/admin frame addresses, if any. */
    fun agentIdOf(frame: JsonObject?): String? {
        if (frame == null) return null
        return frame.stringField(AGENT_ID_KEYS)
            ?: (frame["runtime"] as? JsonObject)?.stringField(AGENT_ID_KEYS)
    }

    fun allowsParams(scope: Set<String>?, params: JsonObject?): Boolean =
        IrohPeerCapabilities.isAgentInScope(scope, agentIdOf(params))

    fun filterResult(scope: Set<String>?, method: String, result: JsonElement): JsonElement {
        if (scope == null) return result
        val isAgentMethod = method.startsWith("agent.")
        return when (result) {
            is JsonArray -> filterArray(scope, isAgentMethod, result)
            is JsonObject -> filterListFields(scope, isAgentMethod, result)
            else -> result
        }
    }

    private fun filterListFields(scope: Set<String>, isAgentMethod: Boolean, result: JsonObject): JsonObject {
        if (LIST_KEYS.none { result[it] is JsonArray }) return result
        return JsonObject(
            result.mapValues { (key, value) ->
                if (key in LIST_KEYS && value is JsonArray) filterArray(scope, isAgentMethod, value) else value
            },
        )
    }

    private fun filterArray(scope: Set<String>, isAgentMethod: Boolean, rows: JsonArray): JsonArray =
        JsonArray(rows.filter { row -> rowInScope(scope, isAgentMethod, row) })

    private fun rowInScope(scope: Set<String>, isAgentMethod: Boolean, row: JsonElement): Boolean {
        val obj = row as? JsonObject ?: return true
        val owner = obj.stringField(AGENT_ID_KEYS)
            ?: if (isAgentMethod) obj.stringField(listOf("id")) else null
        return owner == null || owner in scope
    }

    private fun JsonObject.stringField(keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull }
}
