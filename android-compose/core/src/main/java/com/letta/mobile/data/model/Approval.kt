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
)

@Serializable
data class ApprovalReturn(
    @SerialName("tool_call_id") val toolCallId: String,
    val approve: Boolean,
    val reason: String? = null,
    val type: String = "approval",
)
