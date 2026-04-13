package com.letta.mobile.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: String,
    val isReasoning: Boolean = false,
    val toolCalls: List<UiToolCall>? = null,
    val generatedUi: UiGeneratedComponent? = null,
    val approvalRequest: UiApprovalRequest? = null,
    val approvalResponse: UiApprovalResponse? = null,
)

@Immutable
data class UiToolCall(
    val name: String,
    val arguments: String,
    val result: String?,
    val status: String? = null,
)

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
