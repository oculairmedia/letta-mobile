package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.timeline.TimelineNoActiveRunException
import com.letta.mobile.data.timeline.TimelineStreamFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DesktopChatGatewayTest {
    @Test
    fun streamConversationMapsIdleBadRequestToNoActiveRun() = runTest {
        val gateway = gatewayWithResponse(
            body = "No active runs for conversation",
            status = HttpStatusCode.BadRequest,
        )

        assertFailsWith<TimelineNoActiveRunException> {
            gateway.streamConversation("conv-1")
        }
    }

    @Test
    fun streamConversationMapsExpiredNotFoundToNoActiveRun() = runTest {
        val gateway = gatewayWithResponse(
            body = "EXPIRED: stream is now expired",
            status = HttpStatusCode.NotFound,
        )

        assertFailsWith<TimelineNoActiveRunException> {
            gateway.streamConversation("conv-1")
        }
    }

    @Test
    fun streamConversationUsesSharedSseParserFrames() = runTest {
        val gateway = gatewayWithResponse(
            body = """: ping

data: {"id":"a1","message_type":"assistant_message","content":"Remote response"}

data: [DONE]

""",
            status = HttpStatusCode.OK,
        )

        val frames = gateway.streamConversation("conv-1").toList()

        assertEquals(2, frames.size)
        assertEquals(TimelineStreamFrame.Heartbeat, frames[0])
        val message = assertIs<TimelineStreamFrame.Message>(frames[1]).message
        assertEquals("Remote response", assertIs<AssistantMessage>(message).content)
    }

    @Test
    fun listLlmModelsNormalizesBareHttpCatalogLimits() = runTest {
        val gateway = gatewayWithResponse(
            body = """
                [{
                  "id":"openai/MiniMax-M3",
                  "name":"MiniMax-M3",
                  "handle":"openai/MiniMax-M3",
                  "provider_type":"openai"
                }]
            """.trimIndent(),
            status = HttpStatusCode.OK,
            contentType = ContentType.Application.Json.toString(),
        )

        val model = gateway.listLlmModels().single()

        assertEquals(200_000, model.contextWindow)
        assertEquals(16_384, model.maxOutputTokens)
    }

    private fun gatewayWithResponse(
        body: String,
        status: HttpStatusCode,
        contentType: String = "text/event-stream",
    ): DesktopLettaHttpChatGateway {
        val client = HttpClient(MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, contentType),
            )
        }) {
            install(ContentNegotiation) {
                json(desktopChatJson)
            }
        }
        return DesktopLettaHttpChatGateway(
            config = LettaConfig(
                id = "local",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "http://localhost:8283",
            ),
            httpClient = client,
        )
    }
}
