package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
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
        if (message.messageType != MessageType.APPROVAL_RESPONSE) continue
        val response = message.approvalResponse ?: continue
        val topLevelReason = response.reason?.takeIf(String::isNotBlank)
        for (approval in response.approvals) {
            val toolCallId = approval.toolCallId.takeIf(String::isNotBlank) ?: continue
            if (toolCallId !in renderedToolCallIds) continue
            val approved = approval.approved ?: continue
            put(
                toolCallId,
                FoldedToolApproval(
                    decision = if (approved) {
                        UiToolApprovalDecision.Approved
                    } else {
                        UiToolApprovalDecision.Rejected
                    },
                    carriedReason = !approval.reason.isNullOrBlank() || topLevelReason != null,
                    sourceMessageId = message.id,
                ),
            )
        }
    }
}

internal fun List<AppMessage>.fullyAbsorbedApprovalResponseIds(
    foldedApprovals: Map<String, FoldedToolApproval>,
    renderedToolCallIds: Set<String>,
): Set<String> {
    val foldedByResponseId = foldedApprovals.values.groupBy(FoldedToolApproval::sourceMessageId)
    return buildSet {
        for (message in this@fullyAbsorbedApprovalResponseIds) {
            val response = message.approvalResponse
            val folded = foldedByResponseId[message.id].orEmpty()
            if (message.messageType != MessageType.APPROVAL_RESPONSE || response == null || folded.isEmpty()) continue
            val explicit = response.approvals.filter { !it.toolCallId.isNullOrBlank() && it.approved != null }
            val canAbsorb = explicit.isNotEmpty() &&
                explicit.all { it.toolCallId in renderedToolCallIds } &&
                folded.size == explicit.size &&
                folded.none(FoldedToolApproval::carriedReason) &&
                response.reason.isNullOrBlank() &&
                folded.none { it.decision == UiToolApprovalDecision.Rejected }
            if (canAbsorb) add(message.id)
        }
    }
}

internal fun AppMessage.hasExplicitApprovalDecision(): Boolean {
    val response = approvalResponse ?: return false
    return response.approved != null || response.approvals.any { it.approved != null }
}
