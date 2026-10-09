package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.runtime.RuntimeUserInputTools

/**
 * letta-mobile-bglj6.1.22: the approval in [messages] (oldest first) that waits on the person, or
 * null: the newest request carrying a runtime user-input tool (AskUserQuestion) that no later
 * response answers. A later response answers it when it names the same request, or names none.
 *
 * Every other approval is resolved by the runtime, so only these need an answer from wherever the
 * conversation is shown (the Touch canvas answers them without opening the full page).
 */
fun pendingUserInputApproval(messages: List<UiMessage>): UiApprovalRequest? {
    val index = messages.indexOfLast { it.approvalRequest?.requiresUserInput() == true }
    if (index < 0) return null
    val request = messages[index].approvalRequest ?: return null
    val answered = (index + 1 until messages.size).any { later ->
        val response = messages[later].approvalResponse ?: return@any false
        response.requestId == null || response.requestId == request.requestId
    }
    return request.takeUnless { answered }
}

/**
 * letta-mobile-bglj6.1.25: whether this request waits on the person: it carries a runtime
 * user-input tool (AskUserQuestion), or (letta-mobile-bzvro.11) the server's `can_use_tool`
 * control request for it is parked, which only happens under a permission mode that does not
 * approve everything. Every other request (a Bash call under approve-all, say) is resolved by the
 * runtime, even while the projection still holds it as undecided because its tool has not
 * returned yet, so it must render as its plain tool row, never as an approval card. The legacy
 * Android chat applied the same gate (ChatApprovals.requiresUserInput).
 */
fun UiApprovalRequest.requiresUserInput(): Boolean =
    details != null || toolCalls.any { RuntimeUserInputTools.requiresUserInput(it.name) }

private fun PendingApprovalDetails.isCurrentIn(request: UiApprovalRequest): Boolean =
    this === request.details || sameRequestAs(request.details)

private fun PendingApprovalDetails.sameRequestAs(other: PendingApprovalDetails?): Boolean =
    approvalId == other?.approvalId && toolCallId == other?.toolCallId

/**
 * [messages] with each approval request joined to the parked control request that [details]
 * (by tool call id) holds for it. Returns the same list when nothing joins, so an idle
 * conversation keeps its list identity.
 */
fun withPendingApprovalDetails(
    messages: List<UiMessage>,
    details: Map<String, PendingApprovalDetails>,
): List<UiMessage> {
    if (details.isEmpty()) return messages
    var changed = false
    val joined = messages.map { message ->
        val request = message.approvalRequest ?: return@map message
        val match = request.toolCalls.firstNotNullOfOrNull { details[it.toolCallId] }
        // Parked details are immutable per control request, so the approval id is their identity:
        // comparing it (not the whole data class) keeps this per-frame join from walking diff text.
        if (match == null || match.isCurrentIn(request)) {
            message
        } else {
            changed = true
            message.copy(approvalRequest = request.copy(details = match))
        }
    }
    return if (changed) joined else messages
}
