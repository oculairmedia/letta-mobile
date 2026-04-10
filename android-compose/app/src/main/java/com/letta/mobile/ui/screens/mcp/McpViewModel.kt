package com.letta.mobile.ui.screens.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.McpServerRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class McpServerCheckState(
    val isChecking: Boolean = false,
    val isReachable: Boolean? = null,
    val message: String? = null,
)

@androidx.compose.runtime.Immutable
data class McpToolParent(
    val serverId: String,
    val serverName: String,
)

@androidx.compose.runtime.Immutable
data class McpUiState(
    val selectedTab: Int = 0,
    val servers: List<McpServer> = emptyList(),
    val allTools: List<Tool> = emptyList(),
    val serverTools: Map<String, List<Tool>> = emptyMap(),
    val serverChecks: Map<String, McpServerCheckState> = emptyMap(),
    val toolParents: Map<String, McpToolParent> = emptyMap(),
)

@HiltViewModel
class McpViewModel @Inject constructor(
    private val mcpServerRepository: McpServerRepository,
    private val toolRepository: ToolRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<McpUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<McpUiState>> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            val currentChecks = (_uiState.value as? UiState.Success)?.data?.serverChecks.orEmpty()
            _uiState.value = UiState.Loading
            try {
                mcpServerRepository.refreshServers()
                toolRepository.refreshTools()
                
                val servers = mcpServerRepository.servers.value
                val serverToolsMap = mutableMapOf<String, List<Tool>>()
                val toolParents = mutableMapOf<String, McpToolParent>()
                
                servers.forEach { server ->
                    mcpServerRepository.refreshServerTools(server.id)
                    val tools = mcpServerRepository.getServerTools(server.id).first()
                    serverToolsMap[server.id] = tools
                    tools.forEach { tool ->
                        toolParents.putIfAbsent(
                            tool.id,
                            McpToolParent(serverId = server.id, serverName = server.serverName),
                        )
                    }
                }
                
                val allTools = toolRepository.getTools().first()
                _uiState.value = UiState.Success(
                    McpUiState(
                        servers = servers,
                        allTools = allTools,
                        serverTools = serverToolsMap,
                        serverChecks = currentChecks,
                        toolParents = toolParents,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to load MCP data")
                )
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
                mcpServerRepository.deleteServer(serverId)
                loadData()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to delete server")
                )
            }
        }
    }

    fun addServer(params: McpServerCreateParams) {
        viewModelScope.launch {
            try {
                mcpServerRepository.createServer(params)
                loadData()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to add server")
                )
            }
        }
    }

    fun updateServer(serverId: String, params: McpServerUpdateParams) {
        viewModelScope.launch {
            try {
                mcpServerRepository.updateServer(serverId, params)
                loadData()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to update server")
                )
            }
        }
    }

    fun refreshAll() {
        loadData()
    }

    fun checkServer(serverId: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            _uiState.value = UiState.Success(
                current.copy(
                    serverChecks = current.serverChecks + (serverId to McpServerCheckState(isChecking = true))
                )
            )
            try {
                val resync = mcpServerRepository.resyncServerTools(serverId)
                val tools = mcpServerRepository.getServerTools(serverId).first()
                val updated = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    updated.copy(
                        serverTools = updated.serverTools + (serverId to tools),
                        serverChecks = updated.serverChecks + (
                            serverId to McpServerCheckState(
                                isChecking = false,
                                isReachable = true,
                                message = buildReachabilityMessage(tools.size, resync),
                            )
                        ),
                    )
                )
            } catch (e: Exception) {
                val updated = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    updated.copy(
                        serverChecks = updated.serverChecks + (
                            serverId to McpServerCheckState(
                                isChecking = false,
                                isReachable = false,
                                message = mapErrorToUserMessage(e, "Failed to refresh discovered tools"),
                            )
                        )
                    )
                )
            }
        }
    }

    private fun buildReachabilityMessage(
        toolCount: Int,
        resync: com.letta.mobile.data.model.McpServerResyncResult,
    ): String {
        val summaryParts = buildList {
            if (resync.added.isNotEmpty()) add("+${resync.added.size} added")
            if (resync.updated.isNotEmpty()) add("~${resync.updated.size} updated")
            if (resync.deleted.isNotEmpty()) add("-${resync.deleted.size} deleted")
        }

        val discovery = if (toolCount == 0) {
            "No tools discovered"
        } else {
            "$toolCount tools discovered"
        }

        return if (summaryParts.isEmpty()) discovery else "$discovery · ${summaryParts.joinToString(", ")}"
    }
}
