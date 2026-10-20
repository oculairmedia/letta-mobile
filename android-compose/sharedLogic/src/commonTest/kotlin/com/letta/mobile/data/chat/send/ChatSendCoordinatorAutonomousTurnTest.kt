package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.BridgeTurnStatus
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
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendCoordinatorAutonomousTurnTest {
    @Test
    fun autonomousObserverTurnWithoutTurnStartedSettlesOwnTimelineOnTurnDone() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val transport = RecordingChannelTransport()
        var active: String? = CONV_A
        val coordinator = coordinator(timeline, ui, transport) { active }

        // 1. Complete a local turn on CONV_A first so turnStates has CONV_A and lastActiveConversationId = CONV_A
        coordinator.send("hello A").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-a", "run-a", BridgeTurnStatus.Completed))
        advanceUntilIdle()
        timeline.clearedActiveConversations.clear()
        ui.turnsFinished.clear()

        // 2. Switch to CONV_B; an autonomous observer turn streams deltas on CONV_B with NO TurnStarted
        active = CONV_B
        coordinator.handleEvent(
            WsTimelineEvent.MessageDelta(
                message = AssistantMessage(
                    id = "msg-auto-b",
                    contentRaw = JsonPrimitive("autonomous subagent completion reply"),
                    runId = "run-auto-b",
                ),
                conversationId = CONV_B,
                turnId = "turn-auto-b",
                agentId = AGENT_ID,
            ),
        )
        assertTrue(ui.isStreaming(), "autonomous delta on visible conversation must set streaming")

        // 3. Observer emits TurnDone for the autonomous turn -> must settle CONV_B (not CONV_A, not staleIgnored)
        coordinator.handleEvent(
            WsTimelineEvent.TurnDone(
                turnId = "turn-auto-b",
                runId = "run-auto-b",
                status = BridgeTurnStatus.Completed,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(CONV_B), timeline.clearedActiveConversations)
        assertEquals(1, ui.turnsFinished.size)
        assertTrue(!ui.isStreaming(), "streaming must clear when autonomous turn finishes")
    }

    private fun coordinator(
        timeline: RecordingTimelineWriter,
        ui: RecordingUiSink,
        transport: RecordingChannelTransport,
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
        activeConversationId: () -> String?,
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
    )

    private class RecordingUiSink : ChatSendUiSink {
        private var error: String? = null
        private var isStreaming: Boolean = false
        private var isAgentTyping: Boolean = false
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
        override fun onTurnFinished(error: String?) {
            turnsFinished += error
            this.error = error
            isStreaming = false
            isAgentTyping = false
        }
        override fun onTurnVisuallyComplete() { isStreaming = false; isAgentTyping = false }
        override fun onTransientDisconnect(hasActiveSend: Boolean) = Unit
        override fun onDisconnectFailure(error: String) = Unit
    }

    private class RecordingTimelineWriter : TimelineExternalTransportWriter {
        val clearedActiveConversations = mutableListOf<String>()

        override suspend fun clearExternalTransportActive(conversationId: String) {
            clearedActiveConversations += conversationId
        }

        override suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) {
            clearedActiveConversations += conversationId
        }

        override suspend fun appendExternalTransportLocal(
            conversationId: String,
            content: String,
            otid: String,
            attachments: List<MessageContentPart.Image>,
        ): String = otid

        override suspend fun appendExternalTransportLocal(
            agentId: String?,
            conversationId: String,
            content: String,
            otid: String,
            attachments: List<MessageContentPart.Image>,
        ): String = otid

        override suspend fun ingestExternalTransportMessage(
            conversationId: String,
            message: LettaMessage,
            source: String,
        ) = Unit

        override suspend fun ingestExternalTransportMessage(
            agentId: String?,
            conversationId: String,
            message: LettaMessage,
            source: String,
        ) = Unit

        override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) = Unit
        override suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) = Unit
        override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) = Unit
        override suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) = Unit

        override suspend fun reconcileExternalTransportSend(
            conversationId: String,
            agentId: String,
            externalConversationId: String,
            otid: String,
        ) = Unit

        override suspend fun reconcileExternalTransportSendScoped(
            agentId: String?,
            conversationId: String,
            externalConversationId: String,
            otid: String,
        ) = Unit

        override suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?) = Unit
        override suspend fun repairExpiredConversationCursorScoped(
            agentId: String?,
            conversationId: String,
            fallbackSeq: Long?,
        ) = Unit

        override suspend fun cleanupAbandonedAssistantFragments(
            agentId: String?,
            conversationId: String,
            runId: String?,
            turnId: String?,
            reason: String,
            candidateRunIds: Set<String>,
        ): Int = 0

        override suspend fun reconcileRecentMessages(
            agentId: String?,
            conversationId: String,
            reason: String,
            forceRefresh: Boolean,
            connectionGeneration: Long,
        ): RecentMessagesReconcileOutcome = RecentMessagesReconcileOutcome.Applied(0)
    }

    private class RecordingChannelTransport : IChannelTransport {
        override val state: StateFlow<ChannelTransportState> =
            MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val events = MutableSharedFlow<ServerFrame>()
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()
        override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = Unit
        override fun send(
            agentId: String,
            conversationId: String,
            text: String,
            otid: String?,
            contentParts: JsonArray?,
            startNewConversation: Boolean,
        ): Boolean = true

        override fun cancel(conversationId: String): Boolean = true
        override fun bye(): Boolean = true
        override suspend fun disconnect() = Unit
        override fun subscribe(runId: String, cursor: Long): Boolean = true
        override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long) = error("unused")
        override suspend fun sendCronAdd(
            agentId: String,
            name: String,
            description: String,
            prompt: String,
            recurring: Boolean,
            cron: String?,
            every: String?,
            at: String?,
            timezone: String?,
            conversationId: String?,
            timeoutMs: Long,
        ) = error("unused")

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
        override suspend fun createConversation(
            agentId: AgentId,
            summary: String?,
        ): Conversation = conversation("conv-created", agentId.value)

        override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) = Unit
        override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) = Unit
        override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) = Unit
        override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) = Unit
        override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String = "run"
        override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation = conversation("fork", agentId.value)
        private fun conversation(id: String, agentId: String) = Conversation(
            ConversationId(id), AgentId(agentId), "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z",
        )
    }

    private companion object {
        const val AGENT_ID = "agent-1"
        const val CONV_A = "conv-a"
        const val CONV_B = "conv-b"
        var otid = 0
    }
}
