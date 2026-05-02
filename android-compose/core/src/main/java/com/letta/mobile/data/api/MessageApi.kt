package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class MessageApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    companion object {
        /**
         * The Letta API's `limit` parameter counts runs/steps, not individual messages.
         * Each run can contain multiple messages (user, reasoning, assistant, tool calls, etc.).
         * We over-fetch by this multiplier to ensure we get enough messages, then slice client-side.
         */
        private const val RUN_TO_MESSAGE_MULTIPLIER = 5
        private const val MAX_OVER_FETCH_LIMIT = 200
    }

    /**
     * Fetch recent messages with a true message-count limit.
     *
     * The Letta API's `limit` counts runs/steps, not messages. This method over-fetches
     * and slices to provide the expected behavior where `messageLimit` is the actual
     * number of messages returned.
     *
     * @param conversationId The conversation to fetch messages from
     * @param messageLimit Exact number of messages to return (default 20)
     * @param beforeMessageId Fetch messages before this message ID (for pagination)
     * @return List of messages in chronological order (oldest first), limited to messageLimit
     */
    open suspend fun fetchRecentMessages(
        conversationId: String,
        messageLimit: Int = 20,
        beforeMessageId: String? = null,
    ): List<LettaMessage> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        // Over-fetch to account for runs containing multiple messages
        val runLimit = ((messageLimit * RUN_TO_MESSAGE_MULTIPLIER) / 4).coerceIn(messageLimit, MAX_OVER_FETCH_LIMIT)

        // Strategy: Use desc order to fetch the most recent messages,
        // then sort the results in chronological order using a stable sort.
        // 
        // The problem with desc + reverse: within a single run, desc returns
        // [reasoning, assistant] but chronologically it should be [reasoning, assistant]
        // (reasoning happens before assistant). Reversing gives [assistant, reasoning]
        // which is WRONG.
        //
        // Instead: fetch desc (to get recent messages), then sort by (date, otid)
        // where otid is a string that increments monotonically within a run.
        val response = client.get("$baseUrl/v1/conversations/$conversationId/messages") {
            parameter("limit", runLimit)
            parameter("before", beforeMessageId)
            parameter("order", "desc")
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }

        val allMessages: List<LettaMessage> = response.body()

        // Take the most recent N messages (first N in desc order)
        // Then sort chronologically by date, using otid as tiebreaker for same-date messages
        return allMessages.take(messageLimit).sortedWith(
            compareBy<LettaMessage>(
                { it.date ?: "" },       // Primary: chronological date
                { it.otid ?: it.id }      // Tiebreaker: otid (increments within a run) or id
            )
        )
    }

    /**
     * Fetch messages after a specific message ID (for incremental sync).
     *
     * @param conversationId The conversation to fetch messages from
     * @param afterMessageId Fetch messages after this message ID
     * @param messageLimit Max number of messages to return
     * @return List of new messages in chronological order (oldest first)
     */
    open suspend fun fetchMessagesAfter(
        conversationId: String,
        afterMessageId: String?,
        messageLimit: Int = 50,
    ): List<LettaMessage> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        // Over-fetch to account for runs containing multiple messages
        val runLimit = ((messageLimit * RUN_TO_MESSAGE_MULTIPLIER) / 4).coerceIn(messageLimit, MAX_OVER_FETCH_LIMIT)

        val response = client.get("$baseUrl/v1/conversations/$conversationId/messages") {
            parameter("limit", runLimit)
            parameter("after", afterMessageId)
            parameter("order", "asc")
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }

        val allMessages: List<LettaMessage> = response.body()

        // Messages are in asc order (oldest first), take the limit
        // Apply same stable sort as fetchRecentMessages for consistent ordering
        return allMessages.take(messageLimit).sortedWith(
            compareBy<LettaMessage>(
                { it.date ?: "" },
                { it.otid ?: it.id }
            )
        )
    }

    open suspend fun sendMessage(agentId: String, request: MessageCreateRequest): LettaResponse {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/agents/$agentId/messages") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest
    ): ByteReadChannel {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            setBody(request)
            // Message sends can stream responses for longer than the normal REST
            // budget. Keep connect timeout bounded globally, but do not let the
            // generic request/socket timeout own stream lifetime.
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun sendConversationMessageNoStream(
        conversationId: String,
        request: MessageCreateRequest,
    ): LettaResponse {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }


    /**
     * Subscribe to the live SSE stream of the most-recent active run in a conversation.
     *
     * The Letta server exposes POST /v1/conversations/{conversation_id}/stream which
     * "resumes the stream for the most recent active run." Crucially, this endpoint
     * multiplexes events to EVERY subscribed client — not just the run originator —
     * making it a real ambient realtime path for incoming messages produced by any
     * client or server-side activity.
     *
     * Semantics:
     *   - 200 with text/event-stream: returns the raw ByteReadChannel. Caller should
     *     pipe into SseParser.parse() to get a Flow<LettaMessage>.
     *   - 404 with detail containing "No active runs": throws NoActiveRunException.
     *     Caller backs off and retries; a run will eventually start (any client
     *     posting into the conversation triggers one).
     *   - Other non-2xx: throws ApiException.
     *
     * Verified empirically 2026-04-18: a second client calling this endpoint while
     * client A's run is in-flight receives the SAME events with the SAME run_id as
     * client A, ~70ms later. See epic letta-mobile-mge5.
     */
    open suspend fun streamConversation(conversationId: String): ByteReadChannel {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/conversations/$conversationId/stream") {
            contentType(ContentType.Application.Json)
            setBody("{}")
            header(HttpHeaders.Accept, "text/event-stream")
            // Ambient SSE streams are intentionally long-lived. OkHttp PING
            // helps HTTP/2 transport liveness; HTTP/1.1 SSE still needs server
            // heartbeats and the explicit stale-stream watchdog to reconnect.
            // Keep normal REST calls on the bounded global timeout policy.
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }
        // The server has returned either 400 INVALID_ARGUMENT or 404 NOT_FOUND
        // for "no active runs" depending on version/endpoint. Treat both the
        // same — this is the normal idle path, not an error.
        if (response.status.value == 400 || response.status.value == 404) {
            val body = response.bodyAsText()
            if (body.contains("No active runs", ignoreCase = true)) {
                throw NoActiveRunException(conversationId)
            }
            throw ApiException(response.status.value, body)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }
    open suspend fun listMessages(
        agentId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
        conversationId: String? = null,
    ): List<LettaMessage> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/agents/$agentId/messages") {
            parameter("limit", limit)
            parameter("before", before)
            parameter("after", after)
            parameter("order", order)
            conversationId?.let { parameter("conversation_id", it) }
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listConversationMessages(
        conversationId: String,
        limit: Int? = null,
        after: String? = null,
        order: String? = null,
    ): List<LettaMessage> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/conversations/$conversationId/messages") {
            parameter("limit", limit)
            parameter("after", after)
            parameter("order", order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun resetMessages(agentId: String) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.patch("$baseUrl/v1/agents/$agentId/reset-messages") {
            contentType(ContentType.Application.Json)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun cancelMessage(agentId: String, runIds: List<String>? = null): Map<String, String> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/agents/$agentId/messages/cancel") {
            contentType(ContentType.Application.Json)
            setBody(CancelAgentRunRequest(runIds = runIds))
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun searchMessages(request: MessageSearchRequest): List<MessageSearchResult> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/agents/messages/search") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun createBatch(request: CreateBatchMessagesRequest): Job {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/messages/batches") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun retrieveBatch(batchId: String): Job {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/messages/batches/$batchId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listBatches(
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
    ): List<Job> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/messages/batches") {
            parameter("limit", limit)
            parameter("before", before)
            parameter("after", after)
            parameter("order", order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listBatchMessages(
        batchId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
        agentId: String? = null,
    ): BatchMessagesResponse {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/messages/batches/$batchId/messages") {
            parameter("limit", limit)
            parameter("before", before)
            parameter("after", after)
            parameter("order", order)
            parameter("agent_id", agentId)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun cancelBatch(batchId: String) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.patch("$baseUrl/v1/messages/batches/$batchId/cancel")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
