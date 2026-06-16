package com.letta.mobile.data.tools

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IToolRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ToolLibraryState(
    val tools: List<Tool> = emptyList(),
    val mcpToolIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val selectedTags: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isLoadingMcpTools: Boolean = false,
    val hasMorePages: Boolean = true,
    val currentOffset: Int = 0,
    val errorMessage: String? = null,
) {
    val filteredTools: List<Tool>
        get() = ToolLibraryFilter.filter(
            tools = tools,
            searchQuery = searchQuery,
            selectedTags = selectedTags,
        )

    val allTags: List<String>
        get() = tools
            .flatMap { it.tags }
            .distinct()
            .sorted()

    val emptyMessage: String
        get() = if (searchQuery.isBlank()) {
            "No tools available."
        } else {
            "No tools match \"$searchQuery\"."
        }
}

object ToolLibraryFilter {
    fun filter(
        tools: List<Tool>,
        searchQuery: String,
        selectedTags: Set<String>,
    ): List<Tool> {
        var result = tools

        if (selectedTags.isNotEmpty()) {
            result = result.filter { tool ->
                selectedTags.all { tag -> tag in tool.tags }
            }
        }

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            result = result.filter { tool ->
                tool.name.lowercase().contains(q) ||
                    (tool.description?.lowercase()?.contains(q) == true) ||
                    (tool.toolType?.lowercase()?.contains(q) == true) ||
                    (tool.sourceType?.lowercase()?.contains(q) == true) ||
                    tool.tags.any { it.lowercase().contains(q) }
            }
        }

        return result.distinctBy { it.id.value }
    }
}

class ToolLibraryController(
    private val toolRepository: IToolRepository,
    private val mcpServerRepository: IMcpServerRepository,
    private val scope: CoroutineScope,
    private val errorMessageMapper: (Throwable) -> String = { throwable ->
        throwable.message ?: "Failed to load tools."
    },
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : AutoCloseable {
    private val stateFlow = MutableStateFlow(ToolLibraryState())
    val state: StateFlow<ToolLibraryState> = stateFlow

    private var loadGeneration: Int = 0
    private var loadJob: Job? = null
    private var mcpJob: Job? = null
    private var loadMoreJob: Job? = null

    fun start() {
        if (stateFlow.value.tools.isEmpty()) {
            loadTools()
        }
    }

    fun loadTools() {
        loadJob?.cancel()
        mcpJob?.cancel()
        loadJob = scope.launch {
            val generation = ++loadGeneration
            val previousState = stateFlow.value
            stateFlow.value = previousState.copy(
                isLoading = previousState.tools.isEmpty(),
                isLoadingMore = false,
                errorMessage = null,
            )
            try {
                val regularTools = toolRepository.fetchToolsPage(limit = pageSize, offset = 0)
                stateFlow.value = stateFlow.value.copy(
                    tools = regularTools,
                    mcpToolIds = emptySet(),
                    isLoading = false,
                    isLoadingMore = false,
                    isLoadingMcpTools = true,
                    hasMorePages = regularTools.size >= pageSize,
                    currentOffset = regularTools.size,
                    errorMessage = null,
                )
                loadMcpTools(generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                stateFlow.value = previousState.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    isLoadingMcpTools = false,
                    errorMessage = errorMessageMapper(t),
                )
            }
        }
    }

    fun loadMoreTools() {
        val currentState = stateFlow.value
        if (currentState.isLoadingMore || !currentState.hasMorePages) return
        val generation = loadGeneration
        val requestedOffset = currentState.currentOffset

        stateFlow.value = currentState.copy(isLoadingMore = true, errorMessage = null)
        loadMoreJob?.cancel()
        loadMoreJob = scope.launch {
            try {
                val newPage = toolRepository.fetchToolsPage(
                    limit = pageSize,
                    offset = requestedOffset,
                )
                if (generation != loadGeneration) return@launch

                val latestState = stateFlow.value
                val existingIds = latestState.tools.mapTo(mutableSetOf()) { it.id.value }
                val dedupedNew = newPage.filter { tool ->
                    tool.id.value !in latestState.mcpToolIds && existingIds.add(tool.id.value)
                }
                stateFlow.value = latestState.copy(
                    tools = latestState.tools + dedupedNew,
                    isLoadingMore = false,
                    hasMorePages = newPage.size >= pageSize,
                    currentOffset = maxOf(latestState.currentOffset, requestedOffset + newPage.size),
                    errorMessage = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (generation != loadGeneration) return@launch
                stateFlow.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        stateFlow.update { it.copy(searchQuery = query) }
    }

    fun toggleTag(tag: String) {
        stateFlow.update { current ->
            val updated = if (tag in current.selectedTags) {
                current.selectedTags - tag
            } else {
                current.selectedTags + tag
            }
            current.copy(selectedTags = updated)
        }
    }

    fun clearTags() {
        stateFlow.update { it.copy(selectedTags = emptySet()) }
    }

    fun showError(message: String) {
        stateFlow.update {
            it.copy(
                isLoading = false,
                isLoadingMore = false,
                isLoadingMcpTools = false,
                errorMessage = message,
            )
        }
    }

    override fun close() {
        loadJob?.cancel()
        mcpJob?.cancel()
        loadMoreJob?.cancel()
    }

    private fun loadMcpTools(generation: Int) {
        mcpJob?.cancel()
        mcpJob = scope.launch {
            try {
                val mcpTools = mcpServerRepository.fetchAllMcpTools()
                if (generation != loadGeneration) return@launch

                val latestState = stateFlow.value
                val mcpToolIds = mcpTools.map { it.id.value }.toSet()
                val dedupedRegular = latestState.tools.filter { it.id.value !in mcpToolIds }
                stateFlow.value = latestState.copy(
                    tools = mcpTools + dedupedRegular,
                    mcpToolIds = mcpToolIds,
                    isLoadingMcpTools = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (generation != loadGeneration) return@launch
                stateFlow.update { it.copy(isLoadingMcpTools = false) }
            }
        }
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
