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
    val uiState: StateFlow<UiState<ConfigListUiState>> =
        settingsRepository.configs
            .combine(settingsRepository.activeConfig) { configs, active ->
                val activeId = active?.id
                val serverConfigs = configs.map {
                    ServerConfig(
                        id = it.id,
                        mode = if (it.mode == LettaConfig.Mode.CLOUD) ServerMode.CLOUD else ServerMode.SELF_HOSTED,
                        url = it.serverUrl,
                        isActive = it.id == activeId,
                    )
                }
                UiState.Success(ConfigListUiState(configs = serverConfigs.toImmutableList())) as UiState<ConfigListUiState>
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = UiState.Loading,
            )

    /**
     * Kept as a no-op compatibility shim. Old call sites in
     * [ConfigListScreen]'s error retry path call this; the flow-driven
     * `uiState` recomputes automatically whenever the underlying repository
     * StateFlows change, so explicit reloads are no longer required.
     */
    fun loadConfigs() {
        // Intentionally empty — uiState is now flow-driven.
    }

    fun setActiveConfig(configId: String) {
        viewModelScope.launch {
            runCatching { settingsRepository.setActiveConfigId(configId) }
        }
    }

    fun deleteConfig(configId: String) {
        viewModelScope.launch {
            runCatching { settingsRepository.deleteConfig(configId) }
            if (settingsRepository.activeConfig.value == null) {
                ChatPushAlarmScheduler.cancel(appContext)
            }
        }
    }
}
