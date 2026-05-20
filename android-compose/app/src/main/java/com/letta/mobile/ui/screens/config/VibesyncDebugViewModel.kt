package com.letta.mobile.ui.screens.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.api.VibesyncAdminApi
import com.letta.mobile.data.api.VibesyncDebugApi
import com.letta.mobile.data.model.AgentsMdRefreshSummary
import com.letta.mobile.data.model.VibesyncHealthResponse
import com.letta.mobile.data.model.VibesyncStatsResponse
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VibesyncDebugUiState(
    val health: VibesyncHealthResponse? = null,
    val stats: VibesyncStatsResponse? = null,
    val refreshSummary: AgentsMdRefreshSummary? = null,
    val isRefreshingAgentsMd: Boolean = false,
)

@HiltViewModel
class VibesyncDebugViewModel @Inject constructor(
    private val debugApi: VibesyncDebugApi,
    private val adminApi: VibesyncAdminApi,
) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState<VibesyncDebugUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<VibesyncDebugUiState>> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            runCatching {
                VibesyncDebugUiState(
                    health = debugApi.getHealth(),
                    stats = debugApi.getStats(),
                )
            }.onSuccess { _uiState.value = UiState.Success(it) }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to load vibesync status") }
        }
    }

    fun refreshAgentsMd(dryRun: Boolean = true) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(isRefreshingAgentsMd = true))
        viewModelScope.launch {
            runCatching { adminApi.refreshAgentsMd(dryRun = dryRun) }
                .onSuccess { summary -> updateSuccess { it.copy(refreshSummary = summary) } }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to refresh AGENTS.md") }
            updateSuccess { it.copy(isRefreshingAgentsMd = false) }
        }
    }

    private inline fun updateSuccess(transform: (VibesyncDebugUiState) -> VibesyncDebugUiState) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(transform(current))
    }
}
