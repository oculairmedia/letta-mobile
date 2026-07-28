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
 * Approval submission over `admin_rpc`.
 *
 * Phase 2: live approvals go through [AppServerController] only. The shim
 * pending-approval REST fallback is removed; missing/failed controller delivery
 * returns a typed failure so Ask User answers cannot silently drop.
 */
object ApprovalAdminHandlers {
    fun register(
        router: AdminRpcRouter,
        controller: AppServerController? = null,
    ) {
        router.register("approval.submit") { params -> submit(params, controller) }
    }

    private suspend fun submit(
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
        val updatedInput = approval["updated_input"]?.jsonObject
            ?: com.letta.mobile.data.model.AskUserQuestion.decodeAnswerReason(reason)
        val toolCallIds = approval["approvals"]?.jsonArray
            ?.mapNotNull { it.jsonObject["tool_call_id"]?.jsonPrimitive?.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
            .orEmpty()

        if (controller == null) {
            AdminRouteTelemetry.selected(
                AdminRouteTelemetry.Selection(
                    method = "approval.submit",
                    owner = "controller_native",
                    route = "controller_native",
                    outcome = "unavailable",
                    reason = "no_controller",
                ),
            )
            adminError("capability_unavailable: approval.submit requires a live App Server controller")
        }

        val convId = conversationId?.let(::ConversationId)
            ?: throw IllegalArgumentException("conversation_id required")
        runCatching {
            // After wrapper restart the in-memory runtime cache is empty even though
            // App Server still owns the parked approval. Reattach before submit.
            controller.startRuntime(
                agentId = AgentId(agentId),
                conversationId = convId,
                recoverApprovals = true,
            )
            controller.submitApproval(
                agentId = AgentId(agentId),
                conversationId = convId,
                approvalRequestId = approvalRequestId,
                approve = approve,
                reason = reason,
                toolCallId = toolCallIds.firstOrNull(),
                updatedInput = updatedInput,
            )
        }.onSuccess {
            AdminRouteTelemetry.selected(
                AdminRouteTelemetry.Selection(
                    method = "approval.submit",
                    owner = "controller_native",
                    route = "controller_native",
                    outcome = "success",
                ),
            )
            return buildJsonObject { put("status", if (approve) "approved" else "denied") }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            AdminRouteTelemetry.selected(
                AdminRouteTelemetry.Selection(
                    method = "approval.submit",
                    owner = "controller_native",
                    route = "controller_native",
                    outcome = "error",
                    reason = error.message ?: error::class.simpleName ?: "error",
                ),
            )
            adminError("app_server_error: approval.submit failed")
        }
        error("unreachable")
    }
}
