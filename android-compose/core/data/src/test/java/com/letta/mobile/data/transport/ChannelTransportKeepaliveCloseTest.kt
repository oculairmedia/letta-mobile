package com.letta.mobile.data.transport

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KEEPALIVE_CLOSE_TEST_TIMEOUT_MS = 5_000L

class ChannelTransportKeepaliveCloseTest {
    @Test
    fun `after welcome resumes stored active run cursor`() = runBlocking {
        val shim = KeepaliveCloseShimServer()
        val cursorStore = RunCursorStore.inMemory().also { it.record("conv-1", "run-1", 7L) }
        val transport = ChannelTransport(cursorStore)

        try {
            transport.connect(
                baseShimUrl = shim.baseUrl(),
                token = "token",
                deviceId = "device",
                clientVersion = "test",
            )

            shim.frames.receiveType("hello")
            val subscribe = shim.frames.receiveType("subscribe")

            assertEquals("run-1", subscribe.stringValue("run_id"))
            assertEquals(7L, subscribe.longValue("cursor"))
        } finally {
            transport.disconnect()
            shim.close()
        }
    }

    @Test
    fun `client normal close does not reconnect`() = runBlocking {
        val shim = KeepaliveCloseShimServer()
        val transport = ChannelTransport(RunCursorStore.inMemory())

        try {
            transport.connect(
                baseShimUrl = shim.baseUrl(),
                token = "token",
                deviceId = "device",
                clientVersion = "test",
            )
            shim.frames.receiveType("hello")

            transport.disconnect()
            kotlinx.coroutines.delay(1_500L)
            assertEquals(1, shim.helloCount.get())
        } finally {
            transport.disconnect()
            shim.close()
        }
    }

    @Test
    fun `auth close does not reconnect`() = runBlocking {
        val shim = KeepaliveCloseShimServer()
        val transport = ChannelTransport(RunCursorStore.inMemory())

        try {
            transport.connect(
                baseShimUrl = shim.baseUrl(),
                token = "token",
                deviceId = "device",
                clientVersion = "test",
            )
            shim.frames.receiveType("hello")
            shim.closeFirstSocket(4401, "unauthorized")
            val disconnected = withTimeout(KEEPALIVE_CLOSE_TEST_TIMEOUT_MS) {
                transport.state.first { it is ChannelTransportState.Disconnected } as ChannelTransportState.Disconnected
            }

            kotlinx.coroutines.delay(1_500L)
            assertEquals(false, disconnected.willReconnect)
            assertEquals(1, shim.helloCount.get())
        } finally {
            transport.disconnect()
            shim.close()
        }
    }

    @Test
    fun `backoff sequence increases and resets on welcome`() = runBlocking {
        val connection = WebSocketConnection(this, Json { ignoreUnknownKeys = true })
        val first = connection.backoffDelayMs(1)
        val second = connection.backoffDelayMs(2)
        val third = connection.backoffDelayMs(3)

        assertTrue(first in 1_000L..1_200L)
        assertTrue(second in 2_000L..2_400L)
        assertTrue(third in 4_000L..4_800L)

        connection.resetReconnectBackoff()
        assertTrue(connection.backoffDelayMs(1) in 1_000L..1_200L)
    }

    @Test
    fun `redials when shim closes for protocol keepalive timeout`() = runBlocking {
        val shim = KeepaliveCloseShimServer()
        val transport = ChannelTransport(RunCursorStore.inMemory())

        try {
            transport.connect(
                baseShimUrl = shim.baseUrl(),
                token = "token",
                deviceId = "device",
                clientVersion = "test",
            )

            val firstHello = shim.frames.receiveType("hello")
            withTimeout(KEEPALIVE_CLOSE_TEST_TIMEOUT_MS) {
                transport.state.first { it is ChannelTransportState.Connected }
            }
            shim.closeFirstSocketAsKeepaliveTimeout()
            val disconnected = withTimeout(KEEPALIVE_CLOSE_TEST_TIMEOUT_MS) {
                transport.state.first { it is ChannelTransportState.Disconnected } as ChannelTransportState.Disconnected
            }
            assertEquals(true, disconnected.willReconnect)
            val secondHello = shim.frames.receiveType("hello")

            withTimeout(KEEPALIVE_CLOSE_TEST_TIMEOUT_MS) {
                transport.state.first { it is ChannelTransportState.Connected }
            }

            assertEquals("hello", firstHello.stringValue("type"))
            assertEquals("hello", secondHello.stringValue("type"))
            assertEquals(2, shim.helloCount.get())
        } finally {
            transport.disconnect()
            shim.close()
        }
    }

    private class KeepaliveCloseShimServer {
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        private val server = MockWebServer()
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        private val sockets = Channel<WebSocket>(Channel.UNLIMITED)
        val helloCount = AtomicInteger(0)

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                sockets.trySend(webSocket)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val frame = json.parseToJsonElement(text).jsonObject
                                frames.trySend(frame)
                                if (frame.stringValue("type") == "hello") {
                                    val count = helloCount.incrementAndGet()
                                    webSocket.send(welcomeFrame(count))
                                }
                            }

                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                webSocket.close(code, reason)
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
                        }
                    )
            }
            server.start()
        }

        fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

        suspend fun closeFirstSocketAsKeepaliveTimeout() {
            closeFirstSocket(ChannelTransport.KEEPALIVE_PONG_TIMEOUT_CLOSE_CODE, "pong timeout")
        }

        suspend fun closeFirstSocket(code: Int, reason: String) {
            withTimeout(KEEPALIVE_CLOSE_TEST_TIMEOUT_MS) {
                sockets.receive()
            }.close(code, reason)
        }

        fun close() {
            frames.close()
            sockets.close()
            server.shutdown()
        }

        private fun welcomeFrame(index: Int): String =
            """
            {"v":1,"type":"welcome","id":"welcome-$index","ts":"2026-05-27T00:00:00Z",
             "server_id":"server","session_id":"session-$index"}
            """.trimIndent()
    }
}

private suspend fun Channel<JsonObject>.receiveType(type: String): JsonObject =
    withTimeout(KEEPALIVE_CLOSE_TEST_TIMEOUT_MS) {
        while (true) {
            val frame = receive()
            if (frame.stringValue("type") == type) return@withTimeout frame
        }
        error("unreachable")
    }

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.longValue(key: String): Long? =
    this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
