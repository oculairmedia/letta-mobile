package com.letta.mobile.desktop.memory

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohBlockApi
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
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
 * Desktop-side core-memory block API (direct HTTP, mirroring the chat gateway
 * pattern), powering the Memory CRUD panel. This Letta server exposes blocks at
 * `/v1/blocks/{id}` (the per-label agent path 404s here), so reads/updates/
 * deletes go through the global block-by-id endpoints.
 */
interface DesktopBlockApi : AutoCloseable {
    suspend fun getBlockById(blockId: String): Block
    suspend fun updateBlockById(blockId: String, value: String, limit: Int? = null): Block
    suspend fun deleteBlockById(blockId: String)
    suspend fun createAndAttachBlock(agentId: String, label: String, value: String, limit: Int? = null): Block
    override fun close() = Unit
}

class DesktopHttpBlockApi(
    private val config: LettaConfig,
    private val httpClient: HttpClient = createDesktopLettaHttpClient(),
) : DesktopBlockApi {
    private val baseUrl = config.serverUrl.trimEnd('/')

    /** Fetch a block (full value) by its id. */
    override suspend fun getBlockById(blockId: String): Block {
        val response = httpClient.get("$baseUrl/v1/blocks/$blockId") { applyAuth() }
        response.requireSuccess()
        return response.body()
    }

    /** Update a block's value (and optionally limit) by its id. */
    override suspend fun updateBlockById(blockId: String, value: String, limit: Int?): Block {
        val response = httpClient.patch("$baseUrl/v1/blocks/$blockId") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(BlockUpdateParams(value = value, limit = limit))
        }
        response.requireSuccess()
        return response.body()
    }

    /** Delete a block by its id. */
    override suspend fun deleteBlockById(blockId: String) {
        val response = httpClient.delete("$baseUrl/v1/blocks/$blockId") { applyAuth() }
        response.requireSuccess()
    }

    /** Create a new block and attach it to the agent's core memory. */
    override suspend fun createAndAttachBlock(agentId: String, label: String, value: String, limit: Int?): Block {
        val createResponse = httpClient.post("$baseUrl/v1/blocks") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(BlockCreateParams(label = label, value = value, limit = limit))
        }
        createResponse.requireSuccess()
        val block: Block = createResponse.body()
        val attachResponse = httpClient.patch(
            "$baseUrl/v1/agents/$agentId/core-memory/blocks/attach/${block.id.value}",
        ) { applyAuth() }
        if (attachResponse.status.value !in 200..299) {
            // The block was created but couldn't be attached — roll it back so it
            // isn't left orphaned, then surface the attach failure to the caller.
            runCatching { deleteBlockById(block.id.value) }
            attachResponse.requireSuccess()
        }
        return block
    }

    override fun close() {
        httpClient.close()
    }

    private fun HttpRequestBuilder.applyAuth() {
        config.accessToken?.trim()?.takeIf { it.isNotBlank() }?.let(::bearerAuth)
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (status.value !in 200..299) {
            throw IllegalStateException("Block API ${status.value}: ${bodyAsText()}")
        }
    }
}


class DesktopIrohBlockApi(
    directory: IrohAdminRpcAgentDirectory,
) : DesktopBlockApi {
    private val api = IrohBlockApi(directory)

    override suspend fun getBlockById(blockId: String): Block = api.getBlockById(blockId)

    override suspend fun updateBlockById(blockId: String, value: String, limit: Int?): Block =
        api.updateBlockById(blockId, value, limit)

    override suspend fun deleteBlockById(blockId: String) = api.deleteBlockById(blockId)

    override suspend fun createAndAttachBlock(agentId: String, label: String, value: String, limit: Int?): Block =
        api.createAndAttachBlock(agentId, label, value, limit)
}
