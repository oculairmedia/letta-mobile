package com.letta.mobile.data.api

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RunApi @Inject constructor(
    private val apiClient: LettaApiClient,
) {
    suspend fun listRuns(params: RunListParams = RunListParams()): List<Run> {
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

    suspend fun retrieveRun(runId: String): Run {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/runs/$runId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }
}
