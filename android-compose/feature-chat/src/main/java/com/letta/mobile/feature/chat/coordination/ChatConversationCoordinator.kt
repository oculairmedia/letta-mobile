package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.chat.runtime.ChatSessionState

internal const val LOCAL_RUNTIME_REMOTE_AGENT_ERROR = "This agent is remote; create/select a local-runtime agent to use Local LettaCode."

internal sealed interface LocalRuntimeRouting {
    data object Remote : LocalRuntimeRouting
    data object LocalBound : LocalRuntimeRouting
    data class Blocked(val message: String = LOCAL_RUNTIME_REMOTE_AGENT_ERROR) : LocalRuntimeRouting
}

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
    private val scope: CoroutineScope,
    private val agentId: String,
    private val initialMessage: String?,
    private val explicitConversationId: () -> String?,
    // letta-mobile-9cb37: the conversation id the *route* explicitly asked for,
    // snapshotted once at construction (see ChatRouteArgs.pinnedExplicitConversationId).
    // Unlike explicitConversationId() — a live read of the shared CONVERSATION_ID_KEY
    // that setRouteConversationId mutates and that Compose can restore stale across an
    // agent switch — this is the authoritative "open exactly THIS conversation" signal.
    // Null for fresh/blank routes so resume-recent / picker fallbacks are untouched.
    private val pinnedExplicitConversationId: String? = null,
    private val setRouteConversationId: (String?) -> Unit,
    private val isFreshRoute: Boolean,
    private val chatSessionResolver: ChatSessionResolver,
    private val agentRepository: IAgentRepository,
    private val currentConversationTracker: CurrentConversationTracker,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val updateSessionState: ((ChatSessionState) -> ChatSessionState) -> Unit,
    private val pendingClientModeBootstrapMessages: () -> kotlinx.collections.immutable.ImmutableList<UiMessage>,
    private val setPendingClientModeBootstrapUserMessage: (UiMessage) -> Unit,
    private val clearPendingClientModeBootstrapUserMessage: () -> Unit,
    private val currentClientModeConversationId: () -> String?,
    private val setClientModeConversationId: (String?) -> Unit,
    private val startTimelineObserver: (String) -> Unit,
    private val stopTimelineObserver: () -> Unit,
    // letta-mobile-ork1: invoked from loadMessagesInternal so opening a
    // conversation pulls fresh recent messages from the server. Without
    // this the cached TimelineSyncLoop (warm-started by resume-most-
    // recent / notification paths) serves stale state until the user's
    // first send triggers the post-turn_done reconcile.
    private val reconcileRecentMessages: suspend (String, String) -> Unit,
    private val sendMessageViaClientMode: (String) -> Unit,
    private val sendMessageViaTimeline: (String) -> Unit,
    private val markFollowingDuplicateInitialMessageInFlight: () -> Unit,
    private val localRuntimeRouting: () -> LocalRuntimeRouting = { LocalRuntimeRouting.Remote },
) {
    companion object {
        private const val CONVERSATION_CACHE_TTL_MS = 30_000L
    }

    val activeConversationId: String?
        get() = explicitConversationId()

    private val initialMessageConsumed = AtomicBoolean(false)
    private var hasResolvedConversationOnce: Boolean = false
    private var clientModeBootstrapState: ClientModeBootstrapState =
        if (isFreshRoute) ClientModeBootstrapState.NewConversationPending else ClientModeBootstrapState.Idle

    fun conversationId(useClientMode: Boolean): String? =
        activeConversationId ?: if (useClientMode) currentClientModeConversationId() else null

    fun setActiveConversationId(conversationId: String?) {
        setRouteConversationId(conversationId)
    }

    fun resolveConversationAndLoad(useClientModeForResolve: Boolean) {
        val isFirstResolve = !hasResolvedConversationOnce
        hasResolvedConversationOnce = true
        if (isFreshRoute && isFirstResolve && explicitConversationId() == null) {
            setRouteConversationId(null)
        }
        scope.launch {
            updateSessionState { ChatSessionReducer.beginConversationLoad(it) }

            try {
                if (useClientModeForResolve) {
                    resolveClientModeConversation(isFirstResolve)
                    return@launch
                }

                resolveTimelineConversation(isFirstResolve)
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

    private suspend fun resolveClientModeConversation(isFirstResolve: Boolean) {
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
                    resolveMostRecentConversation(CONVERSATION_CACHE_TTL_MS)
                }.getOrNull()?.also { resolved ->
                    setRouteConversationId(resolved)
                    clientModeBootstrapState = ClientModeBootstrapState.Ready(resolved)
                }
            } else {
                null
            }
        currentConversationTracker.setCurrent(clientConversationId)
        val typedAgentId = AgentId(agentId)
        val agent = agentRepository.getCachedAgent(typedAgentId)
            ?: runCatching { agentRepository.getAgent(typedAgentId).first() }.getOrNull()
        if (clientConversationId != null) {
            startTimelineObserver(clientConversationId)
            val summary = ChatConversationSummary(
                id = clientConversationId,
                title = agent?.name ?: uiState.value.agentName,
                agentName = agent?.name ?: uiState.value.agentName,
                updatedAtLabel = "",
                lastMessagePreview = "",
            )
            updateSessionState { current ->
                val next = ChatSessionReducer.conversationsLoaded(current, listOf(summary))
                ChatSessionReducer.hydrateCompleted(next, next.selectionGeneration)
            }
            uiState.value = uiState.value.copy(
                agentName = agent?.name ?: uiState.value.agentName,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
                isStreaming = false,
                isAgentTyping = false,
            )
        } else {
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
        consumeInitialMessageIfPresent(stageFreshClientModeDuplicate = true)?.let { message ->
            sendMessageViaClientMode(message)
        }
    }

    private suspend fun resolveTimelineConversation(isFirstResolve: Boolean) {
        when (val route = localRuntimeRouting()) {
            LocalRuntimeRouting.Remote -> Unit
            LocalRuntimeRouting.LocalBound -> {
                resolveClientModeConversation(isFirstResolve)
                return
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
                return
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
        if (
            pinnedExplicit == null &&
            !suppressFreshRouteFallback &&
            activeConversationId == null &&
            explicitConversationId() == null
        ) {
            resolveMostRecentConversation(CONVERSATION_CACHE_TTL_MS)
        }

        val conversationId = pinnedExplicit
            ?: if (suppressFreshRouteFallback) {
                explicitConversationId()
            } else {
                activeConversationId ?: explicitConversationId()
            }

        if (conversationId == null) {
            updateSessionState { ChatSessionReducer.conversationsLoaded(it, emptyList()) }
            uiState.value = uiState.value.copy(
                messages = persistentListOf(),
                messageListChange = ChatMessageListChange.Full,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
            )
        } else {
            val cachedAgent = agentRepository.getCachedAgent(AgentId(agentId))
            val summary = ChatConversationSummary(
                id = conversationId,
                title = cachedAgent?.name ?: uiState.value.agentName,
                agentName = cachedAgent?.name ?: uiState.value.agentName,
                updatedAtLabel = "",
                lastMessagePreview = "",
            )
            updateSessionState { current ->
                val next = ChatSessionReducer.conversationsLoaded(current, listOf(summary))
                ChatSessionReducer.beginSelectedConversationHydrate(next, next.selectionGeneration)
            }
            loadMessagesInternal()
        }

        consumeInitialMessageIfPresent(stageFreshClientModeDuplicate = false)?.let { message ->
            sendMessageViaTimeline(message)
        }
    }

    private suspend fun resolveMostRecentConversation(maxAgeMs: Long): String? {
        return chatSessionResolver.resolveMostRecentConversation(agentId, maxAgeMs)
            ?.also { setRouteConversationId(it) }
    }

    suspend fun loadMessagesInternal() {
        val loadTimer = Telemetry.startTimer("AdminChatVM", "loadMessages")
        val requestedConversationId = activeConversationId ?: explicitConversationId()
        val currentConversationId = activeConversationId ?: explicitConversationId()
        if (requestedConversationId == null) {
            if (requestedConversationId == currentConversationId) {
                updateSessionState { ChatSessionReducer.conversationsLoaded(it, emptyList()) }
                uiState.value = uiState.value.copy(
                    messages = persistentListOf(),
                    messageListChange = ChatMessageListChange.Full,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                )
            }
            loadTimer.stop("result" to "noConversation")
            return
        }
        if (localRuntimeRouting() == LocalRuntimeRouting.LocalBound) {
            val cachedAgent = agentRepository.getCachedAgent(AgentId(agentId))
            if (requestedConversationId == currentConversationId) {
                val summary = ChatConversationSummary(
                    id = requestedConversationId,
                    title = cachedAgent?.name ?: uiState.value.agentName,
                    agentName = cachedAgent?.name ?: uiState.value.agentName,
                    updatedAtLabel = "",
                    lastMessagePreview = "",
                )
                updateSessionState { current ->
                    val next = ChatSessionReducer.conversationsLoaded(current, listOf(summary))
                    ChatSessionReducer.hydrateCompleted(next, next.selectionGeneration)
                }
                uiState.value = uiState.value.copy(
                    agentName = cachedAgent?.name ?: uiState.value.agentName,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                )
                startTimelineObserver(requestedConversationId)
            }
            loadTimer.stop(
                "conversationId" to requestedConversationId,
                "mode" to "local",
            )
            return
        }
        val cachedAgent = agentRepository.getCachedAgent(AgentId(agentId))
        if (cachedAgent != null) {
            if (requestedConversationId == currentConversationId) {
                val summary = ChatConversationSummary(
                    id = requestedConversationId,
                    title = cachedAgent.name ?: uiState.value.agentName,
                    agentName = cachedAgent.name ?: uiState.value.agentName,
                    updatedAtLabel = "",
                    lastMessagePreview = "",
                )
                updateSessionState { current ->
                    val next = ChatSessionReducer.conversationsLoaded(current, listOf(summary))
                    ChatSessionReducer.beginSelectedConversationHydrate(next, next.selectionGeneration)
                }
                uiState.value = uiState.value.copy(
                    agentName = cachedAgent.name ?: uiState.value.agentName,
                    messages = uiState.value.messages,
                    messageListChange = ChatMessageListChange.Full,
                )
            }
        } else {
            if (requestedConversationId == currentConversationId) {
                updateSessionState { current ->
                    val summary = ChatConversationSummary(
                        id = requestedConversationId,
                        title = uiState.value.agentName,
                        agentName = uiState.value.agentName,
                        updatedAtLabel = "",
                        lastMessagePreview = "",
                    )
                    val next = ChatSessionReducer.conversationsLoaded(current, listOf(summary))
                    ChatSessionReducer.beginSelectedConversationHydrate(next, next.selectionGeneration)
                }
            }
        }
        try {
            val agent = agentRepository.getAgent(AgentId(agentId)).first()
            if (requestedConversationId != (activeConversationId ?: explicitConversationId())) {
                loadTimer.stop("result" to "staleConversation")
                return
            }
            val summary = ChatConversationSummary(
                id = requestedConversationId,
                title = agent.name,
                agentName = agent.name,
                updatedAtLabel = "",
                lastMessagePreview = "",
            )
            updateSessionState { current ->
                val next = ChatSessionReducer.conversationsLoaded(current, listOf(summary))
                ChatSessionReducer.hydrateCompleted(next, next.selectionGeneration)
            }
            uiState.value = uiState.value.copy(
                agentName = agent.name,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
            )
            startTimelineObserver(requestedConversationId)
            // letta-mobile-ork1: kick off a server pull so the cached
            // TimelineSyncLoop catches up on any messages that landed
            // outside this process (other devices, agent runs between
            // sessions). Fire-and-forget — the observer above will pick
            // up the updated state when the reconcile lands. We don't
            // await it here because the user can still send / scroll
            // against the cached view while the fetch is in flight.
            scope.launch {
                runCatching {
                    reconcileRecentMessages(requestedConversationId, "open")
                }.onFailure {
                    Telemetry.error(
                        "AdminChatVM", "loadMessages.reconcileOnOpenFailed", it,
                        "conversationId" to requestedConversationId,
                    )
                }
            }
            loadTimer.stop(
                "conversationId" to requestedConversationId,
                "mode" to "timeline",
            )
        } catch (e: Exception) {
            loadTimer.stopError(e, "conversationId" to requestedConversationId)
            if (requestedConversationId != (activeConversationId ?: explicitConversationId())) {
                return
            }
            updateSessionState { current ->
                ChatSessionReducer.streamDisconnected(
                    state = current,
                    generation = current.selectionGeneration,
                    errorMessage = e.message ?: "Failed to load messages",
                )
            }
            uiState.value = uiState.value.copy(
                isLoadingOlderMessages = false,
            )
        }
    }

    private fun consumeInitialMessageIfPresent(stageFreshClientModeDuplicate: Boolean): String? {
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
            if (stageFreshClientModeDuplicate && isFreshRoute) {
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

    fun loadMessages(useClientModeForCurrentRoute: Boolean) {
        if (!useClientModeForCurrentRoute && activeConversationId == null) {
            resolveConversationAndLoad(useClientModeForResolve = false)
            return
        }
        scope.launch { loadMessagesInternal() }
    }
}
