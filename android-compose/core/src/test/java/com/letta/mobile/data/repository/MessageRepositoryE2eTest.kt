package com.letta.mobile.data.repository

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.domain.ClientToolRegistry
import com.letta.mobile.domain.MessageProcessor
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
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryE2eTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {

    @Test
    fun `sendMessage processes SSE stream through real repository stack`() = runTest {
        val repository = createRepository(
            streamConversationId = "conv-1",
            conversationMessagesJson = "[]",
            ssePayload = """
                data: {"id":"reason-1","message_type":"reasoning_message","reasoning":"Thinking"}

                data: {"id":"tool-1","message_type":"tool_call_message","tool_call":{"name":"search","arguments":"{}","type":"function"}}

                data: {"id":"assistant-1","message_type":"assistant_message","content":"Done"}

                data: [DONE]
            """.trimIndent(),
        )

        val states = repository.sendMessage("agent-1", "Hello", "conv-1").toList()

        assertTrue(states.first() is StreamState.Sending)
        assertTrue(states.any { it is StreamState.ToolExecution && it.toolName == "search" })
        val complete = states.last() as StreamState.Complete
        assertEquals(3, complete.messages.size)
        assertEquals(MessageType.ASSISTANT, complete.messages.last().messageType)
        assertEquals("Done", complete.messages.last().content)
    }

    @Test
    fun `sendMessage keeps existing cached history after streaming completes`() = runTest {
        // After streaming, the server will have the new messages on reload
        // API returns newest first (desc order), repo reverses for chronological display
        val reloadJson = """
            [
              {"id":"assistant-1","message_type":"assistant_message","content":"Done"},
              {"id":"user-1","message_type":"user_message","content":"Hello"}
            ]
        """.trimIndent()
        var getCallCount = 0
        val repository = createRepository(
            streamConversationId = "conv-1",
            conversationMessagesJson = "[]",
            ssePayload = """
                data: {"id":"assistant-1","message_type":"assistant_message","content":"Done"}

                data: [DONE]
            """.trimIndent(),
            overrideGetResponse = { if (getCallCount++ > 0) reloadJson else "[]" },
        )

        val initialMessages = repository.fetchMessages("agent-1", "conv-1")
        repository.sendMessage("agent-1", "Hello", "conv-1").toList()
        val cachedMessages = repository.getMessages("agent-1", "conv-1").toList().last()

        assertTrue(initialMessages.isEmpty())
        assertEquals(2, cachedMessages.size)
        assertEquals(MessageType.USER, cachedMessages.first().messageType)
        assertEquals("Hello", cachedMessages.first().content)
        assertEquals(MessageType.ASSISTANT, cachedMessages.last().messageType)
        assertEquals("Done", cachedMessages.last().content)
    }

    @Test
    fun `fetchMessages maps conversation messages from API`() = runTest {
        val repository = createRepository(
            streamConversationId = "conv-1",
            // API returns newest first (desc order), repo reverses for chronological display
            conversationMessagesJson = """
                [
                  {"id":"assistant-1","message_type":"assistant_message","content":"Hi"},
                  {"id":"user-1","message_type":"user_message","content":"Hello"}
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
    fun `fetchMessages requests descending order and reverses for chronological display`() = runTest {
        var requestedOrder: String? = null
        val repository = createRepository(
            streamConversationId = "conv-1",
            // API returns newest first (desc order)
            conversationMessagesJson = """
                [
                  {"id":"assistant-1","message_type":"assistant_message","content":"Hi"},
                  {"id":"user-1","message_type":"user_message","content":"Hello"}
                ]
            """.trimIndent(),
            ssePayload = "data: [DONE]\n\n",
            onConversationMessagesRequest = { requestedOrder = it },
        )

        val messages = repository.fetchMessages("agent-1", "conv-1")

        assertEquals("desc", requestedOrder)
        // Result should be reversed for chronological order (oldest first)
        assertEquals(listOf("user-1", "assistant-1"), messages.map { it.id })
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
        }

        val repository = MessageRepository(
            MessageApi(apiClient),
            MessageProcessor(ClientToolRegistry()),
        )

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
        onConversationMessagesRequest: ((String?) -> Unit)? = null,
        overrideGetResponse: (() -> String)? = null,
    ): MessageRepository {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")
        val client = trackClient(HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                req.method == HttpMethod.Post && url.contains("/v1/conversations/$streamConversationId/messages") -> {
                    respond(ByteReadChannel(ssePayload.toByteArray()), HttpStatusCode.OK, sseHeaders)
                }
                req.method == HttpMethod.Get && url.contains("/v1/agents/") && url.contains("/messages") && req.url.parameters["conversation_id"] == streamConversationId -> {
                    onConversationMessagesRequest?.invoke(req.url.parameters["order"])
                    val body = overrideGetResponse?.invoke() ?: conversationMessagesJson
                    respond(body, HttpStatusCode.OK, jsonHeaders)
                }
                req.method == HttpMethod.Get && url.contains("/v1/conversations/$streamConversationId/messages") -> {
                    onConversationMessagesRequest?.invoke(req.url.parameters["order"])
                    val body = overrideGetResponse?.invoke() ?: conversationMessagesJson
                    respond(body, HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            } }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })

        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
        }

        return MessageRepository(
            MessageApi(apiClient),
            MessageProcessor(ClientToolRegistry()),
        )
    }
}
