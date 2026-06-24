package com.letta.mobile.data.api

import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleListResponse
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.schedules.CronTask
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
open class ScheduleApi @Inject constructor(
    private val apiClient: LettaApiClient,
) {
    open suspend fun listSchedules(
        agentId: String,
        limit: Int? = null,
        after: String? = null,
    ): ScheduleListResponse {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents/$agentId/schedule") {
            parameter("limit", limit?.toString())
            parameter("after", after)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents/$agentId/schedule/$scheduledMessageId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/agents/$agentId/schedule") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/agents/$agentId/schedule/$scheduledMessageId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    /**
     * List cron tasks from the backend's `/v1/crons` route. This is the
     * cron-backed fallback for self-hosted / admin-shim servers that don't
     * serve the Letta-native `/v1/agents/{id}/schedule` admin route (it
     * 404s there). Parity with the desktop schedules surface, which reads
     * the same route via `CronApi`. Optionally scoped to [agentId].
     */
    open suspend fun listCrons(agentId: String? = null): List<CronTask> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/crons")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        val tasks = response.body<CronsResponse>().tasks
        return if (agentId == null) {
            tasks
        } else {
            tasks.filter { it.agentId == null || it.agentId == agentId }
        }
    }
}

@kotlinx.serialization.Serializable
private data class CronsResponse(
    val tasks: List<CronTask> = emptyList(),
)
