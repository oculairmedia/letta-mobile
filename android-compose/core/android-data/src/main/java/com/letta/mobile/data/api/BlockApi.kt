package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class BlockApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    open suspend fun getBlock(agentId: String, blockLabel: String): Block {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents/$agentId/core-memory/blocks/$blockLabel")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveBlock(blockId: String): Block {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/blocks/$blockId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun countBlocks(): Int {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/blocks/count")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun updateAgentBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/$agentId/core-memory/blocks/$blockLabel") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun updateGlobalBlock(
        blockId: String,
        params: BlockUpdateParams,
        clearDescription: Boolean = false,
        clearLimit: Boolean = false,
    ): Block {
        val (client, baseUrl) = apiClient.session()
        val requestBody = buildJsonObject {
            params.value?.let { put("value", it) }
            when {
                params.description != null -> put("description", params.description)
                clearDescription -> put("description", JsonNull)
            }
            when {
                params.limit != null -> put("limit", params.limit)
                clearLimit -> put("limit", JsonNull)
            }
        }

        val response = client.patch("$baseUrl/v1/blocks/$blockId") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun createBlock(params: BlockCreateParams): Block {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/blocks") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun deleteBlock(blockId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/blocks/$blockId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun attachBlock(agentId: String, blockId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/$agentId/core-memory/blocks/attach/$blockId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun detachBlock(agentId: String, blockId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/$agentId/core-memory/blocks/detach/$blockId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun listBlocks(agentId: String): List<Block> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents/$agentId/core-memory/blocks")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listAllBlocks(
        label: String? = null,
        isTemplate: Boolean? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): List<Block> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/blocks") {
            parameter("label", label)
            parameter("is_template", isTemplate)
            parameter("limit", limit)
            parameter("offset", offset)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listAgentsForBlock(
        blockId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
    ): List<Agent> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/blocks/$blockId/agents") {
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

    open suspend fun attachIdentityToBlock(blockId: String, identityId: String): Block {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/blocks/$blockId/identities/attach/$identityId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun detachIdentityFromBlock(blockId: String, identityId: String): Block {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/blocks/$blockId/identities/detach/$identityId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }
}
