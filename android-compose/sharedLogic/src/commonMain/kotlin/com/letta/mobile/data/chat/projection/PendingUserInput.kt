package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
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
 *
 * letta-mobile-bzvro.40: a message that shows the call only as a plain tool row (no approval
 * request) gets one built from the parked request when [details] names one of its calls. That is
 * how an Iroh client, whose own engine never sees the host's control requests, draws the card: the
 * host's `pending_approvals` snapshot is the only thing that says the call waits on the person.
 */
fun withPendingApprovalDetails(
    messages: List<UiMessage>,
    details: Map<String, PendingApprovalDetails>,
): List<UiMessage> {
    if (details.isEmpty()) return messages
    var changed = false
    val joined = messages.map { message ->
        val next = message.joinedWith(details)
        if (next !== message) changed = true
        next
    }
    return if (changed) joined else messages
}

/**
 * letta-mobile-bzvro.40: [state] with its messages joined to [details]; the same state when nothing
 * joins. A join rewrites rows anywhere in the list, so it is reported as a full change.
 */
fun ChatUiState.withPendingApprovalDetails(details: Map<String, PendingApprovalDetails>): ChatUiState {
    val joined = withPendingApprovalDetails(messages, details)
    if (joined === messages) return this
    return copy(messages = joined.toImmutableList(), messageListChange = ChatMessageListChange.Full)
}

/** letta-mobile-bzvro.14: [this] marking [queued] approvals as waiting in the outbox; the same state when unchanged. */
fun ChatUiState.withQueuedApprovals(queued: Set<String>): ChatUiState =
    if (queued == queuedApprovalRequestIds) this else copy(queuedApprovalRequestIds = queued.toImmutableSet())

private fun UiMessage.joinedWith(details: Map<String, PendingApprovalDetails>): UiMessage {
    val request = approvalRequest ?: return withParkedToolCall(details)
    val match = request.toolCalls.firstNotNullOfOrNull { details[it.toolCallId] }
    // Parked details are immutable per control request, so the approval id is their identity:
    // comparing it (not the whole data class) keeps this per-frame join from walking diff text.
    if (match == null || match.isCurrentIn(request)) return this
    return copy(approvalRequest = request.copy(details = match))
}

/** A plain tool row whose call is parked: the request the card answers, named by the control request's own id. */
private fun UiMessage.withParkedToolCall(details: Map<String, PendingApprovalDetails>): UiMessage {
    if (approvalResponse != null) return this
    val calls = toolCalls ?: return this
    val (parkedCall, parked) = calls.firstNotNullOfOrNull { call ->
        call.toolCallId?.let(details::get)?.takeIf { call.result == null }?.let { call to it }
    } ?: return this
    val request = UiApprovalRequest(
        requestId = parked.approvalId,
        toolCalls = listOf(UiApprovalToolCall(toolCallId = parked.toolCallId, name = parkedCall.name, arguments = parkedCall.arguments)),
        details = parked,
    )
    return copy(approvalRequest = request)
}
