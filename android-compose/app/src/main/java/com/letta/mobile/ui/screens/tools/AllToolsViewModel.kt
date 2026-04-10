package com.letta.mobile.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.repository.McpServerRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class AllToolsUiState(
    val tools: List<Tool> = emptyList(),
    val mcpToolIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val isLoadingMore: Boolean = false,
    val hasMorePages: Boolean = true,
    val currentOffset: Int = 0,
)

@HiltViewModel
class AllToolsViewModel @Inject constructor(
    private val toolRepository: ToolRepository,
    private val mcpServerRepository: McpServerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<AllToolsUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<AllToolsUiState>> = _uiState.asStateFlow()

    companion object {
        const val PAGE_SIZE = 50
    }

    init {
        loadTools()
    }

    fun loadTools() {
        viewModelScope.launch {
            val searchQuery = (_uiState.value as? UiState.Success)?.data?.searchQuery.orEmpty()
            _uiState.value = UiState.Loading
            try {
                val mcpToolsDeferred = async { mcpServerRepository.fetchAllMcpTools() }
                val regularToolsDeferred = async {
                    toolRepository.fetchToolsPage(limit = PAGE_SIZE, offset = 0)
                }

                val mcpTools = mcpToolsDeferred.await()
                val regularTools = regularToolsDeferred.await()

                val mcpToolIds = mcpTools.map { it.id }.toSet()
                val dedupedRegular = regularTools.filter { it.id !in mcpToolIds }
                val combined = mcpTools + dedupedRegular

                _uiState.value = UiState.Success(
                    AllToolsUiState(
                        tools = combined,
                        mcpToolIds = mcpToolIds,
                        searchQuery = searchQuery,
                        isLoadingMore = false,
                        hasMorePages = regularTools.size >= PAGE_SIZE,
                        currentOffset = regularTools.size,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to load tools")
                )
            }
        }
    }

    fun loadMoreTools() {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        if (currentState.isLoadingMore || !currentState.hasMorePages) return

        _uiState.value = UiState.Success(currentState.copy(isLoadingMore = true))

        viewModelScope.launch {
            try {
                val newPage = toolRepository.fetchToolsPage(
                    limit = PAGE_SIZE,
                    offset = currentState.currentOffset,
                )

                val dedupedNew = newPage.filter { it.id !in currentState.mcpToolIds }

                _uiState.value = UiState.Success(
                    currentState.copy(
                        tools = currentState.tools + dedupedNew,
                        isLoadingMore = false,
                        hasMorePages = newPage.size >= PAGE_SIZE,
                        currentOffset = currentState.currentOffset + newPage.size,
                    )
                )
            } catch (_: Exception) {
                _uiState.value = UiState.Success(
                    currentState.copy(isLoadingMore = false)
                )
            }
        }
    }

    fun createTool(name: String, sourceCode: String) {
        viewModelScope.launch {
            try {
                toolRepository.upsertTool(ToolCreateParams(name = name, sourceCode = sourceCode))
                loadTools()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to create tool"))
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(searchQuery = query))
    }

    fun getFilteredTools(): List<Tool> {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        if (currentState.searchQuery.isBlank()) return currentState.tools
        val q = currentState.searchQuery.trim().lowercase()
        return currentState.tools.filter { tool ->
            tool.name.lowercase().contains(q) ||
                (tool.description?.lowercase()?.contains(q) == true) ||
                (tool.toolType?.lowercase()?.contains(q) == true) ||
                (tool.sourceType?.lowercase()?.contains(q) == true) ||
                tool.tags.any { it.lowercase().contains(q) }
        }
    }
}
