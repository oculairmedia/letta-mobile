package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.desktop.DesktopBootstrapState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DesktopChatController(
    bootstrapState: DesktopBootstrapState,
    private val scope: CoroutineScope,
    private val gatewayFactory: () -> DesktopChatGateway = {
        DesktopLettaHttpChatGateway(bootstrapState.config)
    },
) {
    private val sampleState = defaultDesktopChatSurfaceState(bootstrapState)
    private val _state = MutableStateFlow(
        sampleState.copy(
            statusMessage = "Connecting to ${bootstrapState.config.serverUrl}",
        ),
    )
    val state: StateFlow<DesktopChatSurfaceState> = _state.asStateFlow()

    private var gateway: DesktopChatGateway? = null
    private var activeLoop: TimelineSyncLoop? = null
    private var timelineJob: Job? = null
    private var loadJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        loadJob = scope.launch { connectAndLoad() }
    }

    fun close() {
        loadJob?.cancel()
        timelineJob?.cancel()
        activeLoop?.close()
        activeLoop = null
        (gateway as? AutoCloseable)?.close()
        gateway = null
    }

    fun selectConversation(conversationId: String) {
        val current = _state.value
        if (current.isRemoteBacked) {
            scope.launch {
                selectRemoteConversation(conversationId)
            }
        } else {
            _state.value = current.selectConversation(conversationId)
        }
    }

    fun updateComposerText(text: String) {
        _state.update { it.withComposerText(text) }
    }

    fun send() {
        val text = _state.value.composerText.trim()
        if (text.isBlank()) return

        val loop = activeLoop
        if (loop == null || !_state.value.isRemoteBacked) {
            _state.update { it.sendLocalMessage() }
            return
        }

        _state.update {
            it.copy(
                composerText = "",
                isSending = true,
                statusMessage = "Sending",
                errorMessage = null,
            )
        }
        scope.launch {
            try {
                loop.send(text, attachments = emptyList<MessageContentPart.Image>())
                _state.update {
                    it.copy(
                        isSending = false,
                        statusMessage = "Live",
                        errorMessage = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isSending = false,
                        statusMessage = "Send failed",
                        errorMessage = t.message ?: t::class.simpleName ?: "Send failed",
                    )
                }
            }
        }
    }

    private suspend fun connectAndLoad() {
        _state.update {
            it.copy(
                isLoading = true,
                statusMessage = "Loading conversations",
                errorMessage = null,
            )
        }

        try {
            val nextGateway = gatewayFactory()
            gateway = nextGateway
            val conversations = nextGateway.listConversations()
            val summaries = conversations.map { it.toDesktopSummary() }
            val selectedId = summaries.firstOrNull()?.id

            _state.value = sampleState.copy(
                conversations = summaries,
                selectedConversationId = selectedId,
                messagesByConversationId = emptyMap(),
                composerText = "",
                isLoading = false,
                isSending = false,
                isRemoteBacked = true,
                statusMessage = if (summaries.isEmpty()) "No conversations" else "Live",
                errorMessage = null,
            )

            selectedId?.let { selectRemoteConversation(it) }
        } catch (t: Throwable) {
            _state.update {
                sampleState.copy(
                    composerText = it.composerText,
                    isLoading = false,
                    isSending = false,
                    isRemoteBacked = false,
                    statusMessage = "Using local preview",
                    errorMessage = t.message ?: t::class.simpleName ?: "Backend unavailable",
                )
            }
        }
    }

    private suspend fun selectRemoteConversation(conversationId: String) {
        val nextGateway = gateway ?: return
        if (_state.value.conversations.none { it.id == conversationId }) return

        timelineJob?.cancel()
        activeLoop?.close()

        _state.update {
            it.copy(
                selectedConversationId = conversationId,
                conversations = it.conversations.map { conversation ->
                    if (conversation.id == conversationId) {
                        conversation.copy(unreadCount = 0)
                    } else {
                        conversation
                    }
                },
                composerText = "",
                isLoading = true,
                statusMessage = "Loading messages",
                errorMessage = null,
            )
        }

        val loop = TimelineSyncLoop(
            messageApi = nextGateway,
            conversationId = conversationId,
            scope = scope,
            logTag = "DesktopChat",
        )
        activeLoop = loop
        timelineJob = scope.launch {
            loop.state.collect { timeline ->
                updateTimelineMessages(conversationId, timeline)
            }
        }

        try {
            loop.hydrate(limit = 50, recordConversationCursor = true)
            _state.update {
                it.copy(
                    isLoading = false,
                    statusMessage = "Live",
                    errorMessage = null,
                )
            }
        } catch (t: Throwable) {
            _state.update {
                it.copy(
                    isLoading = false,
                    statusMessage = "Message load failed",
                    errorMessage = t.message ?: t::class.simpleName ?: "Message load failed",
                )
            }
        }
    }

    private fun updateTimelineMessages(conversationId: String, timeline: Timeline) {
        val messages = timeline.events
            .sortedBy { it.position }
            .mapNotNull(::timelineEventToUiMessage)
        _state.update { current ->
            current.copy(
                messagesByConversationId = current.messagesByConversationId + (conversationId to messages),
                conversations = current.conversations.map { conversation ->
                    if (conversation.id == conversationId) {
                        conversation.copy(lastMessagePreview = messages.lastPreviewOr(conversation.lastMessagePreview))
                    } else {
                        conversation
                    }
                },
            )
        }
    }
}

private fun Conversation.toDesktopSummary(): DesktopConversationSummary {
    val updatedLabel = lastMessageAt ?: updatedAt ?: createdAt ?: "Remote"
    return DesktopConversationSummary(
        id = id.value,
        title = summary?.takeIf { it.isNotBlank() } ?: "Conversation ${id.value.takeLast(6)}",
        agentName = agentId.value,
        updatedAtLabel = updatedLabel,
        lastMessagePreview = "Loaded from backend",
    )
}

private fun List<UiMessage>.lastPreviewOr(fallback: String): String =
    lastOrNull { it.content.isNotBlank() }?.content?.lineSequence()?.firstOrNull()?.take(140) ?: fallback
