package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.RosterNameResolver
import com.letta.mobile.data.repository.RosterNameTelemetry
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.chat.runtime.ChatSessionState
import com.letta.mobile.data.timeline.SequentialAcquisitionIdGenerator
import com.letta.mobile.data.timeline.TimelineConversationAttributionCapture
import com.letta.mobile.data.timeline.TimelineConversationSelectionMode

internal const val LOCAL_RUNTIME_REMOTE_AGENT_ERROR = "This agent is remote; create/select a local-runtime agent to use Local LettaCode."

internal sealed interface LocalRuntimeRouting {
    data object Remote : LocalRuntimeRouting
    data object LocalBound : LocalRuntimeRouting
    data class Blocked(val message: String = LOCAL_RUNTIME_REMOTE_AGENT_ERROR) : LocalRuntimeRouting
}

@JvmInline
private value class CoordinatorConversationId(val value: String)

@JvmInline
private value class CoordinatorAgentName(val value: String)

private sealed interface ClientModeBootstrapState {
    data object Idle : ClientModeBootstrapState
    data object NewConversationPending : ClientModeBootstrapState
    data class Ready(val conversationId: String) : ClientModeBootstrapState
}

/**
 * Owns chat route/conversation resolution and initial-message delivery policy.
 *
 * The ViewModel still exposes the public screen API, but this coordinator keeps
 * the fragile active-conversation/fresh-route/client-mode bootstrap state in one
 * place so send/search/project collaborators can be wired around a stable seam.
 */
