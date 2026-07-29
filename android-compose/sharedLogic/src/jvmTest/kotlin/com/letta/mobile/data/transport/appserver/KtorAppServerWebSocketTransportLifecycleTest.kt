package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * JVM WebSocket lifecycle tests for the one-socket session generation
 * (letta-mobile-lgns8.21.1), driven against a real embedded Ktor server so they
 * exercise genuine connect/close/frame behavior: truthful readiness, demux of
 * stream vs control message types, malformed-frame tolerance, and terminal
 * close-code classification.
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
    fun sessionOpenReachesReadyAndDemuxesControlAndStreamEvents() = runBlocking {
        val received = Channel<String>(Channel.UNLIMITED)
        val port = startServer {
            coroutineScope {
                launch { sendRepeating(STREAM_STATUS_FRAME) }
                for (frame in incoming) {
                    if (frame is Frame.Text) received.send(frame.readText())
                }
            }
        }
        val transport = transport(port)

        // Deflake: streamFrames is replay=0, so subscribe BEFORE asserting readiness.
        val streamFrameDeferred = async { withTimeout(TIMEOUT) { transport.streamFrames.first() } }

        withTimeout(TIMEOUT) { transport.connectionState.first { it == AppServerConnectionState.Ready } }

        val streamFrame = streamFrameDeferred.await()
        assertIs<AppServerInboundFrame.UpdateLoopStatus>(streamFrame.frame)
        assertEquals(AppServerChannel.Stream, streamFrame.channel)

        transport.sendControl(AppServerCommand.Auth(requestId = "r", token = ""))
        assertTrue(withTimeout(TIMEOUT) { received.receive() }.contains("\"type\":\"auth\""))

        transport.close()
    }

    @Test
    fun sessionClosingTearsDownTheGeneration() = runBlocking {
        val port = startServer {
            close(CloseReason(CloseReason.Codes.NORMAL, "session gone"))
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
        val port = startServer {
            sendRepeating("this-is-not-json")
        }
        val transport = transport(port)

        withTimeout(TIMEOUT) { transport.connectionState.first { it == AppServerConnectionState.Ready } }

        // Unparseable frames demux to control (official JS client labels all as control).
        val frame = withTimeout(TIMEOUT) { transport.controlFrames.first() }
        assertIs<AppServerInboundFrame.DecodeFailure>(frame.frame)
        assertEquals(AppServerConnectionState.Ready, transport.connectionState.value)

        transport.close()
    }

    @Test
    fun streamBackpressureCannotBlockControlDeliveryOnTheSharedSocket() = runBlocking {
        val port = startServer {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    repeat(STREAM_BURST_SIZE) {
                        send(Frame.Text(STREAM_STATUS_FRAME))
                    }
                    send(Frame.Text(AUTH_RESPONSE_FRAME))
                }
            }
        }
        val transport = transport(port)
        val releaseStreamCollector = CompletableDeferred<Unit>()
        val streamCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.streamFrames.collect {
                releaseStreamCollector.await()
            }
        }

        try {
            withTimeout(TIMEOUT) {
                transport.connectionState.first { it == AppServerConnectionState.Ready }
            }
            val controlFrame = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT) { transport.controlFrames.first() }
            }

            transport.sendControl(AppServerCommand.Auth(requestId = "backpressure", token = ""))

            assertIs<AppServerInboundFrame.AuthResponse>(controlFrame.await().frame)
            assertEquals(AppServerConnectionState.Ready, transport.connectionState.value)
        } finally {
            releaseStreamCollector.complete(Unit)
            streamCollector.cancel()
            transport.close()
        }
    }

    @Test
    fun terminalCloseCodeIsClassifiedTerminal() = runBlocking {
        val port = startServer {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        }
        val transport = transport(port)

        val failed = withTimeout(TIMEOUT) {
            transport.connectionState.first { it is AppServerConnectionState.Failed }
        } as AppServerConnectionState.Failed
        assertTrue(failed.terminal, "1008 VIOLATED_POLICY must be terminal, was: $failed")

        transport.close()
    }

    @Test
    fun productionUrlsNeverIncludeChannelQuery() {
        assertTrue(!appServerUrl("ws://127.0.0.1:4500").contains("channel="))
        assertTrue(!appServerUrl("ws://127.0.0.1:4500?channel=control").contains("channel="))
        assertTrue(caughtLegacyChannelIsTerminal())
    }

    private fun caughtLegacyChannelIsTerminal(): Boolean {
        // Unit-level: handshake failure classifier treats 426 as terminal so
        // reconnect supervisors do not spin against an intentionally rejected URL.
        val error = RuntimeException("Server returned HTTP response code: 426 Upgrade Required")
        return error.isTerminalHandshakeFailure()
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
        handler: suspend io.ktor.server.websocket.DefaultWebSocketServerSession.() -> Unit,
    ): Int {
        val embedded = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws") {
                    // Mirror upstream ≥0.29.7: reject legacy split-channel query.
                    if (call.request.queryParameters.contains("channel")) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Upgrade Required"))
                        return@webSocket
                    }
                    handler()
                }
            }
        }
        embedded.start(wait = false)
        server = embedded
        return runBlocking { embedded.engine.resolvedConnectors().first().port }
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.sendRepeating(text: String) {
        while (true) {
            send(Frame.Text(text))
            delay(250)
        }
    }

    private companion object {
        val TIMEOUT = 5.seconds
        const val STREAM_BURST_SIZE = 256
        const val AUTH_RESPONSE_FRAME =
            """{"type":"auth_response","request_id":"backpressure","success":true}"""
        const val STREAM_STATUS_FRAME =
            """{"type":"update_loop_status","runtime":{"agent_id":"a","conversation_id":"c"},"event_seq":1,"emitted_at":"t","idempotency_key":"k","loop_status":{"status":"WAITING_ON_INPUT"}}"""
    }
}
