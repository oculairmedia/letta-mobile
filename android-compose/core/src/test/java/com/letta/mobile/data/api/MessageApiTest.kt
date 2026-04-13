package com.letta.mobile.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.letta.mobile.data.model.MessageCreateRequest

@OptIn(ExperimentalCoroutinesApi::class)
class MessageApiTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData)): MessageApi {
        val client = trackClient(HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
            }
        })
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
        }
        return MessageApi(apiClient)
    }

    @Test
    fun `sendConversationMessage preserves false streaming flag`() = runTest {
        var body: String? = null
        val api = createApi { req ->
            body = requestBody(req.body)
            respond("", HttpStatusCode.OK, jsonHeaders)
        }

        api.sendConversationMessage(
            conversationId = "conversation-1",
            request = MessageCreateRequest(input = "hello", streaming = false),
        )

        val payload = Json.parseToJsonElement(body!!).jsonObject
        assertTrue(payload.containsKey("streaming"))
        assertFalse(payload["streaming"]!!.toString().toBoolean())
    }

    @Test
    fun `sendConversationMessage preserves true streaming flag`() = runTest {
        var body: String? = null
        val api = createApi { req ->
            body = requestBody(req.body)
            respond("", HttpStatusCode.OK, jsonHeaders)
        }

        api.sendConversationMessage(
            conversationId = "conversation-1",
            request = MessageCreateRequest(input = "hello", streaming = true),
        )

        val payload = Json.parseToJsonElement(body!!).jsonObject
        assertEquals("true", payload["streaming"]!!.toString())
    }

    private fun requestBody(body: Any): String {
        val outgoing = body as OutgoingContent
        return when (outgoing) {
            is OutgoingContent.ByteArrayContent -> outgoing.bytes().decodeToString()
            is OutgoingContent.ReadChannelContent -> error("Unsupported request body type: ReadChannelContent")
            is OutgoingContent.WriteChannelContent -> error("Unsupported request body type: WriteChannelContent")
            is OutgoingContent.NoContent -> ""
            is OutgoingContent.ProtocolUpgrade -> error("Unsupported request body type: ProtocolUpgrade")
            else -> error("Unsupported request body type: ${outgoing::class.simpleName}")
        }
    }
}
