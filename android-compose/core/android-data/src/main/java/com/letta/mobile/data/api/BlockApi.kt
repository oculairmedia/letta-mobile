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
class BlockApi @Inject constructor(
    private val apiClient: LettaApiClient
) : com.letta.mobile.data.repository.api.BlockRemoteSource {
    suspend fun getBlock(agentId: String, blockLabel: String): Block {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents/$agentId/core-memory/blocks/$blockLabel")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    override suspend fun retrieveBlock(blockId: String): Block {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/blocks/$blockId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    override suspend fun countBlocks(): Int {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/blocks/count")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    override suspend fun updateAgentBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block {
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

    override suspend fun updateGlobalBlock(
        blockId: String,
        params: BlockUpdateParams,
        clearDescription: Boolean,
        clearLimit: Boolean,
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

    override suspend fun createBlock(params: BlockCreateParams): Block {
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

    override suspend fun deleteBlock(blockId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/blocks/$blockId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    override suspend fun attachBlock(agentId: String, blockId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/$agentId/core-memory/blocks/attach/$blockId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    override suspend fun detachBlock(agentId: String, blockId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/$agentId/core-memory/blocks/detach/$blockId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    override suspend fun listBlocks(agentId: String): List<Block> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/agents/$agentId/core-memory/blocks")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    override suspend fun listAllBlocks(params: BlockListParams): List<Block> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/blocks") {
            parameter("label", params.label)
            parameter("is_template", params.isTemplate)
            parameter("limit", params.limit)
            parameter("offset", params.offset)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    override suspend fun listAgentsForBlock(params: BlockAgentsListParams): List<Agent> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/blocks/${params.blockId.value}/agents") {
            parameter("limit", params.limit)
            parameter("before", params.before)
            parameter("after", params.after)
            parameter("order", params.order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    override suspend fun attachIdentityToBlock(blockId: String, identityId: String): Block {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/blocks/$blockId/identities/attach/$identityId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    override suspend fun detachIdentityFromBlock(blockId: String, identityId: String): Block {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/blocks/$blockId/identities/detach/$identityId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }
}
