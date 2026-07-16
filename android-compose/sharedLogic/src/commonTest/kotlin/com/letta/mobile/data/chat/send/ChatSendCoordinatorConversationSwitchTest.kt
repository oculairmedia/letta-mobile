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
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-dlbqq (Seam A) regression: a conversation SWITCH that lands
 * between the synchronous send() call and the launched coroutine body executing
 * must NOT rebind the message to the newly-active conversation. The target id
 * is sealed at the synchronous call site.
 *
 * Red->green proof: uses a StandardTestDispatcher so scope.launch does NOT run
 * eagerly. We flip the fake's activeConversation from "conv-a" to "conv-b"
 * AFTER invoking send() but BEFORE advanceUntilIdle() runs the coroutine body.
 * Against unmodified main (activeConversationId() read inside the coroutine) the
 * transport binds to "conv-b" and this test FAILS. With the fix (captured
 * before launch) it binds to "conv-a" and PASSES.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendCoordinatorConversationSwitchTest {
    @Test
    fun `switch between send call and coroutine body binds to conversation active at synchronous call`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val timeline = RecordingTimelineWriter()
        val transport = RecordingChannelTransport()
        var activeConversation: String? = "conv-a"
        val coordinator = ChatSendCoordinator(
            scope = CoroutineScope(dispatcher),
            agentId = AGENT_ID,
            activeConfig = { LettaConfig("shim", LettaConfig.Mode.SELF_HOSTED, "http://localhost:8291", "token") },
            wsChatBridge = WsChatBridge(transport),
            timelineRepository = timeline,
            conversationRepository = FakeConversationRepository(),
            ui = NoopUiSink(),
            clearComposerAfterSend = {},
            activeConversationId = { activeConversation },
            setActiveConversationId = { activeConversation = it },
            startTimelineObserver = {},
            clientVersion = { "test" },
            otidGenerator = { "otid-${++otid}" },
        )

        // Synchronous call while conv-a is active; the launched coroutine has
        // NOT run yet under StandardTestDispatcher.
        coordinator.send("hello")
        // A conversation switch lands before the coroutine body executes.
        activeConversation = "conv-b"
        // Now run the launched coroutine body.
        advanceUntilIdle()

        assertEquals(listOf("conv-a"), transport.sentConversationIds)
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
        val sentConversationIds = mutableListOf<String>()
        override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = Unit
        override fun send(agentId: String, conversationId: String, text: String, otid: String?, contentParts: JsonArray?, startNewConversation: Boolean): Boolean {
            sentConversationIds += conversationId
            return true
        }
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

    private class RecordingTimelineWriter : TimelineExternalTransportWriter {
        override suspend fun appendExternalTransportLocal(conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String = otid
        override suspend fun appendExternalTransportLocal(agentId: String?, conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String = otid
        override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage, source: String) = Unit
        override suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage, source: String) = Unit
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
        override suspend fun reconcileRecentMessages(agentId: String?, conversationId: String, reason: String, forceRefresh: Boolean): Int = 0
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
