package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.chat.runtime.ChatComposerError
import com.letta.mobile.data.chat.runtime.ChatComposerPolicy
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.MessageContentPart
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
    private val bootstrapState: DesktopBootstrapState,
    private val scope: CoroutineScope,
    private val attachmentLimits: AttachmentLimits = AttachmentLimits.Default,
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
    private val initialState = initialLiveDesktopChatSurfaceState(bootstrapState)
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<DesktopChatSurfaceState> = _state.asStateFlow()

    private var gateway: DesktopChatGateway? = null
    private var activeLoop: DesktopTimelineLoop? = null
    private var timelineJob: Job? = null
    private var loadJob: Job? = null
    private var selectJob: Job? = null
    private var sendJob: Job? = null
    private var started = false
    private var closed = false

    fun start() {
        if (started || closed) return
        started = true
        loadJob = scope.launch { connectAndLoad() }
    }

    fun retryConnection() {
        if (closed) return
        loadJob?.cancel()
        selectJob?.cancel()
        sendJob?.cancel()
        timelineJob?.cancel()
        activeLoop?.close()
        activeLoop = null
        (gateway as? AutoCloseable)?.close()
        gateway = null
        started = false
        _state.update { current ->
            initialState.withRuntimeState(
                ChatSessionReducer.retryConnection(
                    current = current.runtimeState,
                    initial = initialState.runtimeState,
                ),
            )
        }
        start()
    }

    fun close() {
        if (closed) return
        closed = true
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
        var generation: Long? = null
        var shouldLoadRemote = false
        _state.update { current ->
            val currentRuntime = current.runtimeState
            val next = ChatSessionReducer.selectConversation(
                state = currentRuntime,
                conversationId = conversationId,
                remoteBacked = current.isRemoteBacked,
            )
            shouldLoadRemote = current.isRemoteBacked && next != currentRuntime
            generation = if (shouldLoadRemote) next.selectionGeneration else null
            current.withRuntimeState(next)
        }
        if (shouldLoadRemote) {
            selectJob?.cancel()
            selectJob = scope.launch {
                selectRemoteConversation(conversationId, generation ?: return@launch)
            }
        }
    }

    fun updateComposerText(text: String) {
        if (closed) return
        _state.update { it.withRuntimeState(ChatSessionReducer.updateComposerText(it.runtimeState, text)) }
    }

    fun attachImage(image: MessageContentPart.Image) {
        if (closed) return
        _state.update { current ->
            val next = ChatSessionReducer.attachImage(current.runtimeState, image, attachmentLimits)
            current.withRuntimeState(next).copy(errorMessage = next.composer.error?.toDesktopMessage(attachmentLimits))
        }
    }

    fun removeImageAttachment(index: Int) {
        if (closed) return
        _state.update {
            it.withRuntimeState(ChatSessionReducer.removeImageAttachment(it.runtimeState, index))
                .copy(errorMessage = null)
        }
    }

    fun showComposerError(message: String) {
        if (closed) return
        _state.update { it.copy(errorMessage = message) }
    }

    fun send() {
        if (closed) return
        val draft = ChatComposerPolicy.beginSend(_state.value.composer) ?: return
        val text = draft.text
        val attachments = draft.attachments

        val loop = activeLoop
        if (loop == null || !_state.value.isRemoteBacked) {
            _state.update {
                if (it.connectionState == DesktopChatConnectionState.Demo) {
                    it.sendLocalMessage()
                } else {
                    it
                }
            }
            return
        }

        _state.update {
            it.withRuntimeState(ChatSessionReducer.beginSend(it.runtimeState, draft))
        }
        sendJob?.cancel()
        sendJob = scope.launch {
            try {
                loop.send(text, attachments = attachments)
                if (closed) return@launch
                _state.update {
                    it.withRuntimeState(ChatSessionReducer.sendSucceeded(it.runtimeState))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (closed) return@launch
                _state.update {
                    it.withRuntimeState(
                        ChatSessionReducer.sendFailed(
                            state = it.runtimeState,
                            text = text,
                            attachments = attachments,
                            errorMessage = t.message ?: t::class.simpleName ?: "Send failed",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun connectAndLoad() {
        if (closed) return
        if (bootstrapState.config.serverUrl.isBlank()) {
            _state.value = initialState.withRuntimeState(
                ChatSessionReducer.configNeeded(initialState.runtimeState),
            )
            return
        }

        _state.update {
            it.withRuntimeState(ChatSessionReducer.beginConversationLoad(it.runtimeState))
        }

        try {
            val nextGateway = gatewayFactory()
            gateway = nextGateway
            val conversations = nextGateway.listConversations()
            val summaries = conversations.map { it.toDesktopSummary() }
            val selectedId = summaries.firstOrNull()?.id

            if (closed) return
            val loadedRuntime = ChatSessionReducer.conversationsLoaded(
                state = _state.value.runtimeState,
                conversations = summaries,
            )
            _state.update { it.withRuntimeState(loadedRuntime) }

            selectedId?.let { selectRemoteConversation(it, loadedRuntime.selectionGeneration) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (closed) return
            val message = t.message ?: t::class.simpleName ?: "Backend unavailable"
            _state.update {
                it.withRuntimeState(
                    ChatSessionReducer.conversationLoadFailed(
                        state = it.runtimeState,
                        errorMessage = message,
                    ),
                )
            }
        }
    }

    private suspend fun selectRemoteConversation(conversationId: String, generation: Long) {
        if (!isActiveSelection(generation)) return
        val nextGateway = gateway ?: return
        if (_state.value.conversations.none { it.id == conversationId }) return

        timelineJob?.cancel()
        activeLoop?.close()

        _state.update {
            it.withRuntimeState(ChatSessionReducer.beginSelectedConversationHydrate(it.runtimeState, generation))
        }

        val loop = loopFactory(nextGateway, conversationId, scope)
        activeLoop = loop
        timelineJob = scope.launch {
            loop.state.collect { timeline ->
                updateTimelineMessages(conversationId, generation, timeline)
            }
        }

        try {
            loop.hydrate(limit = 50, recordConversationCursor = true)
            if (!isActiveSelection(generation)) return
            _state.update {
                it.withRuntimeState(ChatSessionReducer.hydrateCompleted(it.runtimeState, generation))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (!isActiveSelection(generation)) return
            _state.update {
                it.withRuntimeState(
                    ChatSessionReducer.streamDisconnected(
                        state = it.runtimeState,
                        generation = generation,
                        errorMessage = t.message ?: t::class.simpleName ?: "Message load failed",
                    ),
                )
            }
        }
    }

    private fun isActiveSelection(generation: Long): Boolean =
        !closed && ChatSessionReducer.isCurrentSelection(_state.value.runtimeState, generation)

    private fun updateTimelineMessages(conversationId: String, generation: Long, timeline: Timeline) {
        if (closed) return
        val messages = timeline.events
            .sortedBy { it.position }
            .mapNotNull(::timelineEventToUiMessage)
        _state.update { current ->
            current.withRuntimeState(
                ChatSessionReducer.timelineMessagesUpdated(
                    state = current.runtimeState,
                    generation = generation,
                    conversationId = conversationId,
                    messages = messages,
                ),
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

private fun ChatComposerError.toDesktopMessage(limits: AttachmentLimits): String = when (this) {
    ChatComposerError.MaxAttachmentCountExceeded -> "Attach up to ${limits.maxAttachmentCount} images."
    ChatComposerError.MaxTotalBase64BytesExceeded -> "Attached images exceed the desktop payload limit."
    ChatComposerError.AttachmentLoadFailed -> "Could not attach image."
}

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
