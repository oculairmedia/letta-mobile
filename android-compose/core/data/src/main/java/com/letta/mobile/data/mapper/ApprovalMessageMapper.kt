package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ApprovalDecisionPayload
import com.letta.mobile.data.model.ApprovalResponsePayload
import com.letta.mobile.data.model.ApprovalToolCallPayload
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.UiToolApprovalDecision

internal data class FoldedToolApproval(
    val decision: UiToolApprovalDecision,
    val carriedReason: Boolean,
    val sourceMessageId: String,
)

internal fun List<AppMessage>.renderedToolCallIds(): Set<String> = buildSet {
    for (message in this@renderedToolCallIds) {
        if (message.messageType == MessageType.TOOL_CALL || message.messageType == MessageType.TOOL_RETURN) {
            message.toolCallId?.takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

/**
 * Tool call ids that already have a `TOOL_RETURN`, i.e. the tool ran to completion.
 *
 * A returned tool call is proof that its approval was resolved: the runtime only
 * executes a gated call after a decision. This is deliberately derived from
 * message data rather than from a runtime event — `ApprovalResolved` is never
 * emitted by the server (letta.js suppresses `approval_response_message` and has
 * no `approval_resolved` signal at all), so an event-driven clear would wait
 * forever. See letta-mobile-jbui1.
 */
internal fun List<AppMessage>.returnedToolCallIds(): Set<String> = buildSet {
    for (message in this@returnedToolCallIds) {
        if (message.messageType == MessageType.TOOL_RETURN) {
            message.toolCallId?.takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

/**
 * Ids of `APPROVAL_REQUEST` messages whose every tool call has already returned,
 * so the request card is stale and must not render.
 *
 * Fail-open by construction: a request with no usable tool calls, or with any call
 * still outstanding, is NOT absorbed. Hiding a genuinely pending approval would
 * strand the turn with no way for the user to answer, which is strictly worse than
 * showing one stale card.
 */
internal fun List<AppMessage>.resolvedApprovalRequestIds(
    returnedToolCallIds: Set<String>,
): Set<String> = buildSet {
    for (message in this@resolvedApprovalRequestIds) {
        if (message.messageType != MessageType.APPROVAL_REQUEST) continue
        val request = message.approvalRequest ?: continue
        val callIds = request.toolCalls.map(ApprovalToolCallPayload::toolCallId)
            .filter(String::isNotBlank)
        if (callIds.isEmpty()) continue
        if (callIds.all { it in returnedToolCallIds }) add(message.id)
    }
}

internal fun List<AppMessage>.foldedApprovals(
    renderedToolCallIds: Set<String>,
): Map<String, FoldedToolApproval> = buildMap {
    for (message in this@foldedApprovals) {
        foldApprovalResponse(message, renderedToolCallIds)
    }
}

private fun MutableMap<String, FoldedToolApproval>.foldApprovalResponse(
    message: AppMessage,
    renderedToolCallIds: Set<String>,
) {
    if (message.messageType != MessageType.APPROVAL_RESPONSE) return
    val response = message.approvalResponse ?: return
    val topLevelReason = response.reason?.takeIf(String::isNotBlank)
    for (approval in response.approvals) {
        putFoldedApproval(approval, message.id, topLevelReason, renderedToolCallIds)
    }
}

private fun MutableMap<String, FoldedToolApproval>.putFoldedApproval(
    approval: ApprovalDecisionPayload,
    sourceMessageId: String,
    topLevelReason: String?,
    renderedToolCallIds: Set<String>,
) {
    val toolCallId = approval.toolCallId.takeIf(String::isNotBlank) ?: return
    if (toolCallId !in renderedToolCallIds) return
    val approved = approval.approved ?: return
    put(
        toolCallId,
        FoldedToolApproval(
            decision = toApprovalDecision(approved),
            carriedReason = !approval.reason.isNullOrBlank() || topLevelReason != null,
            sourceMessageId = sourceMessageId,
        ),
    )
}

private fun toApprovalDecision(approved: Boolean): UiToolApprovalDecision =
    if (approved) UiToolApprovalDecision.Approved else UiToolApprovalDecision.Rejected

internal fun List<AppMessage>.fullyAbsorbedApprovalResponseIds(
    foldedApprovals: Map<String, FoldedToolApproval>,
    renderedToolCallIds: Set<String>,
): Set<String> {
    val foldedByResponseId = foldedApprovals.values.groupBy(FoldedToolApproval::sourceMessageId)
    return buildSet {
        for (message in this@fullyAbsorbedApprovalResponseIds) {
            if (isFullyAbsorbed(message, foldedByResponseId, renderedToolCallIds)) {
                add(message.id)
            }
        }
    }
}

private fun isFullyAbsorbed(
    message: AppMessage,
    foldedByResponseId: Map<String, List<FoldedToolApproval>>,
    renderedToolCallIds: Set<String>,
): Boolean {
    if (message.messageType != MessageType.APPROVAL_RESPONSE) return false
    val response = message.approvalResponse ?: return false
    val folded = foldedByResponseId[message.id].orEmpty()
    if (folded.isEmpty()) return false
    return canAbsorbApprovalResponse(response, folded, renderedToolCallIds)
}

private fun canAbsorbApprovalResponse(
    response: ApprovalResponsePayload,
    folded: List<FoldedToolApproval>,
    renderedToolCallIds: Set<String>,
): Boolean {
    val explicit = response.approvals.filter { it.toolCallId.isNotBlank() && it.approved != null }
    if (explicit.isEmpty()) return false
    if (explicit.any { it.toolCallId !in renderedToolCallIds }) return false
    if (folded.size != explicit.size) return false
    if (folded.any(FoldedToolApproval::carriedReason)) return false
    if (!response.reason.isNullOrBlank()) return false
    return folded.none { it.decision == UiToolApprovalDecision.Rejected }
}

internal fun AppMessage.hasExplicitApprovalDecision(): Boolean {
    val response = approvalResponse ?: return false
    return response.approved != null || response.approvals.any { it.approved != null }
}
