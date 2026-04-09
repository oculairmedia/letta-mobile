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
class MessageRepositoryE2eTest {

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
        val repository = createRepository(
            streamConversationId = "conv-1",
            conversationMessagesJson = "[]",
            ssePayload = """
                data: {"id":"assistant-1","message_type":"assistant_message","content":"Done"}

                data: [DONE]
            """.trimIndent(),
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
            conversationMessagesJson = """
                [
                  {"id":"user-1","message_type":"user_message","content":"Hello"},
                  {"id":"assistant-1","message_type":"assistant_message","content":"Hi"}
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
    fun `fetchMessages requests ascending order and preserves API sequence`() = runTest {
        var requestedOrder: String? = null
        val repository = createRepository(
            streamConversationId = "conv-1",
            conversationMessagesJson = """
                [
                  {"id":"user-1","message_type":"user_message","content":"Hello"},
                  {"id":"assistant-1","message_type":"assistant_message","content":"Hi"}
                ]
            """.trimIndent(),
            ssePayload = "data: [DONE]\n\n",
            onConversationMessagesRequest = { requestedOrder = it },
        )

        val messages = repository.fetchMessages("agent-1", "conv-1")

        assertEquals("asc", requestedOrder)
        assertEquals(listOf("user-1", "assistant-1"), messages.map { it.id })
    }

    private fun createRepository(
        streamConversationId: String,
        conversationMessagesJson: String,
        ssePayload: String,
        onConversationMessagesRequest: ((String?) -> Unit)? = null,
    ): MessageRepository {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")
        val client = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                req.method == HttpMethod.Post && url.contains("/v1/conversations/$streamConversationId/messages") -> {
                    respond(ByteReadChannel(ssePayload.toByteArray()), HttpStatusCode.OK, sseHeaders)
                }
                req.method == HttpMethod.Get && url.contains("/v1/conversations/$streamConversationId/messages") -> {
                    onConversationMessagesRequest?.invoke(req.url.parameters["order"])
                    respond(conversationMessagesJson, HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

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
