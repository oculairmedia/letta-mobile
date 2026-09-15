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
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reported on device (2026-09-14): with a run in flight for agent A, navigating
 * to agent B made A's reply appear in B's timeline.
 *
 * Shape under test: `WsChatBridge.events` is one app-wide flow, and every chat's
 * [ChatSendCoordinator] collects it, so each frame reaches every coordinator.
 * The agent gate in `handleEventLocked` checks only `TurnStarted` and
 * `AgentUpdated`; `MessageDelta` carries no agent id and passes. The receiving
 * coordinator then ingests the delta under ITS OWN agent id, into the conversation
 * the frame names, or - when the frame names none - into `lastActiveConversationId`
 * or `activeConversationId()`, which for a freshly opened chat is its own
 * conversation.
 *
 * Each test broadcasts the same events to every coordinator, which is exactly what
 * the shared flow does, and asserts on where agent B's coordinator writes. The
 * invariant: a coordinator bound to agent B never writes agent A's message into
 * any timeline scoped to agent B.
 *
 * These are the two id-less paths. The path the device hit - deltas that DO carry A's
 * conversation and turn id - is reproduced through the real Iroh stack in
 * `IrohCrossAgentTimelineBleedTest` (jvmTest); calling `handleEvent` directly here does
 * not reproduce production's event sequence for that case, so it is not duplicated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendCoordinatorCrossAgentBleedTest {

    @Test
    fun controlOwnTurnDeltaLandsInOwnConversation() = runTest {
        val timeline = RecordingTimelineWriter()
        val chatB = chat(AGENT_B, activeConversation = "conv-b", timeline)

        broadcast(listOf(chatB), turnStarted(AGENT_B, "conv-b", "turn-b", "run-b"))
        broadcast(listOf(chatB), delta("message-b", conversationId = "conv-b", turnId = "turn-b", runId = "run-b"))
        advanceUntilIdle()

        assertEquals(listOf(Write(AGENT_B, "conv-b", "message-b")), timeline.writes)
    }

    @Test
    fun foreignAgentDeltaWithNoIdentifiersDoesNotLandInOpenChat() = runTest {
        val timeline = RecordingTimelineWriter()
        val chatA = chat(AGENT_A, activeConversation = "conv-a", timeline)
        val chatB = chat(AGENT_B, activeConversation = "conv-b", timeline)
        val both = listOf(chatA, chatB)

        broadcast(both, turnStarted(AGENT_A, "conv-a", "turn-a", "run-a"))
        broadcast(both, delta("message-a", conversationId = null, turnId = null, runId = "run-a"))
        advanceUntilIdle()

        assertNoForeignWrite(timeline, owner = AGENT_B, foreignMessageId = "message-a")
    }

    @Test
    fun freshChatDoesNotAdoptQueuedForeignDeltas() = runTest {
        // B was opened as a new chat: no conversation yet, so deltas with no
        // resolvable conversation are queued and later drained into B's first one.
        val timeline = RecordingTimelineWriter()
        var activeB: String? = null
        val chatB = chat(AGENT_B, activeConversation = null, timeline, onActive = { activeB = it }, active = { activeB })

        broadcast(listOf(chatB), delta("message-a", conversationId = null, turnId = null, runId = "run-a"))
        activeB = "conv-b-new"
        broadcast(listOf(chatB), turnStarted(AGENT_B, "conv-b-new", "turn-b", "run-b"))
        broadcast(listOf(chatB), delta("message-b", conversationId = "conv-b-new", turnId = "turn-b", runId = "run-b"))
        advanceUntilIdle()

        assertNoForeignWrite(timeline, owner = AGENT_B, foreignMessageId = "message-a")
        assertTrue(Write(AGENT_B, "conv-b-new", "message-b") in timeline.writes, "B's own reply still lands")
    }

    // --- harness ---

    private fun TestScope.chat(
        agentId: String,
        activeConversation: String?,
        timeline: RecordingTimelineWriter,
        onActive: (String?) -> Unit = {},
        active: (() -> String?)? = null,
    ): ChatSendCoordinator {
        var current = activeConversation
        return ChatSendCoordinator(
            scope = backgroundScope,
            agentId = agentId,
            activeConfig = { LettaConfig("iroh", LettaConfig.Mode.SELF_HOSTED, "iroh://node@host:4501", accessToken = null) },
            wsChatBridge = WsChatBridge(RecordingChannelTransport()),
            timelineRepository = timeline,
            conversationRepository = FakeConversationRepository(),
            ui = NoopUiSink(),
            clearComposerAfterSend = {},
            activeConversationId = active ?: { current },
            setActiveConversationId = { current = it; onActive(it) },
            startTimelineObserver = {},
            clientVersion = { "test" },
            otidGenerator = { "otid-$agentId" },
        )
    }

    /** What the shared `WsChatBridge.events` flow does: the same event reaches every coordinator. */
    private suspend fun broadcast(chats: List<ChatSendCoordinator>, event: WsTimelineEvent) {
        chats.forEach { it.handleEvent(event) }
    }

    private fun turnStarted(agentId: String, conversationId: String, turnId: String, runId: String) =
        WsTimelineEvent.TurnStarted(turnId = turnId, agentId = agentId, conversationId = conversationId, runId = runId)

    private fun delta(messageId: String, conversationId: String?, turnId: String?, runId: String) =
        WsTimelineEvent.MessageDelta(
            message = AssistantMessage(id = messageId, contentRaw = JsonPrimitive("reply $messageId"), runId = runId),
            conversationId = conversationId,
            turnId = turnId,
        )

    private fun assertNoForeignWrite(timeline: RecordingTimelineWriter, owner: String, foreignMessageId: String) {
        val leaked = timeline.writes.filter { it.agentId == owner && it.messageId == foreignMessageId }
        assertEquals(
            emptyList(),
            leaked,
            "agent $owner's coordinator wrote another agent's message into its own timeline scope. All writes: ${timeline.writes}",
        )
    }

    private data class Write(val agentId: String?, val conversationId: String, val messageId: String)

    private class RecordingTimelineWriter : TimelineExternalTransportWriter {
        val writes = mutableListOf<Write>()
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

    private class RecordingChannelTransport : IChannelTransport {
        override val state: StateFlow<ChannelTransportState> =
            MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val events = MutableSharedFlow<ServerFrame>()
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()
        override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = Unit
        override fun send(agentId: String, conversationId: String, text: String, otid: String?, contentParts: JsonArray?, startNewConversation: Boolean): Boolean = true
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
        override suspend fun getConversation(id: ConversationId): Conversation = conversation(id.value, AGENT_B)
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
        const val AGENT_A = "agent-a"
        const val AGENT_B = "agent-b"
    }
}
