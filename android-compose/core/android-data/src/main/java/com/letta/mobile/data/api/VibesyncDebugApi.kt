package com.letta.mobile.data.api

import com.letta.mobile.data.model.VibesyncHealthResponse
import com.letta.mobile.data.model.VibesyncStatsResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VibesyncDebugApi @Inject constructor(
    private val apiClient: LettaApiClient,
) {
    suspend fun getHealth(): VibesyncHealthResponse {
        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl/health")
        if (response.status.value !in 200..299) throw ApiException(response.status.value, response.bodyAsText())
        return response.body()
    }

    suspend fun getStats(): VibesyncStatsResponse {
        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl/api/stats")
        if (response.status.value !in 200..299) throw ApiException(response.status.value, response.bodyAsText())
        return response.body()
    }
}
