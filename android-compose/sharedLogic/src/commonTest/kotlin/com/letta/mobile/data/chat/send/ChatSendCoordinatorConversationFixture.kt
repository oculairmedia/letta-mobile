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
import kotlinx.serialization.json.JsonArray

@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class ChatSendCoordinatorConversationFixture {
    protected fun coordinator(
        timeline: RecordingTimelineWriter,
        ui: RecordingUiSink,
        transport: FakeChannelTransport,
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
        recordRuntimeEvent: suspend (WsTimelineEvent, String?) -> Unit = { _, _ -> },
        recordRuntimeEvents: suspend (List<ScopedRuntimeEvent>) -> Unit = { events ->
            events.forEach { event -> recordRuntimeEvent(event.event, event.conversationId) }
        },
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
        recordRuntimeEvents = recordRuntimeEvents,
    )

    protected class RecordingUiSink(
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
        override fun onTurnFinished(
            error: String?,
        ) {
            turnsFinished += error
            this.error = error
            isStreaming = false
            isAgentTyping = false
        }
        override fun onTurnVisuallyComplete() { visualCompletions++; isStreaming = false; isAgentTyping = false }
        override fun onTransientDisconnect(
            hasActiveSend: Boolean,
        ) {
            error = null
            isStreaming = hasActiveSend
            isAgentTyping = hasActiveSend
        }
        override fun onDisconnectFailure(error: String) { this.error = error; isStreaming = false; isAgentTyping = false }
    }

    protected class RecordingTimelineWriter : TimelineExternalTransportWriter {
        val externalLocals = mutableListOf<ExternalLocal>()
        val ingestedMessages = mutableListOf<LettaMessage>()
        val sentLocals = mutableListOf<LocalMarker>()
        val failedLocals = mutableListOf<LocalMarker>()
        val clearedActiveConversations = mutableListOf<String>()
        override suspend fun appendExternalTransportLocal(
            conversationId: String,
            content: String,
            otid: String,
            attachments: List<MessageContentPart.Image>,
        ): String {
            externalLocals += ExternalLocal(conversationId, content, otid)
            return otid
        }
        override suspend fun appendExternalTransportLocal(
            agentId: String?,
            conversationId: String,
            content: String,
            otid: String,
            attachments: List<MessageContentPart.Image>,
        ): String = appendExternalTransportLocal(conversationId, content, otid, attachments)
        override suspend fun ingestExternalTransportMessage(
            conversationId: String,
            message: LettaMessage,
            source: String,
        ) {
            ingestedMessages += message
        }
        override suspend fun ingestExternalTransportMessage(
            agentId: String?,
            conversationId: String,
            message: LettaMessage,
            source: String,
        ) {
            ingestedMessages += message
        }
        override suspend fun markExternalTransportLocalSent(
            conversationId: String,
            otid: String,
        ) {
            sentLocals += LocalMarker(conversationId, otid)
        }
        override suspend fun markExternalTransportLocalSent(
            agentId: String?,
            conversationId: String,
            otid: String,
        ) {
            sentLocals += LocalMarker(conversationId, otid)
        }
        override suspend fun markExternalTransportLocalFailed(
            conversationId: String,
            otid: String,
        ) {
            failedLocals += LocalMarker(conversationId, otid)
        }
        override suspend fun markExternalTransportLocalFailed(
            agentId: String?,
            conversationId: String,
            otid: String,
        ) {
            failedLocals += LocalMarker(conversationId, otid)
        }
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
        override suspend fun repairExpiredConversationCursorScoped(agentId: String?, conversationId: String, fallbackSeq: Long?) = Unit
        override suspend fun clearExternalTransportActive(conversationId: String) { clearedActiveConversations += conversationId }
        override suspend fun clearExternalTransportActive(
            agentId: String?,
            conversationId: String,
        ) {
            clearedActiveConversations += conversationId
        }
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
        data class ExternalLocal(val conversationId: String, val content: String, val otid: String)
        data class LocalMarker(val conversationId: String, val otid: String)
    }

    protected class FakeChannelTransport(
        val sendResults: MutableList<Boolean>,
        var activeChatTurn: Boolean = false,
        val activeChatTurnConversations: MutableSet<String> = mutableSetOf(),
    ) : IChannelTransport {
        override val state: StateFlow<ChannelTransportState> =
            MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val events = MutableSharedFlow<ServerFrame>()
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()
        override fun hasActiveChatTurn(conversationId: String): Boolean =
            activeChatTurn || conversationId in activeChatTurnConversations
        override val hasAnyActiveChatTurn: Boolean
            get() = activeChatTurn || activeChatTurnConversations.isNotEmpty()
        val sentTexts = mutableListOf<String>()
        val sentConversationIds = mutableListOf<String>()
        override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = Unit
        override fun send(
            agentId: String,
            conversationId: String,
            text: String,
            otid: String?,
            contentParts: JsonArray?,
            startNewConversation: Boolean,
        ): Boolean {
            sentTexts += text
            sentConversationIds += conversationId
            return sendResults.removeFirstOrNull() ?: true
        }
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
        private fun conversation(
            id: String,
            agentId: String,
        ) = Conversation(ConversationId(id), AgentId(agentId), "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z")
    }

    private var otid = 0
    private companion object { const val AGENT_ID = "agent-1" }
}
