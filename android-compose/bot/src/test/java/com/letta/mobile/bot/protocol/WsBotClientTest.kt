package com.letta.mobile.bot.protocol

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Tag

@Tag("unit")
class WsBotClientTest : WordSpec({
    val json = Json { ignoreUnknownKeys = true }

    fun gatewayUrl(server: MockWebServer): String = server.url("/").toString().removeSuffix("/")

    "WsBotClient" should {
        "stream happy path events and finish on result" {
            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """
                        {"type":"session_init","agent_id":"agent-1","conversation_id":"conv-1","session_id":"sess-1"}
                        """.trimIndent()
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send("{" +
                            "\"type\":\"stream\",\"event\":\"assistant\",\"content\":\"Hello\",\"request_id\":\"$requestId\"}")
                        socket.send("{" +
                            "\"type\":\"stream\",\"event\":\"assistant\",\"content\":\" world\",\"request_id\":\"$requestId\"}")
                        socket.send("{" +
                            "\"type\":\"result\",\"success\":true,\"conversation_id\":\"conv-1\",\"request_id\":\"$requestId\",\"duration_ms\":12}")
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")

                val chunks = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(BotChatRequest(message = "hi", agentId = "agent-1", chatId = "chat-1")).toList()
                    }
                }

                chunks shouldHaveSize 3
                chunks[0].text shouldBe "Hello"
                chunks[0].event shouldBe BotStreamEvent.ASSISTANT
                chunks[1].text shouldBe " world"
                chunks[2].done shouldBe true
                chunks[2].conversationId shouldBe "conv-1"
                chunks[2].agentId shouldBe "agent-1"

                runBlocking { client.close() }
            }
        }

        "serialize letta mobile source metadata into websocket messages" {
            var receivedSourceChannel: String? = null
            var receivedSourceChatId: String? = null

            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """
                        {"type":"session_init","agent_id":"agent-source","conversation_id":"conv-source","session_id":"sess-source"}
                        """.trimIndent()
                    )

                    "message" -> {
                        val source = payload["source"]!!.jsonObject
                        receivedSourceChannel = source["channel"]!!.jsonPrimitive.content
                        receivedSourceChatId = source["chatId"]!!.jsonPrimitive.content
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send(
                            """
                            {"type":"result","success":true,"conversation_id":"conv-source","request_id":"$requestId","duration_ms":1}
                            """.trimIndent()
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")

                runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "hello",
                                agentId = "agent-source",
                                channelId = "letta-mobile",
                                chatId = "agent:agent-1",
                            )
                        ).toList()
                    }
                }

                receivedSourceChannel shouldBe "letta-mobile"
                receivedSourceChatId shouldBe "agent:agent-1"
                client.close()
            }
        }

        "forward tool call and tool result metadata" {
            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """
                        {"type":"session_init","agent_id":"agent-2","conversation_id":"conv-tools","session_id":"sess-tools"}
                        """.trimIndent()
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send(
                            """
                            {"type":"stream","event":"tool_call","tool_name":"search","tool_call_id":"call-1","tool_input":{"query":"kotlin"},"request_id":"$requestId"}
                            """.trimIndent()
                        )
                        socket.send(
                            """
                            {"type":"stream","event":"tool_result","tool_name":"search","tool_call_id":"call-1","content":"done","is_error":false,"request_id":"$requestId"}
                            """.trimIndent()
                        )
                        socket.send(
                            """
                            {"type":"result","success":true,"conversation_id":"conv-tools","request_id":"$requestId","duration_ms":8}
                            """.trimIndent()
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")

                val chunks = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(BotChatRequest(message = "find", agentId = "agent-2", chatId = "chat-2")).toList()
                    }
                }

                chunks[0].event shouldBe BotStreamEvent.TOOL_CALL
                chunks[0].toolName shouldBe "search"
                chunks[0].toolCallId shouldBe "call-1"
                chunks[0].toolInput!!.jsonObject["query"]!!.jsonPrimitive.content shouldBe "kotlin"
                chunks[1].event shouldBe BotStreamEvent.TOOL_RESULT
                chunks[1].text shouldBe "done"
                chunks[1].toolCallId shouldBe "call-1"
                chunks[2].done shouldBe true

                client.close()
            }
        }

        "abort an in-flight request and emit a terminal aborted chunk" {
            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """
                        {"type":"session_init","agent_id":"agent-3","conversation_id":"conv-abort","session_id":"sess-abort"}
                        """.trimIndent()
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"partial","request_id":"$requestId"}
                            """.trimIndent()
                        )
                    }

                    "abort" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send(
                            """
                            {"type":"result","success":false,"aborted":true,"conversation_id":"conv-abort","request_id":"$requestId","duration_ms":1}
                            """.trimIndent()
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")

                val chunks = runBlocking {
                    val chunksDeferred = async {
                        withTimeout(5_000) {
                            client.streamMessage(BotChatRequest(message = "stop", agentId = "agent-3", chatId = "chat-3")).toList()
                        }
                    }

                    withTimeout(5_000) {
                        while (client.connectionState.value != ConnectionState.PROCESSING) {
                            delay(10)
                        }
                    }
                    client.abort()
                    chunksDeferred.await()
                }

                chunks.last().done shouldBe true
                chunks.last().aborted shouldBe true

                client.close()
            }
        }

        "surface SESSION_BUSY as a typed exception" {
            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """
                        {"type":"session_init","agent_id":"agent-4","conversation_id":"conv-busy","session_id":"sess-busy"}
                        """.trimIndent()
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send(
                            """
                            {"type":"error","code":"SESSION_BUSY","message":"Busy","request_id":"$requestId"}
                            """.trimIndent()
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")

                val exception = runCatching {
                    runBlocking {
                        withTimeout(5_000) {
                            client.streamMessage(BotChatRequest(message = "hi", agentId = "agent-4", chatId = "chat-4")).toList()
                        }
                    }
                }.exceptionOrNull() as BotGatewayException

                exception.code shouldBe BotGatewayErrorCode.SESSION_BUSY
                client.close()
            }
        }

        "surface AUTH_FAILED on bad key and return to CLOSED" {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(401)
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "bad-key")

                val exception = runCatching {
                    runBlocking {
                        withTimeout(5_000) {
                            client.streamMessage(BotChatRequest(message = "hi", agentId = "agent-5", chatId = "chat-5")).toList()
                        }
                    }
                }.exceptionOrNull() as BotGatewayException

                exception.code shouldBe BotGatewayErrorCode.AUTH_FAILED
                runBlocking {
                    withTimeout(2_000) {
                        while (client.connectionState.value != ConnectionState.CLOSED) {
                            delay(10)
                        }
                    }
                }
                client.close()
            }
        }

        "read status when agents arrives as an object map" {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/api/v1/status" -> MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                """
                                {
                                  "agents": {
                                    "LettaBot": {"agentId": "agent-1", "status": "ready"},
                                    "PM - letta-mobile": {"agentId": "agent-2", "status": "ready"}
                                  },
                                  "session_count": 2
                                }
                                """.trimIndent()
                            )

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")

                val status = runBlocking {
                    withTimeout(5_000) {
                        client.getStatus()
                    }
                }

                status.status shouldBe "ok"
                status.agents shouldContainExactly listOf("LettaBot", "PM - letta-mobile")
                status.sessionCount shouldBe 2
                status.agentDetails shouldContainExactly listOf(
                    BotAgentInfo(id = "agent-1", name = "LettaBot", status = "ready"),
                    BotAgentInfo(id = "agent-2", name = "PM - letta-mobile", status = "ready"),
                )
                client.close()
            }
        }

        "establish a gateway session without sending a message" {
            val receivedTypes = mutableListOf<String>()
            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                receivedTypes += payload["type"]!!.jsonPrimitive.content
                if (payload["type"]!!.jsonPrimitive.content == "session_start") {
                    socket.send(
                        """
                        {"type":"session_init","agent_id":"agent-ready","conversation_id":"conv-ready","session_id":"sess-ready"}
                        """.trimIndent()
                    )
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")

                runBlocking {
                    withTimeout(5_000) {
                        client.ensureGatewayReady(agentId = "agent-ready")
                    }
                }

                receivedTypes shouldContainExactly listOf("session_start")
                client.connectionState.value shouldBe ConnectionState.READY
                client.close()
            }
        }

        "reconnect and reuse the previous conversation id" {
            val sessionStarts = mutableListOf<String?>()
            var lastSocket: WebSocket? = null
            var connectionCount = 0

            val server = websocketServer { socket, text ->
                lastSocket = socket
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> {
                        sessionStarts += payload["conversation_id"]?.jsonPrimitive?.contentOrNull
                        connectionCount++
                        val conversationId = payload["conversation_id"]?.jsonPrimitive?.contentOrNull ?: "conv-reconnect"
                        socket.send(
                            """
                            {"type":"session_init","agent_id":"agent-6","conversation_id":"$conversationId","session_id":"sess-$connectionCount"}
                            """.trimIndent()
                        )
                    }

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"ok-$connectionCount","request_id":"$requestId"}
                            """.trimIndent()
                        )
                        socket.send(
                            """
                            {"type":"result","success":true,"conversation_id":"conv-reconnect","request_id":"$requestId","duration_ms":2}
                            """.trimIndent()
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")

                val first = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(BotChatRequest(message = "first", agentId = "agent-6", chatId = "chat-6")).toList()
                    }
                }
                first.last().conversationId shouldBe "conv-reconnect"

                runBlocking {
                    lastSocket!!.close(1011, "drop")
                    withTimeout(5_000) {
                        while (client.connectionState.value != ConnectionState.READY ||
                            sessionStarts.filterNotNull().isEmpty()) {
                            delay(20)
                        }
                    }
                }

                val second = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(BotChatRequest(message = "second", agentId = "agent-6", chatId = "chat-6")).toList()
                    }
                }

                second.first().text shouldBe "ok-2"
                sessionStarts.first() shouldBe null
                sessionStarts.filterNotNull().distinct() shouldContainExactly listOf("conv-reconnect")
                client.close()
            }
        }

        "serialize multimodal content arrays as JSON strings" {
            var sentContent: String? = null

            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """
                        {"type":"session_init","agent_id":"agent-7","conversation_id":"conv-mm","session_id":"sess-mm"}
                        """.trimIndent()
                    )

                    "message" -> {
                        sentContent = payload["content"]!!.jsonPrimitive.content
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send(
                            """
                            {"type":"result","success":true,"conversation_id":"conv-mm","request_id":"$requestId","duration_ms":3}
                            """.trimIndent()
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")
                runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "ignored",
                                agentId = "agent-7",
                                chatId = "chat-7",
                                contentItems = listOf(
                                    BotMessageContentItem.text("caption"),
                                    BotMessageContentItem.image(base64 = "AAAA", mediaType = "image/png"),
                                ),
                            )
                        ).toList()
                    }
                }

                val encoded = json.parseToJsonElement(sentContent!!).jsonArray
                encoded[0].jsonObject["type"]!!.jsonPrimitive.content shouldBe "text"
                encoded[0].jsonObject["text"]!!.jsonPrimitive.content shouldBe "caption"
                encoded[1].jsonObject["type"]!!.jsonPrimitive.content shouldBe "image"
                encoded[1].jsonObject["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content shouldBe "image/png"
                client.close()
            }
        }
    }
})

private fun websocketServer(onFrame: (WebSocket, String) -> Unit): MockWebServer {
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            onFrame(webSocket, text)
                        }
                    }
                )
        }
    }
    server.start()
    return server
}
