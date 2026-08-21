package com.letta.mobile.data.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityProperty
import com.letta.mobile.data.model.IdentityRelatedListParams
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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class IdentityApi @Inject constructor(
    private val apiClient: LettaApiClient,
) : com.letta.mobile.data.repository.api.IdentityRemoteSource {
    open override suspend fun listIdentities(): List<Identity> =
        getJson("/v1/identities/")

    open override suspend fun countIdentities(): Int =
        getJson("/v1/identities/count")

    open override suspend fun retrieveIdentity(identityId: String): Identity =
        getJson("/v1/identities/$identityId")

    open override suspend fun createIdentity(params: IdentityCreateParams): Identity =
        postJson("/v1/identities/", params)

    open override suspend fun upsertIdentity(params: IdentityUpsertParams): Identity =
        putJson("/v1/identities/", params)

    open override suspend fun updateIdentity(identityId: String, params: IdentityUpdateParams): Identity =
        patchJson("/v1/identities/$identityId", params)

    open override suspend fun deleteIdentity(identityId: String) {
        ensureSuccess(delete("/v1/identities/$identityId"))
    }

    open override suspend fun upsertIdentityProperties(
        identityId: String,
        properties: List<IdentityProperty>,
    ): Identity = putJson("/v1/identities/$identityId/properties", properties)

    open override suspend fun listAgentsForIdentity(params: IdentityRelatedListParams): List<Agent> =
        listRelated(params, "agents")

    open override suspend fun listBlocksForIdentity(params: IdentityRelatedListParams): List<Block> =
        listRelated(params, "blocks")

    open override suspend fun attachIdentity(agentId: String, identityId: String) {
        ensureSuccess(patch("/v1/agents/$agentId/identities/attach/$identityId"))
    }

    open override suspend fun detachIdentity(agentId: String, identityId: String) {
        ensureSuccess(patch("/v1/agents/$agentId/identities/detach/$identityId"))
    }

    private suspend inline fun <reified T> getJson(path: String): T {
        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl$path")
        return decode(response)
    }

    private suspend inline fun <reified T> postJson(path: String, body: Any): T {
        val (client, baseUrl) = apiClient.session()
        val response = client.post("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return decode(response)
    }

    private suspend inline fun <reified T> putJson(path: String, body: Any): T {
        val (client, baseUrl) = apiClient.session()
        val response = client.put("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return decode(response)
    }

    private suspend inline fun <reified T> patchJson(path: String, body: Any): T {
        val (client, baseUrl) = apiClient.session()
        val response = client.patch("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return decode(response)
    }

    private suspend fun delete(path: String): HttpResponse {
        val (client, baseUrl) = apiClient.session()
        return client.delete("$baseUrl$path")
    }

    private suspend fun patch(path: String): HttpResponse {
        val (client, baseUrl) = apiClient.session()
        return client.patch("$baseUrl$path")
    }

    private suspend inline fun <reified T> listRelated(
        params: IdentityRelatedListParams,
        segment: String,
    ): T {
        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl/v1/identities/${params.identityId}/$segment") {
            parameter("limit", params.limit)
            parameter("before", params.before)
            parameter("after", params.after)
            parameter("order", params.order)
        }
        return decode(response)
    }

    private suspend fun ensureSuccess(response: HttpResponse) {
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        ensureSuccess(response)
        return response.body()
    }
}
