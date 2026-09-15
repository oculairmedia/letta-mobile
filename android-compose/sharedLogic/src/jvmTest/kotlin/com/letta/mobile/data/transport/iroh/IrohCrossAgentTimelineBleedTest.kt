package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.ChatSendCoordinator
import com.letta.mobile.data.chat.send.ChatSendUiSink
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-agent timeline bleed, end to end through the Iroh stack (reported on device 2026-09-14:
 * with agent A's run in flight, opening agent B's chat showed A's reply in B's timeline).
 *
 * Real pieces: [IrohChannelTransport], [AppServerTurnEngine], one app-wide [WsChatBridge], and one
 * [ChatSendCoordinator] per open chat - the production topology. Only the App Server wire is fake:
 * it emits real `stream_delta` JSON for any agent and conversation.
 *
 * View-side contract these tests lean on (read, not assumed): production binds each chat's timeline
 * writer to ONE conversation - `AndroidCanonicalTimelineRuntime.bind` fails any write that names
 * another conversation - and `ChatTimelineObserver` observes exactly `(agentId, conversationId)`.
 * So another agent's message can reach B's screen only if B's coordinator ingests it into B's own
 * conversation. Every test therefore asserts: nothing is ingested into the conversation B's screen
 * shows, while A's run demonstrably streamed (control).
 *
 * Mechanism these tests pin (from the coordinator's own `gate3.coordinatorMessageDelta` routing
 * telemetry): every delta reaches every coordinator over the shared bridge carrying A's
 * `conversationId` and turn id. B's coordinator has no state for that turn, so
 * `resolveStateByTurnId` falls through to `fallbackState()`, which creates a state for B's own
 * open conversation (or `conv-default-<agent>` when none is open) and returns it. The delta's
 * `conversationId` is never consulted, and A's run is ingested into B's timeline.
 */
class IrohCrossAgentTimelineBleedTest {

