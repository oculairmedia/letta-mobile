package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.node.serveIrohCarrierLink
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.controller.node.iroh.IrohNodeProtocolHandler
import computer.iroh.Connection
import kotlinx.coroutines.CompletableDeferred
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.stampDraft
import com.letta.mobile.data.runtime.turnStreamIdentityFor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * FULL-STACK on-host harness for the mobile Iroh chat path (g3cva.8 automation).
 *
 * Exercises the EXACT client the device uses — [IrohChannelTransport.send] — against
 * a real in-process [IrohNodeEndpoint] server bridge driven by a stub controller that
 * emits a known assistant response. No device, no manual taps, no separate wrapper.
 *
 * This reproduces the device round-trip automatically so transport/render bugs can be
 * iterated against a fast green/red signal:
 *   client: IrohChannelTransport(DEBUG_FORCE_IROH_URL=ticket).connect() -> send()
 *   server: IrohNodeEndpoint.start(stubController) -> IrohNodeConnection.serve()
 *   assert: client.events emits AssistantMessage(content contains expected) + TurnDone(completed)
 *
 * These tests dial a REAL QUIC connection via iroh-ffi, but only on this machine: the host binds
 * with relays disabled and its ticket carries its direct addresses (see [loopbackHostBinding]), so
 * nothing reaches a public relay or address lookup and the suite runs in the default jvmTest.
 * Several tests send the same client message id to their own host: each host remembers only the
 * turns it was sent, so none of them may be taken for a re-send.
 */
class IrohChannelTransportEndToEndTest {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private suspend fun runNotebookProtocolRoutingTest(
        createHandler: (CompletableDeferred<String>) -> IrohNodeProtocolHandler,
    ): String {
        val notebookAlpn = "/letta/notebook/1".encodeToByteArray()
        val resultPeer = CompletableDeferred<String>()
        val server = IrohNodeEndpoint(
            binding = loopbackHostBinding(),
            appServerLinks = ::serveIrohCarrierLink,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            authPolicy = IrohAuthPolicy.PeerAllowlist(setOf("other-app-server-peer")),
            protocolHandlers = listOf(createHandler(resultPeer)),
        )
        val client = bindLoopbackEndpoint()
        try {
            server.create()
            server.start(ScriptedReplyController { ASSISTANT_REPLY })
            val connection = client.connect(server.addr(), notebookAlpn)
            val actualPeer = withTimeout(15.seconds) { resultPeer.await() }
            assertEquals(
                IrohDiagnostics.endpointIdHex(client.addr().id()),
                actualPeer,
            )
            connection.close(0L, ByteArray(0))
            return actualPeer
        } finally {
            client.shutdown()
            client.close()
            server.shutdown()
        }
    }

    @Test
    fun hostRoutesNotebookAlpnWithoutAppServerAuthentication() = runBlocking {
        val notebookAlpn = "/letta/notebook/1".encodeToByteArray()
        val routed = runNotebookProtocolRoutingTest { acceptedPeer ->
            object : IrohNodeProtocolHandler {
                override val alpn = notebookAlpn
                override fun authorize(remoteEndpointId: String) = true
                override suspend fun accept(connection: Connection, remoteEndpointId: String) {
                    acceptedPeer.complete(remoteEndpointId)
                    connection.close(0L, ByteArray(0))
                }
            }
        }
        assertTrue(routed.isNotEmpty())
    }

    @Test
    fun notebookHandlerRejectsUnauthorizedPeerWithoutCallingAccept() = runBlocking {
        val notebookAlpn = "/letta/notebook/1".encodeToByteArray()
        val denied = runNotebookProtocolRoutingTest { denied ->
            object : IrohNodeProtocolHandler {
                override val alpn = notebookAlpn
                override fun authorize(remoteEndpointId: String): Boolean {
                    denied.complete(remoteEndpointId)
                    return false
                }
                override suspend fun accept(connection: Connection, remoteEndpointId: String) {
                    error("Unauthorized peer reached notebook handler")
                }
            }
        }
        assertTrue(denied.isNotEmpty())
    }

