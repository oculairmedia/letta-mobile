package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.chat.runtime.ChatComposerError
import com.letta.mobile.data.chat.runtime.ChatComposerPolicy
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.desktop.DesktopBootstrapState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
    private val agentNamesByIdProvider: suspend (agentIds: Set<String>) -> Map<String, String> = { emptyMap() },
    private val agentModelByIdProvider: suspend (agentIds: Set<String>) -> Map<String, String> = { emptyMap() },
    private val loopFactory: (
        gateway: DesktopChatGateway,
        conversation: DesktopConversationSummary,
        scope: CoroutineScope,
    ) -> DesktopTimelineLoop = { gateway, conversation, loopScope ->
        RealDesktopTimelineLoop(
            gateway = gateway,
            conversation = conversation,
            scope = loopScope,
        )
    },
) {
    private val initialState = initialLiveDesktopChatSurfaceState(bootstrapState)
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<DesktopChatSurfaceState> = _state.asStateFlow()

    private val _availableModels = MutableStateFlow<List<LlmModel>>(emptyList())
    val availableModels: StateFlow<List<LlmModel>> = _availableModels.asStateFlow()

    /** Conversations whose delete is in flight — the sidebar shows a spinner. */
    private val _deletingConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingConversationIds: StateFlow<Set<String>> = _deletingConversationIds.asStateFlow()

    private var gateway: DesktopChatGateway? = null

    // Per-conversation model overrides set this session (the picker). The
    // effective composer model otherwise comes from the conversation's agent.
    private var conversationModelById: Map<String, String> = emptyMap()

    private val httpGateway: DesktopLettaHttpChatGateway?
        get() = gateway as? DesktopLettaHttpChatGateway
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

    fun deleteConversation(conversationId: String) {
        if (closed) return
        if (conversationId in _deletingConversationIds.value) return
        scope.launch {
            val nextGateway = gateway ?: return@launch
            // Show a spinner on the row while the server delete is in flight,
            // then remove it in place — no full reconnect, so nothing flashes.
            _deletingConversationIds.update { it + conversationId }
            try {
                nextGateway.deleteConversation(conversationId)
                if (closed) return@launch
                val wasSelected = _state.value.selectedConversationId == conversationId
                _state.update {
                    it.withRuntimeState(
                        ChatSessionReducer.conversationDeleted(it.runtimeState, conversationId),
                    )
                }
                if (wasSelected) {
                    // The active chat was removed; hydrate whatever became selected.
                    timelineJob?.cancel()
                    activeLoop?.close()
                    activeLoop = null
                    val runtime = _state.value.runtimeState
                    val nextSelected = runtime.selectedConversationId
                    if (nextSelected != null) {
                        selectRemoteConversation(nextSelected, runtime.selectionGeneration)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                val message = t.message ?: t::class.simpleName ?: "Delete failed"
                _state.update { current -> current.copy(errorMessage = message) }
            } finally {
                _deletingConversationIds.update { it - conversationId }
            }
        }
    }

    /** Create a new conversation for the active agent and select it. */
    fun createConversation() {
        if (closed) return
        val agentId = _state.value.selectedConversation?.agentId
            ?: _state.value.conversations.firstOrNull()?.agentId
        if (agentId.isNullOrBlank()) {
            _state.update { it.copy(errorMessage = "Select an agent before starting a new chat.") }
            return
        }
        scope.launch {
            try {
                val created = httpGateway?.createConversation(agentId) ?: return@launch
                reloadConversationsAndSelect(preferConversationId = created.id.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.update {
                    it.copy(errorMessage = t.message ?: t::class.simpleName ?: "Could not create chat")
                }
            }
        }
    }

    /**
     * Create a new agent (cloning model/embedding from a template agent so the
     * config is valid for this backend), open a conversation for it, and select
     * it. [onCreated] reports the new agent id so the UI can refresh.
     */
    fun createAgent(
        name: String,
        model: String?,
        embedding: String?,
        onCreated: (String) -> Unit = {},
    ) {
        if (closed) return
        scope.launch {
            try {
                val gw = httpGateway ?: return@launch
                val agent = gw.createAgent(
                    AgentCreateParams(
                        name = name.ifBlank { "New agent" },
                        model = model,
                        embedding = embedding,
                        includeBaseTools = true,
                        memoryBlocks = listOf(
                            BlockCreateParams(label = "human", value = "The user has not shared details yet."),
                            BlockCreateParams(label = "persona", value = "I am $name, a helpful assistant."),
                        ),
                    ),
                )
                onCreated(agent.id.value)
                val conversation = gw.createConversation(agent.id.value)
                reloadConversationsAndSelect(preferConversationId = conversation.id.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.update {
                    it.copy(errorMessage = t.message ?: t::class.simpleName ?: "Could not create agent")
                }
            }
        }
    }

    /** Apply a model override to the active conversation. */
    fun setConversationModel(model: String) {
        if (closed) return
        val conversationId = _state.value.selectedConversationId ?: return
        conversationModelById = conversationModelById + (conversationId to model)
        _state.update { it.copy(composerModelLabel = model) }
        scope.launch {
            runCatching { httpGateway?.setConversationModel(conversationId, model) }
                .onFailure { t ->
                    _state.update {
                        it.copy(errorMessage = t.message ?: "Could not change model")
                    }
                }
        }
    }

    /**
     * Resolve and show the composer's model label for [conversationId]: a
     * session override if the user picked one, otherwise the conversation's
     * agent's configured model (the server-side default), else "Auto".
     */
    private fun applyComposerModelLabel(conversationId: String, agentId: String?) {
        scope.launch {
            val override = conversationModelById[conversationId]
            val label = when {
                !override.isNullOrBlank() -> override
                !agentId.isNullOrBlank() ->
                    runCatching { agentModelByIdProvider(setOf(agentId)) }
                        .getOrNull()?.get(agentId)?.takeIf { it.isNotBlank() } ?: "Auto"
                else -> "Auto"
            }
            if (!closed && _state.value.selectedConversationId == conversationId) {
                _state.update { it.copy(composerModelLabel = label) }
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

            // Load the model catalog for the composer model picker (best-effort).
            scope.launch {
                val models = runCatching { httpGateway?.listLlmModels() }.getOrNull().orEmpty()
                if (!closed) _availableModels.value = models
            }

            reloadConversationsAndSelect(preferConversationId = null)
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

    /**
     * Re-list conversations from the backend, update state, and select either
     * [preferConversationId] (e.g. a freshly created chat/agent) or the most
     * recent. Runs in the caller's coroutine so callers can sequence reliably
     * (no race against an async reload).
     */
    private suspend fun reloadConversationsAndSelect(preferConversationId: String?) {
        val nextGateway = gateway ?: return
        val conversations = nextGateway.listConversations()
        val agentIds = conversations.map { it.agentId.value }.filter { it.isNotBlank() }.toSet()
        val agentNamesById = runCatching { agentNamesByIdProvider(agentIds) }.getOrDefault(emptyMap())
        val summaries = conversations.toChatConversationSummaries(agentNamesById)
        if (closed) return
        val loadedRuntime = ChatSessionReducer.conversationsLoaded(
            state = _state.value.runtimeState,
            conversations = summaries,
        )
        _state.update { it.withRuntimeState(loadedRuntime) }
        val selectedId = preferConversationId?.takeIf { id -> summaries.any { it.id == id } }
            ?: summaries.firstOrNull()?.id
        selectedId?.let { selectRemoteConversation(it, loadedRuntime.selectionGeneration) }
    }

    private suspend fun selectRemoteConversation(conversationId: String, generation: Long) {
        if (!isActiveSelection(generation)) return
        val nextGateway = gateway ?: return
        val conversation = _state.value.conversations.firstOrNull { it.id == conversationId } ?: return

        // Reflect the conversation's effective model in the composer chip.
        applyComposerModelLabel(conversationId, conversation.agentId)

        timelineJob?.cancel()
        activeLoop?.close()

        _state.update {
            it.withRuntimeState(ChatSessionReducer.beginSelectedConversationHydrate(it.runtimeState, generation))
        }

        val loop = loopFactory(nextGateway, conversation, scope)
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
    conversation: DesktopConversationSummary,
    scope: CoroutineScope,
) : DesktopTimelineLoop {
    private val conversationId = conversation.id
    private val agentId = conversation.agentId
    private val transport = if (conversationId.isDefaultShimConversationId() && agentId != null) {
        DefaultShimDesktopTimelineTransport(
            gateway = gateway,
            agentId = agentId,
            externalConversationId = conversationId,
        )
    } else {
        gateway
    }
    private val loopConversationId = if (conversationId.isDefaultShimConversationId() && agentId != null) {
        "desktop-default-shim-$agentId-$conversationId"
    } else {
        conversationId
    }

    private val delegate = TimelineSyncLoop(
        messageApi = transport,
        conversationId = loopConversationId,
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

private class DefaultShimDesktopTimelineTransport(
    private val gateway: DesktopChatGateway,
    private val agentId: String,
    private val externalConversationId: String,
) : TimelineTransport {
    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> =
        gateway.sendConversationMessage(externalConversationId, request)

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        gateway.streamConversation(externalConversationId)

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> =
        gateway.listAgentMessages(
            agentId = agentId,
            limit = limit,
            order = order,
            conversationId = null,
        )

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> =
        gateway.listAgentMessages(
            agentId = agentId,
            limit = limit,
            order = order,
            conversationId = conversationId,
        )
}

private fun String.isDefaultShimConversationId(): Boolean =
    startsWith(DEFAULT_SHIM_CONVERSATION_PREFIX)

private const val DEFAULT_SHIM_CONVERSATION_PREFIX = "conv-default-"
