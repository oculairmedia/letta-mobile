package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApprovalRequest(
    @SerialName("tool_calls") val toolCalls: List<ToolCall>,
)

@Serializable
data class ApprovalResponse(
    val approvals: List<ApprovalReturn>,
    val approve: Boolean? = null,
    @SerialName("approval_request_id") val approvalRequestId: String? = null,
    val reason: String? = null,
)

@Serializable
data class ApprovalReturn(
    @SerialName("tool_call_id") val toolCallId: String,
    val approve: Boolean? = null,
    val reason: String? = null,
    val type: String = "approval",
    @SerialName("tool_return") val toolReturn: String? = null,
    val status: String? = null,
    val stdout: List<String>? = null,
    val stderr: List<String>? = null,
)
