package com.letta.mobile.data.repository.http

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.AgentBlockTarget
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Agent + label block write for plain HTTP Letta servers
 * (`PATCH /v1/agents/{id}/core-memory/blocks/{label}`). Hosts whose HTTP
 * admin repository should also serve
 * [com.letta.mobile.data.repository.api.IAgentBlockWriteRepository] delegate
 * to this, so the shared admin repository stays unchanged.
 */
class LettaHttpAgentBlockWriter(
    private val config: LettaConfig,
    private val httpClient: HttpClient,
) {
    suspend fun write(target: AgentBlockTarget, params: BlockUpdateParams): Block {
        val url = "${config.serverUrl.trimEnd('/')}/v1/agents/${target.agentId}/core-memory/blocks/${target.label}"
        val response = httpClient.patch(url) {
            config.accessToken?.trim()?.takeIf { it.isNotBlank() }?.let(::bearerAuth)
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in SUCCESS_RANGE) {
            throw LettaHttpAdminRepositoryException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    private companion object {
        val SUCCESS_RANGE = 200..299
    }
}
