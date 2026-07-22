package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * JVM WebSocket lifecycle tests for the atomic dual-socket generation
 * (letta-mobile-lgns8.2), driven against a real embedded Ktor server so they
 * exercise genuine connect/close/frame behavior: truthful readiness, one-socket
 * failure tearing down both, malformed-frame tolerance, and terminal close-code
 * classification.
 */
class KtorAppServerWebSocketTransportLifecycleTest {
    private var server: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterTest
    fun tearDown() {
        server?.stop(0, 0)
        scope.cancel()
    }

    @Test
    fun bothSocketsOpenReachReadyAndPreserveBothChannelEvents() = runBlocking {
        val received = Channel<String>(Channel.UNLIMITED)
        val port = startServer { channel ->
            if (channel == "control") {
                for (frame in incoming) {
                    if (frame is Frame.Text) received.send(frame.readText())
                }
            } else {
                // Emit repeatedly so a subscriber that attaches after readiness still
                // catches a frame (streamFrames is replay=0).
                sendRepeating("""{"type":"update_loop_status","runtime":{"agent_id":"a","conversation_id":"c"},"event_seq":1,"emitted_at":"t","idempotency_key":"k","loop_status":{"status":"WAITING_ON_INPUT"}}""")
            }
        }
        val transport = transport(port)

        withTimeout(TIMEOUT) { transport.connectionState.first { it == AppServerConnectionState.Ready } }

        // Stream-channel event is delivered to the transport.
        val streamFrame = withTimeout(TIMEOUT) { transport.streamFrames.first() }
        assertIs<AppServerInboundFrame.UpdateLoopStatus>(streamFrame.frame)

        // Control-channel send reaches the server.
        transport.sendControl(AppServerCommand.Auth(requestId = "r", token = ""))
        assertTrue(withTimeout(TIMEOUT) { received.receive() }.contains("\"type\":\"auth\""))

        transport.close()
    }

    @Test
    fun oneSocketClosingTearsDownTheWholeGeneration() = runBlocking {
        val port = startServer { channel ->
            if (channel == "stream") {
                close(CloseReason(CloseReason.Codes.NORMAL, "stream gone"))
            } else {
                awaitCancellationLike()
            }
        }
        val transport = transport(port)

        val terminalState = withTimeout(TIMEOUT) {
            transport.connectionState.first { it is AppServerConnectionState.Failed }
        }
        assertIs<AppServerConnectionState.Failed>(terminalState)
        assertEquals(false, withTimeout(TIMEOUT) { transport.isConnected.first { !it } })

        transport.close()
    }

    @Test
    fun malformedFrameIsToleratedWithoutTearingDownAReadyGeneration() = runBlocking {
        val port = startServer { channel ->
            if (channel == "stream") {
                sendRepeating("this-is-not-json")
            } else {
                awaitCancellationLike()
            }
        }
        val transport = transport(port)

        val frame = withTimeout(TIMEOUT) { transport.streamFrames.first() }
        assertIs<AppServerInboundFrame.DecodeFailure>(frame.frame)
        // A malformed frame must not fail the generation.
        assertEquals(AppServerConnectionState.Ready, transport.connectionState.value)

        transport.close()
    }

    @Test
    fun terminalCloseCodeIsClassifiedTerminal() = runBlocking {
        val port = startServer { channel ->
            if (channel == "control") {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            } else {
                awaitCancellationLike()
            }
        }
        val transport = transport(port)

        val failed = withTimeout(TIMEOUT) {
            transport.connectionState.first { it is AppServerConnectionState.Failed }
        } as AppServerConnectionState.Failed
        assertTrue(failed.terminal, "1008 VIOLATED_POLICY must be terminal, was: $failed")

        transport.close()
    }

    private fun transport(port: Int): KtorAppServerWebSocketTransport {
        val httpClient = HttpClient(ClientCIO) { install(ClientWebSockets) }
        return KtorAppServerWebSocketTransport(
            httpClient = httpClient,
            baseUrl = "ws://127.0.0.1:$port",
            scope = scope,
        )
    }

    private fun startServer(
        handler: suspend io.ktor.server.websocket.DefaultWebSocketServerSession.(channel: String) -> Unit,
    ): Int {
        val embedded = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws") {
                    val channel = call.request.queryParameters["channel"].orEmpty()
                    handler(channel)
                }
            }
        }
        embedded.start(wait = false)
        server = embedded
        return runBlocking { embedded.engine.resolvedConnectors().first().port }
    }

    // Keep a server session open until the client disconnects/cancels.
    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.awaitCancellationLike() {
        for (frame in incoming) {
            // drain; loop exits when the peer closes
        }
    }

    // Emit [text] on a short interval until the session is cancelled/closed, so a
    // late subscriber to the replay=0 stream flow still observes a frame.
    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.sendRepeating(text: String) {
        while (true) {
            send(Frame.Text(text))
            delay(250)
        }
    }

    private companion object {
        val TIMEOUT = 30.seconds
    }
}
