package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.BotGatewayErrorCode
import com.letta.mobile.bot.protocol.BotGatewayException
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.BotStreamEvent
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.BugReportRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class ProjectChatContext(
    val identifier: String,
    val name: String,
    val lettaFolderId: String? = null,
    val filesystemPath: String? = null,
    val gitUrl: String? = null,
    val lastSyncAt: String? = null,
    val activeCodingAgents: String? = null,
)

@androidx.compose.runtime.Immutable
data class PendingToolCall(
    val id: String,
    val name: String,
    val startedAt: Long = System.currentTimeMillis(),
)

enum class ProjectBriefSectionKey {
    Description,
    KeyDecisions,
    TechStack,
    ActiveGoals,
    RecentChanges,
}

@androidx.compose.runtime.Immutable
data class ProjectBriefSection(
    val key: ProjectBriefSectionKey,
    val blockLabel: String,
    val content: String,
    val updatedAt: String? = null,
)

@androidx.compose.runtime.Immutable
data class ProjectBriefUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    // ImmutableMap so Compose treats this whole state as stable — raw
    // kotlin.collections.Map is an unstable interface type to the
    // compiler (it could be a MutableMap at runtime). See o7ob.2.6.
    val sections: kotlinx.collections.immutable.ImmutableMap<ProjectBriefSectionKey, ProjectBriefSection> =
        kotlinx.collections.immutable.persistentMapOf(),
    val error: String? = null,
)

enum class BugSeverity(val wireValue: String) {
    Critical("critical"),
    High("high"),
    Medium("medium"),
    Low("low"),
}

@androidx.compose.runtime.Immutable
data class ProjectBugReportDraft(
    val title: String = "",
    val description: String = "",
    val severity: BugSeverity = BugSeverity.Medium,
    val tags: ImmutableList<String> = persistentListOf(),
    val attachmentReferences: ImmutableList<String> = persistentListOf(),
)

@androidx.compose.runtime.Immutable
data class ProjectBugReportUiState(
    val isSubmitting: Boolean = false,
    val recentReports: ImmutableList<ProjectBugReport> = persistentListOf(),
    val lastSubmittedPrompt: String? = null,
    val error: String? = null,
)

enum class ProjectAgentStatusTone {
    Neutral,
    Good,
    Busy,
    Error,
}

@androidx.compose.runtime.Immutable
data class ProjectAgentActivity(
    val id: String,
    val name: String,
    val statusLabel: String,
    val statusTone: ProjectAgentStatusTone,
    val detail: String? = null,
    val model: String? = null,
    val lastActivity: String? = null,
)

@androidx.compose.runtime.Immutable
data class ProjectAgentsUiState(
    val isLoading: Boolean = false,
    val agents: ImmutableList<ProjectAgentActivity> = persistentListOf(),
    val error: String? = null,
)

sealed interface ConversationState {
    @androidx.compose.runtime.Immutable
    data object Loading : ConversationState

    @androidx.compose.runtime.Immutable
    data class Ready(val conversationId: String) : ConversationState

    @androidx.compose.runtime.Immutable
    data object NoConversation : ConversationState

    @androidx.compose.runtime.Immutable
    data class Error(val message: String) : ConversationState
}

@androidx.compose.runtime.Immutable
data class ChatUiState(
    val conversationState: ConversationState = ConversationState.Loading,
    val messages: ImmutableList<UiMessage> = persistentListOf(),
    val isLoadingMessages: Boolean = true,
    val isLoadingOlderMessages: Boolean = false,
    val hasMoreOlderMessages: Boolean = false,
    val isStreaming: Boolean = false,
    val isAgentTyping: Boolean = false,
    val pendingTools: ImmutableList<PendingToolCall> = persistentListOf(),
    val agentName: String = "",
    val error: String? = null,
    val composerError: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val activeApprovalRequestId: String? = null,
    val collapsedRunIds: kotlinx.collections.immutable.ImmutableSet<String> = persistentSetOf(),
    val expandedReasoningMessageIds: kotlinx.collections.immutable.ImmutableSet<String> = persistentSetOf(),
    val projectBrief: ProjectBriefUiState = ProjectBriefUiState(),
    val bugReports: ProjectBugReportUiState = ProjectBugReportUiState(),
    val projectAgents: ProjectAgentsUiState = ProjectAgentsUiState(),
    /**
     * Pending image attachments staged in the composer. Reset on successful send.
     * Cap enforced by [MAX_COMPOSER_ATTACHMENTS].
     */
    val pendingAttachments: ImmutableList<com.letta.mobile.data.model.MessageContentPart.Image> =
        persistentListOf(),
    /**
     * Surfaced when the LettaBot harness substituted a fresh conversation ID for
     * the one we requested (i.e. our requested conv was unrecoverable on the
     * gateway/SDK side, gateway opened a new conversation and reported it back
     * via session_init). The original Letta-server timeline rows for the prior
     * conversation remain visible; new client-mode turns persist under the new
     * conversation. Dismissable. See `letta-mobile-c87t`.
     */
    val clientModeConversationSwap: ClientModeConversationSwap? = null,
)

/**
 * Banner state for the gateway's conversation-substitution recovery path.
 *
 * Two distinct cases are surfaced here:
 *
 *   * [Substituted] — letta-mobile-c87t (legacy path, kept for back-compat
 *     with older lettabot gateways that don't support the c87t.2 refusal):
 *     the gateway already silently swapped the conversation underneath us
 *     and reported the new id via `session_init`. Banner is informational;
 *     the user is already in the new conversation.
 *
 *   * [NotResumable] — letta-mobile-c87t.2: the new (preferred) path. The
 *     gateway refused the silent substitution and returned a typed
 *     `CONVERSATION_NOT_RESUMABLE` error frame. The user is still anchored
 *     to the prior conversation; nothing has been migrated. Banner offers
 *     an explicit "start a fresh chat" action so the user is in control.
 */
sealed class ClientModeConversationSwap {
    /** The conv id the user asked to resume — common to both variants. */
    abstract val requestedConversationId: String

    /**
     * The gateway already moved us into a different conversation.
     * Informational banner, dismissable.
     */
    data class Substituted(
        override val requestedConversationId: String,
        val newConversationId: String,
    ) : ClientModeConversationSwap()

    /**
     * The gateway refused to resume `requestedConversationId`. The user
     * has not been moved. They can either dismiss (stay anchored,
     * possibly retry later) or take an action that retries with
     * `force_new=true` to start a fresh conversation deliberately.
     */
    data class NotResumable(
        override val requestedConversationId: String,
    ) : ClientModeConversationSwap()
}

/**
 * letta-mobile-c87t.2: buffered send context preserved when the gateway
 * refuses to resume a conversation. Used by
 * `AdminChatViewModel.startFreshConversationAfterUnresumable` to retry
 * the original send under a brand-new conversation.
 */
private data class PendingClientModeSend(
    val agentId: String,
    val text: String,
    val unresumableConversationId: String,
)

/** Upper bound on composer image attachments per message. */
const val MAX_COMPOSER_ATTACHMENTS = 4

/** Upper bound on per-message total base64 payload (approximate — ~8 MB). */
const val MAX_COMPOSER_TOTAL_BYTES = 8 * 1024 * 1024