internal class ChatConversationCoordinator(
    config: ChatConversationCoordinatorConfig,
) {
    private val scope = config.scope
    private val agentId = config.route.agentId
    private val initialMessage = config.route.initialMessage
    private val explicitConversationId = config.route.explicitConversationId
    private val pinnedExplicitConversationId = config.route.pinnedExplicitConversationId
    private val setRouteConversationId = config.route.setConversationId
    private val isFreshRoute = config.route.isFresh
    private val chatSessionResolver = config.chatSessionResolver
    private val agentRepository = config.agentRepository
    private val currentConversationTracker = config.currentConversationTracker
    private val uiState = config.uiState
    private val updateSessionState = config.updateSessionState
    private val pendingClientModeBootstrapMessages = config.bootstrap.pendingMessages
    private val setPendingClientModeBootstrapUserMessage = config.bootstrap.setPendingUserMessage
    private val currentClientModeConversationId = config.bootstrap.currentConversationId
    private val startTimelineObserver = config.observer.start
    private val stopTimelineObserver = config.observer.stop
    private val recentMessagesReconcileLauncher = config.reconcileLauncher
    private val sendMessageViaClientMode = config.send.viaClientMode
    private val sendMessageViaTimeline = config.send.viaTimeline
    private val markFollowingDuplicateInitialMessageInFlight = config.send.markDuplicateInitialMessageInFlight
    private val localRuntimeRouting = config.localRuntimeRouting
    private val hydrationIdentity = config.hydration.identity
    private val hydrationGeneration = config.hydration.generation
    fun currentHydrationGeneration(conversationId: String): ChatHydrationTrace.Generation? = hydrationGeneration(conversationId)

    companion object {
        private const val CONVERSATION_CACHE_TTL_MS = 30_000L
    }

    val activeConversationId: String?
        get() = explicitConversationId()

    private val initialMessageConsumed = AtomicBoolean(false)
    private var hasResolvedConversationOnce: Boolean = false
    private var hydratedConversationId: String? =
        (activeConversationId ?: currentClientModeConversationId()).takeIf { uiState.value.messages.isNotEmpty() }
    private var clientModeBootstrapState: ClientModeBootstrapState =
        if (isFreshRoute) ClientModeBootstrapState.NewConversationPending else ClientModeBootstrapState.Idle
    private val rosterNameResolver = RosterNameResolver(
        fetch = { id -> agentRepository.getAgent(AgentId(id)).first() },
        source = "ChatConversationCoordinator",
    )

    fun conversationId(mode: ConversationAccessMode): String? =
        activeConversationId ?: if (mode == ConversationAccessMode.Client) currentClientModeConversationId() else null

    /** Records a route-open only when the route already supplies a stable conversation id. */
    fun recordOpenRequested() {
        activeConversationId?.let { ChatHydrationTrace.begin(hydrationIdentity(it)) }
    }

    fun setActiveConversationId(conversationId: String?) {
        setRouteConversationId(conversationId)
    }

    /**
     * letta-mobile-6bqi1: true when the current conversation's messages are
     * already on screen. Reads the VM-level render cache, which survives device
     * rotation (the VM outlives the Activity) and same-VM re-entry. A fresh VM
     * (new route key) starts with empty messages, so genuine first loads still
     * show the loader.
     */
    private val isConversationAlreadyHydrated: Boolean
        get() = uiState.value.messages.isNotEmpty()

    private fun hydrationAvailability(summary: ChatConversationSummary): HydrationAvailability =
        if (isConversationAlreadyHydrated && hydratedConversationId == summary.id) {
            HydrationAvailability.Hydrated
        } else {
            HydrationAvailability.NeedsLoading
        }

    /**
     * letta-mobile-6bqi1: mark the selected conversation loaded without
     * re-entering Loading when its messages are already on screen. Both reducers
     * are generation-guarded identically from the post-`conversationsLoaded`
     * generation, so the swap is transparent to the timeline observer's
     * generation source — only the Loading transition is skipped.
     */
    private fun hydrateOrShowLoading(
        current: ChatSessionState,
        summary: ChatConversationSummary,
        hydration: HydrationAvailability,
    ): ChatSessionState {
        val next = ChatSessionReducer.conversationsLoaded(current, listOf(summary))
        return if (hydration == HydrationAvailability.Hydrated) {
            ChatSessionReducer.hydrateCompleted(next, next.selectionGeneration)
        } else {
            ChatSessionReducer.beginSelectedConversationHydrate(next, next.selectionGeneration)
        }
    }

    fun resolveConversationAndLoad(mode: ConversationAccessMode) {
        val attempt = if (hasResolvedConversationOnce) ResolutionAttempt.Subsequent else ResolutionAttempt.Initial
        if (isFreshRoute && attempt == ResolutionAttempt.Initial && explicitConversationId() == null) {
            setRouteConversationId(null)
        }
        scope.launch {
            // letta-mobile-6bqi1: skip the eager Loading flash when the
            // conversation's messages are already on screen (re-entry / device
            // rotation). The resolve below still runs — conversationsLoaded +
            // the guarded hydrate keep the session Live — so the background
            // reconcile still re-fills the conversation.
            if (!isConversationAlreadyHydrated) {
                updateSessionState { ChatSessionReducer.beginConversationLoad(it) }
            }

            try {
                val resolved = if (mode == ConversationAccessMode.Client) {
                    resolveClientModeConversation(attempt)
                } else {
                    resolveTimelineConversation(attempt)
                }
                if (resolved) hasResolvedConversationOnce = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Failed to resolve conversation", e)
                updateSessionState { ChatSessionReducer.conversationLoadFailed(it, e.message ?: "Failed to load conversation") }
                uiState.value = uiState.value.copy(
                    messages = persistentListOf(),
                    messageListChange = ChatMessageListChange.Full,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = false,
                    isAgentTyping = false,
                )
            }
        }
    }

    private suspend fun resolveClientModeConversation(attempt: ResolutionAttempt): Boolean {
        val isFirstResolve = attempt == ResolutionAttempt.Initial
        // letta-mobile-9cb37: honor the route's explicit conversation request on
        // the first resolve before any cached/most-recent fallback (mirrors the
        // timeline path) so an agent switch with an explicit conversationId opens
        // exactly that conversation rather than the target agent's last one.
        val pinnedExplicit = pinnedExplicitConversationId?.takeIf { isFirstResolve }
        if (pinnedExplicit != null && explicitConversationId() != pinnedExplicit) {
            setRouteConversationId(pinnedExplicit)
            clientModeBootstrapState = ClientModeBootstrapState.Ready(pinnedExplicit)
        }

        val suppressFreshRouteFallbackClient =
            clientModeBootstrapState == ClientModeBootstrapState.NewConversationPending ||
                (isFreshRoute && isFirstResolve)
        val clientConversationId = pinnedExplicit
            ?: explicitConversationId()
            ?: currentClientModeConversationId()?.also { cached ->
                // letta-mobile-go8el follow-up: PR #177 wired setRouteConversationId on the
                // resolveMostRecent fallback below but missed this branch — the legacy
                // clientModeConversationId SavedStateHandle key still persists across sessions
                // (will be deleted after a soak per the bead). When the user reopens a chat
                // and resolve takes THIS branch (cached value present), we must mirror the
                // id into the unified `conversationId` key so WsChatSendCoordinator's read of
                // chatConversationCoordinator.activeConversationId (which derives from
                // explicitConversationId()) doesn't return null and silently mint a fresh conv.
                setRouteConversationId(cached)
                clientModeBootstrapState = ClientModeBootstrapState.Ready(cached)
            }
            ?: if (!suppressFreshRouteFallbackClient) {
                runCatching {
                    resolveMostRecentConversation(CONVERSATION_CACHE_TTL_MS.milliseconds)
                }.getOrNull()?.also { resolved ->
                    setRouteConversationId(resolved)
                    clientModeBootstrapState = ClientModeBootstrapState.Ready(resolved)
                }
            } else {
                null
            }
        val typedAgentId = AgentId(agentId)
        val agent = agentRepository.getCachedAgent(typedAgentId)
            ?: runCatching { agentRepository.getAgent(typedAgentId).first() }.getOrNull()
        if (clientConversationId != null) {
            if (clientConversationId != (activeConversationId ?: explicitConversationId())) return false
            currentConversationTracker.setCurrent(clientConversationId)
            val summary = ChatConversationSummary(
                id = clientConversationId,
                title = agent?.name ?: uiState.value.agentName,
                agentName = agent?.name ?: uiState.value.agentName,
                updatedAtLabel = "",
                lastMessagePreview = "",
            )
            val hydration = hydrationAvailability(summary)
            updateSessionState { current -> hydrateOrShowLoading(current, summary, hydration) }
            startTimelineObserver(clientConversationId)
            if (hydration == HydrationAvailability.NeedsLoading && !loadMessagesInternal()) {
                return false
            }
            uiState.value = uiState.value.copy(
                agentName = agent?.name ?: uiState.value.agentName,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
                isStreaming = false,
                isAgentTyping = false,
            )
        } else {
            currentConversationTracker.setCurrent(null)
            stopTimelineObserver()
            updateSessionState { ChatSessionReducer.conversationsLoaded(it, emptyList()) }
            uiState.value = uiState.value.copy(
                agentName = agent?.name ?: uiState.value.agentName,
                messages = pendingClientModeBootstrapMessages(),
                messageListChange = ChatMessageListChange.Full,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
                isStreaming = false,
                isAgentTyping = false,
            )
        }
        consumeInitialMessageIfPresent(DuplicateInitialMessagePolicy.StageFreshClientDuplicate)?.let { message ->
            sendMessageViaClientMode(message)
        }
        return true
    }

    private suspend fun resolveTimelineConversation(attempt: ResolutionAttempt): Boolean {
        val isFirstResolve = attempt == ResolutionAttempt.Initial
        when (val route = localRuntimeRouting()) {
            LocalRuntimeRouting.Remote -> Unit
            LocalRuntimeRouting.LocalBound -> {
                return resolveClientModeConversation(attempt)
            }
            is LocalRuntimeRouting.Blocked -> {
                stopTimelineObserver()
                currentConversationTracker.setCurrent(null)
                updateSessionState { ChatSessionReducer.conversationLoadFailed(it, route.message) }
                uiState.value = uiState.value.copy(
                    messages = persistentListOf(),
                    messageListChange = ChatMessageListChange.Full,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = false,
                    isAgentTyping = false,
                    error = route.message,
                )
                return true
            }
        }

        // letta-mobile-9cb37: when the route explicitly asked for a conversation
        // (e.g. the subagent "view conversation" shortcut targeting `default`),
        // that request must win on the first resolve — even across an agent
        // switch, where the live explicitConversationId() may have been restored
        // stale to the target agent's prior active/last conversation. Pin it into
        // the route key so downstream loads/sends agree, and skip the
        // resolve-most-recent fallback that would otherwise override it.
        val pinnedExplicit = pinnedExplicitConversationId?.takeIf { isFirstResolve }
        if (pinnedExplicit != null && explicitConversationId() != pinnedExplicit) {
            setRouteConversationId(pinnedExplicit)
        }

        val suppressFreshRouteFallback = isFreshRoute && isFirstResolve
        val usedMostRecentFallback = pinnedExplicit == null &&
            !suppressFreshRouteFallback &&
            activeConversationId == null &&
            explicitConversationId() == null
        if (usedMostRecentFallback) {
            resolveMostRecentConversation(CONVERSATION_CACHE_TTL_MS.milliseconds)
        }

        val routeStateId = activeConversationId
        val conversationId = pinnedExplicit
            ?: if (suppressFreshRouteFallback) {
                explicitConversationId()
            } else {
                activeConversationId ?: explicitConversationId()
            }

        // letta-mobile-grrhq: the fallback already recorded its own (richer)
        // selection inside resolveMostRecentConversation; only record here when
        // it did NOT run, so an explicit route stays distinguishable from a
        // resolver fallback in the emitted chain.
        if (!usedMostRecentFallback) {
            recordNonFallbackSelection(
                conversationId = conversationId,
                pinnedExplicit = pinnedExplicit,
                fromRouteState = routeStateId != null && conversationId == routeStateId,
            )
        }

        val resolved = if (conversationId == null) {
            updateSessionState { ChatSessionReducer.conversationsLoaded(it, emptyList()) }
            uiState.value = uiState.value.copy(
                messages = persistentListOf(),
                messageListChange = ChatMessageListChange.Full,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
            )
            true
        } else {
            val cachedAgent = agentRepository.getCachedAgent(AgentId(agentId))
            reportNameFallbackIfUnresolved(cachedAgent?.name)
            val agent = cachedAgent ?: resolveMissingAgentName()
            val summary = ChatConversationSummary(
                id = conversationId,
                title = agent?.name ?: uiState.value.agentName,
                agentName = agent?.name ?: uiState.value.agentName,
                updatedAtLabel = "",
                lastMessagePreview = "",
            )
            updateSessionState { current ->
                hydrateOrShowLoading(current, summary, hydrationAvailability(summary))
            }
            agent?.name?.let { uiState.value = uiState.value.copy(agentName = it) }
            loadMessagesInternal()
        }

        consumeInitialMessageIfPresent(DuplicateInitialMessagePolicy.SuppressDuplicate)?.let { message ->
            sendMessageViaTimeline(message)
        }
        return resolved
    }

    /**
     * letta-mobile-z5lqt: telemetry only. Records that the cached agent had no
     * name and the `?: uiState.value.agentName` fallback is about to be taken.
     * The fallback expressions themselves are untouched.
     */
    private fun reportNameFallbackIfUnresolved(resolvedName: String?) {
        if (resolvedName != null) return
        RosterNameTelemetry.nameFallback(
            site = RosterNameTelemetry.NameFallbackSite.CHAT_COORDINATOR,
            agentId = agentId,
            fallbackKind = RosterNameTelemetry.FallbackKind.PREVIOUS_UI_NAME,
            rosterSize = agentRepository.agents.value.size,
        )
    }

    private suspend fun resolveMissingAgentName() = rosterNameResolver.resolve(agentId)

    internal val rosterNameResolverForTest get() = rosterNameResolver

    /**
     * letta-mobile-grrhq: the resolver/route DECISION half of the acquisition
     * chain. Recorded here and read by the ViewModel when it starts the timeline
     * observer, so the selection that produced a conversation id and the
     * acquisition that opens a holder for it share one acquisitionId.
     *
     * Diagnostic only: nothing reads this to make a routing decision.
     */
    @Volatile
    var lastConversationSelection: TimelineConversationSelectionRecord? = null
        private set

    /** Bounded record of how this coordinator last chose a conversation id. */
    internal data class TimelineConversationSelectionRecord(
        val acquisitionId: String,
        val selectionMode: TimelineConversationSelectionMode,
        val capture: TimelineConversationAttributionCapture?,
    )

    private val acquisitionIds = SequentialAcquisitionIdGenerator()

    private fun recordSelection(
        selectionMode: TimelineConversationSelectionMode,
        capture: TimelineConversationAttributionCapture?,
    ) {
        // Fully inert when the diagnostic is off: no capture is built and no
        // cached-conversation read is performed.
        if (!Telemetry.timelineAcquisitionProvenanceEnabled.get()) return
        lastConversationSelection = TimelineConversationSelectionRecord(
            acquisitionId = acquisitionIds.next(),
            selectionMode = selectionMode,
            capture = capture,
        )
    }

    /**
     * Record a selection made WITHOUT the most-recent fallback (explicit route
     * id, restored route state, or no conversation at all), so an explicit route
     * and a resolver fallback are distinguishable in the log.
     */
    private fun recordNonFallbackSelection(
        conversationId: String?,
        pinnedExplicit: String?,
        fromRouteState: Boolean,
    ) {
        if (!Telemetry.timelineAcquisitionProvenanceEnabled.get()) return
        val mode = when {
            pinnedExplicit != null -> TimelineConversationSelectionMode.EXPLICIT_CONVERSATION_ID
            conversationId == null -> TimelineConversationSelectionMode.DEFAULT_FALLBACK
            fromRouteState -> TimelineConversationSelectionMode.ROUTE_STATE
            else -> TimelineConversationSelectionMode.EXPLICIT_CONVERSATION_ID
        }
        recordSelection(
            mode,
            chatSessionResolver.captureAttribution(
                requestedAgentId = agentId,
                selectedConversationId = conversationId,
                parentAgentId = parentAgentIdForAttribution(),
                selectionMode = mode,
            ),
        )
    }

    /**
     * The canonical parent agent for attribution classification, when this route
     * is known to be a subagent view.
     *
     * Returns null today: the chat route does not carry its dispatching parent,
     * so attribution is honestly reported as UNKNOWN rather than guessed. The
     * seam exists so a parent can be supplied once the subagent registry is
     * threaded here, WITHOUT changing any emitted shape.
     */
    private fun parentAgentIdForAttribution(): String? = null

    private suspend fun resolveMostRecentConversation(maxAge: Duration): String? {
        // letta-mobile-grrhq: H1 under test. If a CHILD-agent route reaches this
        // fallback and the newest cached conversation for that child is the
        // PARENT's, this is where the second holder's identity gets decided.
        val selection = chatSessionResolver.resolveMostRecentConversationWithProvenance(
            agentId = agentId,
            maxAgeMs = maxAge.inWholeMilliseconds,
            parentAgentId = parentAgentIdForAttribution(),
        )
        recordSelection(TimelineConversationSelectionMode.MOST_RECENT_FALLBACK, selection.capture)
        return selection.conversationId
            ?.also { setRouteConversationId(it) }
    }

    suspend fun loadMessagesInternal(): Boolean {
        val loadTimer = Telemetry.startTimer("AdminChatVM", "loadMessages")
        val requestedConversationId = (activeConversationId ?: explicitConversationId())
            ?.let(::CoordinatorConversationId)
            ?: return completeEmptyConversationLoad(loadTimer)
        if (localRuntimeRouting() == LocalRuntimeRouting.LocalBound) {
            return loadLocalConversation(requestedConversationId, loadTimer)
        }
        publishCachedConversation(requestedConversationId)
        return loadRemoteConversation(requestedConversationId, loadTimer)
    }

    private fun completeEmptyConversationLoad(loadTimer: Telemetry.Timer): Boolean {
        updateSessionState { ChatSessionReducer.conversationsLoaded(it, emptyList()) }
        uiState.value = uiState.value.copy(
            messages = persistentListOf(),
            messageListChange = ChatMessageListChange.Full,
            isLoadingOlderMessages = false,
            hasMoreOlderMessages = false,
        )
        loadTimer.stop("result" to "noConversation")
        return true
    }

    private fun loadLocalConversation(conversationId: CoordinatorConversationId, loadTimer: Telemetry.Timer): Boolean {
        val cachedAgent = agentRepository.getCachedAgent(AgentId(agentId))
        reportNameFallbackIfUnresolved(cachedAgent?.name)
        if (isCurrentConversation(conversationId)) {
            val agentName = CoordinatorAgentName(cachedAgent?.name ?: uiState.value.agentName)
            val summary = conversationSummary(conversationId, agentName)
            updateSessionState { current ->
                val next = ChatSessionReducer.conversationsLoaded(current, listOf(summary))
                ChatSessionReducer.hydrateCompleted(next, next.selectionGeneration)
            }
            uiState.value = uiState.value.copy(
                agentName = agentName.value,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
            )
            hydratedConversationId = conversationId.value
            startTimelineObserver(conversationId.value)
        }
        loadTimer.stop("conversationId" to conversationId.value, "mode" to "local")
        return true
    }

    private fun publishCachedConversation(conversationId: CoordinatorConversationId) {
        if (!isCurrentConversation(conversationId)) return
        val cachedAgent = agentRepository.getCachedAgent(AgentId(agentId))
        val agentName = CoordinatorAgentName(cachedAgent?.name ?: uiState.value.agentName)
        val summary = conversationSummary(conversationId, agentName)
        updateSessionState { current ->
            hydrateOrShowLoading(current, summary, hydrationAvailability(summary))
        }
        if (cachedAgent != null) {
            uiState.value = uiState.value.copy(
                agentName = agentName.value,
                messageListChange = ChatMessageListChange.Full,
            )
        }
    }

    private suspend fun loadRemoteConversation(conversationId: CoordinatorConversationId, loadTimer: Telemetry.Timer): Boolean =
        try {
            val agent = agentRepository.getAgent(AgentId(agentId)).first()
            if (!isCurrentConversation(conversationId)) {
                loadTimer.stop("result" to "staleConversation")
                false
            } else {
                completeRemoteConversationLoad(
                    conversationId,
                    CoordinatorAgentName(agent.name),
                    loadTimer,
                )
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failRemoteConversationLoad(conversationId, error, loadTimer)
        }

    private fun completeRemoteConversationLoad(
        conversationId: CoordinatorConversationId,
        agentName: CoordinatorAgentName,
        loadTimer: Telemetry.Timer,
    ) {
        val summary = conversationSummary(conversationId, agentName)
        updateSessionState { current ->
            val next = ChatSessionReducer.conversationsLoaded(current, listOf(summary))
            ChatSessionReducer.hydrateCompleted(next, next.selectionGeneration)
        }
        uiState.value = uiState.value.copy(
            agentName = agentName.value,
            isLoadingOlderMessages = false,
            hasMoreOlderMessages = false,
        )
        hydratedConversationId = conversationId.value
        startTimelineObserver(conversationId.value)
        recentMessagesReconcileLauncher.launch(ConversationOpenReconcileRequest(conversationId.value))
        loadTimer.stop("conversationId" to conversationId.value, "mode" to "timeline")
    }

    private fun failRemoteConversationLoad(
        conversationId: CoordinatorConversationId,
        error: Exception,
        loadTimer: Telemetry.Timer,
    ): Boolean {
        loadTimer.stopError(error, "conversationId" to conversationId.value)
        if (!isCurrentConversation(conversationId)) return false
        updateSessionState { current ->
            ChatSessionReducer.streamDisconnected(
                state = current,
                generation = current.selectionGeneration,
                errorMessage = error.message ?: "Failed to load messages",
            )
        }
        uiState.value = uiState.value.copy(isLoadingOlderMessages = false)
        return false
    }

    private fun isCurrentConversation(conversationId: CoordinatorConversationId): Boolean =
        conversationId.value == (activeConversationId ?: explicitConversationId())

    private fun conversationSummary(
        conversationId: CoordinatorConversationId,
        agentName: CoordinatorAgentName,
    ): ChatConversationSummary =
        ChatConversationSummary(
            id = conversationId.value,
            title = agentName.value,
            agentName = agentName.value,
            updatedAtLabel = "",
            lastMessagePreview = "",
        )

    private fun consumeInitialMessageIfPresent(policy: DuplicateInitialMessagePolicy): String? {
        val message = initialMessage?.takeIf { it.isNotBlank() } ?: return null
        if (!initialMessageConsumed.compareAndSet(false, true)) return null

        val deliveryKey = InitialRouteMessageDeliveryGuard.key(
            agentId = agentId,
            conversationId = activeConversationId ?: explicitConversationId() ?: currentClientModeConversationId(),
            message = message,
        )
        return if (InitialRouteMessageDeliveryGuard.tryConsume(deliveryKey)) {
            message
        } else {
            android.util.Log.w(
                "AdminChatViewModel",
                "Suppressed duplicate initial route message agent=$agentId " +
                    "conversation=${activeConversationId ?: explicitConversationId() ?: currentClientModeConversationId()} " +
                    "messageHash=${message.hashCode()}",
            )
            markFollowingDuplicateInitialMessageInFlight()
            if (policy == DuplicateInitialMessagePolicy.StageFreshClientDuplicate && isFreshRoute) {
                val alreadyVisible = pendingClientModeBootstrapMessages().any {
                    it.role == "user" && it.content == message
                } || uiState.value.messages.any {
                    it.role == "user" && it.content == message
                }
                if (!alreadyVisible) {
                    setPendingClientModeBootstrapUserMessage(
                        UiMessage(
                            id = "client-user-initial-duplicate-${message.hashCode()}",
                            role = "user",
                            content = message,
                            timestamp = java.time.Instant.now().toString(),
                        )
                    )
                }
                updateSessionState { ChatSessionReducer.conversationsLoaded(it, emptyList()) }
                uiState.value = uiState.value.copy(
                    messages = pendingClientModeBootstrapMessages(),
                    messageListChange = ChatMessageListChange.Full,
                    isStreaming = true,
                    isAgentTyping = true,
                )
            } else {
                uiState.value = uiState.value.copy(
                    isStreaming = true,
                    isAgentTyping = true,
                )
            }
            null
        }
    }

    fun loadMessages(mode: ConversationAccessMode) {
        if (mode == ConversationAccessMode.Timeline && activeConversationId == null) {
            resolveConversationAndLoad(ConversationAccessMode.Timeline)
            return
        }
        scope.launch { loadMessagesInternal() }
    }
}
