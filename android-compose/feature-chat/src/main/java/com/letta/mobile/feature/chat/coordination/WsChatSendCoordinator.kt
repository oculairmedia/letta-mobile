package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.send.ChatSendCoordinator
import com.letta.mobile.data.chat.send.ChatSendUiSink
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.runtime.toRuntimeEventDrafts
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState

/**
 * Owns the admin-shim mobile WebSocket send path.
 *
 * letta-mobile-9ejia.5: the platform-neutral send orchestration (optimistic
 * bubble, otid reconciliation, pending-send queue, turn lifecycle on
 * [WsTimelineEvent]) now lives in the shared [ChatSendCoordinator] in
 * sharedLogic so desktop can reuse it. This class stays Android-side only to
 * (a) translate the shared [ChatSendUiSink] callbacks onto the Compose-coupled
 * [ChatUiState] and (b) supply the Android-only runtime-event recording seam
 * (the `toRuntimeEventDrafts` mapper + sink live in core:data). There is NO
 * behavior change versus the previous inline implementation.
 */
internal class WsChatSendCoordinator(
    scope: CoroutineScope,
    private val agentId: String,
    activeConfig: () -> LettaConfig?,
    wsChatBridge: WsChatBridge,
    timelineRepository: TimelineExternalTransportWriter,
    conversationRepository: IConversationRepository,
    private val uiState: MutableStateFlow<ChatUiState>,
    clearComposerAfterSend: () -> Unit,
    private val activeConversationId: () -> String?,
    @Suppress("UNUSED_PARAMETER") isFreshRoute: Boolean = false,
    setActiveConversationId: (String) -> Unit,
    startTimelineObserver: (String) -> Unit,
    clientVersionProvider: ChatClientVersionProvider,
    private val backendDescriptor: () -> BackendDescriptor? = { null },
    private val runtimeEventSink: suspend (List<RuntimeEventDraft>) -> Unit = {},
) {
    private val uiSink = object : ChatSendUiSink {
        override fun currentError(): String? = uiState.value.error
        override fun isStreaming(): Boolean = uiState.value.isStreaming
        override fun isAgentTyping(): Boolean = uiState.value.isAgentTyping

        override fun onSendDispatched(conversationId: String?) {
            uiState.update { current ->
                current.copy(
                    conversationState = conversationId
                        ?.let { ConversationState.Ready(it) }
                        ?: current.conversationState,
                    isStreaming = true,
                    isAgentTyping = true,
                    error = null,
                )
            }
        }

        override fun onSendQueued(conversationId: String) {
            uiState.update { current ->
                current.copy(
                    conversationState = ConversationState.Ready(conversationId),
                    isStreaming = true,
                    isAgentTyping = true,
                    error = null,
                )
            }
        }

        override fun onSendFailed(message: String) {
            uiState.update { current ->
                current.copy(
                    error = message,
                    isStreaming = false,
                    isAgentTyping = false,
                )
            }
        }

        override fun onError(message: String?) {
            uiState.update { current -> current.copy(error = message) }
        }

        override fun onTurnStarted(conversationId: String) {
            uiState.update { current ->
                current.copy(
                    conversationState = ConversationState.Ready(conversationId),
                    isStreaming = true,
                    isAgentTyping = true,
                    error = null,
                )
            }
        }

        override fun onMessageDelta(conversationId: String) {
            uiState.update { current ->
                current.copy(
                    conversationState = ConversationState.Ready(conversationId),
                    isStreaming = true,
                    isAgentTyping = true,
                    error = null,
                )
            }
        }

        override fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int) {
            uiState.update { current ->
                current.copy(
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens,
                )
            }
        }

        override fun onTurnFinished(error: String?) {
            uiState.update { current ->
                current.copy(
                    isStreaming = false,
                    isAgentTyping = false,
                    error = error,
                )
            }
        }

        override fun onTurnVisuallyComplete() {
            uiState.update { current ->
                current.copy(
                    isStreaming = false,
                    isAgentTyping = false,
                )
            }
        }

        override fun onTransientDisconnect(hasActiveSend: Boolean) {
            uiState.update { current ->
                current.copy(
                    error = null,
                    isStreaming = current.isStreaming || hasActiveSend,
                    isAgentTyping = current.isAgentTyping || hasActiveSend,
                )
            }
        }

        override fun onDisconnectFailure(error: String) {
            uiState.update { current ->
                current.copy(
                    error = error,
                    isStreaming = false,
                    isAgentTyping = false,
                )
            }
        }
    }

    private val delegate = ChatSendCoordinator(
        scope = scope,
        agentId = agentId,
        activeConfig = activeConfig,
        wsChatBridge = wsChatBridge,
        timelineRepository = timelineRepository,
        conversationRepository = conversationRepository,
        ui = uiSink,
        clearComposerAfterSend = clearComposerAfterSend,
        activeConversationId = activeConversationId,
        setActiveConversationId = setActiveConversationId,
        startTimelineObserver = startTimelineObserver,
        clientVersion = { clientVersionProvider.clientVersion },
        otidGenerator = { "cm-android-${UUID.randomUUID()}" },
        recordRuntimeEvent = ::recordRuntimeEvent,
    )

    fun send(
        text: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): Job = delegate.send(text, attachments)

    fun cancel(): Boolean = delegate.cancel()

    internal suspend fun handleEvent(event: WsTimelineEvent) = delegate.handleEvent(event)

    private suspend fun recordRuntimeEvent(
        event: WsTimelineEvent,
        conversationIdOverride: String?,
    ) {
        val backend = backendDescriptor() ?: return
        // The shared coordinator resolves its own active-conversation fallback
        // and passes it as [conversationIdOverride]; only the screen-level
        // active conversation id remains to be supplied here.
        val conversationId = (conversationIdOverride ?: activeConversationId())
            ?.let(::ConversationId)
        val drafts = event.toRuntimeEventDrafts(
            backend = backend,
            fallbackAgentId = AgentId(agentId),
            fallbackConversationId = conversationId,
        )
        if (drafts.isEmpty()) return
        runCatching {
            runtimeEventSink(drafts)
        }.onFailure { error ->
            Telemetry.error("AdminChatVM", "runtimeEvent.recordFailed", error)
        }
    }
}
