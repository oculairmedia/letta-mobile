package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.ConversationId
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
 * For live AppServer turns, route directly through [AppServerController] so the
 * active runtime receives an `ApprovalResponse` on the control channel. The shim
 * pending-approval REST endpoint remains a fallback for durable shim approvals.
 */
object ApprovalAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String, controller: AppServerController? = null) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("approval.submit") { params -> submit(api, params, controller) }
    }

    private suspend fun submit(
        api: AdminHandlerSupport,
        params: JsonObject?,
        controller: AppServerController?,
    ): JsonElement {
        val agentId = params.requireParam(AdminParamKey("agent_id"))
        val conversationId = param(params, AdminParamKey("conversation_id"))
        val payload = params?.get("payload")?.jsonObject
            ?: throw IllegalArgumentException("payload required")
        val approval = payload["messages"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IllegalArgumentException("approval message required")
        val approvalRequestId = approval["approval_request_id"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("approval_request_id required")
        val approve = approval["approve"]?.jsonPrimitive?.booleanOrNull
            ?: approval["approvals"]?.jsonArray?.firstOrNull()?.jsonObject?.get("approve")?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("approval decision required")
        val reason = approval["reason"]?.jsonPrimitive?.contentOrNull
            ?: approval["approvals"]?.jsonArray?.firstOrNull()?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
        // letta-mobile-vilsn: structured close payload (e.g. an AskUserQuestion
        // answer), decoded upstream in MessageRepositoryApproval and threaded here
        // as a first-class field instead of the controller re-decoding a `reason`
        // sentinel.
        // Backward-compat: older clients still encode the answer into the `reason`
        // sentinel (AskUserQuestion.encodeAnswerReason) rather than sending
        // `updated_input`. Decode it here so a new host keeps working with an
        // un-upgraded app/desktop client.
        val updatedInput = approval["updated_input"]?.jsonObject
            ?: com.letta.mobile.data.model.AskUserQuestion.decodeAnswerReason(reason)
        val toolCallIds = approval["approvals"]?.jsonArray
            ?.mapNotNull { it.jsonObject["tool_call_id"]?.jsonPrimitive?.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
            .orEmpty()

        if (controller != null) {
            runCatching {
                controller.submitApproval(
                    agentId = AgentId(agentId),
                    conversationId = conversationId?.let(::ConversationId),
                    approvalRequestId = approvalRequestId,
                    approve = approve,
                    reason = reason,
                    toolCallId = toolCallIds.firstOrNull(),
                    updatedInput = updatedInput,
                )
            }.onSuccess {
                return buildJsonObject { put("status", if (approve) "approved" else "denied") }
            }
        }

        if (toolCallIds.isEmpty()) throw IllegalArgumentException("tool_call_id required")
        // letta-mobile-vilsn.8: the shim's `v1/approvals/{run_id}/decision` REST
        // endpoint only accepts decision/scope/reason — it has no updated_input
        // field. If we reach this fallback (no live controller, or the controller
        // submit above failed) with a structured answer to deliver, silently
        // posting a bare decision would report success while actually
        // closing an AskUserQuestion/ExitPlanMode call with no answer, stalling
        // the agent on a placeholder. Fail loudly instead of lying about it.
        if (approve && updatedInput != null) {
            throw IllegalStateException(
                "cannot deliver a structured answer (updated_input) through the shim " +
                    "approvals decision fallback — no live AppServer controller available " +
                    "for this submission",
            )
        }
        val pending = api.get(AdminPath.shim("v1", "approvals", "pending")) {
            query("agent_id", agentId)
        }.jsonObject["pending"]?.jsonArray.orEmpty()

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
        return api.post(AdminPath.shim("v1", "approvals", runId, "decision"), body = decisionBody.toString())
    }
}
