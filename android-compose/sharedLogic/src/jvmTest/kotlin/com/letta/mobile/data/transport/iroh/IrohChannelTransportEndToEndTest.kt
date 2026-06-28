package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

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
 */
class IrohChannelTransportEndToEndTest {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun deviceSendOverIrohRoundTripsAssistantResponse() = runBlocking {
        val server = IrohNodeEndpoint(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        server.create()
        server.start(EchoAssistantController(reply = ASSISTANT_REPLY))
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

            val frames = mutableListOf<ServerFrame>()
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
            withTimeout(30_000) {
                while (true) {
                    val gotAssistant = frames.any {
                        it is ServerFrame.AssistantMessage && it.content.contains(ASSISTANT_REPLY)
                    }
                    val gotDone = frames.any { it is ServerFrame.TurnDone }
                    if (gotAssistant && gotDone) break
                    kotlinx.coroutines.delay(50)
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

    /**
     * Stub controller that starts any runtime and, on runTurn, emits a single assistant
     * stream frame followed by a Completed lifecycle — the minimal real-response shape.
     */
    private class EchoAssistantController(private val reply: String) : AppServerController {
        override val state = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)

        override suspend fun startRuntime(
            agentId: AgentId,
            conversationId: ConversationId,
            cwd: String?,
            mode: AppServerPermissionMode?,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): CanonicalRuntime = CanonicalRuntime(
            scope = AppServerRuntimeScope(agentId = agentId.value, conversationId = conversationId.value, actingUserId = null),
            agent = null,
            conversation = null,
            created = null,
        )

        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = flow {
            emit(
                RuntimeEventDraft(
                    backendId = BackendId("harness"),
                    runtimeId = RuntimeId("harness"),
                    agentId = command.agentId,
                    conversationId = command.conversationId,
                    source = RuntimeEventSource.LocalRuntime,
                    payload = RuntimeEventPayload.RemoteStreamFrame(
                        frameId = "harness-frame-1",
                        messageId = "harness-msg-1",
                        messageType = "assistant_message",
                        body = reply,
                    ),
                ),
            )
            emit(
                RuntimeEventDraft(
                    backendId = BackendId("harness"),
                    runtimeId = RuntimeId("harness"),
                    agentId = command.agentId,
                    conversationId = command.conversationId,
                    source = RuntimeEventSource.LocalRuntime,
                    payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed),
                ),
            )
        }

        override suspend fun sync(
            runtime: AppServerRuntimeScope,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): AppServerInboundFrame.SyncResponse = throw UnsupportedOperationException()

        override suspend fun abort(
            runtime: AppServerRuntimeScope,
            runId: String?,
        ): AppServerInboundFrame.AbortMessageResponse = throw UnsupportedOperationException()
    }

    @Test
    fun multipleTurnsOverOneIrohConnectionEachGetResponse() = runBlocking {
        val server = IrohNodeEndpoint(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        server.create()
        // Reply echoes the turn index so we can assert each turn got ITS own response.
        server.start(PerTurnEchoController())
        val ticket = server.ticketString()

        val transport = IrohChannelTransport(
            scope = clientScope,
            onConnect = {},
            forcedIrohUrl = "iroh://$ticket",
        )
        try {
            transport.connect("iroh://$ticket", "", "harness-device", "harness-1")

            val frames = mutableListOf<ServerFrame>()
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
                withTimeout(20_000) {
                    while (true) {
                        val gotReply = frames.any {
                            it is ServerFrame.AssistantMessage && it.content.contains("reply-for-turn-$i")
                        }
                        val doneCount = frames.count { it is ServerFrame.TurnDone }
                        if (gotReply && doneCount >= i) break
                        kotlinx.coroutines.delay(50)
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
        val turn1 = withTimeout(5_000) {
            val drafts = mutableListOf<com.letta.mobile.runtime.RuntimeEventDraft>()
            engine.runTurn(cmd("one")).collect { drafts.add(it) }
            drafts
        }
        assertTrue(
            turn1.any { (it.payload as? RuntimeEventPayload.RunLifecycleChanged)?.status == RuntimeRunStatus.Failed },
            "Stuck turn 1 should force-complete with Failed via the idle watchdog. Got: ${turn1.map { it.payload::class.simpleName }}",
        )
        // Turn 2 must still run (the engine lock was released), proving no permanent jam.
        val turn2Ran = withTimeout(5_000) {
            var sawStarted = false
            engine.runTurn(cmd("two")).collect {
                if (it.payload is RuntimeEventPayload.RunLifecycleChanged) sawStarted = true
            }
            sawStarted
        }
        assertTrue(turn2Ran, "Turn 2 must run after turn 1's idle timeout — the engine must not stay jammed.")
    }

    /** Emits one assistant reply per turn, echoing the input text so each turn is distinguishable. */
    private class PerTurnEchoController : AppServerController {
        override val state = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)
        override suspend fun startRuntime(
            agentId: AgentId,
            conversationId: ConversationId,
            cwd: String?,
            mode: AppServerPermissionMode?,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): CanonicalRuntime = CanonicalRuntime(
            scope = AppServerRuntimeScope(agentId = agentId.value, conversationId = conversationId.value, actingUserId = null),
            agent = null, conversation = null, created = null,
        )

        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = flow {
            val turnText = (command.input as? TurnInput.UserMessage)?.text ?: "?"
            val n = turnText.substringAfterLast('-')
            emit(
                RuntimeEventDraft(
                    backendId = BackendId("harness"), runtimeId = RuntimeId("harness"),
                    agentId = command.agentId, conversationId = command.conversationId,
                    source = RuntimeEventSource.LocalRuntime,
                    payload = RuntimeEventPayload.RemoteStreamFrame(
                        frameId = "frame-$n", messageId = "msg-$n",
                        messageType = "assistant_message", body = "reply-for-turn-$n",
                    ),
                ),
            )
            emit(
                RuntimeEventDraft(
                    backendId = BackendId("harness"), runtimeId = RuntimeId("harness"),
                    agentId = command.agentId, conversationId = command.conversationId,
                    source = RuntimeEventSource.LocalRuntime,
                    payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed),
                ),
            )
        }

        override suspend fun sync(runtime: AppServerRuntimeScope, recoverApprovals: Boolean, forceDeviceStatus: Boolean): AppServerInboundFrame.SyncResponse = throw UnsupportedOperationException()
        override suspend fun abort(runtime: AppServerRuntimeScope, runId: String?): AppServerInboundFrame.AbortMessageResponse = throw UnsupportedOperationException()
    }

    private companion object {
        const val ASSISTANT_REPLY = "harness-assistant-reply-OK"
    }
}
