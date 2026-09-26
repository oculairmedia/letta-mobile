package com.letta.mobile.data.repository.http

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.AgentBlockTarget
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Agent + label block writes for plain HTTP Letta servers. Hosts whose HTTP
 * admin repository should also serve
 * [com.letta.mobile.data.repository.api.IAgentBlockWriteRepository] delegate
 * to this, so the shared admin repository stays unchanged.
 *  - write: `PATCH /v1/agents/{id}/core-memory/blocks/{label}`
 *  - create (bfooy.5): `POST /v1/blocks`, then attach it to the agent
 *  - delete (bfooy.5): resolve the label's block id, then `DELETE /v1/blocks/{id}`
 */
class LettaHttpAgentBlockWriter(
    private val config: LettaConfig,
    private val httpClient: HttpClient,
) {
    private val baseUrl = config.serverUrl.trimEnd('/')

    suspend fun write(target: AgentBlockTarget, params: BlockUpdateParams): Block =
        httpClient.patch(agentBlockUrl(target)) { jsonBody(params) }.requireSuccess().body()

    suspend fun create(target: AgentBlockTarget, value: String): Block {
        val created: Block = httpClient.post("$baseUrl/v1/blocks") {
            jsonBody(BlockCreateParams(label = target.label, value = value))
        }.requireSuccess().body()
        httpClient.patch("$baseUrl/v1/agents/${target.agentId}/core-memory/blocks/attach/${created.id.value}") {
            authorize()
        }.requireSuccess()
        return created
    }

    suspend fun delete(target: AgentBlockTarget) {
        val block: Block = httpClient.get(agentBlockUrl(target)) { authorize() }.requireSuccess().body()
        httpClient.delete("$baseUrl/v1/blocks/${block.id.value}") { authorize() }.requireSuccess()
    }

    private fun agentBlockUrl(target: AgentBlockTarget): String =
        "$baseUrl/v1/agents/${target.agentId}/core-memory/blocks/${target.label}"

    private fun HttpRequestBuilder.authorize() {
        config.accessToken?.trim()?.takeIf { it.isNotBlank() }?.let(::bearerAuth)
    }

    private inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) {
        authorize()
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.requireSuccess(): HttpResponse {
        if (status.value !in SUCCESS_RANGE) {
            throw LettaHttpAdminRepositoryException(status.value, bodyAsText())
        }
        return this
    }

    private companion object {
        val SUCCESS_RANGE = 200..299
    }
}
