package com.letta.mobile.data.api

import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement

@Singleton
open class GroupApi @Inject constructor(
    private val apiClient: LettaApiClient,
) {
    open suspend fun listGroups(
        managerType: String? = null,
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
        projectId: String? = null,
        showHiddenGroups: Boolean? = null,
    ): List<Group> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/groups/") {
            parameter("manager_type", managerType)
            parameter("before", before)
            parameter("after", after)
            parameter("limit", limit)
            parameter("order", order)
            parameter("project_id", projectId)
            parameter("show_hidden_groups", showHiddenGroups)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun countGroups(): Int {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/groups/count")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveGroup(groupId: String): Group {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/groups/$groupId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun createGroup(params: GroupCreateParams): Group {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/groups/") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun updateGroup(groupId: String, params: GroupUpdateParams): Group {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/groups/$groupId") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun deleteGroup(groupId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/groups/$groupId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun sendGroupMessage(groupId: String, request: MessageCreateRequest): LettaResponse {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/groups/$groupId/messages") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun sendGroupMessageStream(groupId: String, request: MessageCreateRequest): ByteReadChannel {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/groups/$groupId/messages/stream") {
            contentType(ContentType.Application.Json)
            setBody(request.copy(streaming = true))
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun updateGroupMessage(groupId: String, messageId: String, request: JsonElement): LettaMessage {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/groups/$groupId/messages/$messageId") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listGroupMessages(
        groupId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
    ): List<LettaMessage> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/groups/$groupId/messages") {
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

    open suspend fun resetGroupMessages(groupId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/groups/$groupId/reset-messages") {
            contentType(ContentType.Application.Json)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
