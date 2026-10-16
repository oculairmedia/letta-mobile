package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.chat.runtime.ChatSessionState
import com.letta.mobile.ui.chat.render.ChatUiState
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
    val observer: TimelineObserverConfig,
    val reconcileLauncher: RecentMessagesReconcileLauncher,
    val send: ConversationSendConfig,
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

internal data class TimelineObserverConfig(
    val start: (String) -> Unit,
    val stop: suspend () -> Unit,
)

internal data class ConversationSendConfig(
    val viaTimeline: (String) -> Unit,
    val markDuplicateInitialMessageInFlight: () -> Unit,
)

internal data class HydrationRouteConfig(
    val identity: (String) -> ChatHydrationTrace.Identity,
    val generation: (String) -> ChatHydrationTrace.Generation? = ChatHydrationTrace::current,
)
