package com.letta.mobile.data.api

import com.letta.mobile.data.model.PmAgentMetadata
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class ProjectAgentApi @Inject constructor(
    private val apiClient: LettaApiClient,
) {
    open suspend fun lookup(repo: String): PmAgentMetadata? {
        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl/api/agents/lookup") {
            url { parameters.append("repo", repo) }
        }
        return when (response.status.value) {
            in 200..299 -> response.body()
            404 -> null
            else -> throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
