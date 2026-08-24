package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.runtime.ChatComposerPolicy
import com.letta.mobile.data.chat.runtime.ChatComposerSendDraft
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.ChatStreamInputs
import com.letta.mobile.data.chat.runtime.ChatStreamingPresence
import com.letta.mobile.data.chat.runtime.ChatStreamingPresencePolicy
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryGateway
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.chat.runtime.persistedTitleCandidate
import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ModelCatalog
import com.letta.mobile.data.model.withCatalogModelRouting
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.desktop.DesktopBootstrapState
import com.letta.mobile.ui.chat.render.ChatTimelineProjector
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class DesktopChatController(
    private val bootstrapState: DesktopBootstrapState,
    private val scope: CoroutineScope,
    private val attachmentLimits: AttachmentLimits = AttachmentLimits.Default,
    private val gatewayFactory: suspend () -> DesktopChatGateway = {
        createDefaultDesktopChatGateway(bootstrapState.config)
    },
    private val agentNamesByIdProvider: suspend (agentIds: Set<String>) -> Map<String, String> = { emptyMap() },
    private val agentByIdProvider: suspend (agentIds: Set<String>) -> Map<String, Agent> = { emptyMap() },
    // The backend doesn't yet persist a conversation's archived flag, so we keep a
    // local, durable record of archived ids and overlay it on every load. Still
    // PATCHes the server so this lights up automatically once the backend lands.
    loadArchivedConversationIds: () -> Set<String> = { emptySet() },
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

    private val approvalCoordinator = DesktopChatApprovalCoordinator(
        scope = scope,
        onError = { message ->
            if (!closed) {
                _state.update { it.copy(errorMessage = message) }
            }
        },
    )

    /**
     * Approval request ids whose decision (answer / dismiss) is in flight, so the
     * structured AskUserQuestion card can disable its buttons while submitting.
     */
    val submittingApprovals: StateFlow<Set<String>> = approvalCoordinator.submittingApprovals

    /**
     * Whether the active gateway can actually submit approvals (i.e. is a
     * [DesktopApprovalSubmitter]). Demo / HTTP-only gateways can't, so the UI must
     * disable/hide the approval-answer buttons instead of offering a silent no-op.
     */
    val canSubmitApprovals: StateFlow<Boolean> = approvalCoordinator.canSubmitApprovals

    /**
     * letta-mobile folder-settings #2: the SELECTED conversation's working
     * directory, as last read from the runtime via
     * [DesktopWorkingDirectoryController.currentWorkingDirectory]. Null while
     * unknown/loading, or when the active gateway doesn't support this
     * (remote/HTTP backends) — see [supportsWorkingDirectory].
     */
    private val _selectedConversationWorkingDirectory = MutableStateFlow<String?>(null)
    val selectedConversationWorkingDirectory: StateFlow<String?> =
        _selectedConversationWorkingDirectory.asStateFlow()

    private val _workingDirectoryLoading = MutableStateFlow(false)
    val workingDirectoryLoading: StateFlow<Boolean> = _workingDirectoryLoading.asStateFlow()

    /** Whether the active gateway can report/change a conversation's working directory. */
    val supportsWorkingDirectory: Boolean get() = gateway is DesktopWorkingDirectoryController

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

    // The conversation the user last SENT a prompt into ("now playing" for the
    // bottom bar): sticky across selection changes so jumping back is one click.
    private val _lastPromptedConversationId = MutableStateFlow<String?>(null)
    val lastPromptedConversationId: StateFlow<String?> = _lastPromptedConversationId.asStateFlow()

    // Same stale-guard rationale as thinkingGeneration.
    private var streamingGeneration = 0

    private val interruptCoordinator = DesktopChatInterruptCoordinator(
        scope = scope,
        onForcedLocalStop = ::forceLocalStopClear,
    )

    /**
     * letta-mobile-lgns8.19: conversation whose turn has an abort in flight.
     * Set when the user presses stop and cleared ONLY when the turn's stream
     * actually ends (the send job's finally, driven by the server's terminal
     * frame) — never optimistically. While set, the composer refuses new sends,
     * so a message can't be interleaved into a turn that is still running.
     */
    val cancellingConversationId: StateFlow<String?> = interruptCoordinator.cancellingConversationId

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
     */
    private val _replyPresence = MutableStateFlow(ChatStreamingPresence(isStreaming = false, isAgentTyping = false))
    val replyPresence: StateFlow<ChatStreamingPresence> = _replyPresence.asStateFlow()

    private val presenceJob: Job = scope.launch {
        combine(
            _boundPresenceFacts,
            _streamingConversationId,
            state.map { it.selectedConversationId },
        ) { facts, streamingConversationId, selectedConversationId ->
            val factsForSelected = facts.conversationId != null && facts.conversationId == selectedConversationId
            ChatStreamingPresencePolicy.derive(
                inputs = ChatStreamInputs(
                    previousIsStreaming = false,
                    previousIsAgentTyping = false,
                    anyServerLocalPending = factsForSelected && facts.anyServerLocalPending,
                    tailIsAssistant = factsForSelected && facts.tailIsAssistant,
                    replyStreaming = streamingConversationId != null && streamingConversationId == selectedConversationId,
                    clientModeStreamInFlight = false,
                    a2uiThinkingActive = false,
                    duplicateInitialMessageInFlight = false,
                ),
            )
        }.collect { _replyPresence.value = it }
    }

    private var gateway: DesktopChatGateway? = null

    private val modelCatalogHelper = DesktopChatModelCatalogHelper(
        scope = scope,
        agentByIdProvider = agentByIdProvider,
        onModelsLoaded = { models ->
            if (!closed) _availableModels.value = models
        },
        getSelectedConversationAgentId = { _state.value.selectedConversation?.agentId },
    )

    private val connectionWatcher = DesktopChatConnectionWatcher(
        scope = scope,
        onConnected = {
            runCatching {
                reloadConversationsAndSelect(
                    preferConversationId = _state.value.runtimeState.selectedConversationId,
                )
            }
        },
        onDisconnected = { transportState ->
            if (transportState.isAuthFailure) {
                _state.update { current ->
                    current.withRuntimeState(
                        ChatSessionReducer.conversationLoadFailed(
                            state = current.runtimeState,
                            errorMessage = transportState.reason.ifBlank { "Authentication failed" },
                        ),
                    )
                }
            } else {
                _state.update { current ->
                    current.withRuntimeState(
                        ChatSessionReducer.streamDisconnected(
                            state = current.runtimeState,
                            generation = current.runtimeState.selectionGeneration,
                            errorMessage = transportState.reason.ifBlank { "Connection lost" },
                            statusMessage = if (transportState.willReconnect) "Reconnecting…" else "Stream disconnected",
                        ),
                    )
                }
            }
        },
        onEscalateRetryConnection = { retryConnection() },
    )

    private val remoteSender = DesktopChatRemoteSender(
        onSendSuccess = {
            _state.update {
                it.withRuntimeState(ChatSessionReducer.sendSucceeded(it.runtimeState))
            }
        },
        onSendFailed = { attempt, errorMessage ->
            if (!closed) {
                if (_thinkingConversationId.value == attempt.conversationId) {
                    _thinkingConversationId.value = null
                }
                _state.update {
                    it.withRuntimeState(
                        ChatSessionReducer.sendFailed(
                            state = it.runtimeState,
                            text = attempt.text,
                            attachments = attempt.attachments,
                            errorMessage = errorMessage,
                        ),
                    )
                }
            }
        },
        persistConversationTitle = ::persistConversationTitle,
        onAttemptCompleted = { attempt ->
            if (attempt.streamGen == streamingGeneration &&
                _streamingConversationId.value == attempt.conversationId
            ) {
                _streamingConversationId.value = null
            }
            if (cancellingConversationId.value == attempt.conversationId) {
                interruptCoordinator.clearCancelling()
                if (_thinkingConversationId.value == attempt.conversationId) {
                    _thinkingConversationId.value = null
                }
                attempt.conversationId?.let(interruptCoordinator::recordTerminalAfterCancel)
            }
        },
    )

    private fun bindGateway(next: DesktopChatGateway?) {
        if (gateway !== next) {
            modelCatalogHelper.reset()
        }
        gateway = next
        approvalCoordinator.bindGateway(next)
        connectionWatcher.start(next)
    }

    // Per-conversation model overrides set this session (the picker). The
    // effective composer model otherwise comes from the conversation's agent.
    private var conversationModelById: Map<String, String> = emptyMap()

    private val gatewayExtras: ChatGatewayExtras?
        get() = gateway as? ChatGatewayExtras
    private val conversationSummaryGateway: ConversationSummaryGateway?
        get() = gateway as? ConversationSummaryGateway
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
        bindGateway(null)
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
        connectionWatcher.stop()
        loadJob?.cancel()
        selectJob?.cancel()
        sendJob?.cancel()
        createConversationJob?.cancel()
        timelineJob?.cancel()
        activeLoop?.close()
        activeLoop = null
        (gateway as? AutoCloseable)?.close()
        bindGateway(null)
    }

    /**
     * Selects [conversationId]. Returns the remote-selection [Job] when a
     * remote load (and timeline-loop rebinding) was kicked off, so callers
     * that must not race the loop swap — e.g. notification replies — can
     * await it; null when the selection was a no-op or local-only.
     */
    fun selectConversation(conversationId: String): Job? {
        if (closed) return null
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
        if (!shouldLoadRemote) return null
        selectJob?.cancel()
        return scope.launch {
            selectRemoteConversation(conversationId, generation ?: return@launch)
        }.also { selectJob = it }
    }

    fun deleteConversation(conversationId: String) {
        if (closed) return
        if (conversationId == unsentConversationId) unsentConversationId = null
        if (conversationId in _deletingConversationIds.value) return
        scope.launch {
            val nextGateway = gateway ?: return@launch
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
    fun createConversationForAgent(agentId: String, onCreated: (String) -> Unit = {}) {
        if (closed) return
        if (agentId.isBlank()) return
        createConversationJob?.cancel()
        createConversationJob = scope.launch {
            try {
                val priorUnsent = unsentConversationId
                unsentConversationId = null
                if (priorUnsent != null) {
                    runCatching { gateway?.deleteConversation(priorUnsent) }
                }
                val created = gatewayExtras?.createConversation(agentId) ?: return@launch
                unsentConversationId = created.id.value
                reloadConversationsAndSelect(preferConversationId = created.id.value)
                onCreated(created.id.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.update {
                    it.copy(errorMessage = t.message ?: t::class.simpleName ?: "Could not create chat")
                }
            }
        }
    }

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
     * Create a new agent from pre-resolved model defaults, open a conversation
     * for it, and select it. [onCreated] reports the new agent id so the UI can
     * refresh.
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
                val catalogModel = model?.let {
                    modelCatalogHelper.requireCatalogModel(gw, _availableModels.value, it)
                }
                val agent = gw.createAgent(
                    AgentCreateParams(
                        name = agentName,
                        model = catalogModel?.selectionValue,
                        embedding = embedding,
                        includeBaseTools = true,
                        memoryBlocks = persistentListOf(
                            BlockCreateParams(label = "human", value = "The user has not shared details yet."),
                            BlockCreateParams(label = "persona", value = "I am $agentName, a helpful assistant."),
                        ),
                    ).withCatalogModelRouting(catalogModel?.models.orEmpty()),
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
        val transportModel = ModelCatalog.transportValue(_availableModels.value, model).orEmpty()
        scope.launch {
            runCatching { gatewayExtras?.setConversationModel(conversationId, transportModel) }
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
        scope.launch {
            runCatching { gatewayExtras?.setConversationArchived(conversationId, archived) }
        }
    }

    private fun applyComposerModelLabel(conversationId: String, agentId: String?) {
        scope.launch {
            val override = conversationModelById[conversationId]
            val label = when {
                !override.isNullOrBlank() -> override
                !agentId.isNullOrBlank() ->
                    runCatching { agentByIdProvider(setOf(agentId)) }
                        .getOrNull()?.get(agentId)?.model?.takeIf { it.isNotBlank() } ?: "Auto"
                else -> "Auto"
            }
            if (!closed && _state.value.selectedConversationId == conversationId) {
                _state.update { it.copy(composerModelLabel = label) }
            }
        }
    }

    /**
     * Answer or dismiss a parked approval (e.g. AskUserQuestion) surfaced in the
     * selected conversation.
     */
    fun submitApproval(
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
    ) {
        if (closed) return
        approvalCoordinator.submitApproval(
            ApprovalSubmissionRequest(
                gateway = gateway,
                conversation = _state.value.selectedConversation,
                requestId = requestId,
                toolCallIds = toolCallIds,
                approve = approve,
                reason = reason,
            ),
        )
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

    /**
     * Inline reply from a notification toast: select the target conversation
     * and await the selection's remote load before sending.
     */
    fun replyFromNotification(conversationId: String, text: String) {
        if (closed) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val selection = selectConversation(conversationId)
            withTimeoutOrNull(NOTIFICATION_REPLY_SETTLE_TIMEOUT_MS) { selection?.join() }
            updateComposerText(trimmed)
            send()
        }
    }

    /**
     * Refreshes [selectedConversationWorkingDirectory] from the runtime for
     * the currently selected conversation.
     */
    fun refreshSelectedConversationWorkingDirectory() {
        if (closed) return
        val controller = gateway as? DesktopWorkingDirectoryController
        val conversation = state.value.selectedConversation
        val agentId = conversation?.agentId
        if (controller == null || agentId == null) {
            _selectedConversationWorkingDirectory.value = null
            return
        }
        val conversationId = conversation.id
        scope.launch {
            _workingDirectoryLoading.value = true
            _selectedConversationWorkingDirectory.value =
                runCatching { controller.currentWorkingDirectory(agentId, conversationId) }.getOrNull()
            _workingDirectoryLoading.value = false
        }
    }

    /**
     * Changes the SELECTED conversation's working directory to [path].
     */
    fun changeSelectedConversationWorkingDirectory(path: String) {
        if (closed) return
        val controller = gateway as? DesktopWorkingDirectoryController ?: return
        val conversation = state.value.selectedConversation ?: return
        val agentId = conversation.agentId ?: return
        val conversationId = conversation.id
        scope.launch {
            _workingDirectoryLoading.value = true
            val succeeded = runCatching {
                controller.setWorkingDirectory(agentId, conversationId, path)
            }.getOrDefault(false)
            if (succeeded) {
                _selectedConversationWorkingDirectory.value = path
            }
            _workingDirectoryLoading.value = false
        }
    }

    fun stopActiveRun(conversationId: String) {
        if (closed) return
        interruptCoordinator.stopActiveRun(
            conversationId = conversationId,
            gateway = gateway,
            streamingConversationId = _streamingConversationId.value,
            thinkingConversationId = _thinkingConversationId.value,
        )
    }

    /**
     * Local escape hatch: drop the streaming UI without a terminal frame.
     */
    private fun forceLocalStopClear(conversationId: String, reason: String) {
        sendJob?.cancel()
        _state.update {
            if (it.runtimeState.isSending) {
                it.withRuntimeState(ChatSessionReducer.sendSucceeded(it.runtimeState))
            } else {
                it
            }
        }
        interruptCoordinator.clearCancelling()
        if (_thinkingConversationId.value == conversationId) {
            _thinkingConversationId.value = null
        }
        if (_streamingConversationId.value == conversationId) {
            _streamingConversationId.value = null
        }
        Telemetry.event(
            TELEMETRY_TAG,
            "interrupt.forcedLocalClear",
            "conversationId" to conversationId,
            "reason" to reason,
            level = Telemetry.Level.WARN,
        )
    }

    fun send() {
        if (closed) return
        cancellingConversationId.value?.let { cancelling ->
            if (cancelling == _state.value.selectedConversationId) {
                showComposerError(STOPPING_SEND_BLOCKED_MESSAGE)
                return
            }
        }
        val draft = ChatComposerPolicy.beginSend(_state.value.composer) ?: return
        _state.value.selectedConversationId?.let { _lastPromptedConversationId.value = it }
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
        launchRemoteSend(loop, draft)
    }

    private fun launchRemoteSend(loop: DesktopTimelineLoop, draft: ChatComposerSendDraft) {
        val text = draft.text
        val attachments = draft.attachments
        val sendingConversationId = _state.value.selectedConversationId
        val titleToPersist = titleCandidateForSend(sendingConversationId, text)
        clearUnsentIfMatching(sendingConversationId)
        _state.update {
            it.withRuntimeState(ChatSessionReducer.beginSend(it.runtimeState, draft))
        }
        beginThinking(sendingConversationId)
        _streamingConversationId.value = sendingConversationId
        val streamGen = ++streamingGeneration
        sendJob?.cancel()
        sendJob = scope.launch {
            remoteSender.runRemoteSendAttempt(
                RemoteSendAttempt(
                    loop = loop,
                    text = text,
                    attachments = attachments,
                    conversationId = sendingConversationId,
                    titleToPersist = titleToPersist,
                    streamGen = streamGen,
                ),
            )
        }
    }

    private fun titleCandidateForSend(conversationId: String?, text: String): String? {
        if (conversationId == null) return null
        return _state.value.conversations
            .firstOrNull { it.id == conversationId }
            ?.persistedTitleCandidate(text)
    }

    private fun clearUnsentIfMatching(conversationId: String?) {
        if (conversationId != null && conversationId == unsentConversationId) {
            unsentConversationId = null
        }
    }

    private fun persistConversationTitle(conversationId: String, candidate: String) {
        val summaryGateway = conversationSummaryGateway ?: return
        val conversation = _state.value.conversations.firstOrNull { it.id == conversationId } ?: return
        val originalTitle = conversation.title
        _state.update { current ->
            current.withRuntimeState(
                current.runtimeState.copy(
                    conversations = current.conversations.map { item ->
                        if (item.id == conversationId) item.copy(title = candidate) else item
                    },
                ),
            )
        }
        scope.launch {
            val update = ConversationSummaryUpdate(ConversationId(conversationId), ConversationSummary(candidate))
            runCatching { summaryGateway.setConversationSummary(update) }
                .onFailure {
                    if (closed) return@onFailure
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

    private fun beginThinking(conversationId: String?) {
        if (conversationId == null) return
        val generation = ++thinkingGeneration
        _thinkingConversationId.value = conversationId
        scope.launch {
            kotlinx.coroutines.delay(THINKING_TIMEOUT_MS.milliseconds)
            if (generation == thinkingGeneration && _thinkingConversationId.value == conversationId) {
                _thinkingConversationId.value = null
            }
        }
    }

    private fun isConfigNeededForUrl(): Boolean =
        bootstrapState.config.mode != LettaConfig.Mode.LOCAL && bootstrapState.config.serverUrl.isBlank()

    private suspend fun connectAndLoad() {
        if (closed) return
        if (isConfigNeededForUrl()) {
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
            bindGateway(nextGateway)

            gatewayExtras?.let(modelCatalogHelper::startModelCatalogLoad)

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

    private suspend fun reloadConversationsAndSelect(preferConversationId: String?) {
        val nextGateway = gateway ?: return
        val conversations = nextGateway.listConversations(archiveStatus = ConversationArchiveFilter.All.apiValue)
        val agentIds = conversations.map { it.agentId.value }.filter { it.isNotBlank() }.toSet()
        val agentNamesById = runCatching { agentNamesByIdProvider(agentIds) }.getOrDefault(emptyMap())
        val summaries = conversations.toChatConversationSummaries(agentNamesById)
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

        applyComposerModelLabel(conversationId, conversation.agentId)

        timelineJob?.cancel()
        activeLoop?.close()
        timelineProjector.reset()
        _boundPresenceFacts.value = BoundPresenceFacts()

        _state.update {
            it.withRuntimeState(ChatSessionReducer.beginSelectedConversationHydrate(it.runtimeState, generation))
        }

        val selectionStart = System.currentTimeMillis()
        val loop = loopFactory(nextGateway, conversation, scope)
        activeLoop = loop
        val snapshotEventCount = loop.state.value.events.size
        val selectionToSnapshotMs = System.currentTimeMillis() - selectionStart
        Telemetry.event(
            "ChatPerformance", "selection_to_snapshot",
            "conversationId" to conversationId,
            "durationMs" to selectionToSnapshotMs,
            "eventCount" to snapshotEventCount,
            "hasSnapshot" to (snapshotEventCount > 0),
        )

        timelineJob = scope.launch {
            loop.state.collect { timeline ->
                updateTimelineMessages(conversationId, generation, timeline)
            }
        }

        val refreshStart = System.currentTimeMillis()
        try {
            loop.hydrate(
                DesktopTimelineHydrateRequest(
                    limit = TimelinePageLimit(50),
                    recordConversationCursor = true,
                ),
            )
            val refreshDurationMs = System.currentTimeMillis() - refreshStart
            Telemetry.event(
                "ChatPerformance", "remote_refresh",
                "conversationId" to conversationId,
                "durationMs" to refreshDurationMs,
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
        val projection = timelineProjector.project(
            timeline = timeline,
            prefix = timelineProjector.olderPrefixFor(conversationId),
            previousState = ChatUiState(),
            isActiveRunStreaming = _streamingConversationId.value == conversationId,
            ownAgentId = _state.value.conversations.firstOrNull { it.id == conversationId }?.agentId,
        )
        _boundPresenceFacts.value = BoundPresenceFacts(
            conversationId = conversationId,
            tailIsAssistant = projection.tailIsAssistant,
            anyServerLocalPending = projection.anyLettaServerLocalPending,
        )
        if (projection.noChange) return
        val messages = projection.ui
        approvalCoordinator.reconcileSubmittedApprovals(conversationId, messages)
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

/** How long a notification reply waits for the conversation switch to settle. */
private const val NOTIFICATION_REPLY_SETTLE_TIMEOUT_MS = 5_000L

private const val TELEMETRY_TAG = "DesktopChat"

/** letta-mobile-lgns8.19: shown when a send is attempted while a stop is pending. */
internal const val STOPPING_SEND_BLOCKED_MESSAGE =
    "Stopping the current run — wait for it to finish before sending another message."

/** Conversation-list scope filter, mapped to the `archive_status` query param. */
enum class ConversationArchiveFilter(val apiValue: String, val label: String) {
    Active("active", "Active"),
    Archived("archived", "Archived"),
    All("all", "All"),
}
