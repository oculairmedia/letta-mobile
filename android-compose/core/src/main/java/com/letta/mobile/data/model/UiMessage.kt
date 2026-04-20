package com.letta.mobile.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: String,
    /**
     * Server run id for assistant-side messages that belong to a run.
     * Null for user messages, which trigger runs rather than belonging to one,
     * and for older hydrated history that predates run tracking.
     */
    val runId: String? = null,
    /**
     * Server step id for assistant-side messages that belong to a run step.
     * Null for user messages and older history that predates step tracking.
     */
    val stepId: String? = null,
    val isPending: Boolean = false,
    val isReasoning: Boolean = false,
    val toolCalls: List<UiToolCall>? = null,
    val generatedUi: UiGeneratedComponent? = null,
    val approvalRequest: UiApprovalRequest? = null,
    val approvalResponse: UiApprovalResponse? = null,
    /**
     * Image attachments rendered as thumbnails in the bubble. Populated for
     * outgoing user messages that carried attachments through the Timeline
     * send path, and — since letta-mobile-mge5.24 — for USER/ASSISTANT
     * messages hydrated from server history whose `content` is a multimodal
     * JSON array containing inline image parts.
     */
    val attachments: List<UiImageAttachment> = emptyList(),
)

@Immutable
data class UiImageAttachment(
    val base64: String,
    val mediaType: String,
)

@Immutable
data class UiToolCall(
    val name: String,
    val arguments: String,
    val result: String?,
    val status: String? = null,
    /**
     * Folded-in approval outcome for this specific tool call, when the mapper
     * absorbed a bare `approve=true` / `approve=false` `APPROVAL_RESPONSE`
     * into the owning tool-call bubble instead of emitting a standalone
     * "Approved" / "Rejected" card (letta-mobile-23h5).
     *
     * `null` means no decision is attached — either because the call didn't
     * need approval, because the response carried a reason (in which case the
     * standalone card is retained so the note is visible), or because the
     * decision hasn't arrived yet.
     */
    val approvalDecision: UiToolApprovalDecision? = null,
)

/**
 * Compact, inline representation of an approval decision for display on the
 * tool-call card header (see `ChatMessageComponents.ToolCallCard`).
 */
@Immutable
enum class UiToolApprovalDecision {
    Approved,
    Rejected,
}

@Immutable
data class UiGeneratedComponent(
    val name: String,
    val propsJson: String,
    val fallbackText: String? = null,
)

@Immutable
data class UiApprovalRequest(
    val requestId: String,
    val toolCalls: List<UiApprovalToolCall>,
)

@Immutable
data class UiApprovalToolCall(
    val toolCallId: String,
    val name: String,
    val arguments: String,
)

@Immutable
data class UiApprovalResponse(
    val requestId: String? = null,
    val approved: Boolean? = null,
    val reason: String? = null,
    val approvals: List<UiApprovalDecision> = emptyList(),
)

@Immutable
data class UiApprovalDecision(
    val toolCallId: String,
    val approved: Boolean? = null,
    val status: String? = null,
    val reason: String? = null,
)
