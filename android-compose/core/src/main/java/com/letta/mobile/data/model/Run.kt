package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RunRequestConfig(
    @SerialName("assistant_message_tool_kwarg") val assistantMessageToolKwarg: String? = null,
    @SerialName("assistant_message_tool_name") val assistantMessageToolName: String? = null,
    @SerialName("include_return_message_types") val includeReturnMessageTypes: List<String>? = null,
    @SerialName("use_assistant_message") val useAssistantMessage: Boolean? = null,
)

@Serializable
data class Run(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    val background: Boolean? = null,
    @SerialName("base_template_id") val baseTemplateId: String? = null,
    @SerialName("callback_error") val callbackError: String? = null,
    @SerialName("callback_sent_at") val callbackSentAt: String? = null,
    @SerialName("callback_status_code") val callbackStatusCode: Int? = null,
    @SerialName("callback_url") val callbackUrl: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    @SerialName("request_config") val requestConfig: RunRequestConfig? = null,
    val status: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("total_duration_ns") val totalDurationNs: Long? = null,
    @SerialName("ttft_ns") val ttftNs: Long? = null,
)

@Serializable
data class RunMetrics(
    val id: String,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("run_start_ns") val runStartNs: Long? = null,
    @SerialName("run_ns") val runNs: Long? = null,
    @SerialName("num_steps") val numSteps: Int? = null,
    @SerialName("tools_used") val toolsUsed: List<String> = emptyList(),
    @SerialName("base_template_id") val baseTemplateId: String? = null,
)

@Serializable
data class RunStep(
    val id: String,
    val origin: String? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("provider_category") val providerCategory: String? = null,
    val model: String? = null,
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("trace_id") val traceId: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("error_type") val errorType: String? = null,
    val status: String? = null,
)

@Serializable
data class RunCancelParams(
    @SerialName("run_ids") val runIds: List<String>,
)

@Serializable
data class RunListParams(
    val active: Boolean? = null,
    val after: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("agent_ids") val agentIds: List<String>? = null,
    val ascending: Boolean? = null,
    val background: Boolean? = null,
    val before: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val limit: Int? = null,
    val order: String? = null,
    @SerialName("order_by") val orderBy: String? = null,
    val statuses: List<String>? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)