@HiltViewModel
class AdminChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val timelineRepository: com.letta.mobile.data.timeline.TimelineRepository,
    private val agentRepository: AgentRepository,
    private val blockRepository: BlockRepository,
    private val bugReportRepository: BugReportRepository,
    private val folderRepository: FolderRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val internalBotClient: InternalBotClient,
    private val clientModeChatSender: ClientModeChatSender,
    private val currentConversationTracker: com.letta.mobile.channel.CurrentConversationTracker,
    // Doubled-response fix: marks conversations driven by the WS gateway path
    // so TimelineSyncLoop's direct-Letta SSE subscriber stays dormant for
    // them. Released when Client Mode is disabled (see clientModeEnabled
    // observer below). Plan: 2026-04-clientmode-double-bubble-fix.md.
    private val clientModeSuppressionGate: com.letta.mobile.clientmode.ClientModeSuppressionGate,
) : ViewModel() {
    companion object {
        private const val CONVERSATION_CACHE_TTL_MS = 30_000L
        private const val MESSAGE_SYNC_INTERVAL_MS = 5_000L
        private const val COLLAPSED_RUN_IDS_KEY = "collapsedRunIds"
        private const val EXPANDED_REASONING_MESSAGE_IDS_KEY = "expandedReasoningMessageIds"
        private const val CLIENT_MODE_CONVERSATION_ID_KEY = "clientModeConversationId"
    }

    val agentId: String = savedStateHandle.get<String>("agentId")!!
    private val initialMessage: String? = savedStateHandle.get<String>("initialMessage")
    private val requestedConversationArg: String? = savedStateHandle.get<String>("conversationId")
    private val freshRouteKey: Long? = savedStateHandle.get<Long>("freshRouteKey")
    val scrollToMessageId: String? = savedStateHandle.get<String>("scrollToMessageId")
    private val explicitConversationId: String?
        get() = requestedConversationArg?.takeIf { it.isNotBlank() }
    private val isFreshRoute: Boolean
        get() = freshRouteKey != null || requestedConversationArg?.isBlank() == true
    // letta-mobile-c87t: previously this predicate was
    //   clientModeEnabled.value && (isFreshRoute || explicitConversationId == null)
    // which collapsed to "client mode only fires for fresh routes" — meaning any
    // existing conversation silently bypassed Client Mode and went direct to Letta.
    // The Client Mode toggle is global per Emmanuel's design; if it's on, ALL
    // conversations should engage with the LettaBot harness. Existing conversations
    // are resumed by passing the Letta conversation ID through to the gateway,
    // which uses Letta Code SDK's resumeSession() to switch into them.
    private val shouldUseClientModeForCurrentRoute: Boolean
        get() = clientModeEnabled.value
    // letta-mobile-w2hx.6: per-VM (per-chat) active conversation id. Replaces
    // the process-wide ConversationManager singleton that was keyed only on
    // agentId — that map could pollute one chat's resolve with another
    // chat's resolved conv (the bug w2hx.6 explicitly calls out).
    //
    // The chat row IS this view-model. Its conversation_id is its own state,
    // never shared across chats, never inherited via agent_id. Initial value
    // comes from the route's explicit nav arg if present; otherwise null
    // until resolveConversationAndLoad assigns one.
    @Volatile private var activeConversationId: String? =
        requestedConversationArg?.takeIf { it.isNotBlank() }
    private val clientModeEnabled: StateFlow<Boolean> = settingsRepository.observeClientModeEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val conversationId: String?
        get() = if (shouldUseClientModeForCurrentRoute) currentClientModeConversationId() else activeConversationId
    val projectContext: ProjectChatContext? = savedStateHandle.get<String>("projectIdentifier")?.let { identifier ->
        val name = savedStateHandle.get<String>("projectName") ?: identifier
        ProjectChatContext(
            identifier = identifier,
            name = name,
            lettaFolderId = savedStateHandle.get<String>("projectLettaFolderId"),
            filesystemPath = savedStateHandle.get<String>("projectFilesystemPath"),
            gitUrl = savedStateHandle.get<String>("projectGitUrl"),
            lastSyncAt = savedStateHandle.get<String>("projectLastSyncAt"),
            activeCodingAgents = savedStateHandle.get<String>("projectActiveCodingAgents"),
        )
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * letta-mobile-23h5 (regression fix 2026-04-19): older messages fetched via
     * pagination need to survive the next timeline observer emission (which
     * overwrites `_uiState.messages` with the live timeline contents). Hold
     * a per-conversation prefix here, scoped to a (conversationId → list)
     * pair so a conversation switch resets the prefix automatically.
     *
     * The timeline observer concatenates `olderMessagesPrefix` ahead of its
     * mapped events on every emission; the loader replaces this list when a
     * new page arrives.
     */
    private var olderMessagesPrefix: Pair<String, List<UiMessage>> = "" to emptyList()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    val chatBackground: StateFlow<ChatBackground> = settingsRepository.getChatBackgroundKey()
        .map { ChatBackground.fromKey(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatBackground.Default)

    val chatFontScale: StateFlow<Float> = settingsRepository.getChatFontScale()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)

    fun setChatBackground(background: ChatBackground) {
        viewModelScope.launch {
            settingsRepository.setChatBackgroundKey(background.key)
        }
    }

    fun setChatFontScale(scale: Float) {
        viewModelScope.launch {
            settingsRepository.setChatFontScale(scale)
        }
    }

    private val pendingToolsMap = java.util.concurrent.ConcurrentHashMap<String, PendingToolCall>()
    private var hasSummary = false
    // letta-mobile-flk.6: tracks whether the VM has already resolved its
    // conversation at least once. Used to gate the fresh-route fallback
    // suppression so it only fires on the very first resolveConversationAndLoad
    // call (the actual nav entry). Subsequent re-resolutions — like a
    // mid-session client-mode toggle — are allowed to fall back to the
    // most-recent persisted conversation as before.
    private var hasResolvedConversationOnce: Boolean = false

    private fun collapsedRunIds(): Set<String> =
        savedStateHandle.get<ArrayList<String>>(COLLAPSED_RUN_IDS_KEY)?.toSet().orEmpty()

    private fun expandedReasoningMessageIds(): Set<String> =
        savedStateHandle.get<ArrayList<String>>(EXPANDED_REASONING_MESSAGE_IDS_KEY)?.toSet().orEmpty()

    private fun persistCollapsedRunIds(ids: Set<String>) {
        savedStateHandle[COLLAPSED_RUN_IDS_KEY] = ArrayList(ids)
        _uiState.value = _uiState.value.copy(collapsedRunIds = ids.toImmutableSet())
    }

    private fun persistExpandedReasoningMessageIds(ids: Set<String>) {
        savedStateHandle[EXPANDED_REASONING_MESSAGE_IDS_KEY] = ArrayList(ids)
        _uiState.value = _uiState.value.copy(expandedReasoningMessageIds = ids.toImmutableSet())
    }

    fun toggleRunCollapsed(runId: String) {
        val next = collapsedRunIds().toMutableSet().apply {
            if (!add(runId)) remove(runId)
        }
        persistCollapsedRunIds(next)
    }

    fun toggleReasoningExpanded(messageId: String) {
        val next = expandedReasoningMessageIds().toMutableSet().apply {
            if (!add(messageId)) remove(messageId)
        }
        persistExpandedReasoningMessageIds(next)
    }

    private var timelineObserverJob: kotlinx.coroutines.Job? = null
    private var timelineHydrateSignalJob: kotlinx.coroutines.Job? = null
    private var clientModeStreamJob: Job? = null
    /**
     * letta-mobile-flk2: per-stream paint-rate smoother. Created in
     * [sendMessageViaClientMode] before the stream collect, drained by
     * [ClientModeStreamSmoother.awaitDrained] on `done=true`, and flushed
     * synchronously by [ClientModeStreamSmoother.flushAndStop] on cancel
     * / abort / `finally`. Null between streams.
     */
    private var clientModeSmoother: ClientModeStreamSmoother? = null

    /**
     * letta-mobile-5s1n (regression fix): explicit "Client Mode stream in
     * flight" flag, set BEFORE any timeline mutations and cleared after the
     * stream coroutine's `finally`. Used by the timeline observer to know
     * whether to preserve `isStreaming` / `isAgentTyping` across emissions.
     *
     * We can't rely on `clientModeStreamJob?.isActive`: with
     * `UnconfinedTestDispatcher` (and equivalently fast main-thread
     * dispatch in production), the launched coroutine begins running
     * synchronously inside `viewModelScope.launch { ... }` and triggers
     * observer emissions BEFORE the `clientModeStreamJob` assignment
     * completes — leaving the field at its old (often null) value when
     * the observer reads it.
     */
    @Volatile private var clientModeStreamInFlight: Boolean = false
    /**
     * letta-mobile-5s1n design note (Option A — retained narrow fallback):
     *
     * After 5s1n landed, Client Mode assistant streaming flows through
     * [TimelineRepository.upsertClientModeLocalAssistantChunk] whenever a
     * conversationId is known — i.e., always for existing-route entries, and
     * for every chunk after the gateway's first conversationId echo on
     * fresh-route entries. The bulk of the dual-write is gone.
     *
     * This field is retained as a narrow belt-and-suspenders fallback for
     * fresh-route sends in the window between "user pressed send" and "gateway
     * echoed a conversationId". In normal operation this window contains 0
     * chunks (the gateway echoes on chunk #1) — but if it ever doesn't, the
     * legacy in-memory path keeps the UI rendering instead of dropping
     * content. As soon as a conversationId arrives, the optimistic user
     * bubble is migrated into the timeline (see `migratedToTimeline` below)
     * and subsequent chunks go through the timeline.
     *
     * Tracked as a follow-up under letta-mobile-5s1n-2: if dev-build
     * telemetry on `Client Mode timeline upsert failed` and the legacy chunk
     * handler shows zero hits across a meaningful window of real Client Mode
     * sessions, this field and [handleClientModeStreamChunkLegacy] can be
     * deleted in favour of a chunk-buffer-then-replay strategy.
     */
    private var clientModeMessages: List<UiMessage> = emptyList()
    // Conversation id the current observer job is bound to. Needed so we can
    // detect "same conversation, already observing" vs "user switched convs
    // and we must rebind" — fixing letta-mobile-nw2e, where the previous
    // `isActive == true` guard silently ignored conversation switches.
    private var timelineObserverConversationId: String? = null

    /**
     * letta-mobile-c87t.2: buffered context for a `CONVERSATION_NOT_RESUMABLE`
     * recovery. Set when the gateway refuses to resume a conv id; consumed
     * (and cleared) by [startFreshConversationAfterUnresumable] when the
     * user accepts the actionable banner.
     */
    private var pendingResendAfterUnresumable: PendingClientModeSend? = null

    /**
     * letta-mobile-c87t.2: when set, the next `sendMessageViaClientMode`
     * resolves `priorConversationId = null` regardless of route args /
     * client-mode-conversation-id state. The flag clears as soon as it's
     * read so subsequent sends use the normal resolution.
     */
    private var forceFreshOnNextClientModeSend: Boolean = false

    init {
        // letta-mobile-w2hx.6: route arg already pre-populated `activeConversationId`
        // at field init; no shared singleton to seed.
        if (isFreshRoute) {
            setClientModeConversationId(null)
            currentConversationTracker.setCurrent(null)
        }
        if (agentId.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "No agent selected")
        } else {
            _uiState.value = _uiState.value.copy(
                collapsedRunIds = collapsedRunIds().toImmutableSet(),
                expandedReasoningMessageIds = expandedReasoningMessageIds().toImmutableSet(),
            )
            viewModelScope.launch {
                settingsRepository.observeClientModeEnabled()
                    .distinctUntilChanged()
                    .collect { enabled ->
                        if (!enabled) {
                            clientModeStreamJob?.cancel()
                            // letta-mobile-flk2: drain smoother on
                            // disable so any in-flight characters reach
                            // the timeline before we tear down.
                            clientModeSmoother?.let { sm ->
                                viewModelScope.launch { runCatching { sm.flushAndStop() } }
                            }
                            clientModeSmoother = null
                            clientModeStreamInFlight = false
                            // Doubled-response fix: Client Mode is no longer
                            // the authority for any conversation. Release
                            // every owned conv so TimelineSyncLoop's direct
                            // SSE subscriber resumes on the next loop tick.
                            // Process-wide because a single ClientMode toggle
                            // governs every chat in the app.
                            clientModeSuppressionGate.releaseAll()
                            _uiState.update {
                                it.copy(
                                    isStreaming = false,
                                    isAgentTyping = false,
                                    error = null,
                                )
                            }
                        }
                        resolveConversationAndLoad()
                    }
            }
            if (projectContext != null) {
                loadProjectAgents()
                loadProjectBrief()
                loadRecentBugReports()
            }
        }
    }

    fun loadProjectAgents() {
        val project = projectContext ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                projectAgents = _uiState.value.projectAgents.copy(isLoading = true, error = null)
            )
            try {
                val activities = buildProjectAgentActivities(project)
                _uiState.value = _uiState.value.copy(
                    projectAgents = ProjectAgentsUiState(
                        isLoading = false,
                        agents = activities.toImmutableList(),
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    projectAgents = _uiState.value.projectAgents.copy(
                        isLoading = false,
                        error = mapErrorToUserMessage(e, "Failed to load active agents"),
                    )
                )
            }
        }
    }

    fun loadRecentBugReports() {
        val projectIdentifier = projectContext?.identifier ?: return
        viewModelScope.launch {
            try {
                val recent = bugReportRepository.getRecentBugReports(projectIdentifier)
                _uiState.value = _uiState.value.copy(
                    bugReports = _uiState.value.bugReports.copy(
                        recentReports = recent.toImmutableList(),
                        error = null,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    bugReports = _uiState.value.bugReports.copy(
                        error = mapErrorToUserMessage(e, "Failed to load recent bug reports"),
                    )
                )
            }
        }
    }

    fun submitStructuredBugReport(draft: ProjectBugReportDraft) {
        val project = projectContext ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                bugReports = _uiState.value.bugReports.copy(isSubmitting = true, error = null)
            )
            try {
                val prompt = buildBugReportPrompt(draft)
                val logged = bugReportRepository.logBugReport(
                    ProjectBugReport(
                        projectIdentifier = project.identifier,
                        title = draft.title.trim(),
                        description = draft.description.trim(),
                        severity = draft.severity.wireValue,
                        tags = draft.tags,
                        attachmentReferences = draft.attachmentReferences,
                        structuredPrompt = prompt,
                        createdAt = java.time.Instant.now().toString(),
                    )
                )
                _uiState.value = _uiState.value.copy(
                    bugReports = _uiState.value.bugReports.copy(
                        isSubmitting = false,
                        lastSubmittedPrompt = prompt,
                        recentReports = (listOf(logged) + _uiState.value.bugReports.recentReports
                            .filterNot { it.id == logged.id }
                            .take(4)).toImmutableList(),
                    )
                )
                sendMessage(prompt)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    bugReports = _uiState.value.bugReports.copy(
                        isSubmitting = false,
                        error = mapErrorToUserMessage(e, "Failed to submit bug report"),
                    )
                )
            }
        }
    }

    fun tryHandleSlashCommand(text: String): Boolean {
        if (projectContext == null) return false
        return when (text.trim()) {
            "/bug" -> true
            else -> false
        }
    }

    fun loadProjectBrief() {
        if (projectContext == null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                projectBrief = _uiState.value.projectBrief.copy(isLoading = true, error = null)
            )
            try {
                val blocks = blockRepository.getBlocks(agentId)
                _uiState.value = _uiState.value.copy(
                    projectBrief = ProjectBriefUiState(
                        isLoading = false,
                        sections = buildProjectBriefSections(blocks).toImmutableMap(),
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    projectBrief = _uiState.value.projectBrief.copy(
                        isLoading = false,
                        error = mapErrorToUserMessage(e, "Failed to load project brief"),
                    )
                )
            }
        }
    }

    fun saveProjectBriefSection(
        key: ProjectBriefSectionKey,
        content: String,
    ) {
        val existingSection = _uiState.value.projectBrief.sections[key] ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                projectBrief = _uiState.value.projectBrief.copy(isSaving = true, error = null)
            )
            try {
                val updatedBlock = blockRepository.updateAgentBlock(
                    agentId = agentId,
                    blockLabel = existingSection.blockLabel,
                    params = BlockUpdateParams(value = content),
                )
                val updatedSection = existingSection.copy(
                    content = updatedBlock.value,
                    updatedAt = updatedBlock.updatedAt,
                )
                _uiState.value = _uiState.value.copy(
                    projectBrief = _uiState.value.projectBrief.copy(
                        isSaving = false,
                        sections = (_uiState.value.projectBrief.sections + (key to updatedSection))
                            .toImmutableMap(),
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    projectBrief = _uiState.value.projectBrief.copy(
                        isSaving = false,
                        error = mapErrorToUserMessage(e, "Failed to save project brief"),
                    )
                )
            }
        }
    }

    private fun resolveConversationAndLoad() {
        // letta-mobile-flk.6: capture the fresh-route bit at entry. It's
        // only honored on the *first* call (initial nav entry) — see
        // `hasResolvedConversationOnce` and `suppressFreshRouteFallback`
        // below. Subsequent calls (e.g. mid-session client-mode toggle)
        // resolve normally so legacy behavior is preserved.
        val isFirstResolve = !hasResolvedConversationOnce
        hasResolvedConversationOnce = true
        // letta-mobile-flk.6 / w2hx.6: on a genuine fresh-route entry, ensure
        // this VM's local activeConversationId is null so the UI starts truly
        // empty. Pre-w2hx.6 this had to clear an agent-keyed singleton map
        // because that map could be pre-populated by other surfaces (agent
        // list, recents drawer); now activeConversationId lives on this VM
        // alone and only reflects this chat's nav arg, so the clear is just
        // a local null assignment.
        if (isFreshRoute && isFirstResolve && explicitConversationId == null) {
            activeConversationId = null
            android.util.Log.i(
                "AdminChatViewModel",
                "flk6.clearActive agent=$agentId reason=freshRouteInitialResolve",
            )
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                conversationState = ConversationState.Loading,
                isLoadingMessages = true,
                error = null,
            )

            try {
                if (shouldUseClientModeForCurrentRoute) {
                    // letta-mobile-c87t (PR 2): when we have a Client Mode
                    // conversationId, route through the timeline observer so
                    // SSE-side persisted messages flow into the UI alongside
                    // the optimistic CLIENT_MODE_HARNESS Locals from
                    // appendClientModeLocal. Without this the chat would only
                    // ever show in-memory bubbles and history wouldn't load.
                    //
                    // Resolution order: explicit nav arg → saved-state-handle
                    // pointer → most-recent server-side conversation for this
                    // agent (Client Mode default-load behavior). Fresh routes
                    // — when the user explicitly opened a new conversation
                    // (freshRouteKey set, or conversationId arg was blank) —
                    // skip the recent-conversation fallback and stay on the
                    // in-memory path until the gateway provides one (see
                    // sendMessageViaClientMode migration). See
                    // letta-mobile-c87t and the Apr 26 default-load change.
                    // letta-mobile-flk.6: same suppression rule as the
                    // timeline branch below — only block the fallback on
                    // the *initial* fresh-route nav entry, not on
                    // subsequent re-resolutions.
                    val suppressFreshRouteFallbackClient = isFreshRoute && isFirstResolve
                    val clientConversationId = explicitConversationId
                        ?: currentClientModeConversationId()
                        ?: if (!suppressFreshRouteFallbackClient) {
                            runCatching {
                                resolveMostRecentConversation(CONVERSATION_CACHE_TTL_MS)
                            }.getOrNull()?.also { resolved ->
                                setClientModeConversationId(resolved)
                            }
                        } else {
                            null
                        }
                    currentConversationTracker.setCurrent(clientConversationId)
                    val agent = agentRepository.getCachedAgent(agentId)
                        ?: runCatching { agentRepository.getAgent(agentId).first() }.getOrNull()
                    if (clientConversationId != null) {
                        // Make sure the observer is running for this conv;
                        // it'll repopulate _uiState.messages on first emission.
                        startTimelineObserver(clientConversationId)
                        _uiState.value = _uiState.value.copy(
                            agentName = agent?.name ?: _uiState.value.agentName,
                            conversationState = ConversationState.Ready(clientConversationId),
                            // Don't write messages here — the timeline observer
                            // is the source of truth.
                            isLoadingOlderMessages = false,
                            hasMoreOlderMessages = false,
                            isStreaming = false,
                            isAgentTyping = false,
                            error = null,
                        )
                    } else {
                        // Fresh route, no conv yet — keep in-memory path.
                        stopTimelineObserver()
                        _uiState.value = _uiState.value.copy(
                            agentName = agent?.name ?: _uiState.value.agentName,
                            conversationState = ConversationState.NoConversation,
                            messages = clientModeMessages.toImmutableList(),
                            isLoadingMessages = false,
                            isLoadingOlderMessages = false,
                            hasMoreOlderMessages = false,
                            isStreaming = false,
                            isAgentTyping = false,
                            error = null,
                        )
                    }
                    initialMessage?.let { message ->
                        if (message.isNotBlank()) {
                            sendMessage(message)
                        }
                    }
                    return@launch
                }

                // letta-mobile-flk.6: on the *initial* fresh-route entry,
                // suppress the most-recent-conversation fallback so a "New
                // chat" tap doesn't silently hydrate the UI with the
                // agent's previous server-side conversation. Once the VM
                // has resolved at least once (`hasResolvedConversationOnce`),
                // subsequent re-resolutions (e.g. a mid-session client-mode
                // toggle, exercised by the
                // "toggling client mode off restores timeline messages"
                // test) are allowed to fall back to most-recent as before.
                // This mirrors the existing guard in the Client-Mode branch
                // above (line ~629).
                val suppressFreshRouteFallback = isFreshRoute && isFirstResolve
                if (
                    !suppressFreshRouteFallback &&
                    activeConversationId == null &&
                    explicitConversationId == null
                ) {
                    resolveMostRecentConversation(CONVERSATION_CACHE_TTL_MS)
                }

                // letta-mobile-flk.6 / w2hx.6: a fresh-route entry must NOT
                // inherit a prior `activeConversationId` from before this
                // resolve. Pre-w2hx.6 we also had to guard against a
                // process-wide singleton leaking across chats; that's gone.
                // Once `hasResolvedConversationOnce` flips, behave normally.
                val conversationId = if (suppressFreshRouteFallback) {
                    explicitConversationId?.also { activeConversationId = it }
                } else {
                    activeConversationId ?: explicitConversationId?.also { activeConversationId = it }
                }

                if (conversationId == null) {
                    _uiState.value = _uiState.value.copy(
                        conversationState = ConversationState.NoConversation,
                        messages = persistentListOf(),
                        isLoadingMessages = false,
                        isLoadingOlderMessages = false,
                        hasMoreOlderMessages = false,
                        error = null,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        conversationState = ConversationState.Ready(conversationId),
                        error = null,
                    )
                    loadMessagesInternal()
                }

                initialMessage?.let { message ->
                    if (message.isNotBlank()) {
                        sendMessage(message)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Failed to resolve conversation", e)
                _uiState.value = _uiState.value.copy(
                    conversationState = ConversationState.Error(
                        message = e.message ?: "Failed to load conversation",
                    ),
                    messages = persistentListOf(),
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = false,
                    isAgentTyping = false,
                    error = null,
                )
            }
        }
    }

    /**
     * letta-mobile-w2hx.6: resolve the most-recent server-side conversation
     * for this VM's agent and assign it to this VM's [activeConversationId].
     * Replaces ConversationManager.resolveAndSetActiveConversation, which
     * wrote into a process-wide map keyed on agentId — that map could leak
     * one chat's resolved conv into a sibling chat's resolve. The chat row
     * (this VM) now owns its own active id.
     *
     * @return the resolved conversation id, or null if there are none cached
     *   for this agent after the refresh.
     */
    private suspend fun resolveMostRecentConversation(maxAgeMs: Long): String? {
        conversationRepository.refreshConversationsIfStale(agentId, maxAgeMs)
        val mostRecent = conversationRepository.getCachedConversations(agentId)
            .sortedByDescending { it.lastMessageAt ?: it.createdAt ?: "" }
            .firstOrNull()
            ?: return null
        activeConversationId = mostRecent.id
        return mostRecent.id
    }

    private suspend fun loadMessagesInternal() {
        val loadTimer = Telemetry.startTimer("AdminChatVM", "loadMessages")
        val requestedConversationId = activeConversationId ?: explicitConversationId?.also {
            activeConversationId = it
        }
        val currentConversationId = activeConversationId ?: explicitConversationId
        if (requestedConversationId == null) {
            if (requestedConversationId == currentConversationId) {
                _uiState.value = _uiState.value.copy(
                    conversationState = ConversationState.NoConversation,
                    messages = persistentListOf(),
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    error = null,
                )
            }
            loadTimer.stop("result" to "noConversation")
            return
        }
        val cachedAgent = agentRepository.getCachedAgent(agentId)
        // Timeline is the source of truth — legacy cache is never read here.
        val cachedMessages = emptyList<AppMessage>()
        if (cachedAgent != null || cachedMessages.isNotEmpty()) {
            if (requestedConversationId == currentConversationId) {
                _uiState.value = _uiState.value.copy(
                    agentName = cachedAgent?.name ?: _uiState.value.agentName,
                    messages = if (cachedMessages.isNotEmpty()) cachedMessages.toUiMessages().toImmutableList() else _uiState.value.messages,
                    isLoadingMessages = cachedMessages.isEmpty(),
                    error = null,
                )
            }
        } else {
            if (requestedConversationId == currentConversationId) {
                _uiState.value = _uiState.value.copy(isLoadingMessages = true)
            }
        }
        try {
            val agent = agentRepository.getAgent(agentId).first()
            if (requestedConversationId != (activeConversationId ?: explicitConversationId)) {
                loadTimer.stop("result" to "staleConversation")
                return
            }
            _uiState.value = _uiState.value.copy(
                agentName = agent.name,
                conversationState = ConversationState.Ready(requestedConversationId),
            )

            // Hand control of _uiState.messages to the timeline observer.
            // It calls TimelineRepository.hydrate() and emits as messages
            // arrive. Keep isLoadingMessages=true until the observer sees
            // the first emission (see startTimelineObserver).
            _uiState.value = _uiState.value.copy(
                isLoadingMessages = true,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
            )
            startTimelineObserver(requestedConversationId)
            loadTimer.stop(
                "conversationId" to requestedConversationId,
                "mode" to "timeline",
            )
        } catch (e: Exception) {
            loadTimer.stopError(e, "conversationId" to requestedConversationId)
            if (requestedConversationId != (activeConversationId ?: explicitConversationId)) {
                return
            }
            _uiState.value = _uiState.value.copy(
                conversationState = ConversationState.Ready(requestedConversationId),
                isLoadingMessages = false,
                isLoadingOlderMessages = false,
                error = e.message ?: "Failed to load messages",
            )
        }
    }

    fun loadMessages() {
        if (!shouldUseClientModeForCurrentRoute && activeConversationId == null) {
            resolveConversationAndLoad()
            return
        }
        viewModelScope.launch { loadMessagesInternal() }
    }

    fun retryConversationLoad() {
        resolveConversationAndLoad()
    }

    fun loadOlderMessages() {
        if (clientModeEnabled.value) return
        val conversationId = activeConversationId ?: return
        val currentState = _uiState.value
        if (
            currentState.isLoadingMessages ||
            currentState.isLoadingOlderMessages ||
            !currentState.hasMoreOlderMessages ||
            currentState.isStreaming
        ) {
            return
        }

        val oldestLoadedMessageId = currentState.messages
            .firstOrNull { !it.isPending }
            ?.id
            ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingOlderMessages = true)
            try {
                val olderMessages = messageRepository.fetchOlderMessages(
                    agentId = agentId,
                    conversationId = conversationId,
                    beforeMessageId = oldestLoadedMessageId,
                )
                if (conversationId != activeConversationId) {
                    return@launch
                }

                // letta-mobile-23h5 (regression fix 2026-04-19): merge older
                // messages into the per-conversation backfill prefix instead
                // of writing them straight into _uiState.messages — the
                // timeline observer would otherwise overwrite them on its
                // very next emission. Concatenate previously-backfilled
                // pages with this new page so consecutive scroll-ups grow
                // the prefix monotonically.
                val olderUi = olderMessages.toUiMessages()
                val (prevConv, prevPrefix) = olderMessagesPrefix
                val basePrefix = if (prevConv == conversationId) prevPrefix else emptyList()
                val seenIds = HashSet<String>(basePrefix.size + olderUi.size)
                val grown = ArrayList<UiMessage>(basePrefix.size + olderUi.size)
                for (m in olderUi) if (seenIds.add(m.id)) grown.add(m)
                for (m in basePrefix) if (seenIds.add(m.id)) grown.add(m)
                olderMessagesPrefix = conversationId to grown
                val mergedMessages = mergeOlderMessages(
                    olderMessages = olderUi,
                    existingMessages = _uiState.value.messages,
                )
                _uiState.value = _uiState.value.copy(
                    messages = mergedMessages.toImmutableList(),
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = olderMessages.size >= MessageRepository.OLDER_MESSAGES_PAGE_SIZE,
                )
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Failed to load older messages", e)
                if (conversationId == activeConversationId) {
                    _uiState.value = _uiState.value.copy(isLoadingOlderMessages = false)
                }
            }
        }
    }

    fun sendMessage(text: String) {
        when (_uiState.value.conversationState) {
            ConversationState.Loading -> {
                _uiState.value = _uiState.value.copy(error = "Conversation is still loading")
                return
            }
            is ConversationState.Error -> {
                _uiState.value = _uiState.value.copy(error = "Retry conversation loading before sending a message")
                return
            }
            ConversationState.NoConversation,
            is ConversationState.Ready,
            -> Unit
        }

        val attachments = _uiState.value.pendingAttachments.toList()
        if (text.isBlank() && attachments.isEmpty()) return

        val isClientMode = shouldUseClientModeForCurrentRoute
        Telemetry.event(
            "AdminChatVM", "sendMessage.route",
            "via" to if (isClientMode) "client_mode" else "timeline",
            "length" to text.length,
            "attachments" to attachments.size,
        )
        // letta-mobile-flk.6 debug: log the route + freshness predicates so
        // we can correlate device taps with the lettabot gateway's
        // Auto-resuming vs Forced-new outcome. Remove once verified.
        android.util.Log.i(
            "AdminChatViewModel",
            "flk6.sendMessage agent=$agentId via=${if (isClientMode) "client_mode" else "timeline"} " +
                "isFreshRoute=$isFreshRoute explicitConv=$explicitConversationId " +
                "clientModeConv=${currentClientModeConversationId()} " +
                "active=$activeConversationId",
        )
        if (isClientMode) {
            if (attachments.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    composerError = "Client Mode attachments are not supported yet",
                )
                return
            }
            sendMessageViaClientMode(text)
            return
        }
        sendMessageViaTimeline(text, attachments)
    }

    private fun sendMessageViaClientMode(text: String) {
        clientModeStreamJob?.cancel()
        // letta-mobile-flk2: drain any leftover smoother from a prior
        // stream synchronously before starting a new one. flushAndStop
        // is idempotent and safe to call on a never-started smoother
        // (the paint job won't be active).
        clientModeSmoother?.let { stale ->
            viewModelScope.launch { runCatching { stale.flushAndStop() } }
        }
        clientModeSmoother = null
        // letta-mobile-5s1n (regression fix): mark stream in flight BEFORE
        // launching, so observer emissions inside the launch (which run
        // synchronously on UnconfinedTestDispatcher / immediate main) see
        // the flag even before `clientModeStreamJob` is assigned.
        clientModeStreamInFlight = true
        clientModeStreamJob = viewModelScope.launch {
            val startedAt = java.time.Instant.now().toString()
            val userMessageId = "client-user-${System.currentTimeMillis()}"
            val assistantMessageId = "client-assistant-${System.currentTimeMillis()}"
            // letta-mobile-c87t: when entering an existing-conversation route under
            // Client Mode, prefer the route's conversationId arg so the gateway can
            // resumeSession() into the matching Letta conversation. Fall back to
            // the saved-state-handle pointer for cases where Client Mode set up the
            // conversation itself (fresh-route entry continued in-place).
            // letta-mobile-c87t.2: honor the one-shot forceFresh override
            // set by [startFreshConversationAfterUnresumable]. Reading
            // resets it so unrelated subsequent sends use normal resolution.
            val forceFreshNow = forceFreshOnNextClientModeSend
            forceFreshOnNextClientModeSend = false
            val priorConversationId = if (forceFreshNow) null
                else explicitConversationId ?: currentClientModeConversationId()
            // letta-mobile-w2hx.7: freshness is no longer signalled with a
            // dedicated flag. A fresh-route entry surfaces as
            // `priorConversationId == null` and the gateway opens a new
            // Letta conversation in response. The chat row picks up the
            // returned conversationId on the first chunk.
            // letta-mobile-c87t (PR 2): when we already know the
            // conversationId, append the user bubble through the timeline so
            // the SSE-side reconcile + fuzzy matcher (PR 1) can collapse it
            // against the Letta-persisted echo. This activates the dormant
            // CLIENT_MODE_HARNESS source path, gives 8cm8 the telemetry it
            // needs, and removes the user-bubble dual-write that Meridian
            // flagged.
            //
            // Fresh-route sends still use the in-memory path until the
            // gateway returns a conversationId on the first chunk; at that
            // point we migrate the user bubble into the timeline (see below).
            currentConversationTracker.setCurrent(priorConversationId)
            _inputText.value = ""
            if (priorConversationId != null) {
                // Stop any prior observer for a different conversation, then
                // start one for this Client Mode conversation so the timeline
                // becomes the source of truth for the message list.
                val convId = priorConversationId
                if (timelineObserverConversationId != convId) {
                    stopTimelineObserver()
                }
                // letta-mobile-5s1n (regression fix): set the streaming flags
                // BEFORE appending to the timeline. The observer flow re-emits
                // synchronously on every timeline state change and reads
                // `prev.isStreaming` to decide whether to preserve the in-flight
                // signal (see startTimelineObserver). If we wrote `true` after
                // the append, the observer would race ahead with the old
                // `false`, clobber it, and the spinner would never show.
                _uiState.value = _uiState.value.copy(
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = true,
                    isAgentTyping = true,
                    composerError = null,
                    error = null,
                    pendingAttachments = persistentListOf(),
                    conversationState = ConversationState.Ready(convId),
                )
                // Doubled-response fix: claim this conversation BEFORE the
                // first append/observer wires up so TimelineSyncLoop's direct
                // SSE subscriber stops opening a parallel stream that would
                // ingest the same upstream events as Confirmed bubbles
                // alongside our `cm-assist-*` Locals. Idempotent.
                clientModeSuppressionGate.markOwned(convId)
                runCatching {
                    timelineRepository.appendClientModeLocal(
                        conversationId = convId,
                        content = text,
                    )
                }.onFailure { e ->
                    android.util.Log.w(
                        "AdminChatViewModel",
                        "appendClientModeLocal failed; falling back to in-memory bubble",
                        e,
                    )
                    // Fall back to the in-memory path so the user still sees
                    // their bubble even if the timeline append fails.
                    val fallback = clientModeMessages + UiMessage(
                        id = userMessageId,
                        role = "user",
                        content = text,
                        timestamp = startedAt,
                    )
                    clientModeMessages = fallback
                    _uiState.value = _uiState.value.copy(messages = fallback.toImmutableList())
                }
                // Ensure observer is running so subsequent timeline state
                // emissions (and the SSE-driven Confirmed echoes) reach the
                // UI. Idempotent for the same conversationId.
                startTimelineObserver(convId)
            } else {
                // Fresh-route: no conversationId yet; keep the in-memory path
                // until the gateway echoes one, then migrate.
                stopTimelineObserver()
                val baseMessages = clientModeMessages
                val nextMessages = baseMessages + UiMessage(
                    id = userMessageId,
                    role = "user",
                    content = text,
                    timestamp = startedAt,
                )
                clientModeMessages = nextMessages
                _uiState.value = _uiState.value.copy(
                    messages = nextMessages.toImmutableList(),
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = true,
                    isAgentTyping = true,
                    composerError = null,
                    error = null,
                    pendingAttachments = persistentListOf(),
                    conversationState = ConversationState.NoConversation,
                )
            }

            var latestConversationId = priorConversationId
            // letta-mobile-c87t: track whether the gateway substituted a different
            // conversation for the one we requested (recovery path). On the first
            // chunk that carries a non-null conversationId, compare against
            // priorConversationId; if it differs, emit a banner + propagate the
            // new ID to the nav saved-state-handle so a back-then-re-enter doesn't
            // try to resume the dead requested ID again.
            var swapEvaluated = false
            // letta-mobile-hf93: track whether the gateway ever sent any
            // user-visible payload (text, reasoning, or a tool event) for
            // this turn. If the stream terminates with no payload — e.g. a
            // gateway/upstream-agent error that produced only a result
            // frame — we surface an error instead of silently flashing
            // the typing indicator.
            var sawAssistantPayload = false
            // letta-mobile-5s1n: one-shot guard for the fresh-route migration
            // from in-memory clientModeMessages → timeline. Already-known-conv
            // sends skip migration (the bubble was appended to the timeline
            // up-front in the priorConversationId-non-null branch above).
            var migratedToTimeline = priorConversationId != null
            // letta-mobile-flk2: instantiate the paint-rate smoother for
            // this stream. The onPaint callback delegates to the slice
            // painters which run the original timeline upserts, so byte
            // semantics are unchanged. We capture conversationId
            // dynamically (latestConversationId can update mid-stream
            // when the gateway echoes a fresh conv on the first frame).
            val freshSentAt = runCatching {
                java.time.Instant.parse(startedAt)
            }.getOrDefault(java.time.Instant.now())
            clientModeSmoother = ClientModeStreamSmoother(
                scope = viewModelScope,
                onPaint = { kind, lid, slice ->
                    val conv = latestConversationId?.takeIf { it.isNotBlank() }
                        ?: priorConversationId
                        ?: return@ClientModeStreamSmoother
                    when (kind) {
                        ClientModeStreamSmoother.Kind.ASSISTANT ->
                            paintClientModeAssistantSlice(conv, lid, freshSentAt, slice)
                        ClientModeStreamSmoother.Kind.REASONING ->
                            paintClientModeReasoningSlice(conv, lid, freshSentAt, slice)
                    }
                },
            )
            try {
                clientModeChatSender.streamMessage(
                    screenAgentId = agentId,
                    text = text,
                    conversationId = priorConversationId,
                ).collect { chunk ->
                    chunk.conversationId?.takeIf { it.isNotBlank() }?.let { conversationId ->
                        latestConversationId = conversationId
                        if (!swapEvaluated) {
                            swapEvaluated = true
                            // Substitution detected: gateway opened a fresh conversation
                            // because our requested one was unrecoverable. Surface the
                            // banner and update the nav arg so subsequent renavigations
                            // pick up the new ID.
                            if (priorConversationId != null && priorConversationId != conversationId) {
                                _uiState.update { state ->
                                    state.copy(
                                        clientModeConversationSwap = ClientModeConversationSwap.Substituted(
                                            requestedConversationId = priorConversationId,
                                            newConversationId = conversationId,
                                        ),
                                    )
                                }
                                savedStateHandle["conversationId"] = conversationId

                                // letta-mobile-flk.5: when the gateway swaps
                                // conversations mid-stream (recovery from a
                                // stuck/unrecoverable conv — typically
                                // pending requires_approval), the user's
                                // optimistic Local was appended to the OLD
                                // conv's timeline (or sits in the legacy
                                // clientModeMessages list if this was a
                                // fresh-route turn). The assistant chunks
                                // about to flow on this same stream belong
                                // to the NEW conv on the Letta server. If
                                // we leave the observer pointed at the OLD
                                // conv we'll keep writing assistant Locals
                                // there and the user sees nothing in the
                                // conv they're now navigated to.
                                //
                                // Migrate the user bubble to the new conv,
                                // discard any in-memory bubbles (the new
                                // conv's timeline is now the authority),
                                // and re-point the observer. Subsequent
                                // chunks already use latestConversationId
                                // for their write target via
                                // handleClientModeStreamChunk.
                                runCatching {
                                    // Doubled-response fix: gateway swap means
                                    // Client Mode now owns the NEW conversation.
                                    // Mark it before appending so the direct-SSE
                                    // subscriber for that conv stays dormant.
                                    // We intentionally DO NOT release the old
                                    // priorConversationId — the gateway abandoned
                                    // it and we don't want a sudden direct-SSE
                                    // open to flood the user's now-stale chat
                                    // before they navigate.
                                    clientModeSuppressionGate.markOwned(conversationId)
                                    timelineRepository.appendClientModeLocal(
                                        conversationId = conversationId,
                                        content = text,
                                    )
                                    clientModeMessages = clientModeMessages
                                        .filterNot {
                                            it.id == userMessageId ||
                                                it.id == assistantMessageId
                                        }
                                    setClientModeConversationId(conversationId)
                                    currentConversationTracker.setCurrent(conversationId)
                                    startTimelineObserver(conversationId)
                                    // Mark the legacy fresh-route migration
                                    // as already done so the block below
                                    // (which assumes priorConversationId ==
                                    // null) doesn't double-migrate.
                                    migratedToTimeline = true
                                }.onFailure { e ->
                                    android.util.Log.w(
                                        "AdminChatViewModel",
                                        "Conversation swap migration to new conv timeline failed; staying on old conv",
                                        e,
                                    )
                                }
                            }
                        }
                    }
                    if (!latestConversationId.isNullOrBlank()) {
                        setClientModeConversationId(latestConversationId)
                        currentConversationTracker.setCurrent(latestConversationId)
                        // letta-mobile-5s1n: fresh-route migration. The first
                        // time the gateway echoes a conversationId for a fresh
                        // send, we move the optimistic user bubble into the
                        // timeline (where assistant streaming will also write,
                        // via handleClientModeStreamChunkViaTimeline) and start
                        // the timeline observer so the UI sees Local + assistant
                        // content uniformly. Idempotent — guarded by
                        // `migratedToTimeline`.
                        if (priorConversationId == null && !migratedToTimeline) {
                            migratedToTimeline = true
                            val newConvId = latestConversationId
                            if (newConvId != null) {
                                // Doubled-response fix: fresh-route migration
                                // — the gateway has now decided which Letta
                                // conversation owns this turn. Claim it
                                // before the first timeline write so the
                                // direct-SSE subscriber stays dormant.
                                clientModeSuppressionGate.markOwned(newConvId)
                                runCatching {
                                    timelineRepository.appendClientModeLocal(
                                        conversationId = newConvId,
                                        content = text,
                                    )
                                    // letta-mobile-flk.4: carry over any
                                    // assistant content that the legacy
                                    // in-memory path already accumulated
                                    // BEFORE the gateway echoed conversationId.
                                    // Without this, chunk #1's assistant
                                    // delta lives only in clientModeMessages
                                    // (now invisible because the observer
                                    // takes over), and the timeline-path
                                    // chunk #2 seeds a fresh assistant Local
                                    // with only chunk #2's delta — visibly
                                    // truncating the assistant reply by
                                    // the leading characters and breaking
                                    // markdown rendering when those leading
                                    // chars contained markdown openers
                                    // (`**`, `# `, ``` ``` `, etc.).
                                    //
                                    // We pre-seed the timeline assistant
                                    // Local at the SAME localId the
                                    // timeline path will use
                                    // (`cm-assist-$assistantMessageId`)
                                    // so the next chunk's transform sees
                                    // the prior content and appends/merges
                                    // correctly via the existing snapshot
                                    // heuristics.
                                    val carryover = clientModeMessages
                                        .firstOrNull { it.id == assistantMessageId }
                                    val carryoverContent = carryover?.content
                                        ?.takeIf { it.isNotEmpty() }
                                    if (carryoverContent != null) {
                                        val carryoverLocalId =
                                            "cm-assist-$assistantMessageId"
                                        val carryoverSentAt = runCatching {
                                            java.time.Instant.parse(startedAt)
                                        }.getOrDefault(java.time.Instant.now())
                                        timelineRepository
                                            .upsertClientModeLocalAssistantChunk(
                                                conversationId = newConvId,
                                                localId = carryoverLocalId,
                                                build = {
                                                    com.letta.mobile.data
                                                        .timeline.TimelineEvent
                                                        .Local(
                                                            position = 0.0,
                                                            otid = carryoverLocalId,
                                                            content = carryoverContent,
                                                            role = com.letta
                                                                .mobile.data.timeline
                                                                .Role.ASSISTANT,
                                                            sentAt = carryoverSentAt,
                                                            deliveryState = com.letta
                                                                .mobile.data.timeline
                                                                .DeliveryState.SENT,
                                                            source = com.letta
                                                                .mobile.data.timeline
                                                                .MessageSource
                                                                .CLIENT_MODE_HARNESS,
                                                            messageType = com.letta
                                                                .mobile.data.timeline
                                                                .TimelineMessageType
                                                                .ASSISTANT,
                                                        )
                                                },
                                                transform = { existing ->
                                                    // letta-mobile (lettabot-uww.11):
                                                    // idempotent migration
                                                    // carryover. This is NOT
                                                    // a stream-delta path —
                                                    // both strings are the
                                                    // same accumulated bubble
                                                    // under retries, so equal
                                                    // ⇒ keep, otherwise prefer
                                                    // the longer of the two.
                                                    // No append/concatenate
                                                    // path — that would
                                                    // double the bubble on
                                                    // retry.
                                                    val merged =
                                                        if (carryoverContent.length >=
                                                            existing.content.length
                                                        ) carryoverContent
                                                        else existing.content
                                                    existing.copy(content = merged)
                                                },
                                            )
                                    }
                                    // Drop the in-memory bubbles; the
                                    // observer will render the user bubble
                                    // and (if carried over) assistant
                                    // bubble as Locals.
                                    clientModeMessages = clientModeMessages
                                        .filterNot {
                                            it.id == userMessageId ||
                                                it.id == assistantMessageId
                                        }
                                    startTimelineObserver(newConvId)
                                }.onFailure { e ->
                                    android.util.Log.w(
                                        "AdminChatViewModel",
                                        "Fresh-route migration to timeline failed; staying in-memory",
                                        e,
                                    )
                                }
                            }
                        }
                    }

                    // letta-mobile-hf93: count any payload-bearing chunk —
                    // text, reasoning event, tool call/result — as an
                    // assistant payload. Empty terminal frames don't count.
                    if (chunkCarriesAssistantPayload(chunk)) {
                        sawAssistantPayload = true
                    }

                    if (!chunk.done) {
                        handleClientModeStreamChunk(
                            chunk = chunk,
                            assistantMessageId = assistantMessageId,
                            timestamp = startedAt,
                            conversationId = latestConversationId?.takeIf { it.isNotBlank() },
                        )
                        return@collect
                    }

                    // letta-mobile-flk2 (revised): the gateway may ship
                    // the last text/reasoning delta IN THE SAME FRAME
                    // as `done=true`. Feed that final delta through the
                    // smoother FIRST so it lands in arrival order, then
                    // mark complete and await the tail. Tool-call /
                    // tool-result terminal frames are snapshot-shaped
                    // and go through the legacy chunk handler (which
                    // upserts directly, bypassing the smoother).
                    val isToolFrame = chunk.event == BotStreamEvent.TOOL_CALL ||
                        chunk.event == BotStreamEvent.TOOL_RESULT
                    if (isToolFrame) {
                        handleClientModeStreamChunk(
                            chunk = chunk,
                            assistantMessageId = assistantMessageId,
                            timestamp = startedAt,
                            conversationId = latestConversationId?.takeIf { it.isNotBlank() },
                        )
                    } else if (!chunk.text.isNullOrEmpty() ||
                        chunk.event == BotStreamEvent.REASONING) {
                        // Same path as the !chunk.done branch above,
                        // routing through the smoother. This MUST run
                        // before markStreamComplete so the last delta
                        // is in the buffer when the tail accelerates.
                        handleClientModeStreamChunk(
                            chunk = chunk,
                            assistantMessageId = assistantMessageId,
                            timestamp = startedAt,
                            conversationId = latestConversationId?.takeIf { it.isNotBlank() },
                        )
                    }

                    // letta-mobile-flk2: now drain the smoother. The
                    // tail-drain budget (DRAIN_TAIL_MS=120ms) bounds
                    // perceived latency. Watchdog inside awaitDrained
                    // (DRAIN_TIMEOUT_MS=500ms) ensures we never wedge
                    // here even if the tick job died.
                    clientModeSmoother?.markStreamComplete()
                    clientModeSmoother?.awaitDrained()
                    // letta-mobile-flk2: NO replaceAssistant repaint.
                    // The smoother delivered every byte in arrival
                    // order via paintClientModeAssistantSlice /
                    // paintClientModeReasoningSlice, which use the
                    // identical delta-append upsert. A second
                    // "replace" pass over the same content was the
                    // source of the end-of-stream flicker — markdown
                    // re-parses, layout shifts, cursor toggles.

                    // letta-mobile-hf93: a stream that completed without
                    // any payload would otherwise look identical to a
                    // dropped network round-trip — typing indicator
                    // briefly flashes, then nothing. Surface an explicit
                    // error so the user knows the round-trip happened.
                    val terminalError = if (!sawAssistantPayload && !chunk.aborted) {
                        "Agent returned no content. Try again or check the agent's status."
                    } else {
                        null
                    }

                    _uiState.value = _uiState.value.copy(
                        conversationState = latestConversationId?.let { ConversationState.Ready(it) }
                            ?: ConversationState.NoConversation,
                        isStreaming = false,
                        isAgentTyping = false,
                        error = terminalError ?: _uiState.value.error,
                    )
                }
            } catch (e: Exception) {
                // letta-mobile-c87t.2: when the gateway refused to resume the
                // requested conversation (because the underlying letta-code
                // CLI silently allocated a different one and our new
                // server-side guard rejected the substitution), surface an
                // actionable banner instead of a generic error toast. The
                // user's optimistic bubble stays in the prior conversation's
                // timeline (we don't roll it back — it represents a real
                // attempt). The buffered text is stashed on the swap state
                // so [startFreshConversationAfterUnresumable] can resend it
                // verbatim with force_new=true.
                if (
                    e is BotGatewayException &&
                    e.code == BotGatewayErrorCode.CONVERSATION_NOT_RESUMABLE &&
                    priorConversationId != null
                ) {
                    android.util.Log.w(
                        "AdminChatViewModel",
                        "c87t.2 gateway refused to resume conv=${priorConversationId.take(12)}... — surfacing NotResumable banner",
                    )
                    pendingResendAfterUnresumable = PendingClientModeSend(
                        agentId = agentId,
                        text = text,
                        unresumableConversationId = priorConversationId,
                    )
                    _uiState.value = _uiState.value.copy(
                        conversationState = ConversationState.Ready(priorConversationId),
                        clientModeConversationSwap = ClientModeConversationSwap.NotResumable(
                            requestedConversationId = priorConversationId,
                        ),
                        isStreaming = false,
                        isAgentTyping = false,
                        // Don't show a generic error — the actionable banner
                        // tells the user exactly what happened and offers
                        // the start-fresh action.
                        error = null,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        conversationState = latestConversationId?.let { ConversationState.Ready(it) }
                            ?: ConversationState.NoConversation,
                        error = e.message ?: "Client Mode send failed",
                        isStreaming = false,
                        isAgentTyping = false,
                    )
                }
            } finally {
                // letta-mobile-flk2: drain whatever the smoother still
                // holds (success path will already be drained from the
                // done branch; cancel/throw paths still have buffered
                // characters we must not drop). flushAndStop is
                // synchronous w.r.t. onPaint completion.
                clientModeSmoother?.let { sm ->
                    runCatching { sm.flushAndStop() }
                }
                clientModeSmoother = null
                clientModeStreamInFlight = false
                if (clientModeStreamJob?.isCancelled != false) {
                    clientModeStreamJob = null
                }
            }
        }
    }

    private fun handleClientModeStreamChunk(
        chunk: BotStreamChunk,
        assistantMessageId: String,
        timestamp: String,
        replaceAssistant: Boolean = false,
        // letta-mobile-5s1n: when known, all assistant streaming flows through
        // timelineRepository.upsertClientModeLocalAssistantChunk so the
        // timeline is the single source of truth. Pre-conversationId chunks
        // (rare; only the first chunk of a fresh-route send) keep the legacy
        // in-memory path until the gateway echoes a conversationId.
        conversationId: String? = null,
    ) {
        if (conversationId != null) {
            handleClientModeStreamChunkViaTimeline(
                chunk = chunk,
                conversationId = conversationId,
                assistantMessageId = assistantMessageId,
                timestamp = timestamp,
                replaceAssistant = replaceAssistant,
            )
            return
        }
        // letta-mobile-5s1n (Option A): Fresh-route, conversationId not yet
        // known. Keep the in-memory path for this single chunk; the next
        // chunk will carry conversationId (gateway always echoes by chunk #1
        // in practice) and the migration block in sendMessageViaClientMode
        // will move the optimistic state into the timeline. This branch is a
        // belt-and-suspenders fallback — if it ever fires in production we
        // want to know about it because it indicates the gateway delayed its
        // conversationId echo, which would suggest the buffer-and-replay
        // alternative (Option B) is needed.
        com.letta.mobile.util.Telemetry.event(
            "AdminChatVM", "clientMode.legacyChunkPath",
            "event" to (chunk.event?.name ?: "null"),
            "hasText" to (chunk.text != null),
            level = com.letta.mobile.util.Telemetry.Level.WARN,
        )
        handleClientModeStreamChunkLegacy(
            chunk = chunk,
            assistantMessageId = assistantMessageId,
            timestamp = timestamp,
            replaceAssistant = replaceAssistant,
        )
    }

    /**
     * letta-mobile-5s1n: route a Client Mode assistant stream chunk through
     * the timeline. Idempotent across repeat chunks for the same logical
     * event (same localId), so calling repeatedly grows a single bubble
     * rather than appending duplicates.
     */
    private fun handleClientModeStreamChunkViaTimeline(
        chunk: BotStreamChunk,
        conversationId: String,
        assistantMessageId: String,
        timestamp: String,
        replaceAssistant: Boolean,
    ) {
        val sentAt = runCatching { java.time.Instant.parse(timestamp) }
            .getOrDefault(java.time.Instant.now())
        when (chunk.event) {
            BotStreamEvent.REASONING -> {
                val localId = "cm-reason-${chunk.uuid ?: assistantMessageId}"
                // letta-mobile-flk2: enqueue into the paint-rate smoother
                // instead of upserting directly. The smoother drains the
                // queue at a smoothed cadence (~90-120 cps adaptive) and
                // calls back into [paintClientModeReasoningSlice] which
                // runs the original upsert. Concatenation order and
                // total bytes are preserved (FIFO per channel) so the
                // lettabot-uww.11 byte-perfect reassembly invariants
                // still hold; the smoother only changes WHEN bytes hit
                // the timeline, never WHICH bytes or in what order.
                val delta = chunk.text.orEmpty()
                if (delta.isEmpty()) return
                val smoother = clientModeSmoother
                if (smoother != null) {
                    // letta-mobile-flk2 (revision): enqueue is now
                    // synchronous (JVM monitor lock), so we no longer
                    // launch a coroutine. This eliminates the race
                    // where markStreamComplete() ran before the
                    // launched enqueue acquired its lock — which
                    // caused the typing indicator to stay stuck on at
                    // end-of-stream.
                    smoother.enqueue(
                        kind = ClientModeStreamSmoother.Kind.REASONING,
                        localId = localId,
                        delta = delta,
                    )
                } else {
                    // Defensive fallback: smoother should always be
                    // active during a stream, but if it isn't, paint
                    // synchronously so we never drop characters.
                    viewModelScope.launch {
                        paintClientModeReasoningSlice(
                            conversationId = conversationId,
                            localId = localId,
                            sentAt = sentAt,
                            slice = delta,
                        )
                    }
                }
            }

            BotStreamEvent.TOOL_CALL,
            BotStreamEvent.TOOL_RESULT,
            -> {
                // letta-mobile-lv3e (audit): wire contract for tool_call /
                // tool_result is SNAPSHOT-shaped (NOT delta), unlike the
                // assistant/reasoning text streams. The lettabot WS gateway
                // (ws-gateway.ts:330-370) accumulates tool_call argument
                // deltas server-side and flushes ONE complete tool_call
                // event with fully-merged `toolInput` per tool invocation.
                // tool_result is similarly emitted as a single frame with
                // the complete `content` payload.
                //
                // Replace-not-append semantics here are correct given that
                // contract: each frame fully describes the tool call. If
                // the gateway ever changes to stream tool_input deltas
                // directly (without server-side accumulation), this
                // transform would silently keep only the LAST non-blank
                // toolInput — same shape as the vu6a bug. The
                // `arguments.ifBlank { existing }` fallback below preserves
                // prior args if a follow-up frame omits them, which is
                // the only documented variation.
                val toolCallId = chunk.toolCallId ?: chunk.uuid ?: return
                val localId = "cm-tool-$toolCallId"
                val toolName = chunk.toolName ?: "tool"
                val arguments = chunk.toolInput?.toString().orEmpty()
                val isResult = chunk.event == BotStreamEvent.TOOL_RESULT
                val resultText: String? = if (isResult) chunk.text else null
                val resultIsError: Boolean = isResult && chunk.isError
                viewModelScope.launch {
                    runCatching {
                        timelineRepository.upsertClientModeLocalAssistantChunk(
                            conversationId = conversationId,
                            localId = localId,
                            build = {
                                com.letta.mobile.data.timeline.TimelineEvent.Local(
                                    position = 0.0,
                                    otid = localId,
                                    content = "",
                                    role = com.letta.mobile.data.timeline.Role.ASSISTANT,
                                    sentAt = sentAt,
                                    deliveryState = com.letta.mobile.data.timeline.DeliveryState.SENT,
                                    source = com.letta.mobile.data.timeline.MessageSource.CLIENT_MODE_HARNESS,
                                    messageType = com.letta.mobile.data.timeline.TimelineMessageType.TOOL_CALL,
                                    toolCalls = listOf(
                                        com.letta.mobile.data.model.ToolCall(
                                            id = toolCallId,
                                            name = toolName,
                                            arguments = arguments,
                                        ),
                                    ),
                                    toolReturnContent = resultText,
                                    toolReturnIsError = resultIsError,
                                )
                            },
                            transform = { existing ->
                                val existingTool = existing.toolCalls.firstOrNull()
                                val mergedTool = com.letta.mobile.data.model.ToolCall(
                                    id = existingTool?.id ?: toolCallId,
                                    name = existingTool?.name ?: toolName,
                                    arguments = arguments.ifBlank { existingTool?.arguments.orEmpty() },
                                )
                                existing.copy(
                                    messageType = com.letta.mobile.data.timeline.TimelineMessageType.TOOL_CALL,
                                    toolCalls = listOf(mergedTool),
                                    toolReturnContent = resultText ?: existing.toolReturnContent,
                                    toolReturnIsError = if (isResult) resultIsError else existing.toolReturnIsError,
                                )
                            },
                        )
                    }.onFailure { logTimelineUpsertFailure(it, "TOOL", localId) }
                }
            }

            else -> {
                // letta-mobile-flk2: enqueue assistant deltas into the
                // paint-rate smoother instead of upserting directly. The
                // smoother drains the queue at smoothed cadence and calls
                // back into [paintClientModeAssistantSlice]. See class
                // doc on [ClientModeStreamSmoother] for invariant
                // analysis vs. the lettabot-uww.11 byte-perfect
                // reassembly suite (concatenation order + total bytes
                // both preserved).
                val delta = chunk.text?.takeIf { it.isNotEmpty() } ?: return
                val localId = "cm-assist-$assistantMessageId"
                val smoother = clientModeSmoother
                if (smoother != null) {
                    // letta-mobile-flk2 (revision): enqueue is now
                    // synchronous (JVM monitor lock); see the
                    // REASONING site above for the rationale.
                    smoother.enqueue(
                        kind = ClientModeStreamSmoother.Kind.ASSISTANT,
                        localId = localId,
                        delta = delta,
                    )
                } else {
                    viewModelScope.launch {
                        paintClientModeAssistantSlice(
                            conversationId = conversationId,
                            localId = localId,
                            sentAt = sentAt,
                            slice = delta,
                        )
                    }
                }
            }
        }
    }

    /**
     * letta-mobile-flk2: paints a slice of assistant text into the
     * Client Mode timeline bubble. Called by the smoother's onPaint
     * callback (and by the defensive fallback if no smoother is
     * active). Concatenates the slice onto the existing bubble — same
     * contract as the original delta-append upsert that this method
     * extracted.
     */
    private suspend fun paintClientModeAssistantSlice(
        conversationId: String,
        localId: String,
        sentAt: java.time.Instant,
        slice: String,
    ) {
        if (slice.isEmpty()) return
        runCatching {
            timelineRepository.upsertClientModeLocalAssistantChunk(
                conversationId = conversationId,
                localId = localId,
                build = {
                    com.letta.mobile.data.timeline.TimelineEvent.Local(
                        position = 0.0,
                        otid = localId,
                        content = slice,
                        role = com.letta.mobile.data.timeline.Role.ASSISTANT,
                        sentAt = sentAt,
                        deliveryState = com.letta.mobile.data.timeline.DeliveryState.SENT,
                        source = com.letta.mobile.data.timeline.MessageSource.CLIENT_MODE_HARNESS,
                        messageType = com.letta.mobile.data.timeline.TimelineMessageType.ASSISTANT,
                    )
                },
                transform = { existing ->
                    // The contract is delta-append. The smoother
                    // preserves FIFO order and total bytes, so this
                    // remains byte-perfect with the lettabot-uww.11
                    // reassembly invariants.
                    existing.copy(
                        content = existing.content + slice,
                        messageType = com.letta.mobile.data.timeline.TimelineMessageType.ASSISTANT,
                    )
                },
            )
        }.onFailure { logTimelineUpsertFailure(it, "ASSISTANT", localId) }
    }

    /**
     * letta-mobile-flk2: paints a slice of reasoning text into the
     * Client Mode timeline bubble. Called by the smoother's onPaint
     * callback. See [paintClientModeAssistantSlice] for invariant notes.
     */
    private suspend fun paintClientModeReasoningSlice(
        conversationId: String,
        localId: String,
        sentAt: java.time.Instant,
        slice: String,
    ) {
        if (slice.isEmpty()) return
        runCatching {
            timelineRepository.upsertClientModeLocalAssistantChunk(
                conversationId = conversationId,
                localId = localId,
                build = {
                    com.letta.mobile.data.timeline.TimelineEvent.Local(
                        position = 0.0,
                        otid = localId,
                        content = "",
                        role = com.letta.mobile.data.timeline.Role.ASSISTANT,
                        sentAt = sentAt,
                        deliveryState = com.letta.mobile.data.timeline.DeliveryState.SENT,
                        source = com.letta.mobile.data.timeline.MessageSource.CLIENT_MODE_HARNESS,
                        messageType = com.letta.mobile.data.timeline.TimelineMessageType.REASONING,
                        reasoningContent = slice,
                    )
                },
                transform = { existing ->
                    val prior = existing.reasoningContent.orEmpty()
                    existing.copy(
                        reasoningContent = prior + slice,
                        messageType = com.letta.mobile.data.timeline.TimelineMessageType.REASONING,
                    )
                },
            )
        }.onFailure { logTimelineUpsertFailure(it, "REASONING", localId) }
    }

    /**
     * letta-mobile-flk2: paint-rate smoother for Client Mode streaming.
     *
     * The lettabot WS gateway forwards SDK deltas at network-frame
     * cadence — a single WS frame can carry 5–40 chars, which the
     * timeline upsert paints atomically. The user sees the bubble grow
     * in visible "chunks" rather than typing smoothly, and at
     * `done=true` a redundant repaint flickers the bubble.
     *
     * This class is a pure pacing layer:
     *   - per-(kind, localId) FIFO character queues
     *   - one render-tick coroutine drains all queues at a smoothed
     *     velocity tracking the moving-average input rate
     *   - tail drains within ~120ms of `markStreamComplete()`
     *   - cancel flushes everything synchronously so no characters
     *     are ever dropped
     *
     * Concatenation order is preserved (FIFO per channel) and total
     * bytes are preserved, so this is invariant-safe with respect to
     * the lettabot-uww.11 byte-perfect reassembly tests — the smoother
     * only changes WHEN bytes are flushed to the timeline, never WHICH
     * bytes or in what order.
     */
    internal class ClientModeStreamSmoother(
        private val scope: CoroutineScope,
        private val onPaint: suspend (kind: Kind, localId: String, slice: String) -> Unit,
        private val clock: () -> Long = { System.nanoTime() },
        private val tickMillis: Long = TICK_MS_DEFAULT,
        // Suspends used by the tick loop. Tests pass `delay` from the
        // TestDispatcher so virtual time controls pacing.
        private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ) {
        enum class Kind { ASSISTANT, REASONING }

        private data class Channel(
            val kind: Kind,
            val localId: String,
            val pending: StringBuilder = StringBuilder(),
        )

        private data class ArrivalSample(val nanos: Long, val chars: Int)

        // letta-mobile-flk2 (revision): switched from a coroutine
        // Mutex to a JVM monitor so enqueue/markStreamComplete are
        // both synchronous and non-suspending. The previous design had
        // a race: callers fire-and-forget `viewModelScope.launch {
        // smoother.enqueue(...) }` then synchronously call
        // `markStreamComplete()`; with a suspending mutex, the
        // launched coroutine had not yet acquired the lock, so
        // `markStreamComplete` saw empty channels and completed
        // `drained` before the terminal delta was queued. The typing
        // indicator then briefly cleared, then the late paint
        // re-emitted through the timeline observer with the stream
        // still in flight, leaving the indicator visually stuck.
        // Synchronous critical sections eliminate that race.
        private val lock = Any()
        private val channels = linkedMapOf<String, Channel>()
        // Last 500ms of arrivals across all channels — used to compute
        // the moving-average input velocity.
        private val arrivals = ArrayDeque<ArrivalSample>()
        @Volatile private var done: Boolean = false
        private var doneAtNanos: Long = 0L
        @Volatile private var paintJob: Job? = null
        private val drained = kotlinx.coroutines.CompletableDeferred<Unit>()

        fun enqueue(kind: Kind, localId: String, delta: String) {
            if (delta.isEmpty()) return
            synchronized(lock) {
                val key = "${kind.name}:$localId"
                val ch = channels.getOrPut(key) { Channel(kind, localId) }
                ch.pending.append(delta)
                arrivals.addLast(ArrivalSample(clock(), delta.length))
                pruneArrivalsLocked()
            }
            ensurePaintJobStarted()
        }

        fun markStreamComplete() {
            val nothingPending = synchronized(lock) {
                done = true
                doneAtNanos = clock()
                channels.values.all { it.pending.isEmpty() }
            }
            // letta-mobile-flk2: if nothing was ever enqueued, the paint
            // job was never started — there's no draining to do, so
            // signal completion immediately. Otherwise ensurePaintJob
            // will let the existing tick loop see done==true on its
            // next iteration and self-terminate.
            if (nothingPending) {
                if (!drained.isCompleted) drained.complete(Unit)
                return
            }
            ensurePaintJobStarted()
        }

        /**
         * Suspends until every queued character has been flushed via
         * [onPaint]. Watchdog-bounded by [DRAIN_TIMEOUT_MS] so a tick
         * job bug can never wedge the stream-complete path forever.
         * Returns immediately when the smoother has no pending data
         * (e.g. a stream that emitted only a single terminal frame
         * with no preceding deltas).
         */
        suspend fun awaitDrained() {
            if (drained.isCompleted) return
            // Nothing buffered AND no paint job ever started → no
            // draining to do. Avoid a watchdog wait in this common
            // case (stream-complete arrives before any deltas, or
            // every delta was empty).
            val nothingPending = synchronized(lock) {
                channels.values.all { it.pending.isEmpty() }
            }
            if (nothingPending && paintJob == null) {
                if (!drained.isCompleted) drained.complete(Unit)
                return
            }
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) { drained.await() }
        }

        /**
         * Cancel-path. Drain everything synchronously to the [onPaint]
         * sink, stop the tick job, and complete [awaitDrained] callers.
         * Does NOT respect velocity — on cancel the user wants whatever
         * we have, immediately.
         */
        suspend fun flushAndStop() {
            val toFlush: List<Pair<Channel, String>> = synchronized(lock) {
                val out = channels.values
                    .filter { it.pending.isNotEmpty() }
                    .map { ch ->
                        val slice = ch.pending.toString()
                        ch.pending.setLength(0)
                        ch to slice
                    }
                done = true
                out
            }
            for ((ch, slice) in toFlush) {
                runCatching { onPaint(ch.kind, ch.localId, slice) }
            }
            paintJob?.cancel()
            paintJob = null
            if (!drained.isCompleted) drained.complete(Unit)
        }

        private fun ensurePaintJobStarted() {
            if (paintJob?.isActive == true) return
            paintJob = scope.launch {
                runPaintLoop()
            }
        }

        private suspend fun runPaintLoop() {
            while (true) {
                sleep(tickMillis)
                val finished = tickOnce()
                if (finished) {
                    if (!drained.isCompleted) drained.complete(Unit)
                    return
                }
            }
        }

        /**
         * @return true if the smoother has finished (done && all
         *   channels empty). The tick loop terminates on true.
         */
        private suspend fun tickOnce(): Boolean {
            val (slices, finished) = synchronized(lock) {
                pruneArrivalsLocked()
                val totalPending = channels.values.sumOf { it.pending.length }
                if (totalPending == 0) {
                    emptyList<Triple<Kind, String, String>>() to done
                } else {
                    // Moving-average input velocity over the arrival window.
                    val windowChars = arrivals.sumOf { it.chars }
                    val inputCps = windowChars.toDouble() / (ARRIVAL_WINDOW_MS / 1000.0)

                    // Target: stay just above the input rate so steady-state
                    // doesn't accumulate backlog. Clamped to the cps band.
                    var paintCps = (inputCps * STEADY_STATE_OVER_FACTOR)
                        .coerceIn(FLOOR_CPS, CEILING_CPS)

                    // Tail-drain budget: when done=true, accelerate so the
                    // remaining backlog clears within DRAIN_TAIL_MS.
                    if (done) {
                        val tailCps = totalPending.toDouble() /
                            (DRAIN_TAIL_MS / 1000.0)
                        if (tailCps > paintCps) paintCps = tailCps
                    }

                    // Chars to flush this tick.
                    val budgetChars = (paintCps * tickMillis / 1000.0)
                        .toInt()
                        .coerceAtLeast(1)

                    // Distribute the budget across channels weighted by
                    // their backlog length so reasoning + assistant drain
                    // proportionally and neither starves.
                    val out = mutableListOf<Triple<Kind, String, String>>()
                    var remaining = budgetChars
                    val ordered = channels.values.toList()
                    val totalForShare = totalPending
                    for ((idx, ch) in ordered.withIndex()) {
                        if (ch.pending.isEmpty()) continue
                        val share = if (idx == ordered.lastIndex) {
                            remaining
                        } else {
                            ((budgetChars.toLong() * ch.pending.length) /
                                totalForShare).toInt().coerceAtLeast(1)
                        }
                        val take = minOf(share, ch.pending.length, remaining)
                        if (take <= 0) continue
                        val slice = ch.pending.substring(0, take)
                        ch.pending.delete(0, take)
                        remaining -= take
                        out += Triple(ch.kind, ch.localId, slice)
                        if (remaining <= 0) break
                    }

                    val nowFinished = done && channels.values.all { it.pending.isEmpty() }
                    out to nowFinished
                }
            }

            for ((kind, localId, slice) in slices) {
                runCatching { onPaint(kind, localId, slice) }
            }
            return finished
        }

        private fun pruneArrivalsLocked() {
            val cutoff = clock() - ARRIVAL_WINDOW_MS * 1_000_000L
            while (arrivals.isNotEmpty() && arrivals.first().nanos < cutoff) {
                arrivals.removeFirst()
            }
        }

        companion object {
            const val TICK_MS_DEFAULT: Long = 16L                  // ~60Hz paint
            const val ARRIVAL_WINDOW_MS: Long = 500L
            const val DRAIN_TAIL_MS: Long = 120L
            const val DRAIN_TIMEOUT_MS: Long = 500L                // watchdog
            const val FLOOR_CPS: Double = 30.0
            const val CEILING_CPS: Double = 180.0
            const val STEADY_STATE_OVER_FACTOR: Double = 1.05
        }
    }

    /**
     * letta-mobile-hf93: a chunk "carries assistant payload" when it has
     * any user-visible content — non-empty text, a tool call/result, or
     * (eventually) a non-empty reasoning frame. The terminal `done=true`
     * frame on its own does NOT count, since WsBotClient emits one for
     * every stream regardless of whether the upstream agent produced
     * output.
     */
    private fun chunkCarriesAssistantPayload(chunk: BotStreamChunk): Boolean {
        if (!chunk.text.isNullOrEmpty()) return true
        return when (chunk.event) {
            BotStreamEvent.TOOL_CALL,
            BotStreamEvent.TOOL_RESULT,
            BotStreamEvent.REASONING -> true
            // letta-mobile-flk.5: CONVERSATION_SWAP is a control-plane
            // signal, not user-visible content — must not gate the
            // hf93 "no-payload" terminal-error path.
            BotStreamEvent.CONVERSATION_SWAP,
            BotStreamEvent.ASSISTANT, null -> false
        }
    }

    private fun logTimelineUpsertFailure(t: Throwable, kind: String, localId: String) {
        android.util.Log.w(
            "AdminChatViewModel",
            "Client Mode timeline upsert failed (kind=$kind, localId=$localId)",
            t,
        )
    }

    /**
     * Pre-conversationId fallback path. Used only for chunks that arrive
     * before the gateway echoes a conversationId (typically only the very
     * first chunk of a fresh-route send). Once a conversationId is known
     * the rest of the stream flows through the timeline.
     */
    private fun handleClientModeStreamChunkLegacy(
        chunk: BotStreamChunk,
        assistantMessageId: String,
        timestamp: String,
        replaceAssistant: Boolean,
    ) {
        when (chunk.event) {
            BotStreamEvent.REASONING -> {
                val messageId = chunk.uuid ?: "client-reasoning-${System.currentTimeMillis()}"
                // letta-mobile-lv3e (REVERTS letta-mobile-vu6a): legacy
                // path also receives DELTAS — append, not replace. Same
                // defensive snapshot-shape guard as the timeline path.
                val delta = chunk.text.orEmpty()
                upsertClientModeMessage(
                    messageId = messageId,
                    timestamp = timestamp,
                ) { existing ->
                    val prior = existing?.content.orEmpty()
                    // letta-mobile (lettabot-uww.11 fix): reasoning text is
                    // emitted as PURE DELTAS, same contract as assistant
                    // text. The previous prefix-collision guard silently
                    // dropped chars; trust the contract and append.
                    val merged = if (delta.isEmpty()) prior else prior + delta
                    (existing ?: UiMessage(
                        id = messageId,
                        role = "assistant",
                        content = "",
                        timestamp = timestamp,
                        isReasoning = true,
                    )).copy(
                        content = merged,
                        isReasoning = true,
                    )
                }
            }

            BotStreamEvent.TOOL_CALL,
            BotStreamEvent.TOOL_RESULT,
            -> {
                val toolCallId = chunk.toolCallId ?: chunk.uuid ?: return
                val messageId = "client-tool-$toolCallId"
                val toolName = chunk.toolName ?: "tool"
                val arguments = chunk.toolInput?.toString().orEmpty()
                upsertClientModeMessage(
                    messageId = messageId,
                    timestamp = timestamp,
                ) { existing ->
                    val existingTool = existing?.toolCalls?.firstOrNull()
                    val result = when (chunk.event) {
                        BotStreamEvent.TOOL_RESULT -> chunk.text ?: existingTool?.result
                        else -> existingTool?.result
                    }
                    val status = when (chunk.event) {
                        BotStreamEvent.TOOL_RESULT -> if (chunk.isError) "error" else "success"
                        else -> existingTool?.status
                    }
                    UiMessage(
                        id = messageId,
                        role = "assistant",
                        content = "",
                        timestamp = existing?.timestamp ?: timestamp,
                        toolCalls = listOf(
                            UiToolCall(
                                name = toolName,
                                arguments = arguments.ifBlank { existingTool?.arguments.orEmpty() },
                                result = result,
                                status = status,
                            )
                        ),
                    )
                }
            }

            else -> {
                // letta-mobile-lv3e (REVERTS letta-mobile-vu6a): legacy
                // path receives DELTAS, not snapshots. Append each new
                // fragment to the bubble. The defensive snapshot-shape
                // guard inside upsertClientModeAssistantMessage handles
                // any future protocol change.
                chunk.text?.takeIf { it.isNotEmpty() }?.let { delta ->
                    upsertClientModeAssistantMessage(
                        messageId = assistantMessageId,
                        timestamp = timestamp,
                        content = delta,
                        append = true,
                    )
                }
            }
        }
    }

    private fun upsertClientModeAssistantMessage(
        messageId: String,
        timestamp: String,
        content: String,
        append: Boolean,
    ) {
        upsertClientModeMessage(
            messageId = messageId,
            timestamp = timestamp,
        ) { existing ->
            if (existing != null) {
                // letta-mobile (lettabot-uww.11 fix): when append=true,
                // the WS gateway emits assistant text as PURE DELTAS.
                // Verified by ws-gateway.e2e.test.ts § "assistant text
                // reassembly" (37 byte-perfect reassembly cases).
                //
                // The previous wucn-snapshot-recovery cascade had two
                // defects that silently corrupted user-visible text:
                //   - `existing.content.startsWith(content)` dropped
                //     any delta whose head matched a prefix of the
                //     accumulator (frequent for repeated tokens /
                //     coalescer boundaries).
                //   - the >=32-char "near-snapshot" branch replaced
                //     the accumulator wholesale, destroying everything
                //     before the incoming delta.
                // Together they produced the field repro
                // `A[LLM snapshots]` → `A[LLMapshots|`.
                //
                // The contract is delta-append. Trust it.
                val merged = if (append) {
                    if (content.isEmpty()) existing.content else existing.content + content
                } else content
                existing.copy(content = merged)
            } else {
                UiMessage(
                    id = messageId,
                    role = "assistant",
                    content = content,
                    timestamp = timestamp,
                )
            }
        }
    }

    private fun upsertClientModeMessage(
        messageId: String,
        timestamp: String,
        build: (UiMessage?) -> UiMessage,
    ) {
        val currentMessages = clientModeMessages.toMutableList()
        val index = currentMessages.indexOfFirst { it.id == messageId }
        val existing = currentMessages.getOrNull(index)
        val next = build(existing)
        if (index >= 0) {
            currentMessages[index] = next.copy(timestamp = existing?.timestamp ?: timestamp)
        } else {
            currentMessages += next.copy(timestamp = next.timestamp.ifBlank { timestamp })
        }
        clientModeMessages = currentMessages
        if (clientModeEnabled.value) {
            _uiState.value = _uiState.value.copy(messages = currentMessages.toImmutableList())
        }
    }

    private fun currentClientModeConversationId(): String? =
        savedStateHandle.get<String>(CLIENT_MODE_CONVERSATION_ID_KEY)?.takeIf { it.isNotBlank() }

    private fun setClientModeConversationId(conversationId: String?) {
        savedStateHandle[CLIENT_MODE_CONVERSATION_ID_KEY] = conversationId?.takeIf { it.isNotBlank() }
    }

    private fun stopTimelineObserver() {
        timelineObserverJob?.cancel()
        timelineObserverJob = null
        timelineHydrateSignalJob?.cancel()
        timelineHydrateSignalJob = null
        timelineObserverConversationId = null
    }

    fun reportComposerError(message: String) {
        _uiState.value = _uiState.value.copy(composerError = message)
    }

    fun clearComposerError() {
        if (_uiState.value.composerError == null) return
        _uiState.value = _uiState.value.copy(composerError = null)
    }

    /**
     * Dismiss the conversation-substitution banner emitted when the gateway
     * substituted a fresh conversation for the one we asked it to resume.
     * See `letta-mobile-c87t` and [ClientModeConversationSwap].
     *
     * For [ClientModeConversationSwap.NotResumable] (c87t.2), dismissing
     * also drops the buffered pending-resend so the user is not silently
     * re-sent later. They stay anchored to the unresumable conversation;
     * a subsequent send will retry the same prior id (and likely surface
     * the same banner unless conditions change). To explicitly start a
     * fresh chat, use [startFreshConversationAfterUnresumable].
     */
    fun dismissClientModeConversationSwap() {
        if (_uiState.value.clientModeConversationSwap == null) return
        pendingResendAfterUnresumable = null
        _uiState.update { it.copy(clientModeConversationSwap = null) }
    }

    /**
     * letta-mobile-c87t.2: user accepted the "start a fresh chat" action
     * on the [ClientModeConversationSwap.NotResumable] banner. Clears
     * the unresumable conversation pointer, starts a new client-mode
     * conversation, and re-sends the buffered text under the new conv.
     *
     * If no pending send is buffered (the user dismissed and then asked
     * to start fresh, or this is called from a stale state) we just
     * clear the banner without re-sending — the user can compose a new
     * message manually.
     */
    fun startFreshConversationAfterUnresumable() {
        val swap = _uiState.value.clientModeConversationSwap
        if (swap !is ClientModeConversationSwap.NotResumable) return

        val pending = pendingResendAfterUnresumable
        pendingResendAfterUnresumable = null

        // Clear the unresumable pointer so the next send resolves to
        // a fresh-route flow (priorConversationId == null → gateway
        // opens a brand-new Letta conversation).
        setClientModeConversationId(null)
        savedStateHandle["conversationId"] = null
        currentConversationTracker.setCurrent(null)

        // Drop the banner; the user is about to see a fresh stream.
        _uiState.update { it.copy(clientModeConversationSwap = null) }

        if (pending == null || pending.text.isBlank()) {
            android.util.Log.i(
                "AdminChatViewModel",
                "c87t.2 startFresh: no pending text — banner cleared, user composes manually",
            )
            return
        }

        android.util.Log.i(
            "AdminChatViewModel",
            "c87t.2 startFresh: re-sending ${pending.text.length} chars under fresh conv " +
                "(prior unresumable=${pending.unresumableConversationId.take(12)}...)",
        )
        forceFreshOnNextClientModeSend = true
        sendMessage(pending.text)
    }

    /**
     * Stage a new image attachment in the composer. Enforces [MAX_COMPOSER_ATTACHMENTS]
     * count cap and [MAX_COMPOSER_TOTAL_BYTES] cumulative base64 size cap. Returns
     * true on success; false if a cap was hit (caller should surface the error).
     */
    fun addAttachment(image: com.letta.mobile.data.model.MessageContentPart.Image): Boolean {
        val current = _uiState.value.pendingAttachments
        if (current.size >= MAX_COMPOSER_ATTACHMENTS) {
            Telemetry.event(
                "AdminChatVM",
                "attachment.rejected",
                "reason" to "count_cap",
                "current" to current.size,
                "max" to MAX_COMPOSER_ATTACHMENTS,
            )
            reportComposerError("Attachment limit reached ($MAX_COMPOSER_ATTACHMENTS max).")
            return false
        }
        val newTotal = current.sumOf { it.base64.length } + image.base64.length
        if (newTotal > MAX_COMPOSER_TOTAL_BYTES) {
            Telemetry.event(
                "AdminChatVM",
                "attachment.rejected",
                "reason" to "size_cap",
                "newTotal" to newTotal,
                "max" to MAX_COMPOSER_TOTAL_BYTES,
            )
            reportComposerError("Attachments too large — downscale or remove some before sending.")
            return false
        }
        _uiState.value = _uiState.value.copy(
            pendingAttachments = (current + image).toPersistentList(),
            composerError = null,
        )
        Telemetry.event(
            "AdminChatVM",
            "attachment.added",
            "size" to image.base64.length,
            "mediaType" to image.mediaType,
            "totalCount" to (current.size + 1),
        )
        return true
    }

    /** Remove a staged attachment by index. */
    fun removeAttachment(index: Int) {
        val current = _uiState.value.pendingAttachments
        if (index !in current.indices) return
        _uiState.value = _uiState.value.copy(
            pendingAttachments = (current.toMutableList().also { it.removeAt(index) })
                .toPersistentList(),
        )
        Telemetry.event(
            "AdminChatVM",
            "attachment.removed",
            "index" to index,
        )
    }

    /**
     * Timeline-sync send path. Handles optimistic append, streaming, and
     * reconciliation via [timelineRepository]. The timeline observer
     * ([startTimelineObserver]) mirrors state changes into [_uiState.messages]
     * automatically. Returns immediately after enqueueing the send — all
     * visible state transitions flow through the single sync loop.
     */
    private fun sendMessageViaTimeline(
        text: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image> = emptyList(),
    ) {
        viewModelScope.launch {
            val enqueueTimer = Telemetry.startTimer("AdminChatVM", "send.enqueue")
            _inputText.value = ""
            _uiState.value = _uiState.value.copy(
                isStreaming = true,
                isAgentTyping = true,
                composerError = null,
                // Clear composer attachments optimistically; they're carried on the
                // Local event now so the user bubble still shows them.
                pendingAttachments = persistentListOf(),
            )
            try {
                // letta-mobile-flk.6 / w2hx.6: a fresh-route entry (user
                // tapped "New chat" with no conversationId arg and no in-flight
                // pointer) must NOT inherit a sibling chat's conv id. Pre-w2hx.6
                // we read from a process-wide singleton keyed on agentId; now
                // `activeConversationId` is local to this VM, so a fresh route
                // simply means "no conv yet" and we create a new Letta
                // conversation for this chat.
                var convId: String? = if (isFreshRoute) {
                    explicitConversationId
                } else {
                    explicitConversationId ?: activeConversationId
                }
                if (convId == null) {
                    val summary = text.take(80).let { if (text.length > 80) "$it\u2026" else it }
                    convId = conversationRepository.createConversation(agentId, summary).id
                    activeConversationId = convId
                    hasSummary = true
                    _uiState.value = _uiState.value.copy(
                        conversationState = ConversationState.Ready(convId),
                    )
                } else if (!hasSummary) {
                    runCatching {
                        val summary = text.take(80).let { if (text.length > 80) "$it\u2026" else it }
                        conversationRepository.updateConversation(convId, agentId, summary)
                        hasSummary = true
                    }
                }
                // Ensure observer is attached for this conversation
                startTimelineObserver(convId)
                // Enqueue via the Timeline sync loop — returns immediately after
                // appending the Local event and queuing the HTTP request.
                val otid = if (attachments.isEmpty()) {
                    timelineRepository.sendMessage(convId, text)
                } else {
                    timelineRepository.sendMessage(convId, text, attachments)
                }
                enqueueTimer.stop("otid" to otid, "conversationId" to convId)
                // isStreaming / isAgentTyping will be cleared when we see a Confirmed
                // assistant event land in the timeline (see startTimelineObserver).
            } catch (e: Exception) {
                enqueueTimer.stopError(e)
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    isStreaming = false,
                    isAgentTyping = false,
                )
            }
        }
    }

    /**
     * Subscribe to the [TimelineRepository]'s StateFlow for the given
     * conversation and mirror its events into [_uiState.messages].
     *
     * Idempotent for the SAME conversation — calling repeatedly with the same
     * id while a job is active is a no-op. When the id differs, the previous
     * observer + hydrate-signal job are cancelled and fresh ones are spun up
     * bound to the new conversation.
     *
     * This two-condition guard fixes `letta-mobile-nw2e`: the prior impl only
     * checked `isActive == true` regardless of which conversation the job
     * was bound to, which made switching conversations a silent no-op and
     * left the UI locked onto the first-selected conversation's timeline.
     */
    private fun startTimelineObserver(conversationId: String) {
        if (timelineObserverConversationId == conversationId &&
            timelineObserverJob?.isActive == true
        ) {
            return
        }
        // Conversation switch (or first bind): tear down any in-flight
        // subscriptions from the previous conversation before starting new
        // ones. Without this the hydrate-signal job would leak on every
        // switch (it was a local `val` in the old impl, never cancellable).
        timelineObserverJob?.cancel()
        timelineHydrateSignalJob?.cancel()
        // letta-mobile-23h5 (regression fix 2026-04-19): drop any backfill
        // prefix from the previous conversation so it cannot bleed into the
        // new conversation's history.
        olderMessagesPrefix = "" to emptyList()
        timelineObserverConversationId = conversationId
        timelineObserverJob = viewModelScope.launch {
            val flow = try {
                timelineRepository.observe(conversationId)
            } catch (e: Exception) {
                android.util.Log.e("AdminChatViewModel", "Timeline observe failed", e)
                _uiState.value = _uiState.value.copy(error = "Timeline init failed: ${e.message}")
                return@launch
            }
            // Also subscribe to the loop's sync events so we can definitively
            // clear the loading spinner on Hydrated (even for empty convs).
            val loop = timelineRepository.getOrCreate(conversationId)
            currentConversationTracker.setCurrent(conversationId)
            timelineHydrateSignalJob = viewModelScope.launch {
                loop.events.collect { ev ->
                    when (ev) {
                        is com.letta.mobile.data.timeline.TimelineSyncEvent.Hydrated -> {
                            android.util.Log.i(
                                "AdminChatViewModel",
                                "Timeline ready conv=$conversationId count=${ev.messageCount}",
                            )
                            _uiState.value = _uiState.value.copy(isLoadingMessages = false)
                        }
                        is com.letta.mobile.data.timeline.TimelineSyncEvent.HydrateFailed -> {
                            _uiState.value = _uiState.value.copy(isLoadingMessages = false)
                        }
                        is com.letta.mobile.data.timeline.TimelineSyncEvent.ReconcileError -> {
                            // letta-mobile-j44j: reconcile's GET /messages can
                            // fail after a successful stream (e.g. network blip
                            // after the last SSE frame). TimelineSyncLoop already
                            // retries transient errors internally; by the time
                            // this event reaches us the retry was exhausted.
                            //
                            // The assistant reply has already appeared in the
                            // timeline (Confirmed events arrived during SSE),
                            // so we don't clear messages — we just tell the
                            // user the post-stream sync didn't finish so they
                            // know to pull-to-refresh if the bubble looks off.
                            // Also clear the streaming/typing indicators so the
                            // UI doesn't stay stuck pretending we're still
                            // waiting on the server.
                            _uiState.value = _uiState.value.copy(
                                error = "Couldn't sync agent reply — pull to refresh",
                                isStreaming = false,
                                isAgentTyping = false,
                            )
                        }
                        else -> Unit
                    }
                }
            }

            try {
                flow.collect { timeline ->
                    val live = timeline.events.mapNotNull { it.toUiMessageOrNull() }
                    // Prepend any backfilled older pages for THIS conversation.
                    val (prefixConv, prefixList) = olderMessagesPrefix
                    val prefix = if (prefixConv == conversationId) prefixList else emptyList()
                    val seenIds = HashSet<String>(live.size + prefix.size)
                    val combined = ArrayList<UiMessage>(live.size + prefix.size)
                    for (m in prefix) if (seenIds.add(m.id)) combined.add(m)
                    for (m in live) if (seenIds.add(m.id)) combined.add(m)
                    val ui = combined.toImmutableList()
                    val tailIsAssistant = timeline.events.lastOrNull().let {
                        it is com.letta.mobile.data.timeline.TimelineEvent.Confirmed &&
                            it.messageType == com.letta.mobile.data.timeline.TimelineMessageType.ASSISTANT
                    }
                    // letta-mobile-5s1n (regression fix): derive streaming
                    // flags from LETTA_SERVER Locals only. CLIENT_MODE_HARNESS
                    // Locals are stamped SENT at append (the WS gateway is
                    // the delivery authority — see TimelineSyncLoop.appendClientModeLocal),
                    // so a `SENDING` predicate would always be false for
                    // Client Mode flows and would erroneously clear the
                    // spinner that `sendMessageViaClientMode` set.
                    //
                    // sendMessageViaClientMode owns isStreaming/isAgentTyping
                    // for the Client Mode path end-to-end (sets true on
                    // send, clears in the stream-complete `finally`). We
                    // must NOT overwrite those flags from this observer —
                    // we only contribute the LETTA_SERVER pending signal.
                    val anyLettaServerLocalPending = timeline.events.any {
                        it is com.letta.mobile.data.timeline.TimelineEvent.Local &&
                            it.deliveryState == com.letta.mobile.data.timeline.DeliveryState.SENDING &&
                            it.source != com.letta.mobile.data.timeline.MessageSource.CLIENT_MODE_HARNESS
                    }
                    // If a Client Mode stream is in flight, keep the flags
                    // the send coroutine set; otherwise derive from server
                    // pending (legacy optimistic-send semantics).
                    // Prefer the explicit flag (set BEFORE the launch starts)
                    // over `clientModeStreamJob?.isActive` because the launched
                    // coroutine runs eagerly on Unconfined/main and triggers
                    // observer emissions before the job assignment lands.
                    val streamInFlight = clientModeStreamInFlight
                    // letta-mobile-23h5 (regression fix 2026-04-19): the
                    // timeline source-of-truth path was never flipping
                    // hasMoreOlderMessages to true, so ChatScreen's scroll
                    // detector (`!state.hasMoreOlderMessages → return false`)
                    // never fired loadOlderMessages. Optimistically assume
                    // there's history to fetch any time we have at least one
                    // confirmed message; the first page fetch corrects this
                    // (sets it back to false when fewer than PAGE_SIZE rows
                    // come back).
                    val anyConfirmed = ui.any { !it.isPending }
                    // Optimistically allow the scroll detector to call
                    // loadOlderMessages once we have any confirmed history
                    // — the loader settles the truth (sets back to false
                    // when fewer than PAGE_SIZE rows come back).
                    val newHasMoreOlder = if (anyConfirmed) true
                                          else _uiState.value.hasMoreOlderMessages
                    val prev = _uiState.value
                    val nextIsStreaming = if (streamInFlight) prev.isStreaming
                                          else anyLettaServerLocalPending
                    val nextIsAgentTyping = if (streamInFlight) prev.isAgentTyping
                                            else (anyLettaServerLocalPending && !tailIsAssistant)
                    // Chat flash fix: Do NOT clear isLoadingMessages from the
                    // timeline observer. The Hydrated event (line 2645) is the
                    // sole authority for clearing the loading state. This
                    // prevents the flash where partial messages (e.g., only
                    // user messages) appear before the full hydrated state
                    // arrives, then agent responses "flash in" later.
                    _uiState.value = prev.copy(
                        messages = ui,
                        isStreaming = nextIsStreaming,
                        isAgentTyping = nextIsAgentTyping,
                        hasMoreOlderMessages = newHasMoreOlder,
                    )
                }
            } finally {
                // Tear down the sibling hydrate-signal collector when the
                // main observer terminates (conv switch, scope cancel, etc.).
                // Field-based reference replaces the prior local `val hydrateSignal`
                // so the job is also cancellable from `startTimelineObserver`
                // on conversation switch — see letta-mobile-nw2e.
                timelineHydrateSignalJob?.cancel()
            }
        }
    }

    /** Convert a [TimelineEvent] to a [UiMessage] for display. */
    internal fun com.letta.mobile.data.timeline.TimelineEvent.toUiMessageOrNull(): UiMessage? =
        timelineEventToUiMessage(this)

    fun submitApproval(
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String? = null,
    ) {
        viewModelScope.launch {
            val convId = activeConversationId
            if (convId == null) {
                _uiState.value = _uiState.value.copy(error = "No active conversation available for approval")
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isStreaming = true,
                isAgentTyping = true,
                activeApprovalRequestId = requestId,
            )

            try {
                messageRepository.submitApproval(
                    conversationId = convId,
                    approvalRequestId = requestId,
                    toolCallIds = toolCallIds,
                    approve = approve,
                    reason = reason,
                )
                // Don't reload - approval response will come via streaming
                _uiState.value = _uiState.value.copy(
                    isStreaming = false,
                    isAgentTyping = false,
                    activeApprovalRequestId = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to submit approval",
                    isStreaming = false,
                    isAgentTyping = false,
                    activeApprovalRequestId = null,
                )
            }
        }
    }

    private fun mergeOlderMessages(
        olderMessages: List<UiMessage>,
        existingMessages: List<UiMessage>,
    ): List<UiMessage> {
        if (olderMessages.isEmpty()) return existingMessages

        val existingIds = existingMessages.mapTo(mutableSetOf()) { it.id }
        return olderMessages.filterNot { it.id in existingIds } + existingMessages
    }

    fun resetMessages() {
        if (shouldUseClientModeForCurrentRoute) {
            setClientModeConversationId(null)
            clientModeMessages = emptyList()
            currentConversationTracker.setCurrent(null)
            stopTimelineObserver()
            _uiState.value = _uiState.value.copy(
                conversationState = ConversationState.NoConversation,
                messages = persistentListOf(),
                isLoadingMessages = false,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
                isStreaming = false,
                isAgentTyping = false,
                error = null,
            )
            return
        }
        viewModelScope.launch {
            try {
                val convId = activeConversationId ?: run {
                    _uiState.value = _uiState.value.copy(error = "No active conversation to reset")
                    return@launch
                }
                messageRepository.resetMessages(agentId, convId)
                _uiState.value = _uiState.value.copy(messages = persistentListOf())
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Failed to reset messages", e)
            }
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    val canSendMessages: Boolean
        get() = when (_uiState.value.conversationState) {
            ConversationState.Loading -> false
            is ConversationState.Error -> false
            ConversationState.NoConversation,
            is ConversationState.Ready,
            -> true
        }

    private suspend fun buildProjectAgentActivities(
        project: ProjectChatContext,
    ): List<ProjectAgentActivity> {
        val liveStatuses = runCatching { internalBotClient.listAgents() }
            .getOrDefault(emptyList())
            .associateBy { it.id }

        val folderId = project.lettaFolderId
        if (folderId.isNullOrBlank()) {
            return liveStatuses[agentId]
                ?.let { listOf(it.toProjectAgentActivity(agent = null)) }
                ?: emptyList()
        }

        agentRepository.refreshAgentsIfStale(maxAgeMs = 60_000)
        val folderAgentIds = folderRepository.listAgentsForFolder(folderId)
        val agents = folderAgentIds.mapNotNull { id ->
            agentRepository.getCachedAgent(id)
                ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()
        }

        return agents.map { agent ->
            liveStatuses[agent.id].toProjectAgentActivity(agent)
        }.sortedWith(compareBy<ProjectAgentActivity> { it.statusLabel }.thenBy { it.name.lowercase() })
    }

    private fun BotAgentInfo?.toProjectAgentActivity(agent: Agent?): ProjectAgentActivity {
        val rawStatus = this?.status?.lowercase()
        val (statusLabel, statusTone) = when {
            rawStatus == null && agent?.lastRunCompletion != null -> "Idle" to ProjectAgentStatusTone.Neutral
            rawStatus == null -> "Disconnected" to ProjectAgentStatusTone.Neutral
            rawStatus.contains("error") || rawStatus.contains("fail") -> "Error" to ProjectAgentStatusTone.Error
            rawStatus.contains("working") || rawStatus.contains("running") || rawStatus.contains("busy") -> "Working" to ProjectAgentStatusTone.Busy
            rawStatus.contains("connected") || rawStatus.contains("ready") || rawStatus.contains("idle") -> rawStatus.replaceFirstChar { it.uppercase() } to ProjectAgentStatusTone.Good
            else -> rawStatus.replaceFirstChar { it.uppercase() } to ProjectAgentStatusTone.Neutral
        }

        val metadataWork = agent?.metadata?.get("current_work")?.toString()?.trim('"')
        val detail = metadataWork
            ?: agent?.lastRunCompletion
            ?: if (this != null) "Embedded bot session available" else null

        return ProjectAgentActivity(
            id = agent?.id ?: this?.id.orEmpty(),
            name = agent?.name ?: this?.name.orEmpty(),
            statusLabel = statusLabel,
            statusTone = statusTone,
            detail = detail,
            model = agent?.model,
            lastActivity = agent?.updatedAt ?: agent?.createdAt,
        )
    }

    override fun onCleared() {
        currentConversationTracker.setCurrent(null)
        super.onCleared()
    }

}

private val projectBriefLabelAliases = mapOf(
    ProjectBriefSectionKey.Description to listOf(
        "project_description",
        "project-description",
        "project description",
        "description",
        "brief_description",
    ),
    ProjectBriefSectionKey.KeyDecisions to listOf(
        "key_decisions",
        "key-decisions",
        "key decisions",
        "decisions",
        "project_decisions",
    ),
    ProjectBriefSectionKey.TechStack to listOf(
        "tech_stack",
        "tech-stack",
        "tech stack",
        "stack",
        "technology_stack",
    ),
    ProjectBriefSectionKey.ActiveGoals to listOf(
        "active_goals",
        "active-goals",
        "active goals",
        "goals",
        "current_goals",
    ),
    ProjectBriefSectionKey.RecentChanges to listOf(
        "recent_changes",
        "recent-changes",
        "recent changes",
        "changes",
        "latest_changes",
    ),
)

private fun buildProjectBriefSections(blocks: List<Block>): Map<ProjectBriefSectionKey, ProjectBriefSection> {
    return ProjectBriefSectionKey.entries.mapNotNull { key ->
        val block = blocks.firstOrNull { candidate ->
            val canonical = candidate.label?.canonicalBriefLabel() ?: return@firstOrNull false
            projectBriefLabelAliases.getValue(key).any { alias ->
                canonical == alias.canonicalBriefLabel()
            }
        } ?: return@mapNotNull null

        key to ProjectBriefSection(
            key = key,
            blockLabel = block.label ?: return@mapNotNull null,
            content = block.value,
            updatedAt = block.updatedAt,
        )
    }.toMap()
}

private fun String.canonicalBriefLabel(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

private fun buildBugReportPrompt(draft: ProjectBugReportDraft): String {
    val title = draft.title.trim()
    val description = draft.description.trim()
    val tags = draft.tags.joinToString(", ").ifBlank { "none" }
    val attachments = draft.attachmentReferences.joinToString("\n") { "- $it" }
        .ifBlank { "- none" }

    return buildString {
        appendLine("Bug Report: $title")
        appendLine("Severity: ${draft.severity.wireValue}")
        appendLine("Tags: $tags")
        appendLine("Description:")
        appendLine(description)
        appendLine()
        appendLine("Attached media references:")
        appendLine(attachments)
        appendLine()
        append("Please triage this issue, decide whether to create/update beads, and route it to the appropriate coding agent if needed.")
    }.trim()

}
