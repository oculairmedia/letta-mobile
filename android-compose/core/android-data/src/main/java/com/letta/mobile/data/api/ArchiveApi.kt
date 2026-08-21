package com.letta.mobile.data.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveUpdateParams
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class ArchiveApi @Inject constructor(
    private val apiClient: LettaApiClient,
) : com.letta.mobile.data.repository.api.ArchiveRemoteSource {
    open override suspend fun listArchives(
        before: String?,
        after: String?,
        limit: Int?,
        order: String?,
        name: String?,
        agentId: String?,
    ): List<Archive> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/archives/") {
            parameter("before", before)
            parameter("after", after)
            parameter("limit", limit)
            parameter("order", order)
            parameter("name", name)
            parameter("agent_id", agentId)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun retrieveArchive(archiveId: String): Archive {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/archives/$archiveId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun createArchive(params: ArchiveCreateParams): Archive {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/archives/") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun updateArchive(archiveId: String, params: ArchiveUpdateParams): Archive {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/archives/$archiveId") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun deleteArchive(archiveId: String): Archive {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/archives/$archiveId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun listAgentsForArchive(
        archiveId: String,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
    ): List<Agent> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/archives/$archiveId/agents") {
            parameter("limit", limit)
            parameter("before", before)
            parameter("after", after)
            parameter("order", order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun deletePassageFromArchive(archiveId: String, passageId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/archives/$archiveId/passages/$passageId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
