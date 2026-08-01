package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * letta-mobile-vnp3q: every production App Server WebSocket client must be able
 * to actually CONNECT with the exact engine + `WebSockets` config it constructs.
 *
 * WHY THIS EXISTS. PR #1064 added [applyAppServerFrameLimits] (a non-default
 * `maxFrameSize`) to every App Server `/ws` call site. Ktor's OkHttp engine
 * rejects any non-default `maxFrameSize` at connect time with
 * `"Max frame size switch is not supported in OkHttp engine"`. Nothing caught
 * it: the existing coverage either asserted the CONSTANT
 * ([AppServerWebSocketLimitsTest]), or asserted the plugin CARRIED the value on
 * an already-CIO client ([AppServerWebSocketClientFrameLimitTest]), or connected
 * with a hand-rolled test client that was not the production config
 * ([KtorAppServerWebSocketTransportLifecycleTest]). The production wrapper's App
 * Server link therefore died on 2026-07-31 (reconnect exhausted -> GaveUp ->
 * every native admin route dead) and was only fixed live by #1078.
 *
 * The gap was structural: NO test performed a real connect using a real
 * production engine+config pair. This test closes it.
 *
 * FAIL-ON-REVERT CONTRACT
 *  - The positive cases below break if anyone changes a production client's
 *    engine or WebSockets config to something that cannot complete a handshake.
 *  - [okHttpEngineWithFrameLimitsFailsTheConnect] is the negative control: it
 *    reproduces the incident class on demand, so the positive cases are proven
 *    to be capable of catching it rather than passing vacuously.
 *
 * MAINTENANCE. The configs here MIRROR their production definitions (sharedLogic
 * cannot depend on `desktop` / `iroh-wrapper-cli` / `appserver-cli`). Each mirror
 * carries a SOURCE pin. If you change a pinned site, change the mirror.
 */
class AppServerProductionEngineConnectTest {

