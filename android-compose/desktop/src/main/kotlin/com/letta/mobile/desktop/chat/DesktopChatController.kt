package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.runtime.ChatComposerError
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ChatComposerPolicy
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.ChatStreamingPresence
import com.letta.mobile.data.chat.runtime.ChatStreamingPresencePolicy
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.chat.runtime.persistedTitleCandidate
import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.ui.chat.render.ChatTimelineProjector
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.desktop.DesktopBootstrapState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DesktopChatController(
    private val bootstrapState: DesktopBootstrapState,
    private val scope: CoroutineScope,
    private val attachmentLimits: AttachmentLimits = AttachmentLimits.Default,
    private val gatewayFactory: suspend () -> DesktopChatGateway = {
        createDefaultDesktopChatGateway(bootstrapState.config)
    },
    private val agentNamesByIdProvider: suspend (agentIds: Set<String>) -> Map<String, String> = { emptyMap() },
    private val agentModelByIdProvider: suspend (agentIds: Set<String>) -> Map<String, String> = { emptyMap() },
    // The backend doesn't yet persist a conversation's archived flag, so we keep a
    // local, durable record of archived ids and overlay it on every load. Still
    // PATCHes the server so this lights up automatically once the backend lands.
    private val loadArchivedConversationIds: () -> Set<String> = { emptySet() },
    private val persistArchivedConversationIds: (Set<String>) -> Unit = {},
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

    /** Active / Archived / All filter for the conversation list (re-fetches). */
    private val _archiveFilter = MutableStateFlow(ConversationArchiveFilter.Active)
    val archiveFilter: StateFlow<ConversationArchiveFilter> = _archiveFilter.asStateFlow()

    /**
     * A freshly-created conversation the user hasn't sent anything to yet. We
     * auto-remove it when they navigate away or spin up another, so accidental
     * "New chat" taps don't spam the history. Cleared the moment a message is
     * sent into it.
     */
    private var unsentConversationId: String? = null

    /** Locally-tracked archived conversation ids (durable; see constructor note). */
    private var locallyArchivedIds: Set<String> = loadArchivedConversationIds()

    /** Conversations whose delete is in flight — the sidebar shows a spinner. */
    private val _deletingConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingConversationIds: StateFlow<Set<String>> = _deletingConversationIds.asStateFlow()

    /**
     * Conversation awaiting the agent's reply. Set the moment a prompt is sent
     * and cleared once the agent's response starts landing (or on failure/
     * timeout). Drives the "thinking" indicator — `isSending` alone is too brief
     * because the reply streams in over a separate background subscription.
     */
    private val _thinkingConversationId = MutableStateFlow<String?>(null)
    val thinkingConversationId: StateFlow<String?> = _thinkingConversationId.asStateFlow()

    // Bumped on every send so a stale safety-timeout can't clear the indicator
    // for a newer send in the same conversation.
    private var thinkingGeneration = 0

    /**
     * Conversation whose reply is actively streaming — set on send and cleared
     * only when the send job (which suspends for the whole reply stream)
     * completes, fails, or is cancelled. Unlike [thinkingConversationId], which
     * clears the instant the first token lands, this survives the entire stream,
     * so it (not "thinking") is the correct gate for revealing streamed text
     * progressively in the message list.
     */
    private val _streamingConversationId = MutableStateFlow<String?>(null)
    val streamingConversationId: StateFlow<String?> = _streamingConversationId.asStateFlow()

    // Same stale-guard rationale as thinkingGeneration.
    private var streamingGeneration = 0

    /**
     * Shared Timeline→message projection (the same one Android uses). Gives the
     * desktop list the incremental tail cache, optimistic-twin dedup, A2UI
     * history stripping, and no-change suppression instead of a plain re-map of
     * every event on every emit. Stateful per bound conversation — reset on
     * rebind in [selectRemoteConversation].
     */
    private val timelineProjector = ChatTimelineProjector()

    /**
     * The bound conversation's latest projection facts that the shared streaming-
     * presence policy needs. Updated on every projected timeline emit (and reset
     * on rebind) so [replyPresence] can re-derive without re-projecting.
     */
    private data class BoundPresenceFacts(
        val conversationId: String? = null,
        val tailIsAssistant: Boolean = false,
        val anyServerLocalPending: Boolean = false,
    )

    private val _boundPresenceFacts = MutableStateFlow(BoundPresenceFacts())

    /**
     * The selected conversation's "agent is working" presence, derived by the
     * SHARED [ChatStreamingPresencePolicy] — the same rules Android's chat uses —
     * from the bound conversation's projection facts plus the active reply-stream
     * signal. Desktop is server-mode only, so the client-mode / A2UI-thinking /
     * duplicate-initial branches are inert here.
     *
     * Reactive over both inputs: it re-derives when the timeline re-projects
     * ([_boundPresenceFacts]) AND when a reply stream starts or stops
     * ([_streamingConversationId]) — the latter can change with no new timeline
     * emission, so a per-emission value alone would go stale. This is the
     * desktop-side surfacing of the shared presentation's streaming/typing flags;
     * [isStreaming] gates the progressive streamed-text reveal.
     */
    private val _replyPresence = MutableStateFlow(ChatStreamingPresence(isStreaming = false, isAgentTyping = false))
    val replyPresence: StateFlow<ChatStreamingPresence> = _replyPresence.asStateFlow()

    // Keeps [replyPresence] in sync with its inputs for the controller's life.
    // Cancelled in [close] (NOT retryConnection — presence must survive a
    // reconnect); collecting into a field-backed StateFlow rather than stateIn so
    // the collector is owned and torn down explicitly instead of leaking [scope].
    private val presenceJob: Job = scope.launch {
        combine(
            _boundPresenceFacts,
            _streamingConversationId,
            state.map { it.selectedConversationId },
        ) { facts, streamingConversationId, selectedConversationId ->
            val factsForSelected = facts.conversationId != null && facts.conversationId == selectedConversationId
            ChatStreamingPresencePolicy.derive(
                // Held only on the inert client-mode branch, so the value is unused.
                previousIsStreaming = false,
                previousIsAgentTyping = false,
                anyServerLocalPending = factsForSelected && facts.anyServerLocalPending,
                tailIsAssistant = factsForSelected && facts.tailIsAssistant,
                replyStreaming = streamingConversationId != null && streamingConversationId == selectedConversationId,
                clientModeStreamInFlight = false,
                a2uiThinkingActive = false,
                duplicateInitialMessageInFlight = false,
            )
        }.collect { _replyPresence.value = it }
    }

    private var gateway: DesktopChatGateway? = null

    // Per-conversation model overrides set this session (the picker). The
    // effective composer model otherwise comes from the conversation's agent.
    private var conversationModelById: Map<String, String> = emptyMap()

    private val gatewayExtras: ChatGatewayExtras?
        get() = gateway as? ChatGatewayExtras
    private var activeLoop: DesktopTimelineLoop? = null
    private var timelineJob: Job? = null
    private var loadJob: Job? = null
    private var selectJob: Job? = null
    private var sendJob: Job? = null
    private var createConversationJob: Job? = null
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
        presenceJob.cancel()
        loadJob?.cancel()
        selectJob?.cancel()
        sendJob?.cancel()
        createConversationJob?.cancel()
        timelineJob?.cancel()
        activeLoop?.close()
        activeLoop = null
        (gateway as? AutoCloseable)?.close()
        gateway = null
    }

    fun selectConversation(conversationId: String) {
        if (closed) return
        cleanupUnsentConversation(except = conversationId)
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
        if (conversationId == unsentConversationId) unsentConversationId = null
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
                    // The active chat was removed; cancel any in-flight send so a
                    // late success/failure can't mutate the next conversation,
                    // then hydrate whatever became selected.
                    sendJob?.cancel()
                    if (_thinkingConversationId.value == conversationId) {
                        _thinkingConversationId.value = null
                    }
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
        createConversationForAgent(agentId)
    }

    /**
     * Create and select a conversation for an EXPLICIT agent — used when the
     * rail selects an agent that has no conversations yet (e.g. bulk-imported
     * fleets), where selection can't go through an existing conversation.
     */
    fun createConversationForAgent(agentId: String) {
        if (closed) return
        if (agentId.isBlank()) return
        // Serialize: clicking through several roster agents quickly must not
        // spawn a pile of racing empty conversations (each unaware of the
        // others' unsent chat). The latest click supersedes the previous.
        createConversationJob?.cancel()
        createConversationJob = scope.launch {
            try {
                // Drop the previous untouched chat first so the reload below
                // doesn't surface it (and it never piles up in the history).
                val priorUnsent = unsentConversationId
                unsentConversationId = null
                if (priorUnsent != null) {
                    runCatching { gateway?.deleteConversation(priorUnsent) }
                }
                val created = gatewayExtras?.createConversation(agentId) ?: return@launch
                unsentConversationId = created.id.value
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
     * Remove the pending unsent conversation when the user moves off it without
     * sending. [except] keeps it alive when that conversation is the one being
     * navigated *to* (e.g. re-selecting it). Server-side these should be
     * hard-deleted, not archived — there is nothing to recover.
     */
    private fun cleanupUnsentConversation(except: String?) {
        val pending = unsentConversationId ?: return
        if (pending == except) return
        unsentConversationId = null
        val gw = gateway ?: return
        scope.launch {
            runCatching { gw.deleteConversation(pending) }
                .onSuccess {
                    if (!closed) {
                        _state.update {
                            it.withRuntimeState(
                                ChatSessionReducer.conversationDeleted(it.runtimeState, pending),
                            )
                        }
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
        val agentName = name.ifBlank { "New agent" }
        scope.launch {
            try {
                val gw = gatewayExtras ?: return@launch
                val agent = gw.createAgent(
                    AgentCreateParams(
                        name = agentName,
                        model = model,
                        embedding = embedding,
                        includeBaseTools = true,
                        memoryBlocks = listOf(
                            BlockCreateParams(label = "human", value = "The user has not shared details yet."),
                            BlockCreateParams(label = "persona", value = "I am $agentName, a helpful assistant."),
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
            runCatching { gatewayExtras?.setConversationModel(conversationId, model) }
                .onFailure { t ->
                    _state.update {
                        it.copy(errorMessage = t.message ?: "Could not change model")
                    }
                }
        }
    }

    /** Switch the conversation list between Active / Archived / All (display-only). */
    fun setArchiveFilter(filter: ConversationArchiveFilter) {
        if (closed) return
        _archiveFilter.value = filter
    }

    /**
     * Archive (non-destructive, recoverable) or restore a conversation, then
     * re-list so it leaves/joins the current filter view. Selection is preserved
     * when the affected conversation isn't the one being archived away.
     */
    fun setConversationArchived(conversationId: String, archived: Boolean) {
        if (closed) return
        // Update the durable local record + the visible flag immediately so the row
        // leaves/joins the filtered list right away (the server flag is a no-op today).
        locallyArchivedIds = if (archived) locallyArchivedIds + conversationId else locallyArchivedIds - conversationId
        persistArchivedConversationIds(locallyArchivedIds)
        _state.update { current ->
            val runtime = current.runtimeState
            current.withRuntimeState(
                runtime.copy(
                    conversations = runtime.conversations.map {
                        if (it.id == conversationId) it.copy(archived = archived) else it
                    },
                ),
            )
        }
        // Forward-compatible: tell the server too, so this becomes authoritative
        // once the backend persists `archived`. A failure here doesn't undo the
        // local archive — that's the source of truth for now.
        scope.launch {
            runCatching { gatewayExtras?.setConversationArchived(conversationId, archived) }
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

        val sendingConversationId = prepareConversationForSend(text)
        // This conversation now has content — it's no longer a throwaway.
        if (sendingConversationId != null && sendingConversationId == unsentConversationId) {
            unsentConversationId = null
        }
        _state.update {
            it.withRuntimeState(ChatSessionReducer.beginSend(it.runtimeState, draft))
        }
        beginThinking(sendingConversationId)
        // Mark the reply as streaming for the whole send-stream lifetime (the
        // send job below suspends until the stream completes), so the message
        // list can reveal streamed text progressively the entire time.
        _streamingConversationId.value = sendingConversationId
        val streamGen = ++streamingGeneration
        sendJob?.cancel()
        sendJob = scope.launch {
            try {
                loop.send(
                    DesktopTimelineSendRequest(
                        content = MessageBody(text),
                        attachments = attachments,
                    ),
                )
                if (closed) return@launch
                _state.update {
                    it.withRuntimeState(ChatSessionReducer.sendSucceeded(it.runtimeState))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (closed) return@launch
                if (_thinkingConversationId.value == sendingConversationId) {
                    _thinkingConversationId.value = null
                }
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
            } finally {
                // Clears on normal completion, failure, and cancellation. The
                // generation guard prevents a cancelled prior send from clearing
                // a newer same-conversation send's streaming flag.
                if (streamGen == streamingGeneration && _streamingConversationId.value == sendingConversationId) {
                    _streamingConversationId.value = null
                }
            }
        }
    }

    private fun prepareConversationForSend(text: String): String? =
        _state.value.selectedConversationId?.also { persistConversationTitleIfNeeded(it, text) }

    private fun persistConversationTitleIfNeeded(conversationId: String, firstUserMessage: String) {
        val extras = gatewayExtras ?: return
        val conversation = _state.value.conversations.firstOrNull { it.id == conversationId } ?: return
        val candidate = conversation.persistedTitleCandidate(firstUserMessage) ?: return
        val originalTitle = conversation.title
        _state.update { current ->
            current.withRuntimeState(
                current.runtimeState.copy(
                    conversations = current.conversations.map { conversation ->
                        if (conversation.id == conversationId) conversation.copy(title = candidate) else conversation
                    },
                ),
            )
        }
        scope.launch {
            val update = ConversationSummaryUpdate(ConversationId(conversationId), ConversationSummary(candidate))
            runCatching { extras.setConversationSummary(update) }
                .onFailure {
                    _state.update { current ->
                        current.withRuntimeState(
                            current.runtimeState.copy(
                                conversations = current.conversations.map { item ->
                                    if (item.id == conversationId && item.title == candidate) {
                                        item.copy(title = originalTitle)
                                    } else {
                                        item
                                    }
                                },
                            ),
                        )
                    }
                }
        }
    }

    /**
     * Mark [conversationId] as awaiting the agent's reply, with a safety timeout
     * so the indicator can't get stuck if the reply never lands.
     */
    private fun beginThinking(conversationId: String?) {
        if (conversationId == null) return
        val generation = ++thinkingGeneration
        _thinkingConversationId.value = conversationId
        scope.launch {
            kotlinx.coroutines.delay(THINKING_TIMEOUT_MS)
            // Only the timer for the latest send may clear the indicator.
            if (generation == thinkingGeneration && _thinkingConversationId.value == conversationId) {
                _thinkingConversationId.value = null
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
                val models = runCatching { gatewayExtras?.listLlmModels() }.getOrNull().orEmpty()
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
        // Fetch every conversation (active + archived, newest first); the UI filters
        // the displayed list by [archiveFilter] so switching is instant and the
        // rail stays stable.
        val conversations = nextGateway.listConversations(archiveStatus = ConversationArchiveFilter.All.apiValue)
        val agentIds = conversations.map { it.agentId.value }.filter { it.isNotBlank() }.toSet()
        val agentNamesById = runCatching { agentNamesByIdProvider(agentIds) }.getOrDefault(emptyMap())
        val summaries = conversations.toChatConversationSummaries(agentNamesById)
            // The server can return the same conversation twice while a run is
            // active on it (order_by=last_message_at fan-out) — verified live
            // against /v1/conversations. A duplicate id crashes the sidebar
            // LazyColumn, so dedupe keeping the newest-first entry.
            .distinctBy { it.id }
            .map { if (it.id in locallyArchivedIds) it.copy(archived = true) else it }
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
        // Fresh projection cache + presence facts for the newly-bound conversation.
        timelineProjector.reset()
        _boundPresenceFacts.value = BoundPresenceFacts()

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
            loop.hydrate(
                DesktopTimelineHydrateRequest(
                    limit = TimelinePageLimit(50),
                    recordConversationCursor = true,
                ),
            )
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
        // Project through the shared ChatTimelineProjector: incremental tail
        // cache, optimistic-twin dedup, and A2UI history stripping (so raw
        // <a2ui-json> blocks no longer leak into assistant text). previousState
        // is only read for telemetry, so a default is fine here.
        val projection = timelineProjector.project(
            timeline = timeline,
            prefix = timelineProjector.olderPrefixFor(conversationId),
            previousState = ChatUiState(),
        )
        // A no-op tick (the tail re-emitted unchanged) projects to a UI
        // byte-identical to the current one — skip the state write so a streamed
        // delta burst doesn't churn recompositions.
        // Feed the shared presence policy (via replyPresence) the bound
        // conversation's latest facts. Set before the no-change early-out so the
        // first projection of a conversation always seeds presence; a no-op tick
        // re-emits identical facts, which the StateFlow dedupes.
        _boundPresenceFacts.value = BoundPresenceFacts(
            conversationId = conversationId,
            tailIsAssistant = projection.tailIsAssistant,
            anyServerLocalPending = projection.anyLettaServerLocalPending,
        )
        if (projection.noChange) return
        val messages = projection.ui
        // Stop "thinking" once the agent's reply begins to land. Use the
        // timeline tail (projection.tailIsAssistant) as well as the projected
        // list: an A2UI-only reply is extracted out of the rendered text, so
        // projection.ui still ends with the user's prompt even though an
        // assistant event landed — without the tailIsAssistant check the
        // indicator would hang until the safety timeout (Codex review).
        val agentReplyLanded = projection.tailIsAssistant ||
            messages.lastOrNull()?.role?.equals("user", ignoreCase = true) == false
        if (_thinkingConversationId.value == conversationId && agentReplyLanded) {
            _thinkingConversationId.value = null
        }
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

/** Conversation-list scope filter, mapped to the `archive_status` query param. */
enum class ConversationArchiveFilter(val apiValue: String, val label: String) {
    Active("active", "Active"),
    Archived("archived", "Archived"),
    All("all", "All"),
}
