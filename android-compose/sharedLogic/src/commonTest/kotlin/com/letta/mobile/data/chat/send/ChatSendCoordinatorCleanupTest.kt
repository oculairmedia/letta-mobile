package com.letta.mobile.data.chat.send

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendCoordinatorCleanupTest {
    @Test
    fun transientDisconnectDoesNotCleanupFailOrFinalizeActiveTurn() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(sendResults = mutableListOf(true))
        val coordinator = coordinator(timeline = timeline, ui = ui, transport = transport, activeConversationId = { "conv-1" })

        coordinator.send("hello").join()
        val local = timeline.externalLocals.single()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(WsTimelineEvent.Disconnected(code = 1006, reason = "network", willReconnect = true))
        advanceUntilIdle()

        assertTrue(ui.isStreaming())
        assertTrue(ui.isAgentTyping())
        assertNull(ui.currentError())
        assertEquals(listOf(true), ui.transientDisconnects)
        assertEquals(local.otid, timeline.externalLocals.single().otid)
        assertTrue(timeline.cleanupTails.isEmpty())
        assertTrue(timeline.failedLocals.isEmpty())
        assertTrue(timeline.clearedActiveConversations.isEmpty())
    }

    @Test
    fun terminalDisconnectWithActiveTurnCleansActiveRunTurnAndObservedAssistantRuns() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(timeline = timeline, ui = ui, transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" })

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-active"))
        coordinator.handleEvent(WsTimelineEvent.MessageDelta(AssistantMessage(id = "m1", contentRaw = JsonPrimitive("a"), runId = "run-real-1")))
        coordinator.handleEvent(WsTimelineEvent.MessageDelta(AssistantMessage(id = "m2", contentRaw = JsonPrimitive("b"), runId = "run-real-2")))
        coordinator.handleEvent(WsTimelineEvent.Disconnected(code = 1006, reason = "network lost"))
        advanceUntilIdle()

        assertEquals(
            RecordingTimelineWriter.CleanupTail(
                agentId = AGENT_ID,
                conversationId = "conv-1",
                activeRunId = "run-active",
                activeTurnId = "turn-1",
                candidateRunIds = setOf("run-active", "run-real-1", "run-real-2"),
            ),
            timeline.cleanupTails.single(),
        )
        assertEquals("network lost", ui.currentError())
        assertFalse(ui.isStreaming())
        assertEquals(listOf("conv-1"), timeline.clearedActiveConversations)
    }

    @Test
    fun terminalDisconnectWithNoActiveTurnDoesNotCleanupTailMessages() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink(isStreaming = true, isAgentTyping = true)
        val coordinator = coordinator(timeline = timeline, ui = ui, transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" })

        coordinator.handleEvent(WsTimelineEvent.Disconnected(code = 1006, reason = "gone"))
        advanceUntilIdle()

        assertTrue(timeline.cleanupTails.isEmpty())
        assertTrue(timeline.failedLocals.isEmpty())
        assertEquals("gone", ui.currentError())
        assertFalse(ui.isStreaming())
    }

    @Test
    fun cleanupFailureIsSuppressedAndTurnFinalizationContinues() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter(cleanupFailure = IllegalStateException("boom"))
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(sendResults = mutableListOf(true, true))
        val recorded = mutableListOf<WsTimelineEvent>()
        val coordinator = coordinator(
            timeline = timeline,
            ui = ui,
            transport = transport,
            activeConversationId = { "conv-1" },
            recordRuntimeEvent = { event, _ -> recorded += event },
        )

        coordinator.send("first").join()
        transport.sendResults += false
        coordinator.send("second").join()
        val active = timeline.externalLocals.last()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "failed"))
        advanceUntilIdle()

        assertEquals(listOf(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"), WsTimelineEvent.TurnDone("turn-1", "run-1", "failed")), recorded)
        assertEquals(listOf(RecordingTimelineWriter.LocalMarker("conv-1", active.otid)), timeline.failedLocals)
        assertEquals("Turn failed", ui.currentError())
        assertFalse(ui.isStreaming())
        assertEquals(listOf("conv-1"), timeline.clearedActiveConversations)
        assertEquals(1, transport.sentTexts.count { it == "second" })
    }

    @Test
    fun cleanupCancellationExceptionPropagates() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter(cleanupFailure = CancellationException("cancel cleanup"))
        val coordinator = coordinator(timeline = timeline, ui = RecordingUiSink(), transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" })

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))

        assertFailsWith<CancellationException> {
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "failed"))
        }
    }

    @Test
    fun failedTurnDoneWithSyntheticRunUsesObservedAssistantRunCandidates() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val coordinator = coordinator(timeline = timeline, ui = RecordingUiSink(), transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" })

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "synthetic-turn-run"))
        coordinator.handleEvent(WsTimelineEvent.MessageDelta(AssistantMessage(id = "m1", contentRaw = JsonPrimitive("a"), runId = "run-real")))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "synthetic-turn-run", "cancelled"))
        advanceUntilIdle()

        assertEquals(
            RecordingTimelineWriter.CleanupTail(
                agentId = AGENT_ID,
                conversationId = "conv-1",
                activeRunId = "synthetic-turn-run",
                activeTurnId = "turn-1",
                candidateRunIds = setOf("synthetic-turn-run", "run-real"),
            ),
            timeline.cleanupTails.single(),
        )
    }

    @Test
    fun postSendReconcileSkipsWhenLiveIngestArrivesAfterSend() = runTest(UnconfinedTestDispatcher()) {
        ChatSendCoordinator.postSendReconcileDelaysMs = longArrayOf(10L)
        val timeline = RecordingTimelineWriter()
        val coordinator = coordinator(timeline = timeline, ui = RecordingUiSink(), transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" }, scope = backgroundScope)

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(WsTimelineEvent.MessageDelta(AssistantMessage(id = "m1", contentRaw = JsonPrimitive("a"), runId = "run-1")))
        advanceTimeBy(11L)
        advanceUntilIdle()

        assertTrue(timeline.reconciles.isEmpty())
        ChatSendCoordinator.postSendReconcileDelaysMs = longArrayOf(750L, 2_500L, 6_000L)
    }

    @Test
    fun postSendReconcileStillRunsWithoutLiveIngest() = runTest(UnconfinedTestDispatcher()) {
        ChatSendCoordinator.postSendReconcileDelaysMs = longArrayOf(10L)
        val timeline = RecordingTimelineWriter()
        val coordinator = coordinator(timeline = timeline, ui = RecordingUiSink(), transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" }, scope = backgroundScope)

        coordinator.send("hello").join()
        advanceTimeBy(11L)
        advanceUntilIdle()

        assertEquals(listOf(RecordingTimelineWriter.Reconcile("conv-1", "post-send-10", true)), timeline.reconciles)
        ChatSendCoordinator.postSendReconcileDelaysMs = longArrayOf(750L, 2_500L, 6_000L)
    }

    private fun coordinator(
        timeline: RecordingTimelineWriter,
        ui: RecordingUiSink,
        transport: FakeChannelTransport,
        activeConversationId: () -> String? = { "conv-1" },
        recordRuntimeEvent: suspend (WsTimelineEvent, String?) -> Unit = { _, _ -> },
        scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
    ) = ChatSendCoordinator(
        scope = scope,
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
        recordRuntimeEvent = recordRuntimeEvent,
    )

    private class RecordingUiSink(
        private var error: String? = null,
        private var isStreaming: Boolean = false,
        private var isAgentTyping: Boolean = false,
    ) : ChatSendUiSink {
        val transientDisconnects = mutableListOf<Boolean>()
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
        override fun onTurnFinished(error: String?) { this.error = error; isStreaming = false; isAgentTyping = false }
        override fun onTurnVisuallyComplete() { isStreaming = false; isAgentTyping = false }
        override fun onTransientDisconnect(hasActiveSend: Boolean) { transientDisconnects += hasActiveSend; error = null; isStreaming = hasActiveSend; isAgentTyping = hasActiveSend }
        override fun onDisconnectFailure(error: String) { this.error = error; isStreaming = false; isAgentTyping = false }
    }

    private class RecordingTimelineWriter(private val cleanupFailure: Throwable? = null) : TimelineExternalTransportWriter {
        val externalLocals = mutableListOf<ExternalLocal>()
        val ingestedMessages = mutableListOf<LettaMessage>()
        val sentLocals = mutableListOf<LocalMarker>()
        val failedLocals = mutableListOf<LocalMarker>()
        val clearedActiveConversations = mutableListOf<String>()
        val cleanupTails = mutableListOf<CleanupTail>()
        val reconciles = mutableListOf<Reconcile>()
        override suspend fun appendExternalTransportLocal(conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String { externalLocals += ExternalLocal(conversationId, content, otid); return otid }
        override suspend fun appendExternalTransportLocal(agentId: String?, conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String = appendExternalTransportLocal(conversationId, content, otid, attachments)
        override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage) { ingestedMessages += message }
        override suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage) { ingestedMessages += message }
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
        override suspend fun cleanupAbandonedAssistantFragments(agentId: String?, conversationId: String, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String>): Int { cleanupFailure?.let { throw it }; cleanupTails += CleanupTail(agentId, conversationId, runId, turnId, candidateRunIds); return 0 }
        override suspend fun reconcileRecentMessages(agentId: String?, conversationId: String, reason: String, forceRefresh: Boolean): Int { reconciles += Reconcile(conversationId, reason, forceRefresh); return 0 }
        data class ExternalLocal(val conversationId: String, val content: String, val otid: String)
        data class LocalMarker(val conversationId: String, val otid: String)
        data class CleanupTail(val agentId: String?, val conversationId: String, val activeRunId: String?, val activeTurnId: String?, val candidateRunIds: Set<String>)
        data class Reconcile(val conversationId: String, val reason: String, val forceRefresh: Boolean)
    }

    private class FakeChannelTransport(val sendResults: MutableList<Boolean>) : IChannelTransport {
        override val state: StateFlow<ChannelTransportState> = MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val events = MutableSharedFlow<ServerFrame>()
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()
        val sentTexts = mutableListOf<String>()
        override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = Unit
        override fun send(agentId: String, conversationId: String, text: String, otid: String?, contentParts: JsonArray?, startNewConversation: Boolean): Boolean { sentTexts += text; return sendResults.removeFirstOrNull() ?: true }
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
        var otid = 0
    }
}
