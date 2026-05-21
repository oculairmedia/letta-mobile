package com.letta.mobile.feature.chat

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.mapper.toUiMessages
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
    private val setRouteConversationId: (String?) -> Unit,
    private val isFreshRoute: Boolean,
    private val chatSessionResolver: ChatSessionResolver,
    private val agentRepository: AgentRepository,
    private val currentConversationTracker: CurrentConversationTracker,
    private val uiState: MutableStateFlow<ChatUiState>,
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

    fun markClientModeBootstrapReady(conversationId: String) {
        clientModeBootstrapState = ClientModeBootstrapState.Ready(conversationId)
    }

    fun resolveConversationAndLoad(useClientModeForResolve: Boolean) {
        val isFirstResolve = !hasResolvedConversationOnce
        hasResolvedConversationOnce = true
        if (isFreshRoute && isFirstResolve && explicitConversationId() == null) {
            setRouteConversationId(null)
        }
        scope.launch {
            uiState.value = uiState.value.copy(
                conversationState = ConversationState.Loading,
                isLoadingMessages = true,
                error = null,
            )

            try {
                if (useClientModeForResolve) {
                    resolveClientModeConversation(isFirstResolve)
                    return@launch
                }

                resolveTimelineConversation(isFirstResolve)
            } catch (e: Exception) {
                android.util.Log.w("AdminChatViewModel", "Failed to resolve conversation", e)
                uiState.value = uiState.value.copy(
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

    private suspend fun resolveClientModeConversation(isFirstResolve: Boolean) {
        val suppressFreshRouteFallbackClient =
            clientModeBootstrapState == ClientModeBootstrapState.NewConversationPending ||
                (isFreshRoute && isFirstResolve)
        val clientConversationId = explicitConversationId()
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
        val agent = agentRepository.getCachedAgent(agentId)
            ?: runCatching { agentRepository.getAgent(agentId).first() }.getOrNull()
        if (clientConversationId != null) {
            startTimelineObserver(clientConversationId)
            uiState.value = uiState.value.copy(
                agentName = agent?.name ?: uiState.value.agentName,
                conversationState = ConversationState.Ready(clientConversationId),
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
                isStreaming = false,
                isAgentTyping = false,
                error = null,
            )
        } else {
            stopTimelineObserver()
            uiState.value = uiState.value.copy(
                agentName = agent?.name ?: uiState.value.agentName,
                conversationState = ConversationState.NoConversation,
                messages = pendingClientModeBootstrapMessages(),
                isLoadingMessages = false,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
                isStreaming = false,
                isAgentTyping = false,
                error = null,
            )
        }
        consumeInitialMessageIfPresent(stageFreshClientModeDuplicate = true)?.let { message ->
            sendMessageViaClientMode(message)
        }
    }

    private suspend fun resolveTimelineConversation(isFirstResolve: Boolean) {
        val suppressFreshRouteFallback = isFreshRoute && isFirstResolve
        if (
            !suppressFreshRouteFallback &&
            activeConversationId == null &&
            explicitConversationId() == null
        ) {
            resolveMostRecentConversation(CONVERSATION_CACHE_TTL_MS)
        }

        val conversationId = if (suppressFreshRouteFallback) {
            explicitConversationId()
        } else {
            activeConversationId ?: explicitConversationId()
        }

        if (conversationId == null) {
            uiState.value = uiState.value.copy(
                conversationState = ConversationState.NoConversation,
                messages = persistentListOf(),
                isLoadingMessages = false,
                isLoadingOlderMessages = false,
                hasMoreOlderMessages = false,
                error = null,
            )
        } else {
            uiState.value = uiState.value.copy(
                conversationState = ConversationState.Ready(conversationId),
                error = null,
            )
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
                uiState.value = uiState.value.copy(
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
        val cachedMessages = emptyList<AppMessage>()
        if (cachedAgent != null || cachedMessages.isNotEmpty()) {
            if (requestedConversationId == currentConversationId) {
                uiState.value = uiState.value.copy(
                    agentName = cachedAgent?.name ?: uiState.value.agentName,
                    messages = if (cachedMessages.isNotEmpty()) cachedMessages.toUiMessages().toImmutableList() else uiState.value.messages,
                    isLoadingMessages = cachedMessages.isEmpty(),
                    error = null,
                )
            }
        } else {
            if (requestedConversationId == currentConversationId) {
                uiState.value = uiState.value.copy(isLoadingMessages = true)
            }
        }
        try {
            val agent = agentRepository.getAgent(agentId).first()
            if (requestedConversationId != (activeConversationId ?: explicitConversationId())) {
                loadTimer.stop("result" to "staleConversation")
                return
            }
            uiState.value = uiState.value.copy(
                agentName = agent.name,
                conversationState = ConversationState.Ready(requestedConversationId),
            )
            uiState.value = uiState.value.copy(
                isLoadingMessages = true,
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
            uiState.value = uiState.value.copy(
                conversationState = ConversationState.Ready(requestedConversationId),
                isLoadingMessages = false,
                isLoadingOlderMessages = false,
                error = e.message ?: "Failed to load messages",
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
                uiState.value = uiState.value.copy(
                    conversationState = ConversationState.NoConversation,
                    messages = pendingClientModeBootstrapMessages(),
                    isLoadingMessages = false,
                    isStreaming = true,
                    isAgentTyping = true,
                )
            } else {
                uiState.value = uiState.value.copy(
                    isLoadingMessages = false,
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

    fun resetClientModeConversationState() {
        setClientModeConversationId(null)
        setRouteConversationId(null)
        clientModeBootstrapState = if (isFreshRoute) {
            ClientModeBootstrapState.NewConversationPending
        } else {
            ClientModeBootstrapState.Idle
        }
        clearPendingClientModeBootstrapUserMessage()
        currentConversationTracker.setCurrent(null)
        stopTimelineObserver()
        uiState.value = uiState.value.copy(
            conversationState = ConversationState.NoConversation,
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
