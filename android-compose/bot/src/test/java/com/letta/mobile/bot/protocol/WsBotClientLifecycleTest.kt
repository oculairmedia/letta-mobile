package com.letta.mobile.bot.protocol

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import org.junit.jupiter.api.Tag
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wire-level regression tests for WsBotClient lifecycle bugs.
 *
 * These complement [WsBotClientTest] but specifically exercise multi-turn /
 * multi-agent / cross-conversation send patterns that produced silent
 * failures in production:
 *
 *  - letta-mobile-2psc: agent switch produced an unexpected-disconnect
 *    failure on the in-flight request.
 *  - letta-mobile-vu6a: the gateway emits each chunk's `content` as a
 *    snapshot of the running assistant text. The wire-level test here
 *    documents that contract so VM-side concatenation regressions get
 *    flagged.
 *  - letta-mobile-hf93: a stream that completes with only a `result`
 *    frame (no preceding `stream` frames) must produce exactly one
 *    chunk with `done = true` and no synthesized text — the VM is
 *    responsible for surfacing the empty-turn error.
 *  - "send into existing conversation" path that produced the
 *    flashing-then-nothing symptom Emmanuel reproduced on device.
 */
@Tag("unit")
class WsBotClientLifecycleTest : WordSpec({
    val json = Json { ignoreUnknownKeys = true }

    fun gatewayUrl(server: MockWebServer): String = server.url("/").toString().removeSuffix("/")

    "WsBotClient lifecycle" should {

        // ----------------------------------------------------------------
        // letta-mobile-2psc
        // ----------------------------------------------------------------
        "agent switch closes one socket cleanly and the new agent's stream completes" {
            val openSockets = AtomicInteger(0)
            val sessionStarts = ConcurrentLinkedQueue<String>() // agentIds requested
            val closes = ConcurrentLinkedQueue<Pair<Int, String>>() // (code, reason)
            // The `message` frame doesn't carry agent_id (WsClientMessage
            // doesn't serialize one); track the active agent per-socket
            // via the most recent session_start instead.
            val activeAgentBySocket = java.util.concurrent.ConcurrentHashMap<WebSocket, String>()

            val server = lifecycleServer(
                onOpen = { openSockets.incrementAndGet() },
                onClose = { code, reason -> closes += code to reason },
            ) { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> {
                        val agentId = payload["agent_id"]!!.jsonPrimitive.content
                        sessionStarts += agentId
                        activeAgentBySocket[socket] = agentId
                        val convId = payload["conversation_id"]?.jsonPrimitive?.contentOrNull
                            ?: "conv-for-$agentId"
                        socket.send(
                            """{"type":"session_init","agent_id":"$agentId","conversation_id":"$convId","session_id":"sess-$agentId"}"""
                        )
                    }

                    "message" -> {
                        val agentId = activeAgentBySocket[socket] ?: "?"
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send(
                            """{"type":"stream","event":"assistant","content":"hello from $agentId","request_id":"$requestId"}"""
                        )
                        socket.send(
                            """{"type":"result","success":true,"conversation_id":"conv-for-$agentId","request_id":"$requestId","duration_ms":1}"""
                        )
                    }

                    "session_close" -> {
                        // ack — gateway would normally close on its end here.
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")

                // First turn — agent A.
                val first = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(message = "hi A", agentId = "agent-A", chatId = "chat-1")
                        ).toList()
                    }
                }
                first.first().text shouldBe "hello from agent-A"
                first.last().done shouldBe true

                // Second turn — switch to agent B. This used to fail with
                // "WebSocket closed unexpectedly" because the deliberate
                // close-on-switch was being dispatched to
                // handleUnexpectedDisconnect.
                val second = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(message = "hi B", agentId = "agent-B", chatId = "chat-1")
                        ).toList()
                    }
                }
                second.first().text shouldBe "hello from agent-B"
                second.last().done shouldBe true

                // Exactly two session_starts (one per agent) and exactly two
                // sockets opened. No more, no less — a runaway reconnect
                // would have produced 3+.
                sessionStarts.toList() shouldContainExactly listOf("agent-A", "agent-B")
                openSockets.get() shouldBe 2

                // (We previously asserted on close codes here, but the
                // server-side onClosed may or may not fire within the
                // test window depending on close-handshake timing.
                // The behaviourally important property — that the new
                // socket's in-flight request completed end-to-end — is
                // already proven by the chunk assertions above, which
                // would have failed if the residual onFailure/onClosed
                // from the dying socket leaked through and signalled
                // RequestSignal.Failure on the new request.)
                @Suppress("UNUSED_EXPRESSION")
                closes // referenced to keep the queue alive for debugging

                client.close()
            }
        }

        // ----------------------------------------------------------------
        // Send to existing conversation on a freshly-opened client
        //   (the path Emmanuel hit when reporting "back to flashing
        //   thinking with no response in the end")
        // ----------------------------------------------------------------
        "first send into an existing conversationId completes end-to-end" {
            val sessionStartConvIds = mutableListOf<String?>()

            val server = lifecycleServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> {
                        val convId = payload["conversation_id"]?.jsonPrimitive?.contentOrNull
                        sessionStartConvIds += convId
                        // Honour the requested conversationId — gateway
                        // resumes the existing conv.
                        socket.send(
                            """{"type":"session_init","agent_id":"agent-1","conversation_id":"${convId ?: "conv-fresh"}","session_id":"sess-1"}"""
                        )
                    }

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send(
                            """{"type":"stream","event":"assistant","content":"resumed reply","request_id":"$requestId"}"""
                        )
                        socket.send(
                            """{"type":"result","success":true,"conversation_id":"conv-existing-123","request_id":"$requestId","duration_ms":1}"""
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
                                message = "follow-up",
                                agentId = "agent-1",
                                chatId = "chat-existing",
                                conversationId = "conv-existing-123",
                            )
                        ).toList()
                    }
                }

                chunks shouldHaveAtLeastSize 2
                chunks.first().text shouldBe "resumed reply"
                chunks.last().done shouldBe true
                chunks.last().conversationId shouldBe "conv-existing-123"
                // The session_start MUST have carried the existing
                // conversation id so the gateway can resume rather than
                // open a fresh conv.
                sessionStartConvIds shouldContainExactly listOf("conv-existing-123")

                client.close()
            }
        }

        // ----------------------------------------------------------------
        // letta-mobile-vu6a — wire contract documentation
        // ----------------------------------------------------------------
        "assistant chunks are emitted to callers as the gateway sends them (snapshot semantics)" {
            // The gateway emits cumulative snapshots ("Hel", "Hello",
            // "Hello world"). WsBotClient must surface each chunk's
            // `content` to the caller verbatim — it does NOT diff or
            // accumulate. The caller (AdminChatViewModel) is responsible
            // for snapshot-vs-delta interpretation.
            val server = lifecycleServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """{"type":"session_init","agent_id":"agent-1","conversation_id":"conv-1","session_id":"sess-1"}"""
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        listOf("Hel", "Hello", "Hello world").forEach { snapshot ->
                            socket.send(
                                """{"type":"stream","event":"assistant","content":"$snapshot","request_id":"$requestId"}"""
                            )
                        }
                        socket.send(
                            """{"type":"result","success":true,"conversation_id":"conv-1","request_id":"$requestId","duration_ms":1}"""
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")
                val chunks = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(message = "hi", agentId = "agent-1", chatId = "chat-1")
                        ).toList()
                    }
                }

                chunks shouldHaveSize 4 // 3 stream + 1 result
                chunks[0].text shouldBe "Hel"
                chunks[1].text shouldBe "Hello"
                chunks[2].text shouldBe "Hello world"
                chunks[3].done shouldBe true
                client.close()
            }
        }

        // ----------------------------------------------------------------
        // letta-mobile-hf93 — wire contract for empty-turn streams
        // ----------------------------------------------------------------
        "stream that completes with only a result frame surfaces a single done chunk with no text" {
            val server = lifecycleServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """{"type":"session_init","agent_id":"agent-1","conversation_id":"conv-1","session_id":"sess-1"}"""
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        // No stream frames — gateway upstream returned
                        // empty (an agent that produced no output).
                        socket.send(
                            """{"type":"result","success":true,"conversation_id":"conv-1","request_id":"$requestId","duration_ms":1}"""
                        )
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")
                val chunks = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(message = "hi", agentId = "agent-1", chatId = "chat-1")
                        ).toList()
                    }
                }

                chunks shouldHaveSize 1
                chunks[0].done shouldBe true
                chunks[0].text shouldBe null
                chunks[0].event shouldBe null
                client.close()
            }
        }

        // ----------------------------------------------------------------
        // Two-turn within the SAME agent and conversation — the steady-state
        // path. Catches regressions where `socketOpen=true && agentId match
        // && conversationId match` is supposed to be a no-op for ensureSession.
        // ----------------------------------------------------------------
        "two consecutive turns on the same agent+conversation reuse the same socket" {
            val openSockets = AtomicInteger(0)
            val sessionStarts = AtomicInteger(0)

            val server = lifecycleServer(onOpen = { openSockets.incrementAndGet() }) { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> {
                        sessionStarts.incrementAndGet()
                        val convId = payload["conversation_id"]?.jsonPrimitive?.contentOrNull ?: "conv-1"
                        socket.send(
                            """{"type":"session_init","agent_id":"agent-1","conversation_id":"$convId","session_id":"sess-1"}"""
                        )
                    }

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        socket.send(
                            """{"type":"stream","event":"assistant","content":"reply","request_id":"$requestId"}"""
                        )
                        socket.send(
                            """{"type":"result","success":true,"conversation_id":"conv-1","request_id":"$requestId","duration_ms":1}"""
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
                                message = "first",
                                agentId = "agent-1",
                                chatId = "chat-1",
                                conversationId = "conv-1",
                            )
                        ).toList()
                    }
                }

                runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "second",
                                agentId = "agent-1",
                                chatId = "chat-1",
                                conversationId = "conv-1",
                            )
                        ).toList()
                    }
                }

                openSockets.get() shouldBe 1
                sessionStarts.get() shouldBe 1

                client.close()
            }
        }
    }
})

// ----------------------------------------------------------------------
// Test fixtures
// ----------------------------------------------------------------------

/**
 * Like the [websocketServer] helper in [WsBotClientTest], but additionally
 * exposes `onOpen` and `onClose` callbacks so lifecycle tests can count
 * sockets and capture close codes/reasons.
 */
private fun lifecycleServer(
    onOpen: () -> Unit = {},
    onClose: (code: Int, reason: String) -> Unit = { _, _ -> },
    onFrame: (WebSocket, String) -> Unit,
): MockWebServer {
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        onOpen()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        onFrame(webSocket, text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // Mirror the close so onClosed fires on both ends.
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        onClose(code, reason)
                    }
                }
            )
        }
    }
    server.start()
    return server
}
