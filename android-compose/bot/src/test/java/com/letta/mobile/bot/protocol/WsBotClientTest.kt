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
                chunks[2].text shouldBe null
                chunks[2].event shouldBe null
                chunks[2].toolName shouldBe null
                chunks[2].toolCallId shouldBe null

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
                chunks[2].text shouldBe null
                chunks[2].event shouldBe null
                chunks[2].toolInput shouldBe null

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
                chunks.last().text shouldBe null
                chunks.last().event shouldBe null

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

                // letta-mobile-w2hx.7: the chat row is the source of truth
                // for `conversation_id`. After a reconnect, the caller —
                // here, the test stand-in for AdminChatViewModel — passes
                // the conv id it learned from the first turn. WsBotClient
                // no longer carries an "active conv" fallback that
                // backfills a null arg.
                val second = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "second",
                                agentId = "agent-6",
                                chatId = "chat-6",
                                conversationId = "conv-reconnect",
                            )
                        ).toList()
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

        // letta-mobile-w2hx.8: receive-side demux is keyed on
        // conversation_id with request_id as a fallback. The next
        // three tests exercise the new routing tables directly:
        //   1. fresh-route promotion (null conv → conv-X on first chunk)
        //   2. mid-stream conversation swap (gateway recovery rekeys
        //      the route from conv-A to conv-B)
        //   3. error frames carrying only a request_id still route to
        //      the right in-flight stream
        "fresh force_new reinitializes after prior active conversation" {
            val sessionStarts = mutableListOf<kotlinx.serialization.json.JsonObject>()
            var sawSessionClose = false
            var messageCount = 0
            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> {
                        sessionStarts += payload
                        val requestedConv = payload["conversation_id"]?.jsonPrimitive?.contentOrNull
                        val forceNew = payload["force_new"]?.jsonPrimitive?.content == "true"
                        val initConv = when {
                            forceNew -> "conv-new"
                            requestedConv != null -> requestedConv
                            else -> "conv-old"
                        }
                        socket.send(
                            """
                            {"type":"session_init","agent_id":"agent-fresh","conversation_id":"$initConv","session_id":"sess-${sessionStarts.size}"}
                            """.trimIndent()
                        )
                    }

                    "session_close" -> sawSessionClose = true

                    "message" -> {
                        messageCount += 1
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        val conv = if (messageCount == 1) "conv-old" else "conv-new"
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"ok$messageCount","conversation_id":"$conv","request_id":"$requestId"}
                            """.trimIndent()
                        )
                        socket.send(
                            """
                            {"type":"result","success":true,"conversation_id":"$conv","request_id":"$requestId","duration_ms":4}
                            """.trimIndent()
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")
                val first = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "resume old",
                                agentId = "agent-fresh",
                                chatId = "chat-old",
                                conversationId = "conv-old",
                            )
                        ).toList()
                    }
                }
                first.last().conversationId shouldBe "conv-old"

                val second = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "fresh",
                                agentId = "agent-fresh",
                                chatId = "chat-fresh",
                                conversationId = null,
                                forceNew = true,
                            )
                        ).toList()
                    }
                }

                sawSessionClose shouldBe true
                sessionStarts shouldHaveSize 2
                sessionStarts[0]["conversation_id"]!!.jsonPrimitive.content shouldBe "conv-old"
                sessionStarts[0]["force_new"]?.jsonPrimitive?.content shouldBe "false"
                sessionStarts[1]["conversation_id"]?.jsonPrimitive?.contentOrNull shouldBe null
                sessionStarts[1]["force_new"]?.jsonPrimitive?.content shouldBe "true"
                second.last().conversationId shouldBe "conv-new"
                client.close()
            }
        }

        "send force_new on explicit fresh conversation session_start" {
            var observedForceNew: Boolean? = null
            var observedConversationId: String? = "not-read"
            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> {
                        observedForceNew = payload["force_new"]?.jsonPrimitive?.content == "true"
                        observedConversationId = payload["conversation_id"]?.jsonPrimitive?.contentOrNull
                        socket.send(
                            """
                            {"type":"session_init","agent_id":"agent-fresh","conversation_id":"conv-new","session_id":"sess-fresh"}
                            """.trimIndent()
                        )
                    }

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"ok","conversation_id":"conv-new","request_id":"$requestId"}
                            """.trimIndent()
                        )
                        socket.send(
                            """
                            {"type":"result","success":true,"conversation_id":"conv-new","request_id":"$requestId","duration_ms":4}
                            """.trimIndent()
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")
                val chunks = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "hi",
                                agentId = "agent-fresh",
                                chatId = "chat-fresh",
                                conversationId = null,
                                forceNew = true,
                            )
                        ).toList()
                    }
                }
                observedForceNew shouldBe true
                observedConversationId shouldBe null
                chunks.last().conversationId shouldBe "conv-new"
                client.close()
            }
        }

        "promote pending route into activeRoutes on the first conv-id chunk" {
            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        // session_init requires a conversation_id at the
                        // socket level (it's the gateway's session-bound
                        // default); the per-request route is still fresh
                        // because BotChatRequest.conversationId == null.
                        """
                        {"type":"session_init","agent_id":"agent-fresh","conversation_id":"conv-default","session_id":"sess-fresh"}
                        """.trimIndent()
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        // First chunk announces the brand-new conversation_id —
                        // the route must be promoted from pendingRoutes into
                        // activeRoutes for subsequent chunks to demux correctly.
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"hi","conversation_id":"conv-new","request_id":"$requestId"}
                            """.trimIndent()
                        )
                        // Subsequent chunk arrives without request_id — it must
                        // still find the route via conversation_id lookup.
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":" there","conversation_id":"conv-new"}
                            """.trimIndent()
                        )
                        socket.send(
                            """
                            {"type":"result","success":true,"conversation_id":"conv-new","request_id":"$requestId","duration_ms":4}
                            """.trimIndent()
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")
                val chunks = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "hi",
                                agentId = "agent-fresh",
                                chatId = "chat-fresh",
                                conversationId = null,
                            )
                        ).toList()
                    }
                }
                chunks shouldHaveSize 3
                chunks[0].text shouldBe "hi"
                chunks[0].conversationId shouldBe "conv-new"
                chunks[1].text shouldBe " there"
                chunks[1].conversationId shouldBe "conv-new"
                chunks[2].done shouldBe true
                client.close()
            }
        }

        "rekey route on mid-stream conversation swap" {
            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """
                        {"type":"session_init","agent_id":"agent-swap","conversation_id":"conv-old","session_id":"sess-swap"}
                        """.trimIndent()
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        // Start streaming under the old conv id...
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"a","conversation_id":"conv-old","request_id":"$requestId"}
                            """.trimIndent()
                        )
                        // ...gateway-side recovery swaps the conversation;
                        // demux must rekey activeRoutes from conv-old to conv-new
                        // so this and subsequent frames continue to land on the
                        // same Channel.
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"b","conversation_id":"conv-new","request_id":"$requestId"}
                            """.trimIndent()
                        )
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"c","conversation_id":"conv-new"}
                            """.trimIndent()
                        )
                        socket.send(
                            """
                            {"type":"result","success":true,"conversation_id":"conv-new","request_id":"$requestId","duration_ms":7}
                            """.trimIndent()
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")
                val chunks = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "go",
                                agentId = "agent-swap",
                                chatId = "chat-swap",
                                conversationId = "conv-old",
                            )
                        ).toList()
                    }
                }
                chunks shouldHaveSize 4
                chunks[0].conversationId shouldBe "conv-old"
                chunks[1].conversationId shouldBe "conv-new"
                chunks[2].conversationId shouldBe "conv-new"
                chunks[3].done shouldBe true
                chunks[3].conversationId shouldBe "conv-new"
                client.close()
            }
        }

        "route error frame to in-flight request via request_id when conv-id is absent" {
            val server = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """
                        {"type":"session_init","agent_id":"agent-err","conversation_id":"conv-err","session_id":"sess-err"}
                        """.trimIndent()
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        // Error frame today carries no conversation_id — demux
                        // must fall back to request_id lookup.
                        socket.send(
                            """
                            {"type":"error","code":"STREAM_ERROR","message":"boom","request_id":"$requestId"}
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
                            client.streamMessage(
                                BotChatRequest(
                                    message = "x",
                                    agentId = "agent-err",
                                    chatId = "chat-err",
                                    conversationId = "conv-err",
                                )
                            ).toList()
                        }
                    }
                }.exceptionOrNull() as BotGatewayException
                exception.code shouldBe BotGatewayErrorCode.STREAM_ERROR
                client.close()
            }
        }

        // letta-mobile-w2hx.10: end-to-end no-bleedover acceptance.
        //
        // Two independent WsBotClients (modelling either two connections,
        // or one connection with the gateway's per-agent session pool —
        // either way each chat row gets its own client at the Android
        // boundary) each drive a stream concurrently. Both servers
        // interleave their stream chunks before completing. The
        // architectural guarantee: each client's collected flow contains
        // only its own (agentId, conversationId, request_id) tuple — zero
        // cross-talk, even with overlapping timing. This is the regression
        // test for every bleedover bug we have closed (flk6.sendMessage,
        // forceNew, ConversationManager cache, screenAgentId mismatch).
        "two concurrent streams on different agents do not bleed across clients" {
            // Coordinated barriers so the two servers actually interleave
            // their chunks on the wire rather than running back-to-back.
            val aFirstChunkSent = kotlinx.coroutines.CompletableDeferred<Unit>()
            val bFirstChunkSent = kotlinx.coroutines.CompletableDeferred<Unit>()

            val serverA = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """
                        {"type":"session_init","agent_id":"agent-A","conversation_id":"conv-A","session_id":"sess-A"}
                        """.trimIndent()
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        // Frame 1 from A — under conv-A, request_id rid-A
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"A1","conversation_id":"conv-A","request_id":"$requestId"}
                            """.trimIndent()
                        )
                        aFirstChunkSent.complete(Unit)
                        // Wait for B's first chunk before continuing — guarantees
                        // the two streams are actually interleaved in time.
                        runBlocking { bFirstChunkSent.await() }
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"A2","conversation_id":"conv-A"}
                            """.trimIndent()
                        )
                        socket.send(
                            """
                            {"type":"result","success":true,"conversation_id":"conv-A","request_id":"$requestId","duration_ms":3}
                            """.trimIndent()
                        )
                    }
                }
            }
            val serverB = websocketServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """
                        {"type":"session_init","agent_id":"agent-B","conversation_id":"conv-B","session_id":"sess-B"}
                        """.trimIndent()
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        // Wait for A to send its first chunk first, then B's.
                        runBlocking { aFirstChunkSent.await() }
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"B1","conversation_id":"conv-B","request_id":"$requestId"}
                            """.trimIndent()
                        )
                        bFirstChunkSent.complete(Unit)
                        socket.send(
                            """
                            {"type":"stream","event":"assistant","content":"B2","conversation_id":"conv-B"}
                            """.trimIndent()
                        )
                        socket.send(
                            """
                            {"type":"result","success":true,"conversation_id":"conv-B","request_id":"$requestId","duration_ms":5}
                            """.trimIndent()
                        )
                    }
                }
            }

            serverA.use {
                serverB.use {
                    val clientA = WsBotClient(gatewayUrl(serverA), "secretA")
                    val clientB = WsBotClient(gatewayUrl(serverB), "secretB")

                    val (chunksA, chunksB) = runBlocking {
                        val a = async {
                            withTimeout(5_000) {
                                clientA.streamMessage(
                                    BotChatRequest(
                                        message = "from A",
                                        agentId = "agent-A",
                                        chatId = "chat-A",
                                        conversationId = "conv-A",
                                    )
                                ).toList()
                            }
                        }
                        val b = async {
                            withTimeout(5_000) {
                                clientB.streamMessage(
                                    BotChatRequest(
                                        message = "from B",
                                        agentId = "agent-B",
                                        chatId = "chat-B",
                                        conversationId = "conv-B",
                                    )
                                ).toList()
                            }
                        }
                        a.await() to b.await()
                    }

                    // Architectural guarantee: every chunk in A's flow is
                    // tagged conv-A; every chunk in B's flow is tagged
                    // conv-B. Zero cross-talk, even though the servers
                    // interleaved on the wire.
                    chunksA shouldHaveSize 3
                    chunksA[0].text shouldBe "A1"
                    chunksA[1].text shouldBe "A2"
                    chunksA[2].done shouldBe true
                    chunksA.forEach { chunk ->
                        chunk.conversationId shouldBe "conv-A"
                    }

                    chunksB shouldHaveSize 3
                    chunksB[0].text shouldBe "B1"
                    chunksB[1].text shouldBe "B2"
                    chunksB[2].done shouldBe true
                    chunksB.forEach { chunk ->
                        chunk.conversationId shouldBe "conv-B"
                    }

                    // The terminal chunk from each client carries its own
                    // agentId — chat headers resolved from this never
                    // collide.
                    chunksA[2].agentId shouldBe "agent-A"
                    chunksB[2].agentId shouldBe "agent-B"

                    clientA.close()
                    clientB.close()
                }
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
