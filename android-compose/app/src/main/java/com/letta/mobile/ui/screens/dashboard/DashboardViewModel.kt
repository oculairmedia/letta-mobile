package com.letta.mobile.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.domain.AdminAgentManager
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class DashboardUiState(
    val serverUrl: String = "",
    val isConnected: Boolean = false,
    val agentCount: Int = 0,
    val conversationCount: Int = 0,
    val toolCount: Int = 0,
    val adminAgentId: String? = null,
    val adminAgentName: String = "Letta Admin",
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val allConversationsRepository: AllConversationsRepository,
    private val toolRepository: ToolRepository,
    private val settingsRepository: SettingsRepository,
    private val adminAgentManager: AdminAgentManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<DashboardUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<DashboardUiState>> = _uiState.asStateFlow()

    init {
        loadDashboard()
    }

    fun loadDashboard() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val adminAgent = adminAgentManager.ensureAdminAgent()

                agentRepository.refreshAgents()
                allConversationsRepository.refresh()
                toolRepository.refreshTools()

                val agents = agentRepository.agents.value
                val conversations = allConversationsRepository.conversations.value
                val tools = toolRepository.getTools().first()
                val serverUrl = settingsRepository.activeConfig.value?.serverUrl ?: ""

                _uiState.value = UiState.Success(
                    DashboardUiState(
                        serverUrl = serverUrl,
                        isConnected = true,
                        agentCount = agents.size,
                        conversationCount = conversations.size,
                        toolCount = tools.size,
                        adminAgentId = adminAgent.id,
                        adminAgentName = adminAgent.name,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to connect to server")
            }
        }
    }
}
