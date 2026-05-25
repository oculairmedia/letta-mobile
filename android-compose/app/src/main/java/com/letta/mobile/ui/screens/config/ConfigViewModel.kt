package com.letta.mobile.ui.screens.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.letta.mobile.data.api.CloudConnectionValidationResult
import com.letta.mobile.data.api.CloudConnectionValidator
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.navigation.ConfigRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class ServerMode {
    CLOUD,
    SELF_HOSTED,
    LOCAL,
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
    val isSaving: Boolean = false,
)

@HiltViewModel
class ConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val settingsRepository: ISettingsRepository,
    private val cloudConnectionValidator: CloudConnectionValidator,
) : ViewModel() {

    companion object {
        const val DEFAULT_CLOUD_URL = "https://api.letta.com"
        const val LOCAL_RUNTIME_URL = "local://device"
    }

    // letta-mobile-cdlk: when the route arg signals create-new mode, the
    // screen opens with an empty form and saveConfig mints a fresh UUID
    // instead of overwriting the currently active config. Defaults to false
    // so every existing nav site keeps the original "edit active" behaviour.
    private val createNew: Boolean =
        runCatching { savedStateHandle.toRoute<ConfigRoute>().createNew }.getOrDefault(false)

    private val _uiState = MutableStateFlow<UiState<ConfigUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ConfigUiState>> = _uiState.asStateFlow()

    init {
        loadConfig()
    }

    fun loadConfig() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val activeConfig = settingsRepository.activeConfig.value
                val appTheme = settingsRepository.getTheme().first()
                val themePreset = settingsRepository.getThemePreset().first()
                val dynamicColor = settingsRepository.getDynamicColor().first()
                val enableProjects = settingsRepository.getEnableProjects().first()
                val configUiState = if (activeConfig != null && !createNew) {
                    ConfigUiState(
                        mode = activeConfig.mode.toServerMode(),
                        serverUrl = activeConfig.serverUrl,
                        apiToken = activeConfig.accessToken ?: "",
                        theme = appTheme,
                        themePreset = themePreset,
                        dynamicColor = dynamicColor,
                        enableProjects = enableProjects,
                    )
                } else {
                    // createNew = true: empty form, fresh UUID at save time.
                    // OR there's no active config yet (first-time setup).
                    // Mode falls through to ConfigUiState's default (CLOUD)
                    // — the existing first-time setup expects that default,
                    // and the user toggles to SELF_HOSTED inline if needed.
                    ConfigUiState(
                        theme = appTheme,
                        themePreset = themePreset,
                        dynamicColor = dynamicColor,
                        enableProjects = enableProjects,
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
            ServerMode.LOCAL -> LOCAL_RUNTIME_URL
            ServerMode.SELF_HOSTED ->
                if (currentState.serverUrl == DEFAULT_CLOUD_URL || currentState.serverUrl == LOCAL_RUNTIME_URL) {
                    ""
                } else {
                    currentState.serverUrl
                }
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

    fun saveConfig(onSuccess: () -> Unit, onError: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val state = (_uiState.value as? UiState.Success)?.data ?: return@launch
            if (state.isSaving) return@launch
            try {
                val url = when (state.mode) {
                    ServerMode.CLOUD -> DEFAULT_CLOUD_URL
                    ServerMode.LOCAL -> LOCAL_RUNTIME_URL
                    ServerMode.SELF_HOSTED -> {
                        val raw = state.serverUrl.trim()
                        if (raw.isBlank()) {
                            onError?.invoke("Server URL is required")
                            return@launch
                        }
                        if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                        else "https://$raw"
                    }
                }
                _uiState.value = UiState.Success(state.copy(isSaving = true))
                val apiToken = state.apiToken.trim()
                if (state.mode == ServerMode.CLOUD) {
                    if (apiToken.isBlank()) {
                        _uiState.value = UiState.Success(state.copy(isSaving = false))
                        onError?.invoke(
                            "Letta Cloud API key is required. Paste a key from app.letta.com before saving."
                        )
                        return@launch
                    }
                    when (val validation = cloudConnectionValidator.validate(url, apiToken)) {
                        CloudConnectionValidationResult.Success -> Unit
                        is CloudConnectionValidationResult.Failed -> {
                            _uiState.value = UiState.Success(state.copy(isSaving = false))
                            onError?.invoke(validation.message)
                            return@launch
                        }
                    }
                }
                // letta-mobile-cdlk: createNew bypasses the activeConfig
                // id lookup so '+ Add server' in the backend-switcher sheet
                // actually creates a new entry instead of overwriting the
                // currently active backend.
                val reuseId = if (createNew) null else settingsRepository.activeConfig.value?.id
                val config = LettaConfig(
                    id = reuseId ?: UUID.randomUUID().toString(),
                    mode = state.mode.toLettaMode(),
                    serverUrl = url,
                    accessToken = if (state.mode == ServerMode.LOCAL) null else apiToken.ifBlank { null },
                )
                settingsRepository.saveConfig(config)
                settingsRepository.setTheme(state.theme)
                settingsRepository.setThemePreset(state.themePreset)
                settingsRepository.setDynamicColor(state.dynamicColor)
                settingsRepository.setEnableProjects(state.enableProjects)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Success(state.copy(isSaving = false))
                onError?.invoke(e.message ?: "Failed to save config")
            }
        }
    }
}

private fun LettaConfig.Mode.toServerMode(): ServerMode = when (this) {
    LettaConfig.Mode.CLOUD -> ServerMode.CLOUD
    LettaConfig.Mode.SELF_HOSTED -> ServerMode.SELF_HOSTED
    LettaConfig.Mode.LOCAL -> ServerMode.LOCAL
}

private fun ServerMode.toLettaMode(): LettaConfig.Mode = when (this) {
    ServerMode.CLOUD -> LettaConfig.Mode.CLOUD
    ServerMode.SELF_HOSTED -> LettaConfig.Mode.SELF_HOSTED
    ServerMode.LOCAL -> LettaConfig.Mode.LOCAL
}
