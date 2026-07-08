package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * letta-mobile-qfa81 (P4 row 13): user-critical approval submission over
 * `admin_rpc`. Approvals arrive while a turn is streaming and must be delivered
 * on the per-request `admin_rpc` stream path so the submission is isolated from
 * the live turn stream (k7yyc).
 *
 * The mobile REST-shaped approval payload carries `approval_request_id` and one
 * or more `tool_call_id`s. The admin shim's durable approval API is keyed by the
 * pending run id, so this handler resolves the pending approval by tool_call_id
 * and then POSTs `/shim/v1/approvals/{run_id}/decision`.
 */
object ApprovalAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val proxy = AdminProxyClient(adminBaseUrl)
        router.register("approval.submit") { params -> submit(proxy, params) }
    }

    private fun submit(proxy: AdminProxyClient, params: JsonObject?): JsonElement {
        val agentId = params?.get("agent_id")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("agent_id required")
        val payload = params["payload"]?.jsonObject
            ?: throw IllegalArgumentException("payload required")
        val approval = payload["messages"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IllegalArgumentException("approval message required")
        val approve = approval["approve"]?.jsonPrimitive?.booleanOrNull
            ?: approval["approvals"]?.jsonArray?.firstOrNull()?.jsonObject?.get("approve")?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("approval decision required")
        val reason = approval["reason"]?.jsonPrimitive?.contentOrNull
            ?: approval["approvals"]?.jsonArray?.firstOrNull()?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
        val toolCallIds = approval["approvals"]?.jsonArray
            ?.mapNotNull { it.jsonObject["tool_call_id"]?.jsonPrimitive?.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("tool_call_id required")

        val pending = proxy.get(
            adminProxyRequest("shim", "v1", "approvals", "pending")
                .query("agent_id", agentId)
                .build(),
        ).jsonObject["pending"]?.jsonArray.orEmpty()

        val pendingApproval = pending.firstOrNull { item ->
            val obj = item.jsonObject
            obj["tool_call_id"]?.jsonPrimitive?.contentOrNull in toolCallIds
        }?.jsonObject ?: throw IllegalArgumentException("pending approval not found for tool_call_id")

        val runId = pendingApproval["run_id"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("pending approval missing run_id")

        val decisionBody = buildJsonObject {
            put("decision", if (approve) "approve" else "deny")
            put("scope", if (approve) "Once" else "Deny")
            reason?.let { put("reason", it) }
        }
        return proxy.post(
            adminProxyRequest("shim", "v1", "approvals", runId, "decision").build(),
            decisionBody.toString(),
        )
    }
}
