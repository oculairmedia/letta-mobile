package com.letta.mobile.feature.chat.session

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.feature.chat.ChatConversationCoordinator
import com.letta.mobile.feature.chat.ChatRunExpansionState
import com.letta.mobile.feature.chat.ChatSessionResolver
import com.letta.mobile.feature.chat.ProjectChatContext
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

internal class ChatSessionInitializer(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val isFreshRoute: Boolean,
    private val explicitNewChat: Boolean,
    private val resumeCacheMaxAgeMs: Long,
    @Suppress("UNUSED_PARAMETER") private val projectContext: ProjectChatContext?,
    private val settingsRepository: ISettingsRepository,
    private val sessionResolver: ChatSessionResolver,
    private val conversationCoordinator: ChatConversationCoordinator,
    private val runExpansionState: ChatRunExpansionState,
    private val currentConversationTracker: CurrentConversationTracker,
    private val bannerController: ChatBannerController,
    @Suppress("UNUSED_PARAMETER") private val setClientModeConversationId: (String?) -> Unit,
    private val refreshAvailableAgents: () -> Unit,
    private val observeLastChatSelection: () -> Unit,
    private val seedAgentNameFromMemoryCache: () -> Unit,
    private val observeAgentNameCache: () -> Unit,
    @Suppress("UNUSED_PARAMETER") private val refreshClientModeLocation: () -> Unit,
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
        bootstrapProjectContext()
        resolveConversationAndLoad(false)
    }

    private fun observeActiveConfigChanges() {
        scope.launch {
            settingsRepository.activeConfigChanges.collect {
                refreshAvailableAgents()
            }
        }
    }

    private fun prepareFreshRoute() {
        if (!isFreshRoute) return

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

    private fun bootstrapProjectContext() {
        if (projectContext == null) return
        loadProjectAgents()
        loadProjectBrief()
        loadRecentBugReports()
    }
}
