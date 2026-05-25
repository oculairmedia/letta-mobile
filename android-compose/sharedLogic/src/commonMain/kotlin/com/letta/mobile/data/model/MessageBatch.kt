package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BatchMessageRequest(
    @SerialName("agent_id") val agentId: String,
    val messages: List<JsonElement>,
    @SerialName("max_steps") val maxSteps: Int? = null,
    @SerialName("use_assistant_message") val useAssistantMessage: Boolean? = null,
    @SerialName("assistant_message_tool_name") val assistantMessageToolName: String? = null,
    @SerialName("assistant_message_tool_kwarg") val assistantMessageToolKwarg: String? = null,
    @SerialName("include_return_message_types") val includeReturnMessageTypes: List<String>? = null,
    @SerialName("enable_thinking") val enableThinking: Boolean? = null,
)

@Serializable
data class CreateBatchMessagesRequest(
    val requests: List<BatchMessageRequest>,
    @SerialName("callback_url") val callbackUrl: String? = null,
)

@Serializable
data class BatchMessage(
    val id: String,
    @SerialName("agent_id") val agentId: String? = null,
    val model: String? = null,
    val role: String? = null,
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("step_id") val stepId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val otid: String? = null,
    @SerialName("tool_returns") val toolReturns: JsonElement? = null,
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("batch_item_id") val batchItemId: String? = null,
    @SerialName("is_err") val isErr: Boolean? = null,
    @SerialName("approval_request_id") val approvalRequestId: String? = null,
    val approve: Boolean? = null,
    @SerialName("denial_reason") val denialReason: String? = null,
    val approvals: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class BatchMessagesResponse(
    val messages: List<BatchMessage> = emptyList(),
)
