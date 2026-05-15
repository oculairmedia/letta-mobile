package com.letta.mobile.ui.screens.chat.session

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.screens.chat.ChatConversationCoordinator
import com.letta.mobile.ui.screens.chat.ChatRunExpansionState
import com.letta.mobile.ui.screens.chat.ChatSessionResolver
import com.letta.mobile.ui.screens.chat.ClientModeSendCoordinator
import com.letta.mobile.ui.screens.chat.ProjectChatContext
import com.letta.mobile.ui.screens.chat.state.ChatBannerController
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** Owns the startup wiring previously embedded in AdminChatViewModel.init. */
internal class ChatSessionInitializer(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val isFreshRoute: Boolean,
    private val explicitNewChat: Boolean,
    private val resumeCacheMaxAgeMs: Long,
    private val projectContext: ProjectChatContext?,
    private val settingsRepository: SettingsRepository,
    private val sessionResolver: ChatSessionResolver,
    private val conversationCoordinator: ChatConversationCoordinator,
    private val clientModeCoordinator: ClientModeSendCoordinator,
    private val runExpansionState: ChatRunExpansionState,
    private val currentConversationTracker: CurrentConversationTracker,
    private val bannerController: ChatBannerController,
    private val setClientModeConversationId: (String?) -> Unit,
    private val refreshAvailableAgents: () -> Unit,
    private val observeLastChatSelection: () -> Unit,
    private val seedAgentNameFromMemoryCache: () -> Unit,
    private val observeAgentNameCache: () -> Unit,
    private val refreshClientModeLocation: () -> Unit,
    private val loadProjectAgents: () -> Unit,
    private val loadProjectBrief: () -> Unit,
    private val loadRecentBugReports: () -> Unit,
    private val resolveConversationAndLoad: (Boolean) -> Unit,
) {
    fun run() {
        observeActiveConfigChanges()
        prepareFreshRoute()
        if (agentId.isBlank()) {
            bannerController.showNoAgentSelected()
            return
        }

        observeLastChatSelection()
        seedAgentNameFromMemoryCache()
        observeAgentNameCache()
        runExpansionState.hydrateUiState()
        observeClientModeEnabled()
        bootstrapProjectContext()
    }

    private fun observeActiveConfigChanges() {
        // letta-mobile-ze5l: when the active backend swaps under us, refresh
        // the agent roster so the drawer / picker reflect the new server's
        // agents. The conversation we're on may not exist on the new server
        // (letta-mobile-iow7 covers the cache-invalidation story); for now
        // we let the timeline observers naturally retry against the new URL.
        scope.launch {
            settingsRepository.activeConfigChanges.collect {
                refreshAvailableAgents()
            }
        }
    }

    private fun prepareFreshRoute() {
        // letta-mobile-w2hx.6: route arg already pre-populated
        // `activeConversationId` at field init; no shared singleton to seed.
        if (!isFreshRoute) return

        setClientModeConversationId(null)
        currentConversationTracker.setCurrent(null)
        if (!explicitNewChat && agentId.isNotBlank()) {
            scope.launch {
                maybeResumeRecentConversation()
            }
        } else if (explicitNewChat) {
            Telemetry.event(
                "AdminChatVM", "resumeRecent.explicitNewChat",
                "agentId" to agentId,
            )
        }
    }

    private suspend fun maybeResumeRecentConversation() {
        // Use firstOrNull so a mocked SettingsRepository returning emptyFlow()
        // (in unit tests) doesn't throw at VM init. Unknown-flag treated as
        // "skip resume" — preserves pre-h2b8 fresh-route semantics for tests.
        val flagEnabled = settingsRepository.observeResumeRecentConversation().firstOrNull() ?: false
        if (!flagEnabled) return

        Telemetry.event(
            "AdminChatVM", "resumeRecent.attempted",
            "agentId" to agentId,
        )
        val resumed = runCatching {
            sessionResolver.resolveMostRecentConversation(
                agentId = agentId,
                maxAgeMs = resumeCacheMaxAgeMs,
            )
        }.getOrNull()
        if (resumed != null) {
            Telemetry.event(
                "AdminChatVM", "resumeRecent.succeeded",
                "agentId" to agentId,
                "conversationId" to resumed,
            )
            conversationCoordinator.setActiveConversationId(resumed)
        } else {
            Telemetry.event(
                "AdminChatVM", "resumeRecent.noRecent",
                "agentId" to agentId,
            )
        }
    }

    private fun observeClientModeEnabled() {
        scope.launch {
            settingsRepository.observeClientModeEnabled()
                .distinctUntilChanged()
                .collect { enabled ->
                    if (!enabled) {
                        clientModeCoordinator.cancelActiveStream()
                        bannerController.applyClientModeDisabled()
                    } else {
                        bannerController.applyClientModeEnabled(projectContext?.filesystemPath)
                        refreshClientModeLocation()
                    }
                    resolveConversationAndLoad(enabled)
                }
        }
    }

    private fun bootstrapProjectContext() {
        if (projectContext == null) return
        loadProjectAgents()
        loadProjectBrief()
        loadRecentBugReports()
    }
}
