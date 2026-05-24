package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ApiSession
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.MessageType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Integration tests for [MessageRepository] — the stateless HTTP helper that
 * remains after Phase 5 of the Timeline migration. Legacy streaming-send and
 * in-memory cache tests were removed alongside the underlying implementation
 * (see `letta-mobile-844e`); live message sync is now exercised by
 * `TimelineSyncLoopTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class MessageRepositoryE2eTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {

    @Test
    fun `fetchMessages maps conversation messages from API`() = runTest {
        val repository = createRepository(
            streamConversationId = "conv-1",
            // API returns newest first (desc order); MessageApi sorts by
            // (date, otid) before returning. Supply dates so the test
            // exercises chronological ordering deterministically.
            conversationMessagesJson = """
                [
                  {"id":"assistant-1","message_type":"assistant_message","content":"Hi","date":"2024-03-15T10:01:00Z"},
                  {"id":"user-1","message_type":"user_message","content":"Hello","date":"2024-03-15T10:00:00Z"}
                ]
            """.trimIndent(),
            ssePayload = "data: [DONE]\n\n",
        )

        val messages = repository.fetchMessages("agent-1", "conv-1")

        assertEquals(2, messages.size)
        assertEquals(MessageType.USER, messages.first().messageType)
        assertEquals("Hi", messages.last().content)
    }

    @Test
    fun `fetchMessages requests descending order and sorts chronologically`() = runTest {
        var requestedOrder: String? = null
        var requestedLimit: Int? = null
        val repository = createRepository(
            streamConversationId = "conv-1",
            conversationMessagesJson = """
                [
                  {"id":"assistant-1","message_type":"assistant_message","content":"Hi","date":"2024-03-15T10:01:00Z"},
                  {"id":"user-1","message_type":"user_message","content":"Hello","date":"2024-03-15T10:00:00Z"}
                ]
            """.trimIndent(),
            ssePayload = "data: [DONE]\n\n",
            onConversationMessagesRequest = { order, limit, _ ->
                requestedOrder = order
                requestedLimit = limit?.toIntOrNull()
            },
        )

        val messages = repository.fetchMessages("agent-1", "conv-1")

        assertEquals("desc", requestedOrder)
        assertTrue(
            "Limit should be >= INITIAL_FETCH_LIMIT",
            requestedLimit != null && requestedLimit!! >= MessageRepository.INITIAL_FETCH_LIMIT,
        )
        assertEquals(listOf("user-1", "assistant-1"), messages.map { it.id })
    }

    @Test
    fun `fetchOlderMessages returns page ordered chronologically`() = runTest {
        val requestedBeforeValues = mutableListOf<String?>()
        val repository = createRepository(
            streamConversationId = "conv-1",
            conversationMessagesJson = "[]",
            ssePayload = "data: [DONE]\n\n",
            onConversationMessagesRequest = { _, _, before ->
                requestedBeforeValues += before
            },
            overrideGetResponse = { before ->
                if (before == "user-11") {
                    """
                    [
                      {"id":"assistant-10","message_type":"assistant_message","content":"Older answer","date":"2024-03-15T09:01:00Z"},
                      {"id":"user-10","message_type":"user_message","content":"Older question","date":"2024-03-15T09:00:00Z"}
                    ]
                    """.trimIndent()
                } else {
                    "[]"
                }
            },
        )

        val olderMessages = repository.fetchOlderMessages("agent-1", "conv-1", "user-11")

        assertEquals(listOf("user-11"), requestedBeforeValues)
        assertEquals(listOf("user-10", "assistant-10"), olderMessages.map { it.id })
    }

    @Test
    fun `fetchMessages keeps paging until target message is loaded`() = runTest {
        var requestedAfterValues = mutableListOf<String?>()
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val client = trackClient(HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                req.method == HttpMethod.Get && url.contains("/v1/agents/") && url.contains("/messages") && req.url.parameters["conversation_id"] == "conv-1" -> {
                    val after = req.url.parameters["after"]
                    requestedAfterValues.add(after)
                    val body = if (after == null) {
                        buildString {
                            append("[")
                            for (index in 1..100) {
                                if (index > 1) append(',')
                                append("{\"id\":\"msg-$index\",\"message_type\":\"assistant_message\",\"content\":\"Message $index\"}")
                            }
                            append("]")
                        }
                    } else {
                        """
                        [
                          {"id":"msg-101","message_type":"assistant_message","content":"Message 101"},
                          {"id":"msg-target","message_type":"assistant_message","content":"Target message"}
                        ]
                        """.trimIndent()
                    }
                    respond(body, HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        })

        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
            coEvery { session() } returns ApiSession(client, "http://test")
        }

        val repository = MessageRepository(MessageApi(apiClient))

        val messages = repository.fetchMessages(
            agentId = "agent-1",
            conversationId = "conv-1",
            targetMessageId = "msg-target",
        )

        assertEquals(listOf(null, "msg-100"), requestedAfterValues)
        assertTrue(messages.any { it.id == "msg-target" })
        assertEquals("msg-target", messages.last().id)
    }

    @Test
    fun `fetchConversationInspectorMessages keeps metadata for debugging`() = runTest {
        val repository = createRepository(
            streamConversationId = "conv-1",
            conversationMessagesJson = """
                [
                  {
                    "id":"tool-1",
                    "message_type":"tool_call_message",
                    "date":"2026-04-09T10:00:00Z",
                    "run_id":"run-1",
                    "step_id":"step-1",
                    "tool_call":{"id":"call-1","name":"search","arguments":"{}","type":"function"}
                  },
                  {
                    "id":"event-1",
                    "message_type":"event_message",
                    "event_type":"heartbeat",
                    "event_data":{"phase":"streaming"}
                  }
                ]
            """.trimIndent(),
            ssePayload = "data: [DONE]\n\n",
        )

        val messages = repository.fetchConversationInspectorMessages("conv-1")

        assertEquals(2, messages.size)
        assertEquals("tool_call_message", messages.first().messageType)
        assertTrue(messages.first().detailLines.any { it.first == "Run ID" && it.second == "run-1" })
        assertTrue(messages.first().detailLines.any { it.first == "Arguments" && it.second == "{}" })
        assertEquals("heartbeat", messages.last().summary)
        assertTrue(messages.last().detailLines.any { it.first == "phase" && it.second == "streaming" })
    }

    private fun createRepository(
        streamConversationId: String,
        conversationMessagesJson: String,
        ssePayload: String,
        onConversationMessagesRequest: ((String?, String?, String?) -> Unit)? = null,
        overrideGetResponse: ((String?) -> String)? = null,
    ): MessageRepository {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")
        val client = trackClient(HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                req.method == HttpMethod.Post && url.contains("/v1/conversations/$streamConversationId/messages") -> {
                    respond(io.ktor.utils.io.ByteReadChannel(ssePayload.toByteArray()), HttpStatusCode.OK, sseHeaders)
                }
                req.method == HttpMethod.Get && url.contains("/v1/agents/") && url.contains("/messages") && req.url.parameters["conversation_id"] == streamConversationId -> {
                    onConversationMessagesRequest?.invoke(
                        req.url.parameters["order"],
                        req.url.parameters["limit"],
                        req.url.parameters["before"],
                    )
                    val body = overrideGetResponse?.invoke(req.url.parameters["before"]) ?: conversationMessagesJson
                    respond(body, HttpStatusCode.OK, jsonHeaders)
                }
                req.method == HttpMethod.Get && url.contains("/v1/conversations/$streamConversationId/messages") -> {
                    onConversationMessagesRequest?.invoke(
                        req.url.parameters["order"],
                        req.url.parameters["limit"],
                        req.url.parameters["before"],
                    )
                    val body = overrideGetResponse?.invoke(req.url.parameters["before"]) ?: conversationMessagesJson
                    respond(body, HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        })

        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
            coEvery { session() } returns ApiSession(client, "http://test")
        }

        return MessageRepository(MessageApi(apiClient))
    }
}
