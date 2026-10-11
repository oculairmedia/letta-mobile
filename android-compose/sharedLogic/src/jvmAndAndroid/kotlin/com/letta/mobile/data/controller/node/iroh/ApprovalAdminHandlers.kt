package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.ApprovalSubmitResult
import com.letta.mobile.data.controller.ControllerApprovalAnswer
import com.letta.mobile.data.runtime.ApprovalRelay
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
        // letta-mobile-bzvro.40: this host pushes parked approvals and honours always-allow rule ids.
        if (controller != null) router.featureCapabilities += ApprovalRelay.CAPABILITY
    }

    private suspend fun submit(
        params: JsonObject?,
        controller: AppServerController?,
    ): JsonElement {
        val request = SubmittedApproval.from(params)
        if (controller == null) {
            selected(outcome = "unavailable", reason = "no_controller")
            adminError("capability_unavailable: approval.submit requires a live App Server controller")
        }
        val convId = request.conversationId?.let(::ConversationId)
            ?: throw IllegalArgumentException("conversation_id required")
        val result = try {
            // After wrapper restart the in-memory runtime cache is empty even though
            // App Server still owns the parked approval. Reattach before submit.
            controller.startRuntime(agentId = request.agentId, conversationId = convId, recoverApprovals = true)
            controller.submitApproval(request.answer(convId))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            selected(outcome = "error", reason = error.message ?: error::class.simpleName ?: "error")
            adminError("app_server_error: approval.submit failed")
        }
        return handleApprovalSubmitResult(result, request.approve)
    }

    private fun selected(outcome: String, reason: String? = null) {
        AdminRouteTelemetry.selected(
            AdminRouteTelemetry.Selection(
                method = "approval.submit",
                owner = "controller_native",
                route = "controller_native",
                outcome = outcome,
                reason = reason,
            ),
        )
    }

    /** One `approval.submit` request, validated: the approval message of its message-create payload. */
    private class SubmittedApproval(
        val agentId: AgentId,
        val conversationId: String?,
        private val approval: JsonObject,
    ) {
        private val firstDecision: JsonObject? = approval["approvals"]?.jsonArray?.firstOrNull()?.jsonObject
        val approvalRequestId: String = approval.text("approval_request_id")
            ?: throw IllegalArgumentException("approval_request_id required")
        val approve: Boolean = (approval["approve"] ?: firstDecision?.get("approve"))?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("approval decision required")
        private val reason: String? = approval.text("reason") ?: firstDecision?.text("reason")

        fun answer(conversationId: ConversationId) = ControllerApprovalAnswer(
            agentId = agentId,
            conversationId = conversationId,
            approvalRequestId = approvalRequestId,
            approve = approve,
            reason = reason,
            toolCallId = toolCallIds().firstOrNull(),
            updatedInput = approval["updated_input"]?.jsonObject
                ?: com.letta.mobile.data.model.AskUserQuestion.decodeAnswerReason(reason),
            selectedSuggestionIds = selectedSuggestionIds(approval),
        )

        private fun toolCallIds(): List<String> =
            approval["approvals"]?.jsonArray?.mapNotNull { it.jsonObject.text("tool_call_id") }.orEmpty()

        companion object {
            fun from(params: JsonObject?): SubmittedApproval {
                val agentId = params.requireParam(AdminParamKey("agent_id"))
                val payload = params?.get("payload")?.jsonObject ?: throw IllegalArgumentException("payload required")
                val approval = payload["messages"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: throw IllegalArgumentException("approval message required")
                return SubmittedApproval(AgentId(agentId), param(params, AdminParamKey("conversation_id")), approval)
            }

            private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
        }
    }

    /**
     * letta-mobile-bzvro.40: the always-allow rule ids, on the message or on its first decision. Only
     * an approval carries them; the controller checks them against the request it parked.
     */
    private fun selectedSuggestionIds(approval: JsonObject): List<String> {
        val ids = approval[SELECTED_SUGGESTIONS] ?: approval["approvals"]?.jsonArray?.firstOrNull()?.jsonObject?.get(SELECTED_SUGGESTIONS)
        return (ids as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            .take(MAX_SELECTED_SUGGESTIONS)
    }

    private const val SELECTED_SUGGESTIONS = "selected_permission_suggestion_ids"
    private const val MAX_SELECTED_SUGGESTIONS = 10

    private fun handleApprovalSubmitResult(
        result: ApprovalSubmitResult,
        approve: Boolean,
    ): kotlinx.serialization.json.JsonObject {
        // letta-mobile-qygvv.5: the server refused the decision (e.g. the gate is
        // no longer pending). Fail the RPC so the phone shows it instead of silence.
        if (result is ApprovalSubmitResult.Rejected) {
            selected(outcome = "rejected", reason = result.error)
            adminError("approval_rejected: ${result.error}")
        }
        selected(outcome = "success")
        return buildJsonObject {
            put("status", if (approve) "approved" else "denied")
            put("acknowledged", result == ApprovalSubmitResult.Accepted)
        }
    }
}
