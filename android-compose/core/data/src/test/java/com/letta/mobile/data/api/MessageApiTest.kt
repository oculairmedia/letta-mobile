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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.MessageSearchRequest
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
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
            coEvery { session() } returns ApiSession(client, "http://test")
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

    @Test
    fun `searchMessages posts to messages search and normalizes flat response`() = runTest {
        var url: String? = null
        var body: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            body = requestBody(req.body)
            respond(
                """
                [
                  {
                    "message_id": "msg-1",
                    "agent_id": "agent-1",
                    "conversation_id": "conv-previous",
                    "message_type": "assistant",
                    "content": "needle hit",
                    "created_at": "2026-05-08T12:00:00Z"
                  }
                ]
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val results = api.searchMessages(
            MessageSearchRequest(
                query = "needle",
                searchMode = "fts",
                roles = listOf("user", "assistant"),
                agentId = "agent-1",
                limit = 50,
            )
        )

        assertTrue(url!!.endsWith("/v1/messages/search"))
        val payload = Json.parseToJsonElement(body!!).jsonObject
        assertEquals("needle", payload["query"]!!.jsonPrimitive.content)
        assertEquals("fts", payload["search_mode"]!!.jsonPrimitive.content)
        assertEquals("agent-1", payload["agent_id"]!!.jsonPrimitive.content)
        assertEquals(1, results.size)
        assertEquals("needle hit", results.single().embeddedText)
        assertEquals("msg-1", results.single().message["message_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `searchMessages still decodes legacy wrapped response`() = runTest {
        val api = createApi {
            respond(
                """
                [
                  {
                    "embedded_text": "legacy needle hit",
                    "message": {
                      "id": "msg-old",
                      "agent_id": "agent-1",
                      "role": "assistant",
                      "content": "legacy needle hit",
                      "date": "2026-05-08T12:00:00Z"
                    }
                  }
                ]
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val results = api.searchMessages(MessageSearchRequest(query = "needle"))

        assertEquals(1, results.size)
        assertEquals("legacy needle hit", results.single().embeddedText)
        assertEquals("msg-old", results.single().message["id"]!!.jsonPrimitive.content)
    }

    // letta-mobile-t8q7: centralised idle-pattern classification.
    // Before this change the subscriber re-classified ApiException bodies
    // in its catch block, which still allocated an ApiException + stack
    // on every idle cycle. Routing both "No active runs" and "EXPIRED"
    // bodies into the stackless NoActiveRunException here removes the
    // hot-path stack capture for the entire idle path.
    @Test
    fun `streamConversation 400 with No active runs body throws NoActiveRunException`() = runTest {
        val api = createApi {
            respond(
                """{"detail":"No active runs found for conversation conv-1"}""",
                HttpStatusCode.BadRequest,
                jsonHeaders,
            )
        }

        val thrown = try {
            api.streamConversation("conv-1")
            null
        } catch (e: NoActiveRunException) {
            e
        }
        assertTrue("expected NoActiveRunException, got nothing", thrown != null)
        assertEquals("conv-1", thrown!!.conversationId)
    }

    @Test
    fun `streamConversation 404 with No active runs body throws NoActiveRunException`() = runTest {
        val api = createApi {
            respond(
                """{"detail":"No active runs"}""",
                HttpStatusCode.NotFound,
                jsonHeaders,
            )
        }

        val thrown = try {
            api.streamConversation("conv-1")
            null
        } catch (e: NoActiveRunException) {
            e
        }
        assertTrue("expected NoActiveRunException, got nothing", thrown != null)
    }

    // letta-mobile-gqz3 contract: EXPIRED bodies must be idle, not error.
    // letta-mobile-t8q7: now classified by MessageApi (was: by the subscriber).
    @Test
    fun `streamConversation 400 with EXPIRED body throws NoActiveRunException`() = runTest {
        val api = createApi {
            respond(
                """{"detail":"EXPIRED: Run was created more than 3 hours ago, and is now expired."}""",
                HttpStatusCode.BadRequest,
                jsonHeaders,
            )
        }

        val thrown = try {
            api.streamConversation("conv-1")
            null
        } catch (e: NoActiveRunException) {
            e
        }
        assertTrue("expected NoActiveRunException for EXPIRED body, got nothing", thrown != null)
    }

    @Test
    fun `streamConversation 400 with unrelated body throws ApiException`() = runTest {
        val api = createApi {
            respond(
                """{"detail":"some other validation error"}""",
                HttpStatusCode.BadRequest,
                jsonHeaders,
            )
        }

        val thrown = try {
            api.streamConversation("conv-1")
            null
        } catch (e: NoActiveRunException) {
            error("non-idle 400 should not be classified as NoActiveRun: $e")
        } catch (e: ApiException) {
            e
        }
        assertTrue("expected ApiException, got nothing", thrown != null)
        assertEquals(400, thrown!!.code)
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
