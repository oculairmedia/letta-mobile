package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelApi @Inject constructor(
    private val apiClient: LettaApiClient
) : com.letta.mobile.data.repository.api.ModelRemoteSource {
    override suspend fun listLlmModels(): List<LlmModel> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/models")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    override suspend fun listEmbeddingModels(): List<EmbeddingModel> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/models/embedding")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }
}
