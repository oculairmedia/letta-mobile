package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.chat.runtime.ChatSessionState
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** Typed construction boundary for route policy and lifecycle-scoped chat collaborators. */
internal data class ChatConversationCoordinatorConfig(
    val scope: CoroutineScope,
    val route: ChatConversationRoute,
    val chatSessionResolver: ChatSessionResolver,
    val agentRepository: IAgentRepository,
    val currentConversationTracker: CurrentConversationTracker,
    val uiState: MutableStateFlow<ChatUiState>,
    val updateSessionState: ((ChatSessionState) -> ChatSessionState) -> Unit,
    val bootstrap: ClientModeBootstrapConfig,
    val observer: TimelineObserverConfig,
    val reconcileLauncher: RecentMessagesReconcileLauncher,
    val send: ConversationSendConfig,
    val localRuntimeRouting: () -> LocalRuntimeRouting = { LocalRuntimeRouting.Remote },
    val hydration: HydrationRouteConfig,
)

internal data class ChatConversationRoute(
    val agentId: String,
    val initialMessage: String?,
    val explicitConversationId: () -> String?,
    val pinnedExplicitConversationId: String? = null,
    val setConversationId: (String?) -> Unit,
    val isFresh: Boolean,
)

internal data class ClientModeBootstrapConfig(
    val pendingMessages: () -> ImmutableList<UiMessage>,
    val setPendingUserMessage: (UiMessage) -> Unit,
    val currentConversationId: () -> String?,
)

internal data class TimelineObserverConfig(
    val start: (String) -> Unit,
    val stop: () -> Unit,
)

internal data class ConversationSendConfig(
    val viaClientMode: (String) -> Unit,
    val viaTimeline: (String) -> Unit,
    val markDuplicateInitialMessageInFlight: () -> Unit,
)

internal data class HydrationRouteConfig(
    val identity: (String) -> ChatHydrationTrace.Identity,
    val generation: (String) -> ChatHydrationTrace.Generation? = ChatHydrationTrace::current,
)
