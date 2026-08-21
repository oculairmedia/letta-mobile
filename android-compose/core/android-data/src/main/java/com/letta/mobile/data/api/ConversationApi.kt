package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class ConversationApi @Inject constructor(
    private val apiClient: LettaApiClient
) : com.letta.mobile.data.repository.api.ConversationRemoteSource {
    open override suspend fun listConversations(
        agentId: AgentId?,
        limit: Int?,
        after: String?,
        archiveStatus: String?,
        summarySearch: String?,
        order: String?,
        orderBy: String?,
    ): List<Conversation> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/conversations") {
            parameter("agent_id", agentId?.value)
            parameter("limit", limit)
            parameter("after", after)
            parameter("archive_status", archiveStatus)
            parameter("summary_search", summarySearch)
            parameter("order", order)
            parameter("order_by", orderBy)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listConversations(
        agentId: String?,
        limit: Int? = null,
        after: String? = null,
        archiveStatus: String? = null,
        summarySearch: String? = null,
        order: String? = null,
        orderBy: String? = null,
    ): List<Conversation> = listConversations(agentId?.let(::AgentId), limit, after, archiveStatus, summarySearch, order, orderBy)

    open override suspend fun getConversation(conversationId: ConversationId): Conversation {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/conversations/${conversationId.value}")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun getConversation(conversationId: String): Conversation = getConversation(ConversationId(conversationId))

    open override suspend fun createConversation(params: ConversationCreateParams): Conversation {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/conversations") {
            contentType(ContentType.Application.Json)
            parameter("agent_id", params.agentId.value)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun updateConversation(conversationId: ConversationId, params: ConversationUpdateParams): Conversation {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/conversations/${conversationId.value}") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun updateConversation(conversationId: String, params: ConversationUpdateParams): Conversation =
        updateConversation(ConversationId(conversationId), params)

    open override suspend fun deleteConversation(conversationId: ConversationId) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/conversations/${conversationId.value}")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun deleteConversation(conversationId: String) = deleteConversation(ConversationId(conversationId))

    open override suspend fun forkConversation(conversationId: ConversationId, agentId: AgentId?): Conversation {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/conversations/${conversationId.value}/fork") {
            contentType(ContentType.Application.Json)
            agentId?.let { parameter("agent_id", it.value) }
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun forkConversation(conversationId: String, agentId: String? = null): Conversation =
        forkConversation(ConversationId(conversationId), agentId?.let(::AgentId))

    open override suspend fun cancelConversation(conversationId: ConversationId, agentId: AgentId?) {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/conversations/${conversationId.value}/cancel") {
            contentType(ContentType.Application.Json)
            agentId?.let { parameter("agent_id", it.value) }
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun cancelConversation(conversationId: String, agentId: String? = null) =
        cancelConversation(ConversationId(conversationId), agentId?.let(::AgentId))

    open override suspend fun recompileConversation(
        conversationId: ConversationId,
        dryRun: Boolean,
        agentId: AgentId?,
    ): String {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/conversations/${conversationId.value}/recompile") {
            contentType(ContentType.Application.Json)
            parameter("dry_run", dryRun)
            setBody(
                buildMap {
                    put("agent_id", agentId?.value)
                }.filterValues { it != null }
            )
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun recompileConversation(conversationId: String, dryRun: Boolean = false, agentId: String? = null): String =
        recompileConversation(ConversationId(conversationId), dryRun, agentId?.let(::AgentId))
}
