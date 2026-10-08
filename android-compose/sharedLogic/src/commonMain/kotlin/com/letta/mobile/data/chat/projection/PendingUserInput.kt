package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
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
 * user-input tool (AskUserQuestion). Every other request (a Bash call under approve-all, say) is
 * resolved by the runtime, even while the projection still holds it as undecided because its tool
 * has not returned yet, so it must render as its plain tool row, never as an approval card. The
 * legacy Android chat applied the same gate (ChatApprovals.requiresUserInput).
 */
fun UiApprovalRequest.requiresUserInput(): Boolean =
    toolCalls.any { RuntimeUserInputTools.requiresUserInput(it.name) }
