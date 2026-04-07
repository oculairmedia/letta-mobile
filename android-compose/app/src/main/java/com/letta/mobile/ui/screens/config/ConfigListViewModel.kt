package com.letta.mobile.ui.screens.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerConfig(
    val id: String,
    val mode: ServerMode,
    val url: String,
    val isActive: Boolean
)

data class ConfigListUiState(
    val configs: List<ServerConfig> = emptyList()
)

@HiltViewModel
class ConfigListViewModel @Inject constructor() : ViewModel() {
    
    private val _uiState = MutableStateFlow<UiState<ConfigListUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ConfigListUiState>> = _uiState.asStateFlow()

    init {
        loadConfigs()
    }

    fun loadConfigs() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                TODO("Wire to repository")
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load configs")
            }
        }
    }

    fun setActiveConfig(configId: String) {
        viewModelScope.launch {
            try {
                TODO("Wire to repository")
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to set active config")
            }
        }
    }

    fun deleteConfig(configId: String) {
        viewModelScope.launch {
            try {
                TODO("Wire to repository")
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to delete config")
            }
        }
    }
}
