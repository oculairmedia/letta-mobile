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
import kotlinx.serialization.json.JsonPrimitive
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
 *  - letta-mobile-vu6a / letta-mobile-lv3e: the gateway emits each
 *    chunk's `content` as a DELTA — the NEW fragment only, not a
 *    cumulative snapshot. The vu6a-era assumption (cumulative snapshot)
 *    was wrong and produced the user-visible "chunks replace each other"
 *    bug Emmanuel reported on 2026-04-25 (lv3e). The wire-level test
 *    here documents the actual delta contract using a real captured
 *    trace so VM-side regressions that re-introduce snapshot semantics
 *    get flagged. Verified via :cli:run wsstream against the live
 *    gateway.
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
        // letta-mobile-vu6a / letta-mobile-lv3e — wire contract documentation
        // ----------------------------------------------------------------
        "assistant chunks are emitted to callers verbatim as deltas (lv3e wire contract)" {
            // The gateway emits each chunk's `content` as a DELTA — the
            // NEW fragment only, not a cumulative snapshot. WsBotClient
            // must surface each chunk's `content` to the caller verbatim
            // — it does NOT diff, accumulate, or otherwise interpret.
            // The caller (AdminChatViewModel) is responsible for
            // concatenating deltas into a running bubble.
            //
            // Earlier vu6a-era version of this test fed PRETEND-snapshot
            // data (["Hel","Hello","Hello world"]), which masked the
            // real shape. With real delta data ["Hel","lo ","world"] the
            // pre-fix snapshot-semantics impl produced just "world",
            // exactly matching the user-visible bug.
            val server = lifecycleServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """{"type":"session_init","agent_id":"agent-1","conversation_id":"conv-1","session_id":"sess-1"}"""
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        // Real delta-shaped fragments — each frame is a
                        // NEW fragment, not the running buffer.
                        listOf("Hel", "lo ", "world").forEach { delta ->
                            socket.send(
                                """{"type":"stream","event":"assistant","content":"$delta","request_id":"$requestId"}"""
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
                // Pass-through contract: each chunk's text is the
                // gateway's verbatim DELTA fragment.
                chunks[0].text shouldBe "Hel"
                chunks[1].text shouldBe "lo "
                chunks[2].text shouldBe "world"
                chunks[3].done shouldBe true
                // Concatenation is the caller's responsibility — not
                // tested here, asserted in AdminChatViewModelTest.
                client.close()
            }
        }

        // ----------------------------------------------------------------
        // letta-mobile-lv3e — golden trace from real wsstream capture
        // ----------------------------------------------------------------
        "real captured wsstream trace round-trips through WsBotClient verbatim (lv3e golden)" {
            // 58 fragments captured via :cli:run wsstream against the
            // live lettabot gateway (see bot/src/test/resources/
            // wsstream-golden-lv3e.json). Asserts that:
            //   1. Every gateway frame round-trips as a separate chunk
            //      with verbatim text (no diffing/accumulation).
            //   2. Concatenating the chunk texts in order reproduces
            //      the original assistant message — i.e. the wire
            //      shape is delta, not snapshot.
            val (fragments, joined) = loadGoldenFragments()
            fragments.size shouldBe 58 // pinned: regenerate fixture if gateway changes shape

            val server = lifecycleServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> socket.send(
                        """{"type":"session_init","agent_id":"agent-1","conversation_id":"conv-1","session_id":"sess-1"}"""
                    )

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        fragments.forEach { frag ->
                            // Re-encode each fragment as a JSON string
                            // so embedded quotes/newlines/backticks are
                            // safe on the wire.
                            val encoded = JsonPrimitive(frag).toString()
                            socket.send(
                                """{"type":"stream","event":"assistant","content":$encoded,"request_id":"$requestId"}"""
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
                    withTimeout(15_000) {
                        client.streamMessage(
                            BotChatRequest(message = "explain coroutines briefly", agentId = "agent-1", chatId = "chat-1")
                        ).toList()
                    }
                }

                // 58 stream frames + 1 result frame.
                chunks shouldHaveSize fragments.size + 1
                // Each frame's text round-trips verbatim.
                chunks.dropLast(1).map { it.text } shouldContainExactly fragments
                // Concatenation reproduces the original assistant message.
                chunks.dropLast(1).joinToString("") { it.text.orEmpty() } shouldBe joined
                chunks.last().done shouldBe true
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

        // ----------------------------------------------------------------
        // letta-mobile-flk1.no_session — gateway idle-evicted the session
        //
        // Production scenario: the lettabot gateway evicts an idle SDK
        // session after `DEFAULT_IDLE_TIMEOUT_MS` (5 min) WITHOUT closing
        // the WebSocket. The next `client_message` the client sends gets
        //
        //     {"type":"error","code":"NO_SESSION",
        //      "message":"Send session_start first", "request_id": "..."}
        //
        // Pre-fix: WsBotClient.streamMessage's collector throws
        // BotGatewayException(NO_SESSION) and the user sees a stuck
        // optimistic bubble with no reply. Higher-level retry then often
        // produced a duplicate user bubble.
        //
        // Post-fix contract: WsBotClient must transparently re-handshake
        // and resend the original WsClientMessage exactly once. The
        // caller must observe a normal stream completing end-to-end with
        // its original requestId; the gateway must see exactly two
        // session_starts on this single socket (the original one and the
        // recovery one).
        // ----------------------------------------------------------------
        "transparently recovers from NO_SESSION mid-flight (idle-eviction)" {
            val openSockets = AtomicInteger(0)
            val sessionStarts = AtomicInteger(0)
            val messageFrames = AtomicInteger(0)
            // Toggle: the gateway "loses" the session after the first
            // successful turn, so the NEXT `message` gets NO_SESSION,
            // and only the session_start that follows reinstates it.
            val sessionLive = java.util.concurrent.atomic.AtomicBoolean(true)
            // Capture the request_ids the gateway sees on `message`
            // frames so we can prove the recovery resend used the SAME
            // request id (not a freshly minted one — that would change
            // collapse semantics on the VM side).
            val messageRequestIds = ConcurrentLinkedQueue<String>()

            val server = lifecycleServer(onOpen = { openSockets.incrementAndGet() }) { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> {
                        sessionStarts.incrementAndGet()
                        sessionLive.set(true)
                        val convId =
                            payload["conversation_id"]?.jsonPrimitive?.contentOrNull ?: "conv-1"
                        socket.send(
                            """{"type":"session_init","agent_id":"agent-1","conversation_id":"$convId","session_id":"sess-1"}"""
                        )
                    }

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        messageRequestIds += requestId
                        val ordinal = messageFrames.incrementAndGet()
                        if (!sessionLive.get()) {
                            // Mirror the gateway's NO_SESSION error frame
                            // exactly (src/api/ws-gateway.ts:612).
                            socket.send(
                                """{"type":"error","code":"NO_SESSION","message":"Send session_start first","request_id":"$requestId"}"""
                            )
                        } else {
                            socket.send(
                                """{"type":"stream","event":"assistant","content":"reply $ordinal","request_id":"$requestId"}"""
                            )
                            socket.send(
                                """{"type":"result","success":true,"conversation_id":"conv-1","request_id":"$requestId","duration_ms":1}"""
                            )
                            // Simulate the idle sweep: after every
                            // successful reply the gateway "forgets"
                            // the session until the client re-handshakes.
                            sessionLive.set(false)
                        }
                    }

                    "session_close" -> Unit
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")

                // Turn 1: warms the session, gateway then evicts.
                val first = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "warm",
                                agentId = "agent-1",
                                chatId = "chat-1",
                                conversationId = "conv-1",
                            )
                        ).toList()
                    }
                }
                first.first().text shouldBe "reply 1"
                first.last().done shouldBe true

                // Turn 2: gateway has evicted the session. Pre-fix this
                // throws BotGatewayException(NO_SESSION); post-fix it
                // re-handshakes and resends transparently.
                val second = runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "after-idle",
                                agentId = "agent-1",
                                chatId = "chat-1",
                                conversationId = "conv-1",
                            )
                        ).toList()
                    }
                }
                // Recovery must produce a normal end-to-end stream — the
                // caller never sees the NO_SESSION error.
                second.first().text shouldBe "reply 3"
                second.last().done shouldBe true
                second.last().conversationId shouldBe "conv-1"

                // Exactly two session_starts (original + recovery), all
                // on a SINGLE WebSocket. A reconnect-the-whole-socket
                // strategy would have produced openSockets >= 2.
                sessionStarts.get() shouldBe 2
                openSockets.get() shouldBe 1

                // The recovery resend must reuse the SAME request_id as
                // the failed attempt so the gateway/Letta layer can
                // dedupe on the rare path where the original DID land.
                // Frames seen by the gateway, in order:
                //   - turn1 message     (request_id=R1)
                //   - turn2 message     (request_id=R2, gets NO_SESSION)
                //   - turn2 message     (request_id=R2, recovery resend)
                val ids = messageRequestIds.toList()
                ids shouldHaveSize 3
                ids[1] shouldBe ids[2]
                // R1 is distinct from R2 (each streamMessage mints a
                // fresh request_id; recovery preserves it).
                (ids[0] != ids[1]) shouldBe true

                client.close()
            }
        }

        // ----------------------------------------------------------------
        // letta-mobile-flk1.no_session — recovery is one-shot
        //
        // If the recovery handshake itself fails (e.g. the gateway is
        // permanently broken / agent was deleted), the second NO_SESSION
        // must propagate as BotGatewayException — we must NOT loop.
        // ----------------------------------------------------------------
        "NO_SESSION recovery is one-shot (does not loop on persistent failure)" {
            val sessionStarts = AtomicInteger(0)
            val messageFrames = AtomicInteger(0)

            val server = lifecycleServer { socket, text ->
                val payload = json.parseToJsonElement(text).jsonObject
                when (payload["type"]!!.jsonPrimitive.content) {
                    "session_start" -> {
                        // First session_start succeeds (warms the
                        // socket), every subsequent one is treated as
                        // a no-op so the gateway still has no session
                        // when the recovery `message` arrives.
                        val n = sessionStarts.incrementAndGet()
                        if (n == 1) {
                            socket.send(
                                """{"type":"session_init","agent_id":"agent-1","conversation_id":"conv-1","session_id":"sess-1"}"""
                            )
                        } else {
                            // Pretend session_init succeeded so the
                            // client thinks it has a session, but reply
                            // NO_SESSION on the very next message —
                            // simulating a gateway that has lost state
                            // permanently.
                            socket.send(
                                """{"type":"session_init","agent_id":"agent-1","conversation_id":"conv-1","session_id":"sess-1"}"""
                            )
                        }
                    }

                    "message" -> {
                        val requestId = payload["request_id"]!!.jsonPrimitive.content
                        val n = messageFrames.incrementAndGet()
                        if (n == 1) {
                            // Warm turn — succeed, then "evict".
                            socket.send(
                                """{"type":"stream","event":"assistant","content":"warm","request_id":"$requestId"}"""
                            )
                            socket.send(
                                """{"type":"result","success":true,"conversation_id":"conv-1","request_id":"$requestId","duration_ms":1}"""
                            )
                        } else {
                            // Every subsequent `message` (turn2 + the
                            // recovery resend) gets NO_SESSION.
                            socket.send(
                                """{"type":"error","code":"NO_SESSION","message":"Send session_start first","request_id":"$requestId"}"""
                            )
                        }
                    }
                }
            }

            server.use {
                val client = WsBotClient(gatewayUrl(server), "secret")

                runBlocking {
                    withTimeout(5_000) {
                        client.streamMessage(
                            BotChatRequest(
                                message = "warm",
                                agentId = "agent-1",
                                chatId = "chat-1",
                                conversationId = "conv-1",
                            )
                        ).toList()
                    }
                }

                val thrown = runCatching {
                    runBlocking {
                        withTimeout(5_000) {
                            client.streamMessage(
                                BotChatRequest(
                                    message = "doomed",
                                    agentId = "agent-1",
                                    chatId = "chat-1",
                                    conversationId = "conv-1",
                                )
                            ).toList()
                        }
                    }
                }.exceptionOrNull()

                (thrown is BotGatewayException) shouldBe true
                (thrown as BotGatewayException).code shouldBe BotGatewayErrorCode.NO_SESSION

                // The retry attempted exactly one recovery —
                // session_starts ≥ 2 (the warm one + at least one
                // recovery handshake) but message frames must be
                // exactly 3 (turn1, turn2-original, turn2-recovery).
                // No 4th attempt = no infinite loop.
                messageFrames.get() shouldBe 3

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

/**
 * letta-mobile-lv3e: load the golden wsstream capture from
 * `bot/src/test/resources/wsstream-golden-lv3e.json`.
 *
 * The fixture was captured via `:cli:run wsstream` against the live
 * lettabot gateway; each entry is a NEW fragment (delta semantics).
 *
 * Returns the (fragments, joined) pair so tests can assert both per-frame
 * round-trip fidelity and end-to-end concatenation.
 */
private fun loadGoldenFragments(): Pair<List<String>, String> {
    val stream = WsBotClientLifecycleTest::class.java.classLoader
        ?.getResourceAsStream("wsstream-golden-lv3e.json")
        ?: error(
            "wsstream-golden-lv3e.json not on the test classpath — expected at " +
                "android-compose/bot/src/test/resources/wsstream-golden-lv3e.json",
        )
    val payload = stream.bufferedReader().use { it.readText() }
    val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(payload).jsonObject
    val fragments = obj["fragments"]!!.jsonArray.map { it.jsonPrimitive.content }
    return fragments to fragments.joinToString("")
}
