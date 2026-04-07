package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    suspend fun listLlmModels(): List<LlmModel> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/models").body()
    }

    suspend fun listEmbeddingModels(): List<EmbeddingModel> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/models/embedding").body()
    }
}
