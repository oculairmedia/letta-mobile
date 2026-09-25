package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ztuog: on device, after visiting three agents every delta reached every agent's
 * coordinator, which dropped foreign ones frame by frame (`ws.event.foreignAgentDropped` ×1000 in
 * two hours). Delivery is now keyed by agent over the one shared transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendCoordinatorAgentScopeTest {
    @Test
    fun twoCoordinatorsOnOneTransportEachReceiveOnlyTheirAgentsFrames() = runTest {
        val wire = Wire()
        val chatA = wire.chat(backgroundScope, AGENT_A)
        val chatB = wire.chat(backgroundScope, AGENT_B)
        runCurrent()

        wire.turn(AGENT_A, "turn-a1")
        wire.turn(AGENT_B, "turn-b1")
        wire.turn(AGENT_A, "turn-a2")
        runCurrent()

        assertEquals(listOf("msg-turn-a1", "msg-turn-a2"), chatA.ingestedIds())
        assertEquals(listOf("msg-turn-b1"), chatB.ingestedIds())
        assertEquals(setOf("conv-$AGENT_A"), chatA.ingestedConversations())
        assertEquals(setOf("conv-$AGENT_B"), chatB.ingestedConversations())
        assertEquals(0, foreignDrops(), "the defensive gate must not fire in normal use")
    }

    @Test
    fun deselectingAnAgentStopsDeliveryAndReselectingResumesIt() = runTest {
        val wire = Wire()
        val chatA = wire.chat(backgroundScope, AGENT_A)
        val chatB = wire.chat(backgroundScope, AGENT_B)
        runCurrent()

        chatA.coordinator.selectForEvents()
        wire.turn(AGENT_B, "turn-b1")
        runCurrent()
        assertEquals(emptyList(), chatB.ingestedIds(), "an unselected, idle agent's coordinator receives nothing")
        assertEquals(0, wire.bridge.agentScopes.liveSubscriberCount(AGENT_B))

        chatB.coordinator.selectForEvents()
        runCurrent()
        wire.turn(AGENT_B, "turn-b2")
        wire.turn(AGENT_A, "turn-a1")
        runCurrent()
        assertEquals(listOf("msg-turn-b2"), chatB.ingestedIds(), "re-selection re-attaches")
        assertEquals(emptyList(), chatA.ingestedIds(), "the agent it replaced detached")
        assertEquals(0, foreignDrops())
    }

    private fun foreignDrops(): Int = Telemetry.events.value.count {
        it.name == "ws.event.foreignAgentDropped" && it.attrs["boundAgentId"] in setOf(AGENT_A, AGENT_B)
    }

    private class Wire {
        val transport = FrameTransport()
        val bridge = WsChatBridge(transport)

        fun chat(scope: CoroutineScope, agentId: String): Chat = Chat(scope, agentId, bridge)

        suspend fun emit(frame: ServerFrame) = transport.frameEvents.emit(TransportFrameEvent(frame))

        /** An observer turn: started, one assistant message, stop reason, done — only the first two name the agent. */
        suspend fun turn(agentId: String, turnId: String) {
            val conversationId = "conv-$agentId"
            val runId = "run-$turnId"
            emit(ServerFrame.TurnStarted(id = "ts-$turnId", ts = "", agentId = agentId, conversationId = conversationId, turnId = turnId, runId = runId))
            emit(
                ServerFrame.AssistantMessage(
                    id = "msg-$turnId", agentId = agentId, conversationId = conversationId,
                    turnId = turnId, runId = runId, content = "from $agentId",
                ),
            )
            emit(ServerFrame.StopReason(turnId = turnId, runId = runId, stopReason = "end_turn"))
            emit(ServerFrame.TurnDone(id = "d-$turnId", ts = "", turnId = turnId, runId = runId, status = "completed"))
        }
    }

    private class Chat(scope: CoroutineScope, agentId: String, bridge: WsChatBridge) {
        private val timeline = RecordingTimelineWriter()
        val coordinator = ChatSendCoordinator(
            scope = scope,
            agentId = agentId,
            activeConfig = { LettaConfig("iroh", LettaConfig.Mode.SELF_HOSTED, "iroh://node@host:4501", accessToken = null) },
            wsChatBridge = bridge,
            timelineRepository = timeline,
            conversationRepository = FakeConversationRepository(),
            ui = NoopUiSink(),
            clearComposerAfterSend = {},
            activeConversationId = { "conv-$agentId" },
            setActiveConversationId = {},
            startTimelineObserver = {},
            clientVersion = { "test" },
            otidGenerator = { "otid-$agentId" },
        )

        fun ingestedIds(): List<String> = timeline.ingested.map { it.second.id }.distinct()

        fun ingestedConversations(): Set<String> = timeline.ingested.map { it.first }.toSet()
    }

    private class FrameTransport : NoOpChannelTransport() {
        override val state: StateFlow<ChannelTransportState> =
            MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()

        override fun send(
            agentId: String,
            conversationId: String,
            text: String,
            otid: String?,
            contentParts: JsonArray?,
            startNewConversation: Boolean,
        ): Boolean = true
    }

    private class RecordingTimelineWriter : TimelineExternalTransportWriter {
        val ingested = CopyOnWriteArrayList<Pair<String, LettaMessage>>()
        override suspend fun appendExternalTransportLocal(conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String = otid
        override suspend fun appendExternalTransportLocal(agentId: String?, conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String = otid
        override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage, source: String) {
            ingested += conversationId to message
        }
        override suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage, source: String) {
            ingested += conversationId to message
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

    private class FakeConversationRepository : IConversationRepository {
        override fun getConversations(agentId: AgentId): Flow<List<Conversation>> = emptyFlow()
        override fun getCachedConversations(agentId: AgentId): List<Conversation> = emptyList()
        override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean = true
        override suspend fun refreshConversations(agentId: AgentId) = Unit
        override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean = false
        override suspend fun getConversation(id: ConversationId): Conversation = conversation(id.value, AGENT_A)
        override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation = conversation("conv-created", agentId.value)
        override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) = Unit
        override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) = Unit
        override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) = Unit
        override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) = Unit
        override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String = "run"
        override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation = conversation("fork", agentId.value)
        private fun conversation(id: String, agentId: String) = Conversation(ConversationId(id), AgentId(agentId), "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z")
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

    private companion object {
        const val AGENT_A = "scope-agent-a"
        const val AGENT_B = "scope-agent-b"
    }
}
