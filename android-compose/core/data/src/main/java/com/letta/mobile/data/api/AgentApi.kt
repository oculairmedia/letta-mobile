package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class AgentApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

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

    /**
     * Slim agents projection for picker UIs (e.g. the Schedules dropdown).
     *
     * Calls the admin-shim's opt-in `GET /v1/agents?slim=true`, which returns
     * a lightweight `[{id, name, description}]` array and skips the per-agent
     * `AgentState` synthesis that makes the default [listAgents] response
     * ~621KB for 50 agents. Deserializes into [AgentSummary] — a dedicated,
     * lenient model — NOT the heavy [Agent] (whose required fields the slim
     * payload omits).
     *
     * The default full-agent [listAgents] path is unchanged and still used by
     * screens that need full [Agent] objects (edit-agent, chat config, …).
     */
    open suspend fun listAgentsSlim(
        limit: Int? = null,
        offset: Int? = null,
        tags: List<String>? = null
    ): List<AgentSummary> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents") {
            parameter("slim", true)
            parameter("limit", limit)
            parameter("offset", offset)
            tags?.forEach { parameter("tags", it) }
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(AgentSummary.serializer()),
            response.bodyAsText(),
        )
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

        return client.prepareGet("$baseUrl/v1/agents/${agentId.value}/context") {
            parameter("conversation_id", conversationId?.value)
            parameter("mobile_safe", true)
            parameter("include_raw", false)
        }.execute { response ->
            val responseText = response.bodyAsTextAtMost(MAX_CONTEXT_WINDOW_RESPONSE_BYTES)
            if (response.status.value !in 200..299) {
                throw ApiException(response.status.value, responseText)
            }
            json.decodeFromString(ContextWindowOverview.serializer(), responseText)
        }
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

    private suspend fun HttpResponse.bodyAsTextAtMost(maxBytes: Int): String {
        val declaredLength = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxBytes) {
            throw ResponseTooLargeException(maxBytes, declaredLength)
        }

        val output = ByteArrayOutputStream(minOf(maxBytes, declaredLength?.toInt() ?: READ_BUFFER_BYTES))
        val buffer = ByteArray(READ_BUFFER_BYTES)
        val channel = bodyAsChannel()
        var totalBytes = 0

        while (true) {
            val bytesRead = channel.readAvailable(buffer, 0, minOf(buffer.size, maxBytes + 1 - totalBytes))
            if (bytesRead == -1) break
            if (bytesRead == 0) continue
            totalBytes += bytesRead
            if (totalBytes > maxBytes) {
                throw ResponseTooLargeException(maxBytes, declaredLength)
            }
            output.write(buffer, 0, bytesRead)
        }

        return output.toString(Charsets.UTF_8.name())
    }

    class ResponseTooLargeException(maxBytes: Int, declaredBytes: Long?) : ApiException(
        HttpStatusCode.PayloadTooLarge.value,
        "Context window response is too large for mobile (${declaredBytes ?: "more than $maxBytes"} bytes; cap is $maxBytes bytes)."
    )

    private companion object {
        const val MAX_CONTEXT_WINDOW_RESPONSE_BYTES = 512 * 1024
        const val READ_BUFFER_BYTES = 8 * 1024
    }
}
