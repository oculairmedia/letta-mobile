package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Per-peer agent scope: a device paired with an invite
 * that named agents may only address those agents.
 *
 * Enforcement is centralized here so it cannot be forgotten by a handler:
 *  - requests: any admin_rpc params / runtime frame naming an out-of-scope
 *    `agent_id` (top level or under `runtime`; all of them,) are denied before dispatch;
 *  - results: listings (`agent.list`, `conversation.list`, …) drop rows that
 *    belong to out-of-scope agents.
 *
 * Residual (tracked as a follow-up): reads addressed ONLY by a conversation
 * id do not resolve the conversation's owning agent here; scoped peers cannot
 * discover such ids through filtered listings, and conversation-content scope
 *  still applies.
 */
object AgentScopeGuard {
    const val DENIED = "forbidden: agent not in this device's scope"

    private val AGENT_ID_KEYS = listOf("agent_id", "agentId")
    private val LIST_KEYS = listOf("agents", "conversations", "items", "data")

    /**
     * Every agent a runtime/admin frame names: the top-level `agent_id` and `runtime.agent_id`,
     * each in either spelling. Admin handlers act on the top-level id; relayed runtime frames
     * (`input`, `abort_message`, queue controls) decode and act on `runtime.agent_id`. Checking
     * only the first one found let an in-scope top-level id smuggle an out-of-scope runtime id.
     */
    fun agentIdsOf(frame: JsonObject?): Set<String> {
        val carriers = listOfNotNull(frame, frame?.get("runtime") as? JsonObject)
        return carriers.flatMapTo(mutableSetOf()) { carrier -> AGENT_ID_KEYS.mapNotNull { carrier.stringField(listOf(it)) } }
    }

    /**
     * Unrestricted peers (null scope) and frames naming no agent pass. A scoped peer's frame
     * passes only when it names exactly one agent and that agent is in scope: no legitimate
     * frame carries two different agent ids, so disagreeing carriers are denied outright
     * rather than guessing which one the handler will act on.
     */
    fun allowsParams(scope: Set<String>?, params: JsonObject?): Boolean {
        if (scope == null) return true
        val ids = agentIdsOf(params)
        return ids.size <= 1 && ids.all { IrohPeerCapabilities.isAgentInScope(scope, it) }
    }

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
