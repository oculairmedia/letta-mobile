package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.chat.send.lettaWireJson
import com.letta.mobile.data.model.ApprovalCreate
import com.letta.mobile.data.model.ApprovalSubmission
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.runtime.ApprovalBinding
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `approval.submit` admin_rpc params an Iroh client sends (the host's ApprovalAdminHandlers reads
 * them): the approval message, wrapped as the message-create payload the handler has always taken.
 */
internal object IrohApprovalSubmitBody {
    /** An approve/deny; an AskUserQuestion answer encoded in [reason] becomes the structured `updated_input`. */
    fun decision(approvalRequestId: String, toolCallId: String?, approve: Boolean, reason: String?): ApprovalCreate {
        val decoded = if (approve) AskUserQuestion.decodeAnswerReason(reason) else null
        val effectiveReason = if (decoded != null) null else reason?.takeIf { it.isNotBlank() }
        return ApprovalCreate(
            approvals = toolCallId?.let {
                listOf(ApprovalSubmission(toolCallId = it, approve = approve, reason = effectiveReason))
            },
            approve = approve,
            approvalRequestId = approvalRequestId,
            reason = effectiveReason,
            updatedInput = decoded,
        )
    }

    /** letta-mobile-bzvro.40: an approval that also persists the server-offered rules [suggestionIds]. */
    fun alwaysAllow(binding: ApprovalBinding, suggestionIds: List<String>): ApprovalCreate =
        decision(binding.approvalId, binding.toolCallId, approve = true, reason = null)
            .copy(selectedPermissionSuggestionIds = suggestionIds)

    fun params(agentId: String, conversationId: String, approval: ApprovalCreate): JsonObject {
        val payload = MessageCreateRequest(
            messages = listOf(lettaWireJson.encodeToJsonElement(ApprovalCreate.serializer(), approval)),
            streaming = false,
        )
        return buildJsonObject {
            put("agent_id", agentId)
            conversationId.takeIf { it.isNotBlank() }?.let { put("conversation_id", it) }
            put("payload", lettaWireJson.encodeToJsonElement(MessageCreateRequest.serializer(), payload))
        }
    }
}
