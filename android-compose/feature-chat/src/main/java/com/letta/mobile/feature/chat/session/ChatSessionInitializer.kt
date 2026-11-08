package com.letta.mobile.feature.chat.session

import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.feature.chat.coordination.ChatRunExpansionState
import com.letta.mobile.feature.chat.state.ChatBannerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class ChatSessionInitializer(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val settingsRepository: ISettingsRepository,
    private val bannerController: ChatBannerController,
    private val runExpansionState: ChatRunExpansionState,
    private val refreshAvailableAgents: () -> Unit,
    private val prepareChat: () -> Unit,
    private val loadProject: () -> Unit,
    private val openConversation: () -> Unit,
) {
    fun run() {
        observeActiveConfigChanges()
        if (agentId.isBlank()) {
            bannerController.showNoAgentSelected()
            return
        }

        prepareChat()
        runExpansionState.hydrateUiState()
        loadProject()
        openConversation()
    }

    private fun observeActiveConfigChanges() {
        scope.launch {
            settingsRepository.activeConfigChanges.collect {
                refreshAvailableAgents()
            }
        }
    }

}
