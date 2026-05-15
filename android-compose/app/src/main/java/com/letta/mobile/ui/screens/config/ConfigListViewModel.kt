package com.letta.mobile.ui.screens.config

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import com.letta.mobile.channel.ChatPushAlarmScheduler
import com.letta.mobile.data.health.ServerHealthRepository
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class ServerConfig(
    val id: String,
    val mode: ServerMode,
    val url: String,
    val isActive: Boolean,
    val health: ServerHealthRepository.Health = ServerHealthRepository.Health.UNKNOWN,
)

@androidx.compose.runtime.Immutable
data class ConfigListUiState(
    val configs: ImmutableList<ServerConfig> = persistentListOf()
)

/**
 * letta-mobile-rl0d: Molecule-pilot rewrite of ConfigListViewModel.
 *
 * The previous incarnation built `uiState` from a 4-flow `combine
 * { ... }.stateIn(WhileSubscribed(5_000L), …)` block — readable but
 * boilerplate-heavy, especially once the action-error state needed
 * folding in alongside repository flows. With Molecule the same
 * derivation is a `@Composable` function that reads each flow with
 * `collectAsState` and returns the state directly. No combine
 * arithmetic, no SharingStarted tuning, and the order of operations
 * is exactly what the screen is going to render.
 *
 * Public API is unchanged (same `uiState: StateFlow<UiState<…>>`,
 * same imperative methods) so the screen and existing
 * ConfigListViewModelTest continue to work without modification.
 */
@HiltViewModel
class ConfigListViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val healthRepository: ServerHealthRepository,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _actionError = MutableStateFlow<String?>(null)

    // letta-mobile-rl0d: must be Immediate, not ContextClock. viewModelScope
    // uses Dispatchers.Main.immediate which carries no MonotonicFrameClock,
    // so ContextClock crashes with "A MonotonicFrameClock is not available
    // in this CoroutineContext" the first time anyone navigates to a screen
    // that injects this VM (e.g. BackendSwitcherSheet). Immediate recomposes
    // on every state change with no clock dependency — the right choice for
    // a non-UI presenter that derives state from flows.
    val uiState: StateFlow<UiState<ConfigListUiState>> =
        viewModelScope.launchMolecule(mode = RecompositionMode.Immediate) {
            present()
        }

    @Composable
    private fun present(): UiState<ConfigListUiState> {
        val configs by settingsRepository.configs.collectAsState()
        val active by settingsRepository.activeConfig.collectAsState()
        val healthStates by healthRepository.states.collectAsState()
        val error by _actionError.collectAsState()

        if (error != null) {
            return UiState.Error(error!!)
        }

        val activeId = active?.id
        val rows = configs.map { c ->
            ServerConfig(
                id = c.id,
                mode = if (c.mode == LettaConfig.Mode.CLOUD) ServerMode.CLOUD else ServerMode.SELF_HOSTED,
                url = c.serverUrl,
                isActive = c.id == activeId,
                health = healthStates[c.id] ?: ServerHealthRepository.Health.UNKNOWN,
            )
        }
        return UiState.Success(ConfigListUiState(configs = rows.toImmutableList()))
    }

    /**
     * letta-mobile-qmxn: fire a fresh wake-test pass against every
     * configured backend. Picker UIs call this from a `LaunchedEffect`
     * on open so the dots reflect the current state instead of whatever
     * was cached the last time the list was probed.
     */
    fun refreshHealth() {
        viewModelScope.launch {
            healthRepository.refreshAll()
        }
    }

    /**
     * Kept for [ConfigListScreen]'s error-retry path: clearing
     * [_actionError] lets the presenter recompute to Success from the
     * current repository snapshot.
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

