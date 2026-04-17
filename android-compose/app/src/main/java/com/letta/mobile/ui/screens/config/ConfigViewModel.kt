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
    val enableProjects: Boolean = false,
    val useTimelineSync: Boolean = false,
)

@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    companion object {
        const val DEFAULT_CLOUD_URL = "https://api.letta.com"
    }

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
                val enableProjects = settingsRepository.getEnableProjects().first()
                val useTimelineSync = settingsRepository.getUseTimelineSync().first()
                val configUiState = if (config != null) {
                    ConfigUiState(
                        mode = if (config.mode == LettaConfig.Mode.CLOUD) ServerMode.CLOUD else ServerMode.SELF_HOSTED,
                        serverUrl = config.serverUrl,
                        apiToken = config.accessToken ?: "",
                        theme = appTheme,
                        themePreset = themePreset,
                        dynamicColor = dynamicColor,
                        enableProjects = enableProjects,
                        useTimelineSync = useTimelineSync,
                    )
                } else {
                    ConfigUiState(
                        theme = appTheme,
                        themePreset = themePreset,
                        dynamicColor = dynamicColor,
                        enableProjects = enableProjects,
                        useTimelineSync = useTimelineSync,
                    )
                }
                _uiState.value = UiState.Success(configUiState)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load config")
            }
        }
    }

    fun updateMode(mode: ServerMode) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        val updatedUrl = when (mode) {
            ServerMode.CLOUD -> DEFAULT_CLOUD_URL
            ServerMode.SELF_HOSTED ->
                if (currentState.serverUrl == DEFAULT_CLOUD_URL) "" else currentState.serverUrl
        }
        _uiState.value = UiState.Success(
            currentState.copy(mode = mode, serverUrl = updatedUrl)
        )
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

    fun updateEnableProjects(enabled: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(enableProjects = enabled))
        }
    }

    fun updateUseTimelineSync(enabled: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(useTimelineSync = enabled))
        }
    }

    fun saveConfig(onSuccess: () -> Unit, onError: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val state = (_uiState.value as? UiState.Success)?.data ?: return@launch
                val url = if (state.mode == ServerMode.CLOUD) {
                    DEFAULT_CLOUD_URL
                } else {
                    val raw = state.serverUrl.trim()
                    if (raw.isBlank()) {
                        onError?.invoke("Server URL is required")
                        return@launch
                    }
                    if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                    else "https://$raw"
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
                settingsRepository.setEnableProjects(state.enableProjects)
                settingsRepository.setUseTimelineSync(state.useTimelineSync)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to save config")
            }
        }
    }
}
