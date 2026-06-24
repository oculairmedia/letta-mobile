package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class KtorAppServerWebSocketTransportIntegrationTest {
    @Test
    fun runtimeStartAgainstLiveServerWhenConfigured() = runTest {
        val baseUrl = System.getenv("APP_SERVER_TEST_URL") ?: return@runTest
        val bearerToken = System.getenv("APP_SERVER_TEST_TOKEN")
        val agentId = System.getenv("APP_SERVER_TEST_AGENT_ID")
        val conversationId = System.getenv("APP_SERVER_TEST_CONVERSATION_ID")
        val httpClient = HttpClient(CIO) {
            install(WebSockets)
        }
        val transport = KtorAppServerWebSocketTransport(
            httpClient = httpClient,
            baseUrl = baseUrl,
            scope = backgroundScope,
            bearerToken = bearerToken,
        )
        val client = DefaultAppServerClient(transport, requestTimeoutMs = 10_000)

        try {
            val response = async {
                client.runtimeStart(
                    AppServerCommand.RuntimeStart(
                        requestId = "integration-runtime-start",
                        agentId = agentId,
                        conversationId = conversationId,
                        createConversation = if (conversationId == null) {
                            AppServerRuntimeStartCreateConversationOptions()
                        } else {
                            null
                        },
                        clientInfo = AppServerRuntimeStartClientInfo(
                            name = "letta-mobile-test",
                            title = "sharedLogic App Server integration test",
                        ),
                    ),
                )
            }
            runCurrent()

            val runtimeStartResponse = response.await()
            assertTrue(runtimeStartResponse.success, runtimeStartResponse.error ?: "runtime_start failed")
            val runtime = requireNotNull(runtimeStartResponse.runtime) {
                "runtime_start_response did not include runtime"
            }

            assertNotNull(runtime.agentId)
            if (conversationId != null) {
                assertEquals(conversationId, runtime.conversationId)
            }
        } finally {
            transport.close()
            httpClient.close()
        }
    }
}
