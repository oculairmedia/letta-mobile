package com.letta.mobile.ui.screens.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class ServerMode {
    CLOUD, SELF_HOSTED
}

@androidx.compose.runtime.Immutable
data class ConfigUiState(
    val mode: ServerMode = ServerMode.CLOUD,
    val serverUrl: String = "",
    val apiToken: String = "",
    val theme: AppTheme = AppTheme.SYSTEM,
    val themePreset: ThemePreset = ThemePreset.DEFAULT,
    val dynamicColor: Boolean = false,
)

@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<UiState<ConfigUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ConfigUiState>> = _uiState.asStateFlow()

    init {
        loadConfig()
    }

    fun loadConfig() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val config = settingsRepository.activeConfig.value
                val appTheme = settingsRepository.getTheme().first()
                val themePreset = settingsRepository.getThemePreset().first()
                val dynamicColor = settingsRepository.getDynamicColor().first()
                val configUiState = if (config != null) {
                    ConfigUiState(
                        mode = if (config.mode == LettaConfig.Mode.CLOUD) ServerMode.CLOUD else ServerMode.SELF_HOSTED,
                        serverUrl = config.serverUrl,
                        apiToken = config.accessToken ?: "",
                        theme = appTheme,
                        themePreset = themePreset,
                        dynamicColor = dynamicColor,
                    )
                } else {
                    ConfigUiState(
                        theme = appTheme,
                        themePreset = themePreset,
                        dynamicColor = dynamicColor,
                    )
                }
                _uiState.value = UiState.Success(configUiState)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load config")
            }
        }
    }

    fun updateMode(mode: ServerMode) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(mode = mode))
        }
    }

    fun updateServerUrl(url: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(serverUrl = url))
        }
    }

    fun updateApiToken(token: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(apiToken = token))
        }
    }

    fun updateTheme(theme: AppTheme) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(theme = theme))
        }
    }

    fun updateThemePreset(themePreset: ThemePreset) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(
                currentState.copy(
                    themePreset = themePreset,
                    dynamicColor = if (themePreset == ThemePreset.DEFAULT) currentState.dynamicColor else false,
                )
            )
        }
    }

    fun updateDynamicColor(enabled: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(dynamicColor = enabled))
        }
    }

    fun saveConfig(onSuccess: () -> Unit, onError: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val state = (_uiState.value as? UiState.Success)?.data ?: return@launch
                val url = state.serverUrl.trim()
                if (url.isBlank()) {
                    onError?.invoke("Server URL is required")
                    return@launch
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    onError?.invoke("Server URL must start with http:// or https://")
                    return@launch
                }
                val existingConfig = settingsRepository.activeConfig.value
                val config = LettaConfig(
                    id = existingConfig?.id ?: UUID.randomUUID().toString(),
                    mode = if (state.mode == ServerMode.CLOUD) LettaConfig.Mode.CLOUD else LettaConfig.Mode.SELF_HOSTED,
                    serverUrl = url,
                    accessToken = state.apiToken.trim().ifBlank { null }
                )
                settingsRepository.saveConfig(config)
                settingsRepository.setTheme(state.theme)
                settingsRepository.setThemePreset(state.themePreset)
                settingsRepository.setDynamicColor(state.dynamicColor)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to save config")
            }
        }
    }
}
