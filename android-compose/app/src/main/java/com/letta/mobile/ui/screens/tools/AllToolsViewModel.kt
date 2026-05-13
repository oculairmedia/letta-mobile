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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class AllToolsUiState(
    val tools: ImmutableList<Tool> = persistentListOf(),
    val mcpToolIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val selectedTags: Set<String> = emptySet(),
    val isLoadingMore: Boolean = false,
    val isLoadingMcpTools: Boolean = false,
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
    private var loadGeneration: Int = 0

    companion object {
        const val PAGE_SIZE = 50
    }

    init {
        loadTools()
    }

    fun loadTools() {
        viewModelScope.launch {
            val generation = ++loadGeneration
            val previousState = (_uiState.value as? UiState.Success)?.data
            val searchQuery = previousState?.searchQuery.orEmpty()
            val selectedTags = previousState?.selectedTags.orEmpty()
            if (previousState == null) {
                _uiState.value = UiState.Loading
            }
            try {
                val regularTools = toolRepository.fetchToolsPage(limit = PAGE_SIZE, offset = 0)

                _uiState.value = UiState.Success(
                    AllToolsUiState(
                        tools = regularTools.toImmutableList(),
                        mcpToolIds = emptySet(),
                        searchQuery = searchQuery,
                        selectedTags = selectedTags,
                        isLoadingMore = false,
                        isLoadingMcpTools = true,
                        hasMorePages = regularTools.size >= PAGE_SIZE,
                        currentOffset = regularTools.size,
                    )
                )

                loadMcpTools(generation)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to load tools")
                )
            }
        }
    }

    private fun loadMcpTools(generation: Int) {
        viewModelScope.launch {
            val currentState = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                val mcpTools = mcpServerRepository.fetchAllMcpTools()
                if (generation != loadGeneration) return@launch

                val latestState = (_uiState.value as? UiState.Success)?.data ?: currentState
                val mcpToolIds = mcpTools.map { it.id }.toSet()
                val dedupedRegular = latestState.tools.filter { it.id !in mcpToolIds }

                _uiState.value = UiState.Success(
                    latestState.copy(
                        tools = (mcpTools + dedupedRegular).toImmutableList(),
                        mcpToolIds = mcpToolIds.map { it.value }.toSet(),
                        isLoadingMcpTools = false,
                    )
                )
            } catch (_: Exception) {
                if (generation != loadGeneration) return@launch
                val latestState = (_uiState.value as? UiState.Success)?.data ?: currentState
                _uiState.value = UiState.Success(latestState.copy(isLoadingMcpTools = false))
            }
        }
    }

    fun loadMoreTools() {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        if (currentState.isLoadingMore || !currentState.hasMorePages) return
        val generation = loadGeneration
        val requestedOffset = currentState.currentOffset

        _uiState.value = UiState.Success(currentState.copy(isLoadingMore = true))

        viewModelScope.launch {
            try {
                val newPage = toolRepository.fetchToolsPage(
                    limit = PAGE_SIZE,
                    offset = requestedOffset,
                )

                if (generation != loadGeneration) return@launch

                val latestState = (_uiState.value as? UiState.Success)?.data ?: return@launch
                val existingIds = latestState.tools.mapTo(mutableSetOf()) { it.id.value }
                val dedupedNew = newPage.filter { tool ->
                    tool.id.value !in latestState.mcpToolIds && existingIds.add(tool.id.value)
                }

                _uiState.value = UiState.Success(
                    latestState.copy(
                        tools = (latestState.tools + dedupedNew).toImmutableList(),
                        isLoadingMore = false,
                        hasMorePages = newPage.size >= PAGE_SIZE,
                        currentOffset = maxOf(latestState.currentOffset, requestedOffset + newPage.size),
                    )
                )
            } catch (_: Exception) {
                if (generation != loadGeneration) return@launch
                val latestState = (_uiState.value as? UiState.Success)?.data ?: currentState
                _uiState.value = UiState.Success(
                    latestState.copy(isLoadingMore = false)
                )
            }
        }
    }

    fun createTool(sourceCode: String) {
        viewModelScope.launch {
            try {
                toolRepository.upsertTool(ToolCreateParams(sourceCode = sourceCode))
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
        var result: List<Tool> = currentState.tools

        if (currentState.selectedTags.isNotEmpty()) {
            result = result.filter { tool ->
                currentState.selectedTags.all { tag -> tag in tool.tags }
            }
        }

        if (currentState.searchQuery.isNotBlank()) {
            val q = currentState.searchQuery.trim().lowercase()
            result = result.filter { tool ->
                tool.name.lowercase().contains(q) ||
                    (tool.description?.lowercase()?.contains(q) == true) ||
                    (tool.toolType?.lowercase()?.contains(q) == true) ||
                    (tool.sourceType?.lowercase()?.contains(q) == true) ||
                    tool.tags.any { it.lowercase().contains(q) }
            }
        }

        return result.distinctBy { it.id }
    }

    fun getAllTags(): List<String> {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        return currentState.tools
            .flatMap { it.tags }
            .distinct()
            .sorted()
    }

    fun toggleTag(tag: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        val current = currentState.selectedTags
        val updated = if (tag in current) current - tag else current + tag
        _uiState.value = UiState.Success(currentState.copy(selectedTags = updated))
    }

    fun clearTags() {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(selectedTags = emptySet()))
    }
}
