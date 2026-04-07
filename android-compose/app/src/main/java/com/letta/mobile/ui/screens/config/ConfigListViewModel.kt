package com.letta.mobile.ui.screens.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val configs: List<ServerConfig> = emptyList()
)

@HiltViewModel
class ConfigListViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<ConfigListUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ConfigListUiState>> = _uiState.asStateFlow()

    init {
        loadConfigs()
    }

    fun loadConfigs() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val configs = settingsRepository.configs.value
                val activeId = settingsRepository.activeConfig.value?.id
                val serverConfigs = configs.map {
                    ServerConfig(
                        id = it.id,
                        mode = if (it.mode == LettaConfig.Mode.CLOUD) ServerMode.CLOUD else ServerMode.SELF_HOSTED,
                        url = it.serverUrl,
                        isActive = it.id == activeId
                    )
                }
                _uiState.value = UiState.Success(ConfigListUiState(configs = serverConfigs))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load configs")
            }
        }
    }

    fun setActiveConfig(configId: String) {
        viewModelScope.launch {
            try {
                settingsRepository.setActiveConfigId(configId)
                loadConfigs()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to set active config")
            }
        }
    }

    fun deleteConfig(configId: String) {
        viewModelScope.launch {
            try {
                settingsRepository.deleteConfig(configId)
                loadConfigs()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to delete config")
            }
        }
    }
}
