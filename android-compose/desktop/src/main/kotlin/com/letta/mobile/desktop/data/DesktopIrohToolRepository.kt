package com.letta.mobile.desktop.data

import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

class DesktopIrohToolRepository(
    private val directoryProvider: () -> IrohAdminRpcAgentDirectory?,
) : IToolRepository {
    private val toolsFlow = MutableStateFlow<List<Tool>>(emptyList())
    private var lastRefreshMs = 0L

    override fun getTools(): StateFlow<List<Tool>> = toolsFlow

    override fun getAgentTools(agentId: String): Flow<List<Tool>> =
        flowOf(emptyList())

    override suspend fun countTools(): Int {
        refreshToolsIfStale(DEFAULT_REFRESH_MAX_AGE_MS)
        return toolsFlow.value.size
    }

    override suspend fun refreshTools() {
        val directory = directory()
        val merged = mutableListOf<Tool>()
        var offset = 0
        while (true) {
            val page = directory.listTools(PAGE_SIZE, offset)
            merged += page.filterNot { candidate -> merged.any { it.id == candidate.id } }
            if (page.size < PAGE_SIZE) break
            offset += page.size
        }
        toolsFlow.value = merged
        lastRefreshMs = System.currentTimeMillis()
    }

    override suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (toolsFlow.value.isNotEmpty() && now - lastRefreshMs <= maxAgeMs) return false
        refreshTools()
        return true
    }

    override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> =
        directory().listTools(limit, offset)

    override suspend fun attachTool(agentId: String, toolId: String) {
        directory().setToolAttached(agentId, toolId, attached = true)
    }

    override suspend fun detachTool(agentId: String, toolId: String) {
        directory().setToolAttached(agentId, toolId, attached = false)
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool =
        directory().createTool(params).also(::upsertCache)

    override suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool =
        directory().updateTool(toolId, params).also(::upsertCache)

    override suspend fun deleteTool(toolId: String) {
        directory().deleteTool(toolId)
        toolsFlow.update { tools -> tools.filterNot { it.id == ToolId(toolId) } }
    }

    private fun upsertCache(tool: Tool) {
        toolsFlow.update { tools -> tools.filterNot { it.id == tool.id } + tool }
    }

    private fun directory(): IrohAdminRpcAgentDirectory =
        directoryProvider() ?: throw DesktopRepositoryUnavailableException("IrohAdminRpcAgentDirectory", "tools")

    private companion object {
        const val PAGE_SIZE = 100
        const val DEFAULT_REFRESH_MAX_AGE_MS = 30_000L
    }
}
