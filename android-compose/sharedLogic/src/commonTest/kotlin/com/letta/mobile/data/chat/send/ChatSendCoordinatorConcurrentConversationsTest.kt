package com.letta.mobile.data.chat.send

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-or40x PR2 — the COORDINATOR half of the concurrent-conversation
 * defect. PR1 keyed the transport's turn slots; a perfectly keyed transport was
 * still not enough, because `ChatSendCoordinator` kept ONE process-wide set of
 * `activeWs*` turn-identity fields plus ONE [TurnIdentityLifecycle]:
 *
 *  - a send into conversation B overwrote conversation A's otid / local
 *    conversation / turn / run identity, so A's own terminal was fenced off as
 *    "stale" and A never settled — the conversation that looked frozen on device;
 *  - `clearActiveTurnState()` took no key, so A's turn ending wiped B's in-flight
 *    send identity along with A's;
 *  - `healStaleVisualPresence` asked the unscoped "is ANY turn live?" question,
 *    so B's live turn suppressed A's self-heal forever.
 *
 * Every test below drives TWO conversations through one coordinator and asserts
 * observable settlement behavior, not internal fields.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendCoordinatorConcurrentConversationsTest {

    /**
     * Regression 1: B's send while A's turn is in flight must not evict A's turn
     * identity — A's own terminal still settles A.
     *
     * Against the unkeyed coordinator, B's `acceptSend` replaced the single
     * identity, so `TurnDone(turn-a)` was rejected by the terminal fence and
     * "conv-a" never reached `clearExternalTransportActive` at all.
     */
    @Test
    fun sendIntoSecondConversationDoesNotEvictFirstConversationTurnIdentity() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink()
            val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
            var active: String? = CONV_A
            val coordinator = coordinator(timeline, ui, transport) { active }

            coordinator.send("hello A").join()
            val otidA = timeline.externalLocals.single { it.conversationId == CONV_A }.otid
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))

            // The user switches to B and sends while A is still streaming.
            active = CONV_B
            coordinator.send("hello B").join()
            val otidB = timeline.externalLocals.single { it.conversationId == CONV_B }.otid
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-b", AGENT_ID, CONV_B, "run-b"))

            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-a", "run-a", "completed"))
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-b", "run-b", "completed"))
            advanceUntilIdle()

            // Both conversations settle, each under its OWN terminal and in order.
            assertEquals(listOf(CONV_A, CONV_B), timeline.clearedActiveConversations)
            assertTrue(
                timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_A, otidA)),
                "A's optimistic-local must settle against A",
            )
            assertTrue(
                timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_B, otidB)),
                "B's optimistic-local must settle against B",
            )
            // No cross-attribution: neither otid may be settled into the other conversation.
            assertTrue(timeline.sentLocals.none { it.conversationId == CONV_A && it.otid == otidB })
            assertTrue(timeline.sentLocals.none { it.conversationId == CONV_B && it.otid == otidA })
        }

    /**
     * Regression 2: A's turn ending must not wipe B's in-flight send identity.
     *
     * B's send is accepted while A is still awaiting its `TurnStarted`. Against
     * the unkeyed coordinator, A's `TurnStarted` adopted B's otid as its own
     * "stale otid" and A's terminal then settled B's optimistic-local row and
     * cleared it — B's own terminal arrived to find nothing left to settle.
     */
    @Test
    fun firstConversationTerminalDoesNotWipeSecondConversationInFlightSend() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink()
            val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
            var active: String? = CONV_A
            val coordinator = coordinator(timeline, ui, transport) { active }

            coordinator.send("hello A").join()
            val otidA = timeline.externalLocals.single { it.conversationId == CONV_A }.otid
            active = CONV_B
            coordinator.send("hello B").join()
            val otidB = timeline.externalLocals.single { it.conversationId == CONV_B }.otid

            // A's turn starts and ends while B's send is still awaiting its TurnStarted.
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-a", "run-a", "completed"))
            advanceUntilIdle()

            assertEquals(listOf(CONV_A), timeline.clearedActiveConversations)
            assertTrue(
                timeline.sentLocals.none { it.otid == otidB },
                "A's terminal must not settle B's still in-flight send",
            )
            assertTrue(timeline.failedLocals.none { it.otid == otidB })

            // B's identity survived, so B's own terminal still settles B.
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-b", AGENT_ID, CONV_B, "run-b"))
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-b", "run-b", "completed"))
            advanceUntilIdle()

            assertEquals(listOf(CONV_A, CONV_B), timeline.clearedActiveConversations)
            assertTrue(timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_B, otidB)))
            assertTrue(timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_A, otidA)))
        }

    /**
     * Regression 3: a stale visual presence on A must self-heal even while B
     * holds a live turn. The old unscoped `hasAnyActiveChatTurn` check let B's
     * turn suppress A's heal, so an orphaned "thinking" indicator on A never
     * cleared and the next send queued behind a ghost turn.
     */
    @Test
    fun stalePresenceHealsForOwnConversationWhileAnotherConversationHasLiveTurn() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink(isStreaming = true, isAgentTyping = true)
            val transport = FakeChannelTransport(
                mutableListOf(true),
                activeChatTurn = false,
                activeChatTurnConversations = mutableSetOf(CONV_B),
            )
            val coordinator = coordinator(timeline, ui, transport) { CONV_A }

            coordinator.send("hello A").join()
            advanceUntilIdle()

            assertEquals(1, ui.visualCompletions, "A's stale presence must heal despite B's live turn")
            assertEquals(listOf("hello A"), transport.sentTexts)
            assertEquals(listOf(CONV_A), transport.sentConversationIds)
        }

    /** A send into the conversation that genuinely owns the live turn still must NOT heal. */
    @Test
    fun stalePresenceDoesNotHealWhileOwnConversationHasLiveTurn() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink(isStreaming = true, isAgentTyping = true)
            val transport = FakeChannelTransport(
                mutableListOf(true),
                activeChatTurn = false,
                activeChatTurnConversations = mutableSetOf(CONV_A),
            )
            val coordinator = coordinator(timeline, ui, transport) { CONV_A }

            coordinator.send("hello A").join()
            advanceUntilIdle()

            assertEquals(0, ui.visualCompletions)
        }

    private fun coordinator(
        timeline: RecordingTimelineWriter,
        ui: RecordingUiSink,
        transport: FakeChannelTransport,
        activeConversationId: () -> String?,
    ) = ChatSendCoordinator(
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        agentId = AGENT_ID,
        activeConfig = { LettaConfig("shim", LettaConfig.Mode.SELF_HOSTED, "http://localhost:8291", "token") },
        wsChatBridge = WsChatBridge(transport),
        timelineRepository = timeline,
        conversationRepository = FakeConversationRepository(),
        ui = ui,
        clearComposerAfterSend = {},
        activeConversationId = activeConversationId,
        setActiveConversationId = {},
        startTimelineObserver = {},
        clientVersion = { "test" },
        otidGenerator = { "otid-${++otid}" },
    )

    private class RecordingUiSink(
        private var error: String? = null,
        private var isStreaming: Boolean = false,
        private var isAgentTyping: Boolean = false,
    ) : ChatSendUiSink {
        var visualCompletions = 0
        val turnsFinished = mutableListOf<String?>()
        override fun currentError(): String? = error
        override fun isStreaming(): Boolean = isStreaming
        override fun isAgentTyping(): Boolean = isAgentTyping
        override fun onSendDispatched(conversationId: String?) { error = null; isStreaming = true; isAgentTyping = true }
        override fun onSendQueued(conversationId: String) { error = null; isStreaming = true; isAgentTyping = true }
        override fun onSendFailed(message: String) { error = message; isStreaming = false; isAgentTyping = false }
        override fun onError(message: String?) { error = message }
        override fun onTurnStarted(conversationId: String) { error = null; isStreaming = true; isAgentTyping = true }
        override fun onMessageDelta(conversationId: String) { error = null; isStreaming = true; isAgentTyping = true }
        override fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int) = Unit
        override fun onTurnFinished(error: String?) { turnsFinished += error; this.error = error; isStreaming = false; isAgentTyping = false }
        override fun onTurnVisuallyComplete() { visualCompletions++; isStreaming = false; isAgentTyping = false }
        override fun onTransientDisconnect(hasActiveSend: Boolean) { error = null; isStreaming = hasActiveSend; isAgentTyping = hasActiveSend }
        override fun onDisconnectFailure(error: String) { this.error = error; isStreaming = false; isAgentTyping = false }
    }

    private class RecordingTimelineWriter : TimelineExternalTransportWriter {
        val externalLocals = mutableListOf<ExternalLocal>()
        val ingestedMessages = mutableListOf<LettaMessage>()
        val sentLocals = mutableListOf<LocalMarker>()
        val failedLocals = mutableListOf<LocalMarker>()
        val clearedActiveConversations = mutableListOf<String>()
        override suspend fun appendExternalTransportLocal(conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String { externalLocals += ExternalLocal(conversationId, content, otid); return otid }
        override suspend fun appendExternalTransportLocal(agentId: String?, conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String = appendExternalTransportLocal(conversationId, content, otid, attachments)
        override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage, source: String) { ingestedMessages += message }
        override suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage, source: String) { ingestedMessages += message }
        override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) { sentLocals += LocalMarker(conversationId, otid) }
        override suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) { sentLocals += LocalMarker(conversationId, otid) }
        override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) { failedLocals += LocalMarker(conversationId, otid) }
        override suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) { failedLocals += LocalMarker(conversationId, otid) }
        override suspend fun reconcileExternalTransportSend(conversationId: String, agentId: String, externalConversationId: String, otid: String) = Unit
        override suspend fun reconcileExternalTransportSendScoped(agentId: String?, conversationId: String, externalConversationId: String, otid: String) = Unit
        override suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?) = Unit
        override suspend fun repairExpiredConversationCursorScoped(agentId: String?, conversationId: String, fallbackSeq: Long?) = Unit
        override suspend fun clearExternalTransportActive(conversationId: String) { clearedActiveConversations += conversationId }
        override suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) { clearedActiveConversations += conversationId }
        override suspend fun cleanupAbandonedAssistantFragments(agentId: String?, conversationId: String, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String>): Int = 0
        override suspend fun reconcileRecentMessages(agentId: String?, conversationId: String, reason: String, forceRefresh: Boolean): Int = 0
        data class ExternalLocal(val conversationId: String, val content: String, val otid: String)
        data class LocalMarker(val conversationId: String, val otid: String)
    }

    private class FakeChannelTransport(
        val sendResults: MutableList<Boolean>,
        var activeChatTurn: Boolean = false,
        val activeChatTurnConversations: MutableSet<String> = mutableSetOf(),
    ) : IChannelTransport {
        override val state: StateFlow<ChannelTransportState> = MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val events = MutableSharedFlow<ServerFrame>()
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()
        override fun hasActiveChatTurn(conversationId: String): Boolean =
            activeChatTurn || conversationId in activeChatTurnConversations
        override val hasAnyActiveChatTurn: Boolean
            get() = activeChatTurn || activeChatTurnConversations.isNotEmpty()
        val sentTexts = mutableListOf<String>()
        val sentConversationIds = mutableListOf<String>()
        override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = Unit
        override fun send(agentId: String, conversationId: String, text: String, otid: String?, contentParts: JsonArray?, startNewConversation: Boolean): Boolean { sentTexts += text; sentConversationIds += conversationId; return sendResults.removeFirstOrNull() ?: true }
        override fun cancel(conversationId: String): Boolean = true
        override fun bye(): Boolean = true
        override suspend fun disconnect() = Unit
        override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = A2uiActionDispatchResult.Sent("frame-1")
        override fun subscribe(runId: String, cursor: Long): Boolean = true
        override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long) = error("unused")
        override suspend fun sendCronAdd(agentId: String, name: String, description: String, prompt: String, recurring: Boolean, cron: String?, every: String?, at: String?, timezone: String?, conversationId: String?, timeoutMs: Long) = error("unused")
        override suspend fun sendCronGet(taskId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendCronDelete(taskId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long) = error("unused")
        override suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long) = error("unused")
    }

    private class FakeConversationRepository : IConversationRepository {
        override fun getConversations(agentId: AgentId): Flow<List<Conversation>> = emptyFlow()
        override fun getCachedConversations(agentId: AgentId): List<Conversation> = emptyList()
        override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean = true
        override suspend fun refreshConversations(agentId: AgentId) = Unit
        override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean = false
        override suspend fun getConversation(id: ConversationId): Conversation = conversation(id.value, AGENT_ID)
        override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation = conversation("conv-created", agentId.value)
        override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) = Unit
        override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) = Unit
        override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) = Unit
        override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) = Unit
        override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String = "run"
        override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation = conversation("fork", agentId.value)
        private fun conversation(id: String, agentId: String) = Conversation(ConversationId(id), AgentId(agentId), "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z")
    }

    private companion object {
        const val AGENT_ID = "agent-1"
        const val CONV_A = "conv-a"
        const val CONV_B = "conv-b"
        var otid = 0
    }
}
