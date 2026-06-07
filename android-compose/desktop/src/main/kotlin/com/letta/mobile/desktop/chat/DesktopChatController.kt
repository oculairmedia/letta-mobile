package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.desktop.DesktopBootstrapState
import kotlinx.coroutines.CancellationException
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
    private val loopFactory: (
        gateway: DesktopChatGateway,
        conversationId: String,
        scope: CoroutineScope,
    ) -> DesktopTimelineLoop = { gateway, conversationId, loopScope ->
        RealDesktopTimelineLoop(
            gateway = gateway,
            conversationId = conversationId,
            scope = loopScope,
        )
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
    private var activeLoop: DesktopTimelineLoop? = null
    private var timelineJob: Job? = null
    private var loadJob: Job? = null
    private var selectJob: Job? = null
    private var sendJob: Job? = null
    private var started = false
    private var closed = false
    private var selectionGeneration = 0L

    fun start() {
        if (started || closed) return
        started = true
        loadJob = scope.launch { connectAndLoad() }
    }

    fun close() {
        if (closed) return
        closed = true
        selectionGeneration++
        loadJob?.cancel()
        selectJob?.cancel()
        sendJob?.cancel()
        timelineJob?.cancel()
        activeLoop?.close()
        activeLoop = null
        (gateway as? AutoCloseable)?.close()
        gateway = null
    }

    fun selectConversation(conversationId: String) {
        if (closed) return
        val current = _state.value
        if (current.isRemoteBacked) {
            val generation = nextSelectionGeneration()
            selectJob?.cancel()
            selectJob = scope.launch {
                selectRemoteConversation(conversationId, generation)
            }
        } else {
            _state.value = current.selectConversation(conversationId)
        }
    }

    fun updateComposerText(text: String) {
        if (closed) return
        _state.update { it.withComposerText(text) }
    }

    fun send() {
        if (closed) return
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
        sendJob?.cancel()
        sendJob = scope.launch {
            try {
                loop.send(text, attachments = emptyList<MessageContentPart.Image>())
                if (closed) return@launch
                _state.update {
                    it.copy(
                        isSending = false,
                        statusMessage = "Live",
                        errorMessage = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (closed) return@launch
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
        if (closed) return
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

            if (closed) return
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

            selectedId?.let { selectRemoteConversation(it, nextSelectionGeneration()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (closed) return
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

    private suspend fun selectRemoteConversation(conversationId: String, generation: Long) {
        if (!isCurrentGeneration(generation)) return
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

        val loop = loopFactory(nextGateway, conversationId, scope)
        activeLoop = loop
        timelineJob = scope.launch {
            loop.state.collect { timeline ->
                if (isCurrentGeneration(generation)) {
                    updateTimelineMessages(conversationId, timeline)
                }
            }
        }

        try {
            loop.hydrate(limit = 50, recordConversationCursor = true)
            if (!isCurrentGeneration(generation)) return
            _state.update {
                it.copy(
                    isLoading = false,
                    statusMessage = "Live",
                    errorMessage = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (!isCurrentGeneration(generation)) return
            _state.update {
                it.copy(
                    isLoading = false,
                    statusMessage = "Message load failed",
                    errorMessage = t.message ?: t::class.simpleName ?: "Message load failed",
                )
            }
        }
    }

    private fun nextSelectionGeneration(): Long = ++selectionGeneration

    private fun isCurrentGeneration(generation: Long): Boolean =
        !closed && generation == selectionGeneration

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

interface DesktopTimelineLoop {
    val state: StateFlow<Timeline>
    suspend fun hydrate(limit: Int = 50, recordConversationCursor: Boolean = false)
    suspend fun send(content: String, attachments: List<MessageContentPart.Image> = emptyList()): String
    fun close()
}

private class RealDesktopTimelineLoop(
    gateway: DesktopChatGateway,
    conversationId: String,
    scope: CoroutineScope,
) : DesktopTimelineLoop {
    private val delegate = TimelineSyncLoop(
        messageApi = gateway,
        conversationId = conversationId,
        scope = scope,
        logTag = "DesktopChat",
    )

    override val state: StateFlow<Timeline> = delegate.state

    override suspend fun hydrate(limit: Int, recordConversationCursor: Boolean) {
        delegate.hydrate(limit = limit, recordConversationCursor = recordConversationCursor)
    }

    override suspend fun send(
        content: String,
        attachments: List<MessageContentPart.Image>,
    ): String = delegate.send(content, attachments)

    override fun close() {
        delegate.close()
    }
}
