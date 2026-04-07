package com.letta.mobile.ui.screens.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val isHealthy: Boolean,
    val tools: List<String>
)

data class McpUiState(
    val selectedTab: Int = 0,
    val servers: List<McpServer> = emptyList(),
    val allTools: List<com.letta.mobile.data.model.Tool> = emptyList()
)

@HiltViewModel
class McpViewModel @Inject constructor() : ViewModel() {
    
    private val _uiState = MutableStateFlow<UiState<McpUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<McpUiState>> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                TODO("Wire to repository")
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load MCP data")
            }
        }
    }

    fun selectTab(index: Int) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(selectedTab = index))
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            try {
                TODO("Wire to repository")
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to delete server")
            }
        }
    }

    fun addServer(name: String, url: String) {
        viewModelScope.launch {
            try {
                TODO("Wire to repository")
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to add server")
            }
        }
    }
}