    private val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        transportScope.cancel()
    }

    @Test
    fun engineOwnedRunForAgentADoesNotBleedIntoOpenChatOfAgentB() = runBlocking {
        val stack = stack()
        val timeline = RecordingTimelineWriter()
        stack.chat(AGENT_A, conversation = CONV_A, timeline)
        stack.chat(AGENT_B, conversation = CONV_B, timeline)

        stack.startRun(AGENT_A, CONV_A, timeline)
        stack.streamFullTurn(AGENT_A, CONV_A, seqStart = 10)
        awaitControl(timeline, AGENT_A, CONV_A)

        assertNothingIngestedInto(timeline, CONV_B, stack)
        assertNoWritesUnderAgent(timeline, AGENT_B, stack)
    }

    @Test
    fun runForAgentAKeepsStreamingAfterNavigatingToAgentB() = runBlocking {
        val stack = stack()
        val timeline = RecordingTimelineWriter()
        val chatA = stack.chat(AGENT_A, conversation = CONV_A, timeline)
        stack.chat(AGENT_B, conversation = CONV_B, timeline)

        stack.startRun(AGENT_A, CONV_A, timeline)
        stack.streamAssistant(AGENT_A, CONV_A, "cm-a-early", "before navigating", seq = 10)
        awaitControl(timeline, AGENT_A, CONV_A)
        // Navigating A -> B clears A's view model: its coordinator leaves the shared flow.
        chatA.close()

        stack.streamFullTurn(AGENT_A, CONV_A, seqStart = 20)
        delay(SETTLE)

        assertNothingIngestedInto(timeline, CONV_B, stack)
        assertNoWritesUnderAgent(timeline, AGENT_B, stack)
    }

    @Test
    fun runForAgentADoesNotBleedIntoAgentBsNewChat() = runBlocking {
        val stack = stack()
        val timeline = RecordingTimelineWriter()
        val chatA = stack.chat(AGENT_A, conversation = CONV_A, timeline)
        // B opened as a new chat: no conversation until its first send creates one.
        val chatB = stack.chat(AGENT_B, conversation = null, timeline)

        stack.startRun(AGENT_A, CONV_A, timeline)
        chatA.close()
        stack.streamFullTurn(AGENT_A, CONV_A, seqStart = 10)
        delay(SETTLE)

        chatB.coordinator.send("hello B")
        withTimeout(10.seconds) { while (chatB.currentConversation() == null) delay(10.milliseconds) }
        delay(SETTLE)

        assertNothingIngestedInto(timeline, requireNotNull(chatB.currentConversation()), stack)
        // B's coordinator must not have written A's run anywhere under agent B - not into the new
        // conversation, and not into B's `conv-default-<agent>` fallback either.
        assertNoWritesUnderAgent(timeline, AGENT_B, stack)
    }

    @Test
    fun observedRunForAgentAStartedElsewhereDoesNotBleedIntoOpenChatOfAgentB() = runBlocking {
        // A's run was started on another device: this phone only observes it.
        val stack = stack()
        val timeline = RecordingTimelineWriter()
        stack.chat(AGENT_B, conversation = CONV_B, timeline)

        stack.streamFullTurn(AGENT_A, CONV_A, seqStart = 10)
        delay(SETTLE)

        assertNothingIngestedInto(timeline, CONV_B, stack)
        assertNoWritesUnderAgent(timeline, AGENT_B, stack)
    }

    @Test
    fun runInOneConversationDoesNotBleedIntoAnotherConversationOfTheSameAgent() = runBlocking {
        // Letta agents have many conversations: ownership is (agentId, conversationId), not agent.
        val stack = stack()
        val timeline = RecordingTimelineWriter()
        val chatFirst = stack.chat(AGENT_A, conversation = CONV_A, timeline)

        stack.startRun(AGENT_A, CONV_A, timeline)
        stack.streamAssistant(AGENT_A, CONV_A, "cm-a-early", "before switching", seq = 10)
        awaitControl(timeline, AGENT_A, CONV_A)
        // Switching to another conversation of the same agent replaces the chat's view model.
        chatFirst.close()
        stack.chatAlongside(AGENT_A, conversation = CONV_A2, timeline)

        stack.streamFullTurn(AGENT_A, CONV_A, seqStart = 20)
        delay(SETTLE)

        assertNothingIngestedInto(timeline, CONV_A2, stack)
        assertTrue(
            timeline.writes.filter { it.agentId == AGENT_A }.all { it.conversationId == CONV_A },
            "every write of the run must stay in its own conversation ($CONV_A): ${timeline.writes}",
        )
    }

    // ------------------------------------------------------------------ harness

    private suspend fun stack(): Stack {
        val client = TwoAgentClient()
        val engine = AppServerTurnEngine(client = client)
        val transport = IrohChannelTransport(
            scope = transportScope,
            activeConfigProvider = { IrohConnectConfig("iroh://ticket", "", "device", "test") },
            testDialer = { config ->
                IrohConnectionHandle(
                    config = config,
                    ticket = "ticket",
                    sessionId = "session",
                    turnEngine = engine,
                    observerStreamFrames = client.stream,
                    close = {},
                )
            },
            serverTerminalWaitMs = 200L,
        )
        transport.connect("iroh://ticket", "", "device", "test")
        withTimeout(10.seconds) { while (transport.state.value !is ChannelTransportState.Connected) delay(10.milliseconds) }
        withTimeout(10.seconds) { while (client.subscriberCount < 1) delay(5.milliseconds) }
        val bridge = WsChatBridge(transport)
        val deltas = CopyOnWriteArrayList<WsTimelineEvent.MessageDelta>()
        transportScope.async(start = CoroutineStart.UNDISPATCHED) {
            bridge.events.collect { if (it is WsTimelineEvent.MessageDelta) deltas += it }
        }
        return Stack(client, engine, transport, bridge, deltas)
    }

    private inner class Stack(
        val client: TwoAgentClient,
        val engine: AppServerTurnEngine,
        val transport: IrohChannelTransport,
        val bridge: WsChatBridge,
        val deltas: List<WsTimelineEvent.MessageDelta>,
    ) {
        private val chats = mutableMapOf<String, Chat>()

        fun chat(agentId: String, conversation: String?, timeline: RecordingTimelineWriter): Chat =
            Chat(agentId, conversation, bridge, timeline).also { chats[agentId] = it }

        /** A second chat for an agent already registered (another conversation), without replacing it. */
        fun chatAlongside(agentId: String, conversation: String?, timeline: RecordingTimelineWriter): Chat =
            Chat(agentId, conversation, bridge, timeline)

        /** Sends from [agentId]'s chat and waits until the engine-owned turn is streaming. */
        suspend fun startRun(agentId: String, conversationId: String, timeline: RecordingTimelineWriter) {
            val chat = chats[agentId] ?: chat(agentId, conversationId, timeline)
            chat.coordinator.send("hi $agentId")
            withTimeout(10.seconds) { while (!engine.isBusy(agentId, conversationId)) delay(10.milliseconds) }
            withTimeout(10.seconds) { while (!client.inputReceived) delay(10.milliseconds) }
            withTimeout(10.seconds) { while (client.subscriberCount < 2) delay(5.milliseconds) }
        }

        suspend fun streamAssistant(agentId: String, conversationId: String, id: String, content: String, seq: Long) =
            client.emit(agentId, conversationId, seq, """{"message_type":"assistant_message","id":"$id","content":"$content"}""")

        /** Reasoning, a tool call and its return, an assistant reply, then the terminal. */
        suspend fun streamFullTurn(agentId: String, conversationId: String, seqStart: Long) {
            var seq = seqStart
            client.emit(agentId, conversationId, seq++, """{"message_type":"reasoning_message","id":"rs-$agentId","reasoning":"thinking as $agentId"}""")
            client.emit(agentId, conversationId, seq++, """{"message_type":"tool_call_message","id":"tc-$agentId","tool_call":{"name":"Bash","tool_call_id":"call-$agentId","arguments":"{}"}}""")
            client.emit(agentId, conversationId, seq++, """{"message_type":"tool_return_message","id":"tr-$agentId","tool_call_id":"call-$agentId","status":"success","tool_return":"ok"}""")
            client.emit(agentId, conversationId, seq++, """{"message_type":"assistant_message","id":"cm-$agentId","content":"reply from $agentId"}""")
            client.emit(agentId, conversationId, seq, """{"message_type":"stop_reason","stop_reason":"end_turn"}""")
        }
    }

    private inner class Chat(
        agentId: String,
        conversation: String?,
        bridge: WsChatBridge,
        timeline: RecordingTimelineWriter,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var active: String? = conversation
        fun currentConversation(): String? = active
        val coordinator = ChatSendCoordinator(
            scope = scope,
            agentId = agentId,
            activeConfig = { LettaConfig("iroh", LettaConfig.Mode.SELF_HOSTED, "iroh://node@host:4501", accessToken = null) },
            wsChatBridge = bridge,
            timelineRepository = timeline,
            conversationRepository = FakeConversationRepository(),
            ui = NoopUiSink(),
            clearComposerAfterSend = {},
            activeConversationId = { active },
            setActiveConversationId = { active = it },
            startTimelineObserver = {},
            clientVersion = { "test" },
            otidGenerator = { "otid-$agentId-${System.nanoTime()}" },
        )

        /** What clearing the chat's view model does: its coordinator stops collecting. */
        fun close() = scope.cancel()
    }

    private suspend fun awaitControl(timeline: RecordingTimelineWriter, agentId: String, conversationId: String) {
        withTimeout(10.seconds) {
            while (timeline.writes.none { it.agentId == agentId && it.conversationId == conversationId }) delay(10.milliseconds)
        }
        delay(SETTLE)
    }

    private fun assertNothingIngestedInto(timeline: RecordingTimelineWriter, screenConversation: String, stack: Stack) {
        val leaked = timeline.writes.filter { it.conversationId == screenConversation }
        assertTrue(
            leaked.isEmpty(),
            "another agent's messages were ingested into the conversation B's screen shows ($screenConversation): $leaked\n" +
                "all writes: ${timeline.writes}\n" +
                "bridge deltas (conversationId, turnId, id): ${stack.deltas.map { Triple(it.conversationId, it.turnId, it.message.id) }}",
        )
    }

    /** Agent B sends nothing that produces frames in these tests, so any write under B is A's run. */
    private fun assertNoWritesUnderAgent(timeline: RecordingTimelineWriter, agentId: String, stack: Stack) {
        val underAgent = timeline.writes.filter { it.agentId == agentId }
        assertTrue(
            underAgent.isEmpty(),
            "$agentId's coordinator wrote another agent's run under its own agent: $underAgent\n" +
                "bridge deltas (conversationId, turnId, id): ${stack.deltas.map { Triple(it.conversationId, it.turnId, it.message.id) }}",
        )
    }

    private data class Write(val agentId: String?, val conversationId: String, val messageId: String)

    private class RecordingTimelineWriter : TimelineExternalTransportWriter {
        val writes = CopyOnWriteArrayList<Write>()
        override suspend fun appendExternalTransportLocal(conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String = otid
        override suspend fun appendExternalTransportLocal(agentId: String?, conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String = otid
        override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage, source: String) {
            writes += Write(null, conversationId, message.id)
        }
        override suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage, source: String) {
            writes += Write(agentId, conversationId, message.id)
        }
        override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) = Unit
        override suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) = Unit
        override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) = Unit
        override suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) = Unit
        override suspend fun reconcileExternalTransportSend(conversationId: String, agentId: String, externalConversationId: String, otid: String) = Unit
        override suspend fun reconcileExternalTransportSendScoped(agentId: String?, conversationId: String, externalConversationId: String, otid: String) = Unit
        override suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?) = Unit
        override suspend fun repairExpiredConversationCursorScoped(agentId: String?, conversationId: String, fallbackSeq: Long?) = Unit
        override suspend fun clearExternalTransportActive(conversationId: String) = Unit
        override suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) = Unit
        override suspend fun cleanupAbandonedAssistantFragments(agentId: String?, conversationId: String, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String>): Int = 0
        override suspend fun reconcileRecentMessages(agentId: String?, conversationId: String, reason: String, forceRefresh: Boolean, connectionGeneration: Long): RecentMessagesReconcileOutcome = RecentMessagesReconcileOutcome.Applied(0)
    }

    private class NoopUiSink : ChatSendUiSink {
        override fun currentError(): String? = null
        override fun isStreaming(): Boolean = false
        override fun isAgentTyping(): Boolean = false
        override fun onSendDispatched(conversationId: String?) = Unit
        override fun onSendQueued(conversationId: String) = Unit
        override fun onSendFailed(message: String) = Unit
        override fun onError(message: String?) = Unit
        override fun onTurnStarted(conversationId: String) = Unit
        override fun onMessageDelta(conversationId: String) = Unit
        override fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int) = Unit
        override fun onTurnFinished(error: String?) = Unit
        override fun onTurnVisuallyComplete() = Unit
        override fun onTransientDisconnect(hasActiveSend: Boolean) = Unit
        override fun onDisconnectFailure(error: String) = Unit
    }

    private class FakeConversationRepository : IConversationRepository {
        override fun getConversations(agentId: AgentId): Flow<List<Conversation>> = emptyFlow()
        override fun getCachedConversations(agentId: AgentId): List<Conversation> = emptyList()
        override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean = true
        override suspend fun refreshConversations(agentId: AgentId) = Unit
        override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean = false
        override suspend fun getConversation(id: ConversationId): Conversation = conversation(id.value, AGENT_B)
        override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation = conversation("conv-new-${agentId.value}", agentId.value)
        override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) = Unit
        override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) = Unit
        override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) = Unit
        override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) = Unit
        override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String = "run"
        override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation = conversation("fork", agentId.value)
        private fun conversation(id: String, agentId: String) = Conversation(ConversationId(id), AgentId(agentId), "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z")
    }

    /** One App Server stream shared by the turn engine and the observer, for any agent. */
    private class TwoAgentClient : AppServerClient {
        val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 256)
        override val events: Flow<AppServerReceivedFrame> = stream

        @Volatile var inputReceived = false
        val subscriberCount: Int get() = stream.subscriptionCount.value

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
            inputReceived = true
            awaitCancellation()
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("sync unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            AppServerInboundFrame.AbortMessageResponse(requestId = command.requestId ?: "", runtime = command.runtime, aborted = true, success = true)

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse = error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        suspend fun emit(agentId: String, conversationId: String, seq: Long, delta: String) {
            val body = """
                {
                  "type": "stream_delta",
                  "runtime": {"agent_id": "$agentId", "conversation_id": "$conversationId"},
                  "event_seq": $seq,
                  "emitted_at": "2026-09-14T00:00:00Z",
                  "idempotency_key": "evt-$agentId-$conversationId-$seq",
                  "delta": $delta
                }
            """.trimIndent()
            stream.emit(AppServerProtocol.decodeFrame(body, AppServerChannel.Stream))
        }
    }

    private companion object {
        const val AGENT_A = "agent-a"
        const val AGENT_B = "agent-b"
        const val CONV_A = "local-conv-a"
        const val CONV_B = "local-conv-b"
        const val CONV_A2 = "local-conv-a2"
        val SETTLE = 400.milliseconds
    }
}
