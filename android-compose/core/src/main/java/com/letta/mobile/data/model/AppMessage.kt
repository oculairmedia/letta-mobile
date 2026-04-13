package com.letta.mobile.data.model

import java.time.Instant

/**
 * Application-level message model for UI display.
 * Simplified from the API's LettaMessage types.
 */
data class AppMessage(
    val id: String,
    val date: Instant,
    val messageType: MessageType,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolReturnStatus: String? = null,
    val generatedUi: GeneratedUiPayload? = null,
    val approvalRequest: ApprovalRequestPayload? = null,
    val approvalResponse: ApprovalResponsePayload? = null,
)

enum class MessageType {
    USER,
    ASSISTANT,
    REASONING,
    TOOL_CALL,
    TOOL_RETURN,
    APPROVAL_REQUEST,
    APPROVAL_RESPONSE,
}

data class GeneratedUiPayload(
    val component: String,
    val propsJson: String,
    val fallbackText: String? = null,
)

data class ApprovalRequestPayload(
    val requestId: String,
    val toolCalls: List<ApprovalToolCallPayload>,
)

data class ApprovalToolCallPayload(
    val toolCallId: String,
    val name: String,
    val arguments: String,
)

data class ApprovalResponsePayload(
    val requestId: String? = null,
    val approved: Boolean? = null,
    val reason: String? = null,
    val approvals: List<ApprovalDecisionPayload> = emptyList(),
)

data class ApprovalDecisionPayload(
    val toolCallId: String,
    val approved: Boolean? = null,
    val status: String? = null,
    val reason: String? = null,
)
