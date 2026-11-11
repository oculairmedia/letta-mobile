package com.letta.mobile.desktop.chat

import com.letta.mobile.util.attr
import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.conversations.ConversationListHost
import com.letta.mobile.data.chat.conversations.ConversationManagement
import com.letta.mobile.data.chat.conversations.ConversationManagementPrefs
import com.letta.mobile.data.chat.conversations.ConversationOrigin
import com.letta.mobile.data.chat.conversations.OpenedBranch
import com.letta.mobile.data.chat.runtime.ChatComposerPolicy
import com.letta.mobile.data.chat.runtime.ChatComposerSendDraft
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ChatConnectionState
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.ChatStreamingPresence
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.persistedTitleCandidate
import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.CurrentFocusRequest
import com.letta.mobile.data.shell.FocusAvailability
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.conversationChanges
import com.letta.mobile.data.repository.observeConversationInvalidations
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.withCatalogModelRouting
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.desktop.DesktopBootstrapState
import com.letta.mobile.ui.chat.render.ChatPresenceSignals
import com.letta.mobile.ui.chat.render.ChatTimelinePresenter
import com.letta.mobile.ui.chat.render.TimelineProjection
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.data.chat.send.QueueConversationId
import kotlinx.coroutines.flow.filter
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.ChatSendQueueSource
import com.letta.mobile.ui.chat.session.ChatSendQueueTarget
import com.letta.mobile.ui.chat.session.CoordinatorSendQueue
import com.letta.mobile.ui.chat.session.DerivedStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import com.letta.mobile.ui.chat.session.ChatWorkingDirectoryState
import com.letta.mobile.ui.chat.session.ConversationModelBackend
import com.letta.mobile.ui.chat.session.ConversationModelFocus
import com.letta.mobile.ui.chat.session.ConversationModelHost
import com.letta.mobile.ui.chat.session.ConversationModelUpdate
import com.letta.mobile.ui.chat.session.ConversationModels
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import com.letta.mobile.data.chat.runtime.agentConversationReader
import com.letta.mobile.util.runCatchingCancellable
import com.letta.mobile.util.ReadThroughConversationFleets
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.letta.mobile.data.presence.ActiveTurnCoordinator
import com.letta.mobile.data.presence.ActiveTurnIdentity
import com.letta.mobile.data.presence.ActiveTurnPhase
import com.letta.mobile.data.presence.ActiveTurnTerminalKind
import com.letta.mobile.data.presence.ConversationRunRegistry
import com.letta.mobile.data.presence.ConversationRunState
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class DesktopChatController(
    private val bootstrapState: DesktopBootstrapState,
    private val scope: CoroutineScope,
    /** Read by the shared page's port, so its picker encodes to the limits this controller enforces. */
    internal val attachmentLimits: AttachmentLimits = AttachmentLimits.Default,
    private val gatewayFactory: suspend () -> DesktopChatGateway = {
        createDefaultDesktopChatGateway(bootstrapState.config)
    },
    /** letta-mobile-fyo4s: the agents whose conversations the list reads (agent by agent) and whose names label them. */
    private val rosterProvider: suspend () -> List<Agent>,
    private val agentByIdProvider: suspend (agentIds: Set<String>) -> Map<String, Agent> = { emptyMap() },
    // The backend doesn't yet persist a conversation's archived flag, so we keep a
    // local, durable record of archived ids and overlay it on every load. Still
    // PATCHes the server so this lights up automatically once the backend lands.
    loadArchivedConversationIds: () -> Set<String> = { emptySet() },
    private val persistArchivedConversationIds: (Set<String>) -> Unit = {},
    /** Window-scoped run state; every conversation this window drives publishes its phase here. */
    private val runRegistry: ConversationRunRegistry = ConversationRunRegistry(),
    private val loopFactory: suspend (
        gateway: DesktopChatGateway,
        conversation: DesktopConversationSummary,
        scope: CoroutineScope,
    ) -> DesktopTimelineLoop = { gateway, conversation, loopScope ->
        RealDesktopTimelineLoop(gateway, conversation, loopScope)
    },
    /**
     * letta-mobile-lks7m: the transport carrying Meridian's `conversation_updated` pushes. When set,
     * a conversation another client creates or changes lands in the list without a restart. Null
     * (HTTP / demo backends) keeps the list to startup, reconnect and this window's own writes.
     */
    private val conversationChanges: IChannelTransport? = null,
    /** letta-mobile-bzvro.17 / .18: pinned conversations and recently used models. */
    private val conversationPrefs: DesktopConversationPrefs = DesktopConversationPrefs(),
) {
    private val initialState = initialLiveDesktopChatSurfaceState(bootstrapState)
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<DesktopChatSurfaceState> = _state.asStateFlow()

    /** Active / Archived / All filter for the conversation list (re-fetches). */
    private val _archiveFilter = MutableStateFlow(ConversationArchiveFilter.Active)
    val archiveFilter: StateFlow<ConversationArchiveFilter> = _archiveFilter.asStateFlow()

    /** Unsent new chats are removed when abandoned; sending keeps them. */
    private var unsentConversationId: String? = null

    /** Locally-tracked archived conversation ids (durable; see constructor note). */
    private var locallyArchivedIds: Set<String> = loadArchivedConversationIds()

    private val approvalCoordinator = DesktopChatApprovalCoordinator(
        scope = scope,
        onError = ::showPageError,
    )

    /** Approval state and gateway support exposed to the session port. */
    val approvals: DesktopChatApprovalState = approvalCoordinator

    /** The selected conversation's shared working directory, or null when the gateway cannot report it. */
    val workingDirectory = ChatWorkingDirectoryState(scope) {
        gateway.takeUnless { closed }.workingDirectoryTarget(_state.value.selectedConversation)
    }

    /** The active gateway, for capabilities a port reads off it (the permission mode). */
    internal val activeGateway: DesktopChatGateway? get() = gateway

    /** Conversations whose delete is in flight — the sidebar shows a spinner. */
    private val _deletingConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingConversationIds: StateFlow<Set<String>> = _deletingConversationIds.asStateFlow()

    /** The latest reversible delete, cleared when unavailable or the backend changes. */
    val deletionUndo = DesktopDeletionUndo()

    private val _deleteBehavior = MutableStateFlow(ConversationDeleteBehavior.Permanent)

    /** Active delete behavior (Iroh archives); confirmation wording follows reconnects. */
    val deleteBehavior: StateFlow<ConversationDeleteBehavior> = _deleteBehavior.asStateFlow()

    /** The conversation awaiting a streamed reply; cleared on reply, failure, or timeout. */
    private val _thinkingConversationId = MutableStateFlow<String?>(null)

    private var thinkingGeneration = 0

    /** The reply's conversation until the complete stream ends, unlike the shorter thinking phase. */
    private val activeTurns = DesktopActiveTurnBinding(
        scope, runRegistry, _thinkingConversationId,
    ) { forceLocalStopClear(it, "Local clear") }
    val thinkingConversationId get() = activeTurns.thinkingConversationId

    internal fun bindActiveTurns(sessions: StateFlow<com.letta.mobile.data.presence.ActiveTurnSession?>) =
        activeTurns.bindSessions(sessions)
    internal val activeTurnStates get() = activeTurns.states
    val streamingConversationId: StateFlow<String?> get() = activeTurns.streamingConversationId

    internal fun activeTurnCoordinator(conversationId: String?): ActiveTurnCoordinator? {
        val conversation = state.value.conversations.firstOrNull { it.id == conversationId } ?: return null
        return activeTurns.owner(conversation)?.also(runPhases::bindActiveTurn)
    }

    private val _lastPromptedConversationId = MutableStateFlow<String?>(null)
    val lastPromptedConversationId: StateFlow<String?> = _lastPromptedConversationId.asStateFlow()

    /** Cancellation is projected from the same owner as streaming and header activity. */
    val cancellingConversationId: StateFlow<String?> get() = activeTurns.cancellingConversationId

    /** The shared timeline presenter is stateful per conversation and resets on rebind. */
    private val timelinePresenter = ChatTimelinePresenter()

    /** Latest bound-conversation projection for re-deriving presence; reset on rebind. */
    private data class BoundProjection(
        val conversationId: String? = null,
        val projection: TimelineProjection? = null,
    )

    private val _boundProjection = MutableStateFlow(BoundProjection())

    /** Shared streaming presence derived from the bound projection and active reply. */
    private val _replyPresence = MutableStateFlow(ChatStreamingPresence(isStreaming = false, isAgentTyping = false))
    val replyPresence: StateFlow<ChatStreamingPresence> = _replyPresence.asStateFlow()

    private val presenceJob: Job = scope.launch {
        combine(
            _boundProjection,
            activeTurnStates,
            state.map { it.selectedConversationId },
        ) { bound, turns, selectedConversationId ->
            derivePresence(
                projection = bound.projection?.takeIf { bound.conversationId != null && bound.conversationId == selectedConversationId },
                turnInFlight = turns[selectedConversationId]?.phase?.let { it != ActiveTurnPhase.Idle } == true,
            )
        }
            // Tokens arriving for the selected conversation are the one phase this controller
            // observes first-hand; everything richer comes from the gateway's runtime events.
            .onEach { if (it.isStreaming && !it.isAgentTyping) runPhases.publishTokens(_state.value.selectedConversationId) }
            .collect { _replyPresence.value = it }
    }


    /** Per-conversation run phases, reduced from runtime events for shell presence. */
    val runs: StateFlow<Map<String, ConversationRunState>> = runRegistry.runs

    // `by lazy` so the presence collector above (which publishes into it) can never observe it
    // before construction reaches this line.
    private val runPhases: DesktopChatRunPhases by lazy {
        DesktopChatRunPhases(scope, state, runRegistry, closed = { closed }, onRuntimeEvent = { event ->
        if (state.value.conversations.any { it.id == event.conversationId && it.agentId == event.agentId }) {
            runPhases.bindActiveTurn(activeTurns.owner(ConversationId(event.conversationId), AgentId(event.agentId)))
            activeTurns.onEvent(event)
        }
    }) }

    /** Publish active subagent and background-dream phases through the shared publisher. */
    internal fun publishSubagents(entries: List<com.letta.mobile.data.model.SubagentEntry>) = runPhases.publishSubagents(entries)

    /** Timeline details refine animation only while the shared owner says a turn is active. */
    private fun derivePresence(projection: TimelineProjection?, turnInFlight: Boolean): ChatStreamingPresence {
        if (!turnInFlight) return ChatStreamingPresence(false, false)
        if (projection == null) return ChatStreamingPresence(true, true)
        val presentation = timelinePresenter.present(
            projection = projection,
            signals = ChatPresenceSignals(
                replyStreaming = turnInFlight,
                a2uiThinkingActive = false,
                duplicateInitialMessageInFlight = false,
                turnInFlight = turnInFlight,
            ),
        )
        return ChatStreamingPresence(presentation.isStreaming, presentation.isAgentTyping)
    }

    private var gateway: DesktopChatGateway? = null

    /** Model catalog, conversation picks, and reasoning efforts for the chat port. */
    val models = ConversationModels(scope, ModelHost(), agentByIdProvider)

    private inner class ModelHost : ConversationModelHost {
        override val backend: ConversationModelBackend? get() = ConversationModelBackend.of(gateway)
        override val isClosed: Boolean get() = closed
        override val focus: StateFlow<ConversationModelFocus> = DerivedStateFlow(state.map(::modelFocusOf)) { modelFocusOf(state.value) }

        override fun showModel(update: ConversationModelUpdate) = _state.update { it.withModelUpdate(update) }

        override suspend fun recordModel(model: String) = conversationMarks.recordModel(model)
    }

    private val connectionWatcher = DesktopChatConnectionWatcher(
        scope = scope,
        onConnected = ::onTransportConnected,
        onDisconnected = ::onTransportDisconnected,
        onEscalateRetryConnection = ::retryConnection,
    )

    /** Reconnecting re-reads the roster, preferring whatever the user was already looking at. */
    private suspend fun onTransportConnected() {
        runCatching { reloadConversationsAndSelect(preferConversationId = null) }
    }

    /** Auth failures terminate roster loading; other disconnects may reconnect. */
    private fun onTransportDisconnected(transportState: ChannelTransportState.Disconnected) {
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
    }

    private val remoteSender = DesktopChatRemoteSender(
        onSendSuccess = ::onRemoteSendSucceeded,
        onSendFailed = ::onRemoteSendFailed,
        persistConversationTitle = ::persistConversationTitle,
        onAttemptCompleted = ::onRemoteSendAttemptCompleted,
    )

    private fun onRemoteSendSucceeded(attempt: RemoteSendAttempt) =
        _state.update { it.withRuntimeState(ChatSessionReducer.sendSucceeded(it.runtimeState)) }

    /** The draft comes back with the failure, so a failed send never costs the user what they typed. */
    private fun onRemoteSendFailed(attempt: RemoteSendAttempt, errorMessage: String) {
        if (closed) return
        _thinkingConversationId.compareAndSet(attempt.conversationId, null)
        runPhases.publish(attempt.conversationId, RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Failed, errorMessage))
        _state.update {
            val failed = ChatSessionReducer.sendFailed(it.runtimeState, attempt.text, attempt.attachments, errorMessage)
            it.withRuntimeState(failed)
        }
    }

    /**
     * The end of an attempt, however it ended. The generation check stops a superseded attempt from
     * clearing the indicator of the one that replaced it, and a cancel is only finished once the
     * turn's own terminal frame arrives, never optimistically when stop was pressed.
     */
    private fun onRemoteSendAttemptCompleted(attempt: RemoteSendAttempt, outcome: RemoteSendOutcome) {
        if (outcome != RemoteSendOutcome.Cancelled) attempt.conversationId?.let { id ->
            activeTurns.terminate(ConversationId(id), attempt.turnIdentity(), when (outcome) {
                RemoteSendOutcome.Completed -> ActiveTurnTerminalKind.Finished
                RemoteSendOutcome.Failed -> ActiveTurnTerminalKind.Failed
                RemoteSendOutcome.Cancelled -> error("Cancellation is not terminal evidence")
            })
        }
        // A failed attempt already published its FAILED terminal and a cancelled one publishes
        // CANCELLED when the turn's own terminal lands. Only a run that finished is COMPLETED.
        if (outcome == RemoteSendOutcome.Completed) {
            runPhases.publish(attempt.conversationId, RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed))
        }
        val conversationId = attempt.conversationId ?: return
        if (outcome == RemoteSendOutcome.Failed) _thinkingConversationId.compareAndSet(conversationId, null)
    }

    private fun RemoteSendAttempt.turnIdentity() = ActiveTurnIdentity(localMessageId.value, "runtime:${localMessageId.value}")


    private fun bindGateway(next: DesktopChatGateway?) {
        if (gateway !== next) {
            _deletingConversationIds.value = emptySet()
            pendingDeletes.clear()
            unsentConversationId = null
            models.catalogLoader.reset()
            deletionUndo.backendChanged()
        }
        gateway = next
        _deleteBehavior.value = next?.deleteBehavior ?: ConversationDeleteBehavior.Permanent
        runPhases.bind(next)
        approvalCoordinator.bindGateway(next)
        workingDirectory.bindSupport(next is DesktopWorkingDirectoryController)
        connectionWatcher.start(next)
    }

    private var selectionStillCurrent: () -> Boolean = { true }
    internal var ownerAllowsSending: () -> Boolean = { true }
    internal var workspaceOwner: ShellWorkspaceState? = null
    private var activeLoop: DesktopTimelineLoop? = null
    private var timelineJob: Job? = null
    private var loadJob: Job? = null
    private var selectJob: Job? = null
    private var sendJob: Job? = null
    private var createConversationJob: Job? = null
    private var conversationPushJob: Job? = null

    private var started = false
    private var closed = false

    private fun closeActiveLoopAsync() {
        val loop = activeLoop ?: return
        activeLoop = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) { loop.closeAndAwait() }
    }

    private fun resetTimelineBinding() {
        timelineJob?.cancel()
        closeActiveLoopAsync()
        timelinePresenter.reset()
        _boundProjection.value = BoundProjection()
    }

    fun start() {
        if (started || closed) return
        started = true
        loadJob = scope.launch { connectAndLoad() }
        conversationPushJob = conversationChanges?.let { transport ->
            scope.launch {
                observeConversationInvalidations(transport.conversationChanges()) { dirty ->
                    runCatchingCancellable { refreshConversationRoster(dirty) }
                        .onFailure { Telemetry.error(TELEMETRY_TAG, "roster.refreshFailed", it) }
                }
            }
        }
    }

    fun retryConnection() {
        if (closed) return
        loadJob?.cancel()
        selectJob?.cancel()
        sendJob?.cancel()
        timelineJob?.cancel()
        closeActiveLoopAsync()
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

    /** Retry a disconnected transport immediately when the machine wakes. */
    fun onSystemResumed() {
        if (closed) return
        connectionWatcher.onSystemResumed()
    }

    fun close() {
        if (closed) return
        closed = true
        presenceJob.cancel()
        conversationPushJob?.cancel()
        runPhases.close()
        activeTurns.close()
        connectionWatcher.stop()
        loadJob?.cancel()
        selectJob?.cancel()
        sendJob?.cancel()
        createConversationJob?.cancel()
        timelineJob?.cancel()
        closeActiveLoopAsync()
        (gateway as? AutoCloseable)?.close()
        bindGateway(null)
    }

    /** Select a known chat or resolve an explicit unlisted target. */
    fun selectConversation(conversationId: String, allowUnlisted: Boolean = false, stillCurrent: () -> Boolean = { true }): Job? {
        if (closed || !stillCurrent()) return null
        val rebind = !selectionStillCurrent()
        cleanupUnsentConversation(except = conversationId)
        if (allowUnlisted && _state.value.conversations.none { it.id == conversationId }) {
            val id = ConversationId(conversationId)
            resetSelectionSurface(id, stillCurrent)
            return resolveUnlistedConversation(id, stillCurrent)
        }
        var generation: Long? = null
        var shouldLoadRemote = false
        _state.update { current ->
            val currentRuntime = current.runtimeState
            val candidate = if (rebind && currentRuntime.selectedConversationId == conversationId &&
                currentRuntime.conversations.any { it.id == conversationId }) {
                currentRuntime.copy(selectedConversationId = null)
            } else currentRuntime
            val next = ChatSessionReducer.selectConversation(
                state = candidate,
                conversationId = conversationId,
                remoteBacked = current.isRemoteBacked,
            )
            val rebound = if (candidate !== currentRuntime && next.selectedConversationId == conversationId) {
                next.copy(composer = currentRuntime.composer)
            } else next
            shouldLoadRemote = current.isRemoteBacked && rebound != currentRuntime
            generation = if (shouldLoadRemote) rebound.selectionGeneration else null
            current.withRuntimeState(rebound)
        }
        if (!shouldLoadRemote) {
            if (_state.value.selectedConversationId == conversationId) selectionStillCurrent = stillCurrent
            return null
        }
        selectionStillCurrent = stillCurrent
        selectJob?.cancel()
        return scope.launch {
            selectRemoteConversation(conversationId, generation ?: return@launch)
        }.also { selectJob = it }
    }

    /** Resolve an exact unlisted target only while its captured owner and gateway remain current. */
    private fun resolveUnlistedConversation(id: ConversationId, stillCurrent: () -> Boolean): Job? {
        if (id.value == com.letta.mobile.data.model.IdFormats.DEFAULT_CONVERSATION_ID || !stillCurrent()) return null
        val source = gateway ?: return null
        val owner = workspaceOwner
        val token = owner?.requestToken()
        selectJob?.cancel()
        return scope.launch(start = CoroutineStart.LAZY) {
            if (!lookupStillCurrent(source, owner, stillCurrent)) return@launch
            val resolved = runCatchingCancellable { source.getConversation(id.value) }.getOrElse { error ->
                if (lookupStillCurrent(source, owner, stillCurrent)) reportLookupFailure(owner, token, error, stillCurrent)
                return@launch
            }
            finishUnlistedLookup(id, source, owner, token, stillCurrent, resolved)
        }.also { job ->
            selectJob = job
            job.start()
        }
    }

    private fun lookupStillCurrent(source: DesktopChatGateway, owner: ShellWorkspaceState?, check: () -> Boolean): Boolean =
        !closed && gateway === source && workspaceOwner === owner && check()

    private fun reportLookupFailure(
        owner: ShellWorkspaceState?, token: CurrentFocusRequest?, error: Throwable, stillCurrent: () -> Boolean,
    ) {
        val missing = error is com.letta.mobile.data.timeline.TimelineTransportHttpException &&
            error.code == io.ktor.http.HttpStatusCode.NotFound.value
        if (missing && owner != null && (token == null ||
            !owner.reportAvailability(token, conversation = FocusAvailability.Unavailable))) return
        if (stillCurrent()) showError(
            if (missing) "This conversation is unavailable." else "Could not load this conversation. Try again.",
        )
    }

    private suspend fun finishUnlistedLookup(
        id: ConversationId, source: DesktopChatGateway, owner: ShellWorkspaceState?, token: CurrentFocusRequest?,
        stillCurrent: () -> Boolean, resolved: com.letta.mobile.data.model.Conversation,
    ) {
        if (!lookupStillCurrent(source, owner, stillCurrent) || resolved.id != id || resolved.agentId.value.isBlank()) return
        if (owner != null && (token == null || !owner.completeFocus(token, id, resolved.agentId))) return
        val rebound = owner?.requestToken()
        val current = { lookupStillCurrent(source, owner) {
            if (owner != null && rebound != null) owner.accepts(rebound) else stillCurrent()
        } }
        if (!current()) return
        val row = listOf(resolved).toChatConversationSummaries().single()
        val updated = _state.updateAndGet { state ->
            if (!current() || state.selectedConversationId != id.value) state else state.copy(
                conversations = listOf(row) + state.conversations.filterNot { it.id == id.value },
                selectedConversationId = null,
            )
        }
        if (!current() || updated.selectedConversationId != null || updated.conversations.none { it.id == id.value }) return
        models.picks.seedFromHost(listOf(resolved))
        selectJob = null
        selectConversation(id.value, stillCurrent = current)?.join()
    }

    internal fun clearSelection(stillCurrent: () -> Boolean) = resetSelectionSurface(null, stillCurrent)

    private fun resetSelectionSurface(target: ConversationId?, stillCurrent: () -> Boolean) {
        if (closed || !stillCurrent()) return
        val rebind = !selectionStillCurrent()
        if (_state.value.selectedConversationId == target?.value && !rebind) return
        selectionStillCurrent = stillCurrent
        selectJob?.cancel()
        resetTimelineBinding()
        _canonicalPresentation.value = null
        _state.update { it.copy(selectedConversationId = target?.value, selectionGeneration = it.selectionGeneration + 1,
            isLoading = false, isSending = false, errorMessage = null, composerText = "", pendingImageAttachments = emptyList()) }
    }

    fun deleteConversation(conversationId: String) {
        if (closed) return
        if (conversationId == unsentConversationId) unsentConversationId = null
        val source = gateway ?: return
        val token = workspaceOwner?.requestToken()
        ConversationManagement(scope, ListHost(source, deletionUndo.backendGeneration, token),
            ConversationManagementPrefs(conversationPrefs.pins, conversationPrefs.lineage)).delete(ConversationId(conversationId))
    }

    /** Each delete in flight: the row it removes and the backend it started on, for the undo. */
    private val pendingDeletes = mutableMapOf<String, Pair<ChatConversationSummary?, Long>>()

    /** The backend deleted [conversationId]: offer undo and retain an unavailable open target. */
    private suspend fun onConversationDeleted(conversationId: String, behavior: ConversationDeleteBehavior) {
        if (closed) return
        val (listed, startedOn) = pendingDeletes.remove(conversationId) ?: (null to deletionUndo.backendGeneration)
        if (startedOn != deletionUndo.backendGeneration) return
        activeTurns.delete(ConversationId(conversationId), behavior)
        deletionUndo.offer(conversationId, listed, behavior, startedOn)
        if (behavior == ConversationDeleteBehavior.MovesToArchived) {
            _state.update { current ->
                current.copy(conversations = current.conversations.map { row ->
                    if (row.id == conversationId) row.copy(archived = true) else row
                })
            }
            return
        }
        var deletedGeneration: Long? = null
        _state.update { current ->
            val runtime = current.runtimeState
            val removed = ChatSessionReducer.conversationDeleted(runtime, conversationId)
            deletedGeneration = if (runtime.selectedConversationId == conversationId) runtime.selectionGeneration + 1 else null
            current.withRuntimeState(deletedGeneration?.let { generation ->
                removed.copy(
                    selectedConversationId = conversationId, selectionGeneration = generation, isLoading = false, isSending = false,
                    connectionState = if (removed.conversations.isEmpty()) ChatConnectionState.NoConversations
                    else ChatConnectionState.Live, errorMessage = "This conversation is unavailable.",
                )
            } ?: removed)
        }
        if (deletedGeneration == null || _state.value.selectionGeneration != deletedGeneration) return
        sendJob?.cancel()
        if (_thinkingConversationId.value == conversationId) _thinkingConversationId.value = null
        timelineJob?.cancel()
        closeActiveLoopAsync()
    }

    /** Restore the deleted chat and its archive state, only on the backend that deleted it. */
    fun undoDeleteConversation(offer: DesktopDeletionUndo.Offer) {
        if (closed) return
        deletionUndo.clear(offer.conversationId)
        val target = gateway?.takeIf { deletionUndo.isCurrent(offer) }
        if (target == null) {
            showError("Can't undo: the chat backend changed since it was deleted.")
            return
        }
        scope.launch { restoreAndRelist(target, offer) }
    }

    private suspend fun restoreAndRelist(target: DesktopChatGateway, offer: DesktopDeletionUndo.Offer) {
        try {
            target.restoreDeletedConversation(offer.conversationId, offer.wasArchived)
            if (!refreshConversationRoster()) putBackListedRow(offer)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            showError(t.message ?: t::class.simpleName ?: "Restore failed")
        }
    }

    /** Re-adds the row an undone delete removed, for when the roster cannot be re-read yet. */
    private fun putBackListedRow(offer: DesktopDeletionUndo.Offer) {
        _state.update { current ->
            val runtime = current.runtimeState
            val roster = DesktopDeletionUndo.rosterWithRowPutBack(runtime.conversations, offer) ?: return@update current
            current.withRuntimeState(runtime.copy(conversations = roster))
        }
    }

    private fun showError(message: String) = _state.update { it.copy(errorMessage = message) }

    /** Create for an explicit agent; the callback decides whether the result is still current. */
    fun createConversationForAgent(agentId: String, stillCurrent: () -> Boolean = { true }, onCreated: (String) -> Unit = {}) {
        if (closed || !stillCurrent()) return
        if (agentId.isBlank()) return
        val source = gateway ?: return
        val creator = source as? ChatGatewayExtras ?: return
        val generation = _state.value.selectionGeneration
        val isCurrent = { !closed && gateway === source && stillCurrent() }
        createConversationJob?.cancel()
        createConversationJob = scope.launch {
            try {
                if (!isCurrent()) return@launch
                val priorUnsent = unsentConversationId
                unsentConversationId = null
                if (priorUnsent != null) {
                    runCatchingCancellable { source.deleteConversation(priorUnsent) }
                }
                if (!isCurrent()) return@launch
                val created = creator.createConversation(agentId)
                if (!isCurrent()) return@launch
                check(created.agentId.value == agentId) { "Created conversation belongs to another agent" }
                models.picks.seedFromHost(listOf(created))
                val row = listOf(created).toChatConversationSummaries().single()
                _state.update { it.copy(conversations = listOf(row) + it.conversations.filterNot { item -> item.id == row.id }) }
                unsentConversationId = created.id.value
                onCreated(created.id.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (isCurrent() && ChatSessionReducer.isCurrentSelection(_state.value.runtimeState, generation)) {
                    _state.update { it.copy(errorMessage = t.message ?: t::class.simpleName ?: "Could not create chat") }
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
                    if (!closed && gateway === gw) {
                        _state.update { current ->
                            val before = current.runtimeState
                            val removed = ChatSessionReducer.conversationDeleted(before, pending)
                            val retained = if (before.selectedConversationId == pending) removed.copy(
                                selectedConversationId = pending,
                                selectionGeneration = before.selectionGeneration + 1,
                                isLoading = false,
                                isSending = false,
                                errorMessage = "This conversation is unavailable.",
                            ) else removed
                            current.withRuntimeState(retained)
                        }
                    }
                }
        }
    }

    /** Create an agent and starting conversation with catalog routing; the owner decides whether to open it. */
    suspend fun createAgentAndOpen(params: AgentCreateParams): com.letta.mobile.data.agenteditor.AgentCreated {
        val source = gateway.takeUnless { closed } ?: error("Not connected to a backend")
        val gw = source as? ChatGatewayExtras ?: error("Not connected to a backend")
        val backend = ConversationModelBackend.of(source) ?: error("Not connected to a backend")
        val agentName = params.name?.ifBlank { null } ?: "New agent"
        val catalogModel = params.model?.let { models.catalogLoader.requireCatalogModel(backend, models.available.value, it) }
        val agent = gw.createAgent(
            params.copy(
                name = agentName,
                model = catalogModel?.selectionValue ?: params.model,
                includeBaseTools = params.includeBaseTools ?: true,
                memoryBlocks = params.memoryBlocks ?: persistentListOf(
                    BlockCreateParams(label = "human", value = "The user has not shared details yet."),
                    BlockCreateParams(label = "persona", value = "I am $agentName, a helpful assistant."),
                ),
            ).withCatalogModelRouting(catalogModel?.models.orEmpty()),
        )
        if (closed || gateway !== source) throw CancellationException("Chat source changed")
        val conversation = gw.createConversation(agent.id.value)
        if (closed || gateway !== source) throw CancellationException("Chat source changed")
        check(conversation.agentId == agent.id) { "Created conversation belongs to another agent" }
        models.picks.seedFromHost(listOf(conversation))
        val row = listOf(conversation).toChatConversationSummaries(mapOf(agent.id.value to agent.name)).single()
        _state.update { it.copy(conversations = listOf(row) + it.conversations.filterNot { item -> item.id == row.id }) }
        return com.letta.mobile.data.agenteditor.AgentCreated(agent.id.value, conversation.id.value)
    }

    /** Switch the conversation list between Active / Archived / All (display-only). */
    fun setArchiveFilter(filter: ConversationArchiveFilter) {
        if (closed) return
        _archiveFilter.value = filter
    }

    /** Archive or restore, then re-list without changing an unrelated selection. */
    fun setConversationArchived(conversationId: String, archived: Boolean) {
        if (closed) return
        conversationManagement.setArchived(ConversationId(conversationId), archived)
    }

    /** The shared row actions on the backend: titles, pins, archive, delete, `/recompile`, fork and edit-and-resend. */
    internal val conversationManagement =
        ConversationManagement(scope, ListHost(), ConversationManagementPrefs(conversationPrefs.pins, conversationPrefs.lineage))

    /** letta-mobile-zxk2n.5: settled chats, markers, auto-pin and the chat's display options. */
    internal val conversationMarks = DesktopConversationMarks(conversationPrefs)

    private inner class ListHost(
        private val deleteSource: DesktopChatGateway? = null,
        private val deleteGeneration: Long = 0,
        private val deleteToken: CurrentFocusRequest? = null,
    ) : ConversationListHost {
        private fun sourceMatches() = deleteSource == null ||
            (deleteSource === this@DesktopChatController.gateway && deleteGeneration == deletionUndo.backendGeneration)
        override val gateway: DesktopChatGateway? get() = deleteSource ?: this@DesktopChatController.gateway
        override val isClosed: Boolean get() = closed || !sourceMatches()

        override val gatewayChanges get() = state.map { }

        override fun origin(): ConversationOrigin? = _state.value.selectedConversation?.let {
            ConversationOrigin(ConversationId(it.id), it.agentId?.takeIf(String::isNotBlank)?.let(::AgentId))
        }

        override suspend fun openBranch(branch: OpenedBranch) {
            if (closed) return
            gateway?.let { inheritWorkingDirectory(it, branch) }
            reloadConversationsAndSelect(preferConversationId = branch.conversation.id.value)
            applyBranchDraft(branch.draft)
        }

        override fun showArchived(conversation: ConversationId, archived: Boolean) {
            val id = conversation.value
            locallyArchivedIds = if (archived) locallyArchivedIds + id else locallyArchivedIds - id
            persistArchivedConversationIds(locallyArchivedIds)
            _state.update { current ->
                val listed = current.runtimeState.conversations.map { if (it.id == id) it.copy(archived = archived) else it }
                current.withRuntimeState(current.runtimeState.copy(conversations = listed))
            }
        }

        override fun beginDelete(conversation: ConversationId): Boolean {
            val id = conversation.value
            if (id in _deletingConversationIds.value) return false
            _deletingConversationIds.update { it + id }
            pendingDeletes[id] = _state.value.conversations.firstOrNull { it.id == id } to deletionUndo.backendGeneration
            return true
        }

        override suspend fun endDelete(conversation: ConversationId, behavior: ConversationDeleteBehavior?) {
            if (!sourceMatches()) return
            try {
                if (behavior != null) onConversationDeleted(conversation.value, behavior)
                if (behavior != null && behavior != ConversationDeleteBehavior.MovesToArchived &&
                    deleteToken?.exactConversation == conversation) {
                    workspaceOwner?.reportAvailability(deleteToken, conversation = FocusAvailability.Unavailable)
                }
            } finally {
                pendingDeletes.remove(conversation.value)
                _deletingConversationIds.update { it - conversation.value }
            }
        }

        override fun showError(message: String) {
            if (!isClosed) _state.update { it.copy(errorMessage = message) }
        }

        override fun listedTitle(conversation: ConversationId): String? =
            _state.value.conversations.firstOrNull { it.id == conversation.value }?.title

        override fun showTitle(
            conversation: ConversationId,
            title: ConversationSummary,
            onlyIf: ConversationSummary?,
        ) = _state.update { current ->
            val conversations = current.conversations.map { item ->
                val replace = item.id == conversation.value && (onlyIf == null || item.title == onlyIf.value)
                if (replace) item.copy(title = title.value) else item
            }
            current.withRuntimeState(current.runtimeState.copy(conversations = conversations))
        }
    }

    /**
     * Answer or dismiss a parked approval (e.g. AskUserQuestion) surfaced in the
     * selected conversation.
     */
    fun submitApproval(answer: ChatApprovalAnswer) {
        if (closed) return
        approvalCoordinator.submitApproval(
            ApprovalSubmissionRequest(gateway = gateway, conversation = _state.value.selectedConversation, answer = answer),
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
            val error = next.composer.error?.toDesktopMessage(attachmentLimits)
            val attached = current.withRuntimeState(next).withoutComposerError()
            if (error != null) attached.withComposerError(error) else attached
        }
    }

    fun removeImageAttachment(index: Int) {
        if (closed) return
        _state.update {
            it.withRuntimeState(ChatSessionReducer.removeImageAttachment(it.runtimeState, index)).withoutComposerError()
        }
    }

    /** A composer error: the shared page shows it in the composer, not the page snackbar. */
    fun showComposerError(message: String) {
        if (closed) return
        _state.update { it.withComposerError(message) }
    }

    /** The composer's error was dismissed; a page error that differs from it stays. */
    fun clearComposerError() {
        if (closed) return
        _state.update { it.withoutComposerError() }
    }

    /** A page error is not written after this controller closes. */
    private fun showPageError(message: String) {
        if (closed) return
        _state.update { it.copy(errorMessage = message) }
    }

    /** Select a notification reply's target and await its load before sending. */
    fun replyFromNotification(conversationId: String, text: String, stillCurrent: () -> Boolean = { true }) {
        if (closed || !stillCurrent()) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val generation = _state.value.selectionGeneration
        scope.launch {
            if (!stillCurrent() || !ChatSessionReducer.isCurrentSelection(_state.value.runtimeState, generation)) return@launch
            val selection = selectConversation(conversationId, stillCurrent = stillCurrent)
            withTimeoutOrNull(NOTIFICATION_REPLY_SETTLE_TIMEOUT_MS.milliseconds) { selection?.join() }
            if (!stillCurrent() || _state.value.selectedConversationId != conversationId) return@launch
            updateComposerText(trimmed)
            send()
        }
    }

    fun stopActiveRun(conversationId: String) {
        if (closed) return
        val coordinator = activeTurnCoordinator(conversationId) ?: return
        val captured = coordinator.state.value
        val turn = ActiveTurnIdentity(captured.runId ?: return, captured.turnId ?: return)
        coordinator.interrupt(turn, java.util.UUID.randomUUID().toString()) {
            activeTurns.abortCaptured(captured, turn, gateway as? DesktopTurnAborter, conversationChanges)
        }
    }

    /** Releases local send resources after the shared owner records LocalClear. */
    private fun forceLocalStopClear(conversationId: String, reason: String) {
        if (streamingConversationId.value == null || streamingConversationId.value == conversationId) sendJob?.cancel()
        _state.update {
            if (it.selectedConversationId == conversationId && it.runtimeState.isSending) {
                it.withRuntimeState(ChatSessionReducer.sendSucceeded(it.runtimeState))
            } else {
                it
            }
        }
        if (_thinkingConversationId.value == conversationId) {
            _thinkingConversationId.value = null
        }
        runPhases.publish(conversationId, RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Cancelled, reason))
    }

    fun send() {
        if (!isSelectionCurrent()) return
        _state.update { it.withoutComposerError() }
        if (stopBlocksSend()) {
            showComposerError(STOPPING_SEND_BLOCKED_MESSAGE)
            return
        }
        val draft = ChatComposerPolicy.beginSend(_state.value.composer) ?: return
        // As before, a draft send with no connection is a no-op: the composer cannot send then.
        dispatchSend(draft, onRefused = ::showComposerError, onNotConnected = {})
    }

    /**
     * Sends [text] without touching the draft or its staged images (a starter prompt, "send
     * again"), through the same route as [send]. A send that cannot start says so on the page.
     */
    fun sendText(text: String) {
        if (!isSelectionCurrent() || text.isBlank()) return
        if (stopBlocksSend()) {
            showPageError(STOPPING_SEND_BLOCKED_MESSAGE)
            return
        }
        val draft = ChatComposerSendDraft(text = text.trim(), attachments = emptyList(), nextState = _state.value.composer)
        dispatchSend(draft, onRefused = ::showPageError, onNotConnected = { showPageError(SEND_NOT_CONNECTED_MESSAGE) })
    }

    internal fun isSelectionCurrent(): Boolean = !closed && selectionStillCurrent() && ownerAllowsSending()
    internal fun isTargetCurrent(): Boolean = !closed && selectionStillCurrent() &&
        workspaceOwner?.selectedConversationId?.value == _state.value.selectedConversationId
    internal fun canEditDraft(): Boolean = isSelectionCurrent() ||
        (!closed && selectionStillCurrent() && _state.value.selectedConversationId == null &&
            workspaceOwner?.currentFocus?.chatMode == com.letta.mobile.data.shell.CurrentFocusChatMode.New &&
            workspaceOwner?.page?.kind == com.letta.mobile.data.shell.ShellPageKind.Chat)

    private fun stopBlocksSend(): Boolean =
        activeTurnStates.value[_state.value.selectedConversationId]?.phase == ActiveTurnPhase.Cancelling

    /** [draft]'s nextState is the composer after the send: emptied by [send], untouched by [sendText]. */
    private fun dispatchSend(draft: ChatComposerSendDraft, onRefused: (String) -> Unit, onNotConnected: () -> Unit) {
        _state.value.selectedConversationId?.let { _lastPromptedConversationId.value = it }
        // The canonical route owns its own send. It deliberately runs no legacy loop, so falling
        // through to the loop check below would drop the message on the floor.
        if (_canonicalPresentation.value != null) {
            launchCanonicalSend(draft, onRefused)
            return
        }
        val loop = activeLoop
        if (loop == null || !_state.value.isRemoteBacked) {
            if (_state.value.connectionState == DesktopChatConnectionState.Demo) {
                _state.update { it.sendLocalMessage(draft) }
            } else {
                onNotConnected()
            }
            return
        }
        launchRemoteSend(loop, draft)
    }

    /** The shared send coordinator uses the selected conversation's canonical presentation transport. */
    private fun selectedCanonicalCoordinator(): com.letta.mobile.data.chat.send.ChatSendCoordinator? {
        val conversationId = _state.value.selectedConversationId
        val conversation = _state.value.conversations.firstOrNull { it.id == conversationId } ?: return null
        val agentId = conversation.agentId ?: return null
        val activeGateway = gateway ?: return null
        return canonicalSendFor?.invoke(agentId, desktopTimelineTransportFor(activeGateway, conversation))
    }

    /** letta-mobile-1n5py: the canonical route's queue for the selected conversation, else null. */
    private fun selectedQueueTarget(): ChatSendQueueTarget? {
        val selectedId = _state.value.selectedConversationId ?: return null
        val controls = selectedCanonicalCoordinator()?.sendQueue ?: return null
        return ChatSendQueueTarget(controls, QueueConversationId(selectedId))
    }

    private fun launchCanonicalSend(draft: ChatComposerSendDraft, onRefused: (String) -> Unit) {
        val conversationId = _state.value.selectedConversationId
        val coordinator = selectedCanonicalCoordinator()
        if (coordinator == null) {
            onRefused(CANONICAL_SEND_UNAVAILABLE_MESSAGE)
            return
        }
        titleCandidateForSend(conversationId, draft.text)?.let { title ->
            conversationId?.let { persistConversationTitle(it, title) }
        }
        clearUnsentIfMatching(conversationId)
        _state.update { it.withRuntimeState(ChatSessionReducer.beginSend(it.runtimeState, draft)) }
        // The coordinator drives the indicators through the UI sink from here, including the
        // failure paths, so nothing else may set them on this route.
        sendJob = coordinator.send(draft.text, draft.attachments)
    }

    private fun launchRemoteSend(loop: DesktopTimelineLoop, draft: ChatComposerSendDraft) {
        val text = draft.text
        val attachments = draft.attachments
        val sendingConversationId = _state.value.selectedConversationId
        val titleToPersist = titleCandidateForSend(sendingConversationId, text)
        clearUnsentIfMatching(sendingConversationId)
        _state.update { it.withRuntimeState(ChatSessionReducer.beginSend(it.runtimeState, draft)) }
        beginThinking(sendingConversationId)
        val identity = "desktop-send-${java.util.UUID.randomUUID()}"
        runPhases.publish(sendingConversationId, RuntimeEventPayload.LocalUserAppend(identity, text))
        if (!activeTurns.hasSession) activeTurnCoordinator(sendingConversationId)?.start(ActiveTurnIdentity(identity, "runtime:$identity"))
        sendJob?.cancel()
        sendJob = scope.launch {
            remoteSender.runRemoteSendAttempt(
                RemoteSendAttempt(
                    loop = loop,
                    text = text,
                    attachments = attachments,
                    conversationId = sendingConversationId,
                    titleToPersist = titleToPersist,
                    localMessageId = LocalMessageId(identity),
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

    private fun persistConversationTitle(conversationId: String, candidate: String) =
        conversationManagement.persistTitle(ConversationId(conversationId), candidate)
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

            ConversationModelBackend.of(nextGateway)?.let(models::load)

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

    /** Each agent's conversations over the current gateway, read through (the list has no "load more"). */
    private val fleets = ReadThroughConversationFleets<DesktopChatGateway> { it.agentConversationReader() }

    private suspend fun reloadConversationsAndSelect(preferConversationId: String?) {
        val nextGateway = gateway ?: return
        val summaries = loadConversationSummaries(nextGateway)
        if (closed || gateway !== nextGateway) return
        val loadedRuntime = _state.updateAndGet { current ->
            val beforeLoad = current.runtimeState
            val loaded = ChatSessionReducer.conversationsLoaded(beforeLoad, summaries)
            current.withRuntimeState(if (preferConversationId != null) loaded else loaded.copy(
                selectedConversationId = beforeLoad.selectedConversationId, selectionGeneration = beforeLoad.selectionGeneration,
                composer = beforeLoad.composer, messagesByConversationId = beforeLoad.messagesByConversationId))
        }.runtimeState
        val selectedId = preferConversationId?.takeIf { id -> summaries.any { it.id == id } }
            ?: loadedRuntime.selectedConversationId
        selectedId?.let { selectRemoteConversation(it, loadedRuntime.selectionGeneration) }
    }
    private suspend fun loadConversationSummaries(
        nextGateway: DesktopChatGateway,
        dirty: Map<ConversationId, AgentId?>? = null,
    ): List<ChatConversationSummary> {
        val (conversations, roster) = fleets.fleetFor(nextGateway).refreshForInvalidations(dirty, rosterProvider) {
            nextGateway.getConversation(it.value)
        }
        models.picks.seedFromHost(conversations) // dxz5s: host overrides, before any selection reads them
        // Every listed conversation is a roster agent's, so the roster names them all.
        val agentNamesById = _state.value.conversations.mapNotNull { row -> row.agentId?.let { it to row.agentName } }.toMap() +
            roster.filter { it.name.isNotBlank() }.associate { it.id.value to it.name }
        return conversations.toChatConversationSummaries(agentNamesById)
            .map { if (it.id in locallyArchivedIds) it.copy(archived = true) else it }
    }

    /** Refresh the catalog without selecting a chat or disturbing its active timeline. */
    private suspend fun refreshConversationRoster(dirty: Map<ConversationId, AgentId?>? = null): Boolean {
        val nextGateway = gateway?.takeUnless { closed } ?: return false
        val runtime = _state.value.runtimeState
        if (!runtime.isRemoteBacked || runtime.connectionState !in ROSTER_REFRESHABLE_STATES) return false
        val deleting = _deletingConversationIds.value
        val summaries = loadConversationSummaries(nextGateway, dirty).filterNot { it.id in deleting }
        if (closed || gateway !== nextGateway) return false
        mergeConversationRoster(summaries)
        return true
    }

    private fun mergeConversationRoster(summaries: List<ChatConversationSummary>) {
        _state.update { current ->
            val next = ChatSessionReducer.conversationRosterRefreshed(current.runtimeState, summaries)
            val populated = next.connectionState == ChatConnectionState.NoConversations && summaries.isNotEmpty()
            current.withRuntimeState(if (populated) next.copy(connectionState = ChatConnectionState.Live, statusMessage = "Live") else next)
        }
    }

    /** The canonical opener receives the target-specific transport; null keeps the in-memory loop. */
    var canonicalOpen: (suspend (DesktopCanonicalOpenRequest) -> com.letta.mobile.data.timeline.CanonicalTimelinePresentation)? = null
    var canonicalEligible: (String) -> Boolean = { false }

    /** The canonical host supplies the matching send coordinator alongside its opener. */
    var canonicalSendFor: (
        (agentId: String, transport: com.letta.mobile.data.timeline.TimelineTransport) ->
        com.letta.mobile.data.chat.send.ChatSendCoordinator
    )? = null

    /** The turn-indicator and error state the shared send coordinator is allowed to move. */
    internal val sendSurface: DesktopChatSendSurface = ControllerSendSurface()

    /** The shared send coordinator's desktop state binding. */
    private inner class ControllerSendSurface : DesktopChatSendSurface {
        override fun currentError(): String? = _state.value.errorMessage
        override fun setError(message: String?) {
            _state.update { it.copy(errorMessage = message) }
        }
        override fun streamingConversationId(): String? = streamingConversationId.value
        override fun thinkingConversationId(): String? = thinkingConversationId.value
        private var dispatchedTurn: Pair<String, ActiveTurnIdentity>? = null
        override fun setStreaming(conversationId: String?) {
            if (activeTurns.hasSession) return
            if (conversationId == null) {
                // Runtime-backed turns settle only on a correlated gateway terminal.
                if (gateway !is DesktopRuntimeEventSource) dispatchedTurn?.let { (id, turn) ->
                    activeTurns.terminate(ConversationId(id), turn, ActiveTurnTerminalKind.Finished)
                }
                return
            }
            val owner = activeTurnCoordinator(conversationId) ?: return
            if (owner.state.value.phase != ActiveTurnPhase.Idle) return
            val identity = java.util.UUID.randomUUID().toString()
            val turn = ActiveTurnIdentity(identity, identity)
            dispatchedTurn = conversationId to turn
            owner.start(turn)
        }
        override fun setThinking(conversationId: String?) { _thinkingConversationId.value = conversationId }
        override fun selectedConversationId(): String? = _state.value.selectedConversationId
        override fun settleSend(failed: Boolean) {
            _state.update { it.withRuntimeState(ChatSessionReducer.sendSettled(it.runtimeState, failed)) }
        }
    }
    private val _canonicalPresentation = MutableStateFlow<com.letta.mobile.data.timeline.CanonicalTimelinePresentation?>(null)
    val canonicalPresentation = _canonicalPresentation.asStateFlow()

    /** The canonical route fills no message map: the selected conversation's last non-empty live overlay, newest first. */
    @Volatile internal var liveReply: Pair<String, List<com.letta.mobile.data.chat.projection.ChatRenderItem>>? = null
        private set

    /** The selected canonical conversation's send queue, re-resolved as selection changes. */
    val sendQueue: ChatSendQueueSource = CoordinatorSendQueue(
        targets = combine(canonicalPresentation, state.map { it.selectedConversationId }.distinctUntilChanged()) { _, _ ->
            selectedQueueTarget()
        },
        canQueueWhileStreaming = DerivedStateFlow(canonicalPresentation.map { it != null }) { canonicalPresentation.value != null },
        current = ::selectedQueueTarget,
    )

    private suspend fun selectRemoteConversation(conversationId: String, generation: Long) {
        if (!isActiveSelection(generation)) return
        val nextGateway = gateway ?: return
        val conversation = _state.value.conversations.firstOrNull { it.id == conversationId } ?: return

        activeTurns.reconcile(conversation)
        if (!isActiveSelection(generation)) return
        models.showModelOf(ConversationId(conversationId), conversation.agentId?.let(::AgentId))

        resetTimelineBinding()

        _state.update {
            it.withRuntimeState(ChatSessionReducer.beginSelectedConversationHydrate(it.runtimeState, generation))
        }

        _canonicalPresentation.value = null
        liveReply = null
        val canonical = canonicalOpen.takeIf { canonicalEligible(conversationId) }
        if (canonical != null) {
            timelineJob = scope.launch {
                try {
                    val presentation = canonical(
                        DesktopCanonicalOpenRequest(
                            agentId = requireNotNull(conversation.agentId) { "Canonical route requires an agent" },
                            conversationId = conversationId,
                            transport = desktopTimelineTransportFor(nextGateway, conversation),
                            scope = this,
                        ),
                    )
                    try {
                        if (!isActiveSelection(generation)) return@launch
                        _canonicalPresentation.value = presentation
                        launch { presentation.live.filter(List<*>::isNotEmpty).collect { liveReply = conversationId to it } }
                        _state.update { it.withRuntimeState(ChatSessionReducer.hydrateCompleted(it.runtimeState, generation)) }
                        kotlinx.coroutines.awaitCancellation()
                    } finally {
                        if (_canonicalPresentation.value === presentation) _canonicalPresentation.value = null
                        presentation.close()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (isActiveSelection(generation)) {
                        _state.update {
                            it.withRuntimeState(ChatSessionReducer.hydrateFailed(it.runtimeState, generation,
                                failure.message ?: "Canonical timeline failed to open"))
                        }
                    }
                }
            }
            return
        }

        val selectionStart = System.currentTimeMillis()
        val loop = loopFactory(nextGateway, conversation, scope)
        activeLoop = loop
        val snapshotEventCount = loop.state.value.events.size
        val selectionToSnapshotMs = System.currentTimeMillis() - selectionStart
        Telemetry.event(
            "ChatPerformance", "selection_to_snapshot",
            "conversationId" attr conversationId,
            "durationMs" attr selectionToSnapshotMs,
            "eventCount" attr snapshotEventCount,
            "hasSnapshot" attr (snapshotEventCount > 0),
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
                "conversationId" attr conversationId,
                "durationMs" attr refreshDurationMs,
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
                    ChatSessionReducer.hydrateFailed(
                        state = it.runtimeState,
                        generation = generation,
                        errorMessage = t.message ?: t::class.simpleName ?: "Message load failed",
                    ),
                )
            }
        }
    }

    private fun isActiveSelection(generation: Long): Boolean =
        !closed && selectionStillCurrent() && ChatSessionReducer.isCurrentSelection(_state.value.runtimeState, generation)

    private fun updateTimelineMessages(conversationId: String, generation: Long, timeline: Timeline) {
        if (!isActiveSelection(generation)) return
        val projection = timelinePresenter.project(
            timeline = timeline,
            prefix = timelinePresenter.olderPrefixFor(conversationId),
            previousState = ChatUiState(),
            isActiveRunStreaming = activeTurnStates.value[conversationId]?.phase?.let { it != ActiveTurnPhase.Idle } == true,
            ownAgentId = _state.value.conversations.firstOrNull { it.id == conversationId }?.agentId,
        )
        _boundProjection.value = BoundProjection(conversationId = conversationId, projection = projection)
        if (projection.noChange) return
        val messages = projection.ui
        approvalCoordinator.reconcileSubmittedApprovals(ConversationId(conversationId), messages)
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

/** Connection states in which the session is settled enough to merge a re-read roster into. */
private val ROSTER_REFRESHABLE_STATES = setOf(
    ChatConnectionState.Live,
    ChatConnectionState.NoConversations,
    ChatConnectionState.Sending,
    ChatConnectionState.SendFailed,
)

/** Shown when a send finds no live connection to send on (outside the demo backend). */
internal const val SEND_NOT_CONNECTED_MESSAGE = "Not connected: the message was not sent."

/** Shown when a conversation cannot send on the canonical timeline route. */
internal const val CANONICAL_SEND_UNAVAILABLE_MESSAGE = "This conversation cannot send on the canonical timeline route."

/** letta-mobile-lgns8.19: shown when a send is attempted while a stop is pending. */
internal const val STOPPING_SEND_BLOCKED_MESSAGE =
    "Stopping the current run — wait for it to finish before sending another message."

/** Conversation-list scope filter: which listed rows show (the list reads every row and filters itself). */
enum class ConversationArchiveFilter(val label: String) {
    Active("Active"),
    Archived("Archived"),
    All("All"),
}
