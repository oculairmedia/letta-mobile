package com.letta.mobile.ui.screens.config

import androidx.lifecycle.ViewModel
import com.letta.mobile.data.model.AgentsMdRefreshSummary
import com.letta.mobile.data.model.VibesyncHealthResponse
import com.letta.mobile.data.model.VibesyncStatsResponse
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VibesyncDebugUiState(
    val health: VibesyncHealthResponse? = null,
    val stats: VibesyncStatsResponse? = null,
    val healthError: String? = null,
    val refreshSummary: AgentsMdRefreshSummary? = null,
    val isRefreshingAgentsMd: Boolean = false,
)

/**
 * The VibeSync debug page. letta-mobile-ckmub.4: its health, stats and AGENTS.md refresh calls went
 * over the Letta REST client to the admin shim, which is gone, and no App Server or Iroh route
 * carries them; the page reports that instead of reaching for another server. The page itself is
 * triaged by the Android admin-screen slice (ckmub S17).
 */
@HiltViewModel
class VibesyncDebugViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow<UiState<VibesyncDebugUiState>>(UiState.Error(UNAVAILABLE))
    val uiState: StateFlow<UiState<VibesyncDebugUiState>> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.value = UiState.Error(UNAVAILABLE)
    }

    @Suppress("UNUSED_PARAMETER")
    fun refreshAgentsMd(dryRun: Boolean = true) = Unit

    private companion object {
        const val UNAVAILABLE = "VibeSync debug is not available: no App Server or Iroh route serves it."
    }
}
