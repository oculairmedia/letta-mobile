package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    suspend fun getBlock(agentId: String, blockLabel: String): Block {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/agents/$agentId/blocks/$blockLabel").body()
    }

    suspend fun updateBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.patch("$baseUrl/v1/agents/$agentId/blocks/$blockLabel") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }.body()
    }

    suspend fun listBlocks(agentId: String): List<Block> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/agents/$agentId/blocks").body()
    }
}
