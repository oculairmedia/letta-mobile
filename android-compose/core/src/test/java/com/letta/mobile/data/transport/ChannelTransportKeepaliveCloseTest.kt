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
import org.junit.Test

private const val KEEPALIVE_CLOSE_TEST_TIMEOUT_MS = 5_000L

class ChannelTransportKeepaliveCloseTest {
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
                transport.state.first { it is ChannelTransport.State.Connected }
            }
            shim.closeFirstSocketAsKeepaliveTimeout()
            val secondHello = shim.frames.receiveType("hello")

            withTimeout(KEEPALIVE_CLOSE_TEST_TIMEOUT_MS) {
                transport.state.first { it is ChannelTransport.State.Connected }
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
            withTimeout(KEEPALIVE_CLOSE_TEST_TIMEOUT_MS) {
                sockets.receive()
            }.close(
                ChannelTransport.KEEPALIVE_PONG_TIMEOUT_CLOSE_CODE,
                "pong timeout",
            )
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