    private var server: EmbeddedServer<*, *>? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0, 0)
    }

    // ---------------------------------------------------------------- positive

    @Test
    fun irohWrapperLiveControllerClientCompletesARealFrameExchange() {
        assertRealConnectSucceeds(irohWrapperLiveControllerClient())
    }

    @Test
    fun irohWrapperStubControllerClientCompletesARealFrameExchange() {
        assertRealConnectSucceeds(irohWrapperStubControllerClient())
    }

    @Test
    fun desktopLettaHttpClientCompletesARealFrameExchange() {
        assertRealConnectSucceeds(desktopLettaHttpClient())
    }

    @Test
    fun desktopWsChannelTransportClientCompletesARealFrameExchange() {
        assertRealConnectSucceeds(desktopWsChannelTransportClient())
    }

    @Test
    fun appServerCliProbeClientCompletesARealFrameExchange() {
        assertRealConnectSucceeds(appServerCliProbeClient())
    }

    @Test
    fun everyProductionClientCarriesTheSharedFrameCeiling() {
        productionClients().forEach { (name, factory) ->
            factory().use { client ->
                assertEquals(
                    AppServerWebSocketLimits.MAX_FRAME_BYTES,
                    client.plugin(ClientWebSockets).maxFrameSize,
                    "$name lost the App Server inbound frame ceiling",
                )
            }
        }
    }

    // ---------------------------------------------------------------- negative

    /**
     * NEGATIVE CONTROL — reproduces the 2026-07-31 incident exactly.
     *
     * This is not a wish that OkHttp worked; it is the proof that a real connect
     * against a real server is what distinguishes a working engine from a broken
     * one. If this test ever starts PASSING the connect, Ktor's OkHttp engine
     * gained `maxFrameSize` support and this control should be revisited — but
     * the positive tests above remain the contract.
     */
    @Test
    fun okHttpEngineWithFrameLimitsFailsTheConnect() {
        val port = startEchoServer()
        val client = HttpClient(OkHttp) {
            install(ClientWebSockets) { applyAppServerFrameLimits() }
        }
        val failure = client.use {
            runCatching {
                runBlocking {
                    withTimeout(TIMEOUT) {
                        it.webSocket("ws://127.0.0.1:$port/ws") {
                            send(Frame.Text(PING))
                        }
                    }
                }
            }.exceptionOrNull()
        } ?: fail(
            "OkHttp + applyAppServerFrameLimits connected — the negative control no longer " +
                "reproduces the #1064->#1077 incident class, so the positive cases above are " +
                "no longer known to be able to catch it. Re-verify before deleting this test.",
        )

        val text = failure.chainText()
        assertTrue(
            text.contains("Max frame size switch is not supported"),
            "expected the OkHttp maxFrameSize rejection, got: $text",
        )
    }

    // ----------------------------------------------------------------- harness

    private fun assertRealConnectSucceeds(client: HttpClient) {
        val port = startEchoServer()
        client.use {
            runBlocking {
                withTimeout(TIMEOUT) {
                    it.webSocket("ws://127.0.0.1:$port/ws") {
                        send(Frame.Text(PING))
                        val echoed = incoming.receive()
                        assertTrue(echoed is Frame.Text, "expected a text frame, got $echoed")
                        assertEquals(PING, echoed.readText())
                    }
                }
            }
        }
    }

    /** Minimal stand-in for the App Server `/ws` endpoint: echoes text frames. */
    private fun startEchoServer(): Int {
        val embedded = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws") {
                    for (frame in incoming) {
                        if (frame is Frame.Text) send(Frame.Text(frame.readText()))
                    }
                }
            }
        }
        embedded.start(wait = false)
        server = embedded
        return runBlocking { embedded.engine.resolvedConnectors().first().port }
    }

    private fun productionClients(): List<Pair<String, () -> HttpClient>> = listOf(
        "iroh-wrapper-cli live controller" to ::irohWrapperLiveControllerClient,
        "iroh-wrapper-cli stub controller" to ::irohWrapperStubControllerClient,
        "desktop createDesktopLettaHttpClient" to ::desktopLettaHttpClient,
        "desktop DesktopWsChannelTransport.defaultWsClient" to ::desktopWsChannelTransportClient,
        "appserver-cli restart-replay probe" to ::appServerCliProbeClient,
    )

    // -------------------------------------------- mirrored production configs

    /**
     * SOURCE: `iroh-wrapper-cli/src/main/kotlin/com/letta/mobile/cli/commands/
     * AppServerServeIrohCommand.kt` — `createLiveController`. This is the client
     * that went dark in production on 2026-07-31.
     */
    private fun irohWrapperLiveControllerClient(): HttpClient = HttpClient(ClientCIO) {
        install(ClientWebSockets) { applyAppServerFrameLimits() }
        install(HttpTimeout) {
            requestTimeoutMillis = WRAPPER_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = WRAPPER_REQUEST_TIMEOUT_MS
        }
    }

    /**
     * SOURCE: `AppServerServeIrohCommand.kt` — `createStubController`.
     */
    private fun irohWrapperStubControllerClient(): HttpClient = HttpClient(ClientCIO) {
        install(ClientWebSockets) { applyAppServerFrameLimits() }
    }

    /**
     * SOURCE: `desktop/src/main/kotlin/com/letta/mobile/desktop/chat/
     * DesktopChatGateway.kt` — `createDesktopLettaHttpClient`. The desktop
     * `engine { https { trustManager = NativeTrustManager.trustManager } }`
     * block is deliberately omitted: it is desktop-only wiring that affects TLS
     * trust, not the WebSocket handshake this test guards. Everything that
     * touches engine/plugin compatibility is mirrored.
     */
    private fun desktopLettaHttpClient(): HttpClient = HttpClient(ClientCIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        install(ClientWebSockets) { applyAppServerFrameLimits() }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
    }

    /**
     * SOURCE: `desktop/src/main/kotlin/com/letta/mobile/desktop/data/
     * DesktopWsChannelTransport.kt` — `defaultWsClient` (trust manager omitted,
     * see above).
     */
    private fun desktopWsChannelTransportClient(): HttpClient = HttpClient(ClientCIO) {
        install(ClientWebSockets) { applyAppServerFrameLimits() }
    }

    /**
     * SOURCE: `appserver-cli/src/main/kotlin/com/letta/mobile/appservercli/
     * AppServerRestartReplayProbe.kt` — `withClient`.
     */
    private fun appServerCliProbeClient(): HttpClient = HttpClient(ClientCIO) {
        install(ClientWebSockets) { applyAppServerFrameLimits() }
        install(HttpTimeout) {
            requestTimeoutMillis = PROBE_TURN_TIMEOUT_MS
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = PROBE_TURN_TIMEOUT_MS
        }
    }

    private fun Throwable.chainText(): String = buildString {
        var current: Throwable? = this@chainText
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            append(current::class.simpleName).append(": ").append(current.message).append(" | ")
            current = current.cause
        }
    }

    private companion object {
        val TIMEOUT = 20.seconds
        const val PING = """{"type":"auth","request_id":"vnp3q","token":""}"""
        const val WRAPPER_REQUEST_TIMEOUT_MS = 120_000L
        const val PROBE_TURN_TIMEOUT_MS = 180_000L
    }
}
