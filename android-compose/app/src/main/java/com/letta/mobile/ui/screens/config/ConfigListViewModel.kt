package com.letta.mobile.ui.screens.config

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.channel.ChatPushAlarmScheduler
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class ServerConfig(
    val id: String,
    val mode: ServerMode,
    val url: String,
    val isActive: Boolean
)

@androidx.compose.runtime.Immutable
data class ConfigListUiState(
    val configs: ImmutableList<ServerConfig> = persistentListOf()
)

@HiltViewModel
class ConfigListViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    // letta-mobile-cdlk: observe the underlying flows so the backend-switcher
    // sheet stays in sync with concurrent edits (e.g. user adds a config via
    // ConfigScreen, comes back, opens the sheet — the new entry must appear
    // without a manual reload). The previous one-shot `loadConfigs()` only
    // refreshed when this VM's setActiveConfig / deleteConfig completed, which
    // missed cross-screen state changes.
    //
    // `_actionError` is folded into the same combined flow so a thrown
    // setActiveConfig / deleteConfig still surfaces via uiState (matches
    // the previous explicit-Error-state contract that ConfigListViewModelTest
    // pins). Set to null after a successful action to clear the banner.
    private val _actionError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<UiState<ConfigListUiState>> =
        combine(
            settingsRepository.configs,
            settingsRepository.activeConfig,
            _actionError,
        ) { configs, active, error ->
            if (error != null) {
                UiState.Error(error) as UiState<ConfigListUiState>
            } else {
                val activeId = active?.id
                val serverConfigs = configs.map {
                    ServerConfig(
                        id = it.id,
                        mode = if (it.mode == LettaConfig.Mode.CLOUD) ServerMode.CLOUD else ServerMode.SELF_HOSTED,
                        url = it.serverUrl,
                        isActive = it.id == activeId,
                    )
                }
                UiState.Success(ConfigListUiState(configs = serverConfigs.toImmutableList()))
            }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = UiState.Loading,
            )

    /**
     * Kept for [ConfigListScreen]'s error-retry path: clearing
     * [_actionError] lets the flow-driven `uiState` recompute to Success
     * from the current repository snapshot.
     */
    fun loadConfigs() {
        _actionError.value = null
    }

    fun setActiveConfig(configId: String) {
        viewModelScope.launch {
            try {
                settingsRepository.setActiveConfigId(configId)
                _actionError.value = null
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Failed to set active config"
            }
        }
    }

    fun deleteConfig(configId: String) {
        viewModelScope.launch {
            try {
                settingsRepository.deleteConfig(configId)
                _actionError.value = null
                if (settingsRepository.activeConfig.value == null) {
                    ChatPushAlarmScheduler.cancel(appContext)
                }
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Failed to delete config"
            }
        }
    }
}
