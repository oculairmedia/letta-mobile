package com.letta.mobile.ui.screens.lettabot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.screens.settings.ClientModeConnectionState
import com.letta.mobile.ui.screens.settings.ClientModeConnectionTester
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the global LettaBot connection settings pane (gb57.9).
 *
 * Mirrors the legacy [com.letta.mobile.ui.screens.settings.AgentSettingsUiState] client-mode fields, but is
 * scoped to a dedicated screen so the LettaBot connection settings are no longer
 * tied to a single agent's settings UI.
 */
@androidx.compose.runtime.Immutable
data class LettaBotConnectionUiState(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val connectionState: ClientModeConnectionState = ClientModeConnectionState.Idle,
    val isSaving: Boolean = false,
)

/**
 * ViewModel for the LettaBot connection settings pane.
 *
 * Owns the global LettaBot connection config (URL, API key, master toggle). The
 * underlying storage already lives in [SettingsRepository] as app-wide DataStore /
 * EncryptedPrefs entries — this VM just exposes the same surface previously bolted
 * onto AgentSettingsViewModel, but on a dedicated screen.
 */
@HiltViewModel
class LettaBotConnectionViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val connectionTester: ClientModeConnectionTester,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<LettaBotConnectionUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<LettaBotConnectionUiState>> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val enabled = settingsRepository.observeClientModeEnabled().first()
                val baseUrl = settingsRepository.observeClientModeBaseUrl().first()
                val apiKey = settingsRepository.getClientModeApiKey().orEmpty()
                _uiState.value = UiState.Success(
                    LettaBotConnectionUiState(
                        enabled = enabled,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to load LettaBot connection settings")
                )
            }
        }
    }

    fun setEnabled(value: Boolean) {
        currentState()?.let { state ->
            _uiState.value = UiState.Success(
                state.copy(
                    enabled = value,
                    connectionState = ClientModeConnectionState.Idle,
                )
            )
        }
    }

    fun setBaseUrl(value: String) {
        currentState()?.let { state ->
            _uiState.value = UiState.Success(
                state.copy(
                    baseUrl = value,
                    connectionState = ClientModeConnectionState.Idle,
                )
            )
        }
    }

    fun setApiKey(value: String) {
        currentState()?.let { state ->
            _uiState.value = UiState.Success(
                state.copy(
                    apiKey = value,
                    connectionState = ClientModeConnectionState.Idle,
                )
            )
        }
    }

    fun testConnection() {
        val state = currentState() ?: return
        val baseUrl = state.baseUrl.trim()
        val apiKey = state.apiKey.trim().ifBlank { null }

        if (baseUrl.isBlank()) {
            _uiState.value = UiState.Success(
                state.copy(
                    connectionState = ClientModeConnectionState.Failure(
                        message = "Enter a server URL first",
                        testedAtMillis = System.currentTimeMillis(),
                    )
                )
            )
            return
        }

        viewModelScope.launch {
            currentState()?.let { current ->
                _uiState.value = UiState.Success(
                    current.copy(connectionState = ClientModeConnectionState.Testing)
                )
            }

            val result = connectionTester.test(baseUrl = baseUrl, apiKey = apiKey)
            val timestamp = System.currentTimeMillis()
            currentState()?.let { current ->
                _uiState.value = UiState.Success(
                    current.copy(
                        connectionState = result.fold(
                            onSuccess = { ClientModeConnectionState.Success(timestamp) },
                            onFailure = {
                                val error = it as? Exception
                                    ?: RuntimeException(it.message ?: "Connection test failed", it)
                                ClientModeConnectionState.Failure(
                                    message = mapErrorToUserMessage(error, "Connection test failed"),
                                    testedAtMillis = timestamp,
                                )
                            },
                        )
                    )
                )
            }

            delay(5_000)

            currentState()?.let { latest ->
                if (latest.connectionState !is ClientModeConnectionState.Testing) {
                    _uiState.value = UiState.Success(
                        latest.copy(connectionState = ClientModeConnectionState.Idle)
                    )
                }
            }
        }
    }

    fun save(onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val state = currentState() ?: return
        viewModelScope.launch {
            _uiState.value = UiState.Success(state.copy(isSaving = true))
            try {
                settingsRepository.setClientModeEnabled(state.enabled)
                settingsRepository.setClientModeBaseUrl(state.baseUrl.trim())
                settingsRepository.setClientModeApiKey(state.apiKey.trim().ifBlank { null })
                _uiState.value = UiState.Success(state.copy(isSaving = false))
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Success(state.copy(isSaving = false))
                onError(mapErrorToUserMessage(e, "Failed to save LettaBot connection settings"))
            }
        }
    }

    private fun currentState(): LettaBotConnectionUiState? =
        (_uiState.value as? UiState.Success)?.data
}