    @Test
    fun deviceSendOverIrohRoundTripsAssistantResponse() = runBlocking {
        val server = IrohNodeEndpoint(
            binding = loopbackHostBinding(),
            appServerLinks = ::serveIrohCarrierLink,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
        )
        server.create()
        server.start(ScriptedReplyController { ASSISTANT_REPLY })
        val ticket = server.ticketString()

        val transport = IrohChannelTransport(
            scope = clientScope,
            onConnect = {},
            forcedIrohUrl = "iroh://$ticket",
        )

        try {
            // Mirror the mobile WsChatBridge connect contract.
            transport.connect(
                baseShimUrl = "iroh://$ticket",
                token = "",
                deviceId = "harness-device",
                clientVersion = "harness-1",
            )

            val frames = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
            val collector = clientScope.async {
                transport.events.collect { frames.add(it) }
            }

            transport.send(
                agentId = "agent-test",
                conversationId = "conv-test",
                text = "hi",
                otid = "otid-1",
                contentParts = null,
                startNewConversation = false,
            )

            // Wait until we observe an AssistantMessage carrying the reply AND a terminal TurnDone.
            withTimeout(30.seconds) {
                while (true) {
                    val gotAssistant = frames.any {
                        it is ServerFrame.AssistantMessage && it.content.contains(ASSISTANT_REPLY)
                    }
                    val gotDone = frames.any { it is ServerFrame.TurnDone }
                    if (gotAssistant && gotDone) break
                    kotlinx.coroutines.delay(50.milliseconds)
                }
            }
            collector.cancel()

            val assistant = frames.filterIsInstance<ServerFrame.AssistantMessage>()
            assertTrue(assistant.any { it.content.contains(ASSISTANT_REPLY) },
                "Expected an AssistantMessage containing '$ASSISTANT_REPLY'. Got: ${frames.map { it::class.simpleName }}")
            // The UI maps AssistantMessage.content as a BARE message string (WsFrameMapper
            // wraps it as JsonPrimitive(content)). So content must be the assistant text,
            // NOT the raw stream_delta JSON envelope — otherwise the chat renders a JSON blob.
            assertTrue(assistant.any { it.content == ASSISTANT_REPLY },
                "AssistantMessage.content must be the bare assistant text '$ASSISTANT_REPLY', " +
                    "not a JSON envelope. Got contents: ${assistant.map { it.content.take(120) }}")
            assertTrue(frames.any { it is ServerFrame.TurnDone },
                "Expected a terminal TurnDone frame. Got: ${frames.map { it::class.simpleName }}")
        } finally {
            runCatching { transport.disconnect() }
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun multipleTurnsOverOneIrohConnectionEachGetResponse() = runBlocking {
        val server = IrohNodeEndpoint(
            binding = loopbackHostBinding(),
            appServerLinks = ::serveIrohCarrierLink,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
        )
        server.create()
        // Reply echoes the turn index so we can assert each turn got ITS own response.
        server.start(ScriptedReplyController(reply = ::replyForTurn))
        val ticket = server.ticketString()

        val transport = IrohChannelTransport(
            scope = clientScope,
            onConnect = {},
            forcedIrohUrl = "iroh://$ticket",
        )
        try {
            transport.connect("iroh://$ticket", "", "harness-device", "harness-1")

            val frames = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
            val collector = clientScope.async { transport.events.collect { frames.add(it) } }

            val turns = 3
            for (i in 1..turns) {
                transport.send(
                    agentId = "agent-test",
                    conversationId = "conv-test",
                    text = "turn-$i",
                    otid = "otid-$i",
                    contentParts = null,
                    startNewConversation = false,
                )
                // Wait for THIS turn's assistant reply + a TurnDone before sending the next.
                withTimeout(20.seconds) {
                    while (true) {
                        val gotReply = frames.any {
                            it is ServerFrame.AssistantMessage && it.content.contains("reply-for-turn-$i")
                        }
                        val doneCount = frames.count { it is ServerFrame.TurnDone }
                        if (gotReply && doneCount >= i) break
                        kotlinx.coroutines.delay(50.milliseconds)
                    }
                }
            }
            collector.cancel()

            for (i in 1..turns) {
                assertTrue(
                    frames.any { it is ServerFrame.AssistantMessage && it.content.contains("reply-for-turn-$i") },
                    "Turn $i should have produced an assistant reply over the shared Iroh connection. " +
                        "Got assistant contents: ${frames.filterIsInstance<ServerFrame.AssistantMessage>().map { it.content.take(60) }}",
                )
            }
        } finally {
            runCatching { transport.disconnect() }
            runCatching { server.shutdown() }
        }
    }


    @Test
    fun busyTurnEngineEmitsTerminalFailureForAcceptedSend() = runBlocking {
        val engine = AppServerTurnEngine(client = BusyAppServerClient())
        val transport = IrohChannelTransport(
            scope = clientScope,
            activeConfigProvider = { IrohConnectConfig("iroh://ticket", "", "device", "test") },
            testDialer = { config ->
                IrohConnectionHandle(
                    config = config,
                    ticket = "ticket",
                    sessionId = "session",
                    turnEngine = engine,
                    close = {},
                )
            },
        )
        val frames = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            val first = clientScope.async {
                engine.runTurn(
                    TurnCommand(
                        backendId = BackendId("iroh-app-server"),
                        runtimeId = RuntimeId("iroh:session"),
                        agentId = AgentId("agent"),
                        conversationId = ConversationId("conversation"),
                        input = TurnInput.UserMessage(localMessageId = "local-1", text = "first"),
                    ),
                ).collect { }
            }
            withTimeout(2.seconds) {
                while (!engine.isBusy("agent", "conversation")) delay(10.milliseconds)
            }

            assertTrue(transport.send("agent", "conversation", "second", "otid-2", null, false))
            // letta-mobile-1n5py: the dispatcher first waits out a lease release (2 s grace).
            withTimeout(5.seconds) {
                while (frames.none { it is ServerFrame.TurnDone && it.status == "failed" }) delay(10.milliseconds)
            }

            assertTrue(frames.any { it is ServerFrame.Error && it.code == "iroh_turn_engine_busy" })
            assertEquals(1, frames.count { it is ServerFrame.TurnDone && it.status == "failed" })
            first.cancel()
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    @Test
    fun nonTerminatingTurnDoesNotPermanentlyJamTheEngine() = runBlocking {
        // A controller that NEVER emits a terminal stop_reason — reproduces the
        // c0qm0 device hang at the engine layer. The idle watchdog must force the
        // stuck turn to Failed so the NEXT turn still runs.
        val engine = AppServerTurnEngine(
            client = object : com.letta.mobile.data.transport.appserver.AppServerClient {
                override val events = kotlinx.coroutines.flow.MutableSharedFlow<com.letta.mobile.data.transport.appserver.AppServerReceivedFrame>(extraBufferCapacity = 16)
                override suspend fun runtimeStart(command: com.letta.mobile.data.transport.appserver.AppServerCommand.RuntimeStart) =
                    AppServerInboundFrame.RuntimeStartResponse(requestId = command.requestId, success = true, runtime = AppServerRuntimeScope("a", "c"))
                override suspend fun input(command: com.letta.mobile.data.transport.appserver.AppServerCommand.Input) { /* never replies */ }
                override suspend fun sync(command: com.letta.mobile.data.transport.appserver.AppServerCommand.Sync) = throw UnsupportedOperationException()
                override suspend fun abort(command: com.letta.mobile.data.transport.appserver.AppServerCommand.AbortMessage) = throw UnsupportedOperationException()
                override suspend fun adminRpc(command: com.letta.mobile.data.transport.appserver.AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse = throw UnsupportedOperationException()
                override suspend fun sendExternalToolResponse(command: com.letta.mobile.data.transport.appserver.AppServerCommand.ExternalToolCallResponse) {}
            },
            turnIdleTimeoutMs = 300L, // fast for the test
        )
        fun cmd(text: String) = TurnCommand(
            backendId = BackendId("t"), runtimeId = RuntimeId("t"),
            agentId = AgentId("a"), conversationId = ConversationId("c"),
            input = TurnInput.UserMessage(localMessageId = "m", text = text),
        )

        // Turn 1 never gets a terminal frame -> must end (Failed) via idle watchdog, not hang.
        val turn1 = withTimeout(5.seconds) {
            val drafts = mutableListOf<com.letta.mobile.runtime.RuntimeEventDraft>()
            engine.runTurn(cmd("one")).collect { drafts.add(it) }
            drafts
        }
        assertTrue(
            turn1.any { (it.payload as? RuntimeEventPayload.RunLifecycleChanged)?.status == RuntimeRunStatus.Failed },
            "Stuck turn 1 should force-complete with Failed via the idle watchdog. Got: ${turn1.map { it.payload::class.simpleName }}",
        )
        // Turn 2 must still run (the engine lock was released), proving no permanent jam.
        val turn2Ran = withTimeout(5.seconds) {
            var sawStarted = false
            engine.runTurn(cmd("two")).collect {
                if (it.payload is RuntimeEventPayload.RunLifecycleChanged) sawStarted = true
            }
            sawStarted
        }
        assertTrue(turn2Ran, "Turn 2 must run after turn 1's idle timeout — the engine must not stay jammed.")
    }

    @Test
    fun consecutiveMultiTurnWithLatencyPasses() = runBlocking {
        // Simulates real-world timing: first turn takes 500ms (LLM latency),
        // second turn should still work after it completes (c0qm0 regression guard).
        val server = IrohNodeEndpoint(
            binding = loopbackHostBinding(),
            appServerLinks = ::serveIrohCarrierLink,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
        )
        server.create()
        server.start(ScriptedReplyController(latency = 500.milliseconds, reply = ::replyForTurn))
        val ticket = server.ticketString()

        val transport = IrohChannelTransport(
            scope = clientScope,
            onConnect = {},
            forcedIrohUrl = "iroh://$ticket",
        )
        try {
            transport.connect("iroh://$ticket", "", "harness-device", "harness-1")

            val frames = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
            val collector = clientScope.async { transport.events.collect { frames.add(it) } }

            // Turn 1
            transport.send("agent-test", "conv-test", "turn-1", "otid-1", null, false)
            withTimeout(10.seconds) {
                while (frames.count { it is ServerFrame.TurnDone } < 1) delay(50.milliseconds)
            }

            // Wait a bit then send turn 2
            delay(100.milliseconds)
            transport.send("agent-test", "conv-test", "turn-2", "otid-2", null, false)
            withTimeout(10.seconds) {
                while (frames.count { it is ServerFrame.TurnDone } < 2) delay(50.milliseconds)
            }

            val assistant = frames.filterIsInstance<ServerFrame.AssistantMessage>()
            assertTrue(assistant.any { it.content.contains("reply-for-turn-1") }, "Turn 1 assistant")
            assertTrue(assistant.any { it.content.contains("reply-for-turn-2") }, "Turn 2 assistant")
            collector.cancel()
        } finally {
            runCatching { transport.disconnect() }
            runCatching { server.shutdown() }
        }
    }

    /**
     * A client message id is unique per conversation, not per host: the same id sent to two
     * conversations of one host is two turns, and each must run. Only a re-send to the SAME
     * conversation is a duplicate.
     */
    @Test
    fun theSameClientMessageIdInAnotherConversationRunsItsOwnTurn() = runBlocking {
        val server = IrohNodeEndpoint(
            binding = loopbackHostBinding(),
            appServerLinks = ::serveIrohCarrierLink,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
        )
        server.create()
        server.start(ScriptedReplyController(reply = ::replyForTurn))
        val ticket = server.ticketString()
        val transport = IrohChannelTransport(scope = clientScope, onConnect = {}, forcedIrohUrl = "iroh://$ticket")
        try {
            transport.connect("iroh://$ticket", "", "harness-device", "harness-1")
            val frames = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
            val collector = clientScope.async { transport.events.collect { frames.add(it) } }
            fun answered(turn: String) = frames.any { it is ServerFrame.AssistantMessage && it.content == replyForTurn(turn) }

            transport.send("agent-test", "conv-first", "turn-a", "otid-shared", null, false)
            val firstAnswered = withTimeoutOrNull(15.seconds) {
                while (!answered("turn-a") || frames.count { it is ServerFrame.TurnDone } < 1) delay(50.milliseconds)
            }
            assertTrue(firstAnswered != null, "the first conversation's turn must run")

            transport.send("agent-test", "conv-second", "turn-b", "otid-shared", null, false)
            val secondAnswered = withTimeoutOrNull(15.seconds) {
                while (!answered("turn-b") || frames.count { it is ServerFrame.TurnDone } < 2) delay(50.milliseconds)
            }
            assertTrue(
                secondAnswered != null,
                "the same client message id in another conversation is another turn and must run, " +
                    "not be ignored as a re-send. Got: ${frames.map { it::class.simpleName }}",
            )
            collector.cancel()
        } finally {
            runCatching { transport.disconnect() }
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun largeFrameDoesNotCauseOom() = runBlocking {
        val server = IrohNodeEndpoint(
            binding = loopbackHostBinding(),
            appServerLinks = ::serveIrohCarrierLink,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
        )
        server.create(); server.start(ScriptedReplyController { BIG_REPLY })
        val transport = IrohChannelTransport(scope = clientScope, onConnect = {}, forcedIrohUrl = "iroh://${server.ticketString()}")
        try {
            transport.connect("iroh://${server.ticketString()}", "", "h", "h")
            val frames = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
            val collector = clientScope.async { transport.events.collect { frames.add(it) } }
            transport.send("a", "c", "hi", "otid-1", null, false)
            withTimeout(15.seconds) { while (frames.count { it is ServerFrame.TurnDone } < 1) delay(50.milliseconds) }
            // Should have completed without OOM — the big frame should be handled gracefully
            assertTrue(frames.any { it is ServerFrame.TurnDone }, "Turn should complete even with oversized frame")
            assertTrue(
                frames.any { it is ServerFrame.AssistantMessage && it.content == BIG_REPLY },
                "the oversized assistant frame must arrive whole (frame_part chunking), not be dropped",
            )
            collector.cancel()
        } finally { runCatching { transport.disconnect() }; runCatching { server.shutdown() } }
    }

    /**
     * A host controller whose turns answer with one assistant message, after [latency], and then
     * complete. It emits what the real turn engine emits for that: the App Server's `stream_delta`
     * envelope as a remote stream frame, stamped with the turn's stream identity, then a Completed
     * lifecycle. A bare text body is not a frame the engine ever produces; the node drops it.
     */
    private class ScriptedReplyController(
        private val latency: Duration = Duration.ZERO,
        delegate: AppServerController = FakeAppServerController(),
        private val reply: (String) -> String,
    ) : AppServerController by delegate {
        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = flow {
            val input = command.input as TurnInput.UserMessage
            val identity = turnStreamIdentityFor(input.localMessageId)
            delay(latency)
            emit(requireNotNull(identity.stampDraft(command.draft(assistantFrame(command, input, reply(input.text))))))
            delay(latency / 2)
            emit(command.draft(RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed)))
        }

        private fun TurnCommand.draft(payload: RuntimeEventPayload) = RuntimeEventDraft(
            backendId = BackendId("harness"),
            runtimeId = RuntimeId("harness"),
            agentId = agentId,
            conversationId = conversationId,
            source = RuntimeEventSource.LocalRuntime,
            payload = payload,
        )

        private fun assistantFrame(command: TurnCommand, input: TurnInput.UserMessage, text: String): RuntimeEventPayload {
            val messageId = "harness-msg-${input.localMessageId}"
            val envelope = buildJsonObject {
                put("type", "stream_delta")
                put("runtime", buildJsonObject {
                    put("agent_id", command.agentId.value)
                    put("conversation_id", command.conversationId.value)
                })
                // Unique per frame, as the App Server mints it: the host relays a frame key only once.
                put("idempotency_key", "harness-delta-${java.util.UUID.randomUUID()}")
                put("delta", buildJsonObject {
                    put("message_type", "assistant_message")
                    put("id", messageId)
                    put("content", text)
                })
            }
            return RuntimeEventPayload.RemoteStreamFrame(
                frameId = "harness-frame-${input.localMessageId}",
                messageId = messageId,
                messageType = "assistant_message",
                body = envelope.toString(),
            )
        }
    }

    private companion object {
        const val ASSISTANT_REPLY = "harness-assistant-reply-OK"

        /** Larger than one 1 MiB Iroh frame, so the host must send it as frame parts. */
        val BIG_REPLY = "x".repeat(2_000_000)

        /** Echoes the turn index of a `turn-N` prompt, so each turn's reply is distinguishable. */
        fun replyForTurn(prompt: String): String = "reply-for-turn-${prompt.substringAfterLast('-')}"
    }
}

private class BusyAppServerClient : com.letta.mobile.data.transport.appserver.AppServerClient {
    override val events: MutableSharedFlow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 16)

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
        AppServerInboundFrame.RuntimeStartResponse(
            requestId = command.requestId,
            success = true,
            runtime = AppServerRuntimeScope(
                agentId = requireNotNull(command.agentId),
                conversationId = requireNotNull(command.conversationId),
            ),
        )

    override suspend fun input(command: AppServerCommand.Input) {
        kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        throw UnsupportedOperationException()

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        throw UnsupportedOperationException()

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
        throw UnsupportedOperationException()

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
}
