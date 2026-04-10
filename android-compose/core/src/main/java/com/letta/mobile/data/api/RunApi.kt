package com.letta.mobile.data.api

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunCancelParams
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.RunStep
import com.letta.mobile.data.model.UsageStatistics
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class RunApi @Inject constructor(
    private val apiClient: LettaApiClient,
) {
    open suspend fun listRuns(params: RunListParams = RunListParams()): List<Run> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/runs/") {
            parameter("active", params.active)
            parameter("after", params.after)
            parameter("agent_id", params.agentId)
            params.agentIds?.forEach { parameter("agent_ids", it) }
            parameter("ascending", params.ascending)
            parameter("background", params.background)
            parameter("before", params.before)
            parameter("conversation_id", params.conversationId)
            parameter("limit", params.limit)
            parameter("order", params.order)
            parameter("order_by", params.orderBy)
            params.statuses?.forEach { parameter("statuses", it) }
            parameter("stop_reason", params.stopReason)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveRun(runId: String): Run {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/runs/$runId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listRunMessages(
        runId: String,
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
    ): List<LettaMessage> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/runs/$runId/messages") {
            parameter("before", before)
            parameter("after", after)
            parameter("limit", limit)
            parameter("order", order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveRunUsage(runId: String): UsageStatistics {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/runs/$runId/usage")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveRunMetrics(runId: String): RunMetrics {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/runs/$runId/metrics")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listRunSteps(
        runId: String,
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
    ): List<RunStep> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/runs/$runId/steps") {
            parameter("before", before)
            parameter("after", after)
            parameter("limit", limit)
            parameter("order", order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun cancelRun(agentId: String, runId: String): Map<String, String> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/agents/$agentId/messages/cancel") {
            contentType(ContentType.Application.Json)
            setBody(RunCancelParams(runIds = listOf(runId)))
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun deleteRun(runId: String) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.delete("$baseUrl/v1/runs/$runId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
