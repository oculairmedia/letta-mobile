package com.letta.mobile.data.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityProperty
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.model.IdentityUpsertParams
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class IdentityApi @Inject constructor(
    private val apiClient: LettaApiClient,
) : com.letta.mobile.data.repository.api.IdentityRemoteSource {
    open override suspend fun listIdentities(): List<Identity> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/identities/")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun countIdentities(): Int {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/identities/count")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun retrieveIdentity(identityId: String): Identity {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/identities/$identityId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun createIdentity(params: IdentityCreateParams): Identity {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/identities/") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun upsertIdentity(params: IdentityUpsertParams): Identity {
        val (client, baseUrl) = apiClient.session()

        val response = client.put("$baseUrl/v1/identities/") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun updateIdentity(identityId: String, params: IdentityUpdateParams): Identity {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/identities/$identityId") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun deleteIdentity(identityId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/identities/$identityId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open override suspend fun upsertIdentityProperties(identityId: String, properties: List<IdentityProperty>): Identity {
        val (client, baseUrl) = apiClient.session()

        val response = client.put("$baseUrl/v1/identities/$identityId/properties") {
            contentType(ContentType.Application.Json)
            setBody(properties)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun listAgentsForIdentity(
        identityId: String,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
    ): List<Agent> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/identities/$identityId/agents") {
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

    open override suspend fun listBlocksForIdentity(
        identityId: String,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
    ): List<Block> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/identities/$identityId/blocks") {
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

    open override suspend fun attachIdentity(agentId: String, identityId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/$agentId/identities/attach/$identityId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open override suspend fun detachIdentity(agentId: String, identityId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/$agentId/identities/detach/$identityId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
