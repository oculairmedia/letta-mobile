package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.client.request.forms.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class AgentApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    open suspend fun listAgents(
        limit: Int? = null,
        offset: Int? = null,
        tags: List<String>? = null
    ): List<Agent> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents") {
            parameter("limit", limit)
            parameter("offset", offset)
            tags?.forEach { parameter("tags", it) }
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun getAgent(agentId: AgentId): Agent {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents/${agentId.value}")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun getAgent(agentId: String): Agent = getAgent(AgentId(agentId))

    open suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId? = null): ContextWindowOverview {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents/${agentId.value}/context") {
            parameter("conversation_id", conversationId?.value)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun getContextWindow(agentId: String, conversationId: String? = null): ContextWindowOverview =
        getContextWindow(AgentId(agentId), conversationId?.let(::ConversationId))

    open suspend fun countAgents(): Int {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents/count")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun createAgent(params: AgentCreateParams): Agent {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/agents") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun updateAgent(agentId: AgentId, params: AgentUpdateParams): Agent {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/${agentId.value}") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun updateAgent(agentId: String, params: AgentUpdateParams): Agent = updateAgent(AgentId(agentId), params)

    open suspend fun deleteAgent(agentId: AgentId) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/agents/${agentId.value}")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun deleteAgent(agentId: String) = deleteAgent(AgentId(agentId))

    open suspend fun exportAgent(agentId: AgentId): String {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents/${agentId.value}/export")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun exportAgent(agentId: String): String = exportAgent(AgentId(agentId))

    open suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String? = null,
        overrideExistingTools: Boolean? = null,
        projectId: ProjectId? = null,
        stripMessages: Boolean? = null,
    ): ImportedAgentsResponse {
        val (client, baseUrl) = apiClient.session()

        val response = client.submitFormWithBinaryData(
            url = "$baseUrl/v1/agents/import",
            formData = formData {
                append("file", fileBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                })
                overrideName?.let { append("override_name", it) }
                overrideExistingTools?.let { append("override_existing_tools", it.toString()) }
                projectId?.let { append("project_id", it.value) }
                stripMessages?.let { append("strip_messages", it.toString()) }
            }
        )
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun attachArchive(agentId: AgentId, archiveId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/${agentId.value}/archives/attach/$archiveId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun attachArchive(agentId: String, archiveId: String) = attachArchive(AgentId(agentId), archiveId)

    open suspend fun detachArchive(agentId: AgentId, archiveId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/${agentId.value}/archives/detach/$archiveId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun detachArchive(agentId: String, archiveId: String) = detachArchive(AgentId(agentId), archiveId)
}
