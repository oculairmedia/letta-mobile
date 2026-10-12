package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.chat.approval.OutboxApproval
import com.letta.mobile.data.chat.approval.OutboxTarget
import com.letta.mobile.data.chat.runtime.AlwaysAllowAnswer
import com.letta.mobile.data.chat.send.lettaWireJson
import com.letta.mobile.data.model.ApprovalCreate
import com.letta.mobile.data.model.ApprovalSubmission
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.runtime.ApprovalBinding
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
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

    /** letta-mobile-bzvro.14: an outbox entry as the host takes it (an always-allow when it carries rule ids). */
    fun paramsFor(entry: OutboxApproval): JsonObject {
        val approval = if (entry.selectedSuggestionIds.isEmpty()) {
            decision(entry.requestId, entry.toolCallIds.firstOrNull(), entry.approve, entry.reason)
        } else {
            alwaysAllow(ApprovalBinding(entry.key, entry.requestId), entry.selectedSuggestionIds)
        }
        return params(entry.agentId, entry.conversationId, approval)
    }

    /** letta-mobile-bzvro.14: an always-allow as an outbox entry, keyed by the call it answers. */
    fun outboxEntry(answer: AlwaysAllowAnswer): OutboxApproval = OutboxApproval.of(
        OutboxTarget(answer.agentId, answer.conversationId),
        ChatApprovalAnswer(
            requestId = answer.binding.approvalId,
            toolCallIds = listOf(answer.binding.toolCallId),
            approve = true,
            reason = null,
            selectedSuggestionIds = answer.suggestionIds,
            suggestionBinding = answer.binding,
        ),
    )

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
