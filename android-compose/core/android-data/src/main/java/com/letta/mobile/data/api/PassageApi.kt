package com.letta.mobile.data.api

import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.PassageCreateParams
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
class PassageApi @Inject constructor(
    private val apiClient: LettaApiClient
) : com.letta.mobile.data.repository.api.PassageRemoteSource {
    override suspend fun listPassages(
        agentId: String,
        limit: Int?,
        after: String?,
        search: String?,
    ): List<Passage> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents/$agentId/archival-memory") {
            parameter("limit", limit)
            parameter("after", after)
            parameter("search", search)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    override suspend fun createPassage(agentId: String, params: PassageCreateParams): Passage {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/agents/$agentId/archival-memory") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        val createdPassages: List<Passage> = response.body()
        return when (createdPassages.size) {
            1 -> createdPassages.single()
            0 -> throw ApiException(response.status.value, "Archival memory create returned no passages")
            else -> throw ApiException(
                response.status.value,
                "Archival memory create returned ${createdPassages.size} passages; expected exactly one"
            )
        }
    }

    override suspend fun deletePassage(agentId: String, passageId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/agents/$agentId/archival-memory/$passageId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    override suspend fun searchArchival(
        agentId: String,
        query: String,
        limit: Int?,
    ): List<Passage> {
        return listPassages(agentId = agentId, limit = limit, search = query)
    }
}
