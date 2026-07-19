package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ApprovalDecisionPayload
import com.letta.mobile.data.model.ApprovalResponsePayload
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
