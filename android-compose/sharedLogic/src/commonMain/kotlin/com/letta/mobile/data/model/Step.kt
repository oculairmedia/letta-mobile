package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Step(
    val id: String,
    val origin: String? = null,
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("provider_id") val providerId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("provider_category") val providerCategory: String? = null,
    val model: String? = null,
    @SerialName("model_endpoint") val modelEndpoint: String? = null,
    @SerialName("context_window_limit") val contextWindowLimit: Int? = null,
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: Map<String, JsonElement> = emptyMap(),
    @SerialName("trace_id") val traceId: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    val tags: List<String> = emptyList(),
    val tid: String? = null,
    val messages: List<LettaMessage> = emptyList(),
    val feedback: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("error_type") val errorType: String? = null,
    @SerialName("error_data") val errorData: Map<String, JsonElement> = emptyMap(),
    val status: String? = null,
)

@Serializable
data class StepMetrics(
    val id: String,
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("provider_id") val providerId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("step_start_ns") val stepStartNs: Long? = null,
    @SerialName("llm_request_start_ns") val llmRequestStartNs: Long? = null,
    @SerialName("llm_request_ns") val llmRequestNs: Long? = null,
    @SerialName("tool_execution_ns") val toolExecutionNs: Long? = null,
    @SerialName("step_ns") val stepNs: Long? = null,
    @SerialName("base_template_id") val baseTemplateId: String? = null,
    @SerialName("template_id") val templateId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
)

@Serializable
data class ProviderTrace(
    val id: String,
    @SerialName("request_json") val requestJson: Map<String, JsonElement> = emptyMap(),
    @SerialName("response_json") val responseJson: Map<String, JsonElement> = emptyMap(),
    @SerialName("step_id") val stepId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class StepListParams(
    val before: String? = null,
    val after: String? = null,
    val limit: Int? = null,
    val order: String? = null,
    @SerialName("order_by") val orderBy: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val model: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("trace_ids") val traceIds: List<String>? = null,
    val feedback: String? = null,
    @SerialName("has_feedback") val hasFeedback: Boolean? = null,
    val tags: List<String>? = null,
    @SerialName("project_id") val projectId: String? = null,
)

@Serializable
data class StepFeedbackUpdateParams(
    val feedback: String? = null,
    val tags: List<String>? = null,
)
