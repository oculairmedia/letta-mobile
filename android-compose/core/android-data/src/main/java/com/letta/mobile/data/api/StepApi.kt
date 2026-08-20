package com.letta.mobile.data.api

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.StepMetrics
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class StepApi @Inject constructor(
    private val apiClient: LettaApiClient,
) {
    open suspend fun listSteps(params: StepListParams = StepListParams()): List<Step> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/steps/") {
            parameter("before", params.before)
            parameter("after", params.after)
            parameter("limit", params.limit)
            parameter("order", params.order)
            parameter("order_by", params.orderBy)
            parameter("start_date", params.startDate)
            parameter("end_date", params.endDate)
            parameter("model", params.model)
            parameter("agent_id", params.agentId)
            params.traceIds?.forEach { parameter("trace_ids", it) }
            parameter("feedback", params.feedback)
            parameter("has_feedback", params.hasFeedback)
            params.tags?.forEach { parameter("tags", it) }
            parameter("project_id", params.projectId)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveStep(stepId: String): Step {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/steps/$stepId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveStepMetrics(stepId: String): StepMetrics {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/steps/$stepId/metrics")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveStepTrace(stepId: String): ProviderTrace? {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/steps/$stepId/trace")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listStepMessages(
        stepId: String,
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
    ): List<LettaMessage> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/steps/$stepId/messages") {
            parameter("before", before)
            parameter("after", after)
            parameter("limit", limit)
            parameter("order", order)
            parameter("order_by", "created_at")
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun updateStepFeedback(stepId: String, params: StepFeedbackUpdateParams): Step {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/steps/$stepId/feedback") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }
}
