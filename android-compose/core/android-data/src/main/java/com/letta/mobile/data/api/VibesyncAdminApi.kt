package com.letta.mobile.data.api

import com.letta.mobile.data.model.AgentsMdRefreshRequest
import com.letta.mobile.data.model.AgentsMdRefreshSummary
import com.letta.mobile.data.model.ProjectId
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class VibesyncAdminApi @Inject constructor(
    private val apiClient: LettaApiClient,
) {
    open suspend fun refreshAgentsMd(projectId: String? = null, dryRun: Boolean = true): AgentsMdRefreshSummary {
        val (client, baseUrl) = apiClient.session()
        val response = client.post("$baseUrl/api/admin/agents-md/refresh") {
            contentType(ContentType.Application.Json)
            setBody(AgentsMdRefreshRequest(projectId = projectId?.let(::ProjectId), dryRun = dryRun))
        }
        if (response.status.value !in 200..299) throw ApiException(response.status.value, response.bodyAsText())
        return response.body()
    }
}
