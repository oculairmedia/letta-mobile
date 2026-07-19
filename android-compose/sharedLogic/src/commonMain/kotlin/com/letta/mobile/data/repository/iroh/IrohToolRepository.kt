package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.AgentId
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

class IrohToolRepository(
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
        toolsFlow.value = fetchAllTools(directory())
        lastRefreshMs = System.currentTimeMillis()
    }

    override suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (toolsFlow.value.isNotEmpty() && now - lastRefreshMs <= maxAgeMs) return false
        refreshTools()
        return true
    }

    override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> =
        fetchToolsPage(directory(), ToolPageRequest(limit, offset))

    override suspend fun attachTool(agentId: String, toolId: String) {
        setToolAttachment(ToolAttachmentRequest(AgentId(agentId), ToolId(toolId), attached = true))
    }

    override suspend fun detachTool(agentId: String, toolId: String) {
        setToolAttachment(ToolAttachmentRequest(AgentId(agentId), ToolId(toolId), attached = false))
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool =
        directory().createTool(params).also(::upsertCache)

    override suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool =
        updateToolById(ToolId(toolId), params).also(::upsertCache)

    override suspend fun deleteTool(toolId: String) {
        deleteToolById(ToolId(toolId))
    }

    private suspend fun fetchAllTools(directory: IrohAdminRpcAgentDirectory): List<Tool> {
        val paging = ToolPagingState()
        while (true) {
            val page = fetchToolsPage(directory, paging.nextRequest())
            val newTools = page.filterNot { candidate -> paging.merged.any { it.id == candidate.id } }
            if (newTools.isEmpty()) break
            paging.merged += newTools
            if (page.size < PAGE_SIZE) break
            paging.advanceBy(page.size)
        }
        return paging.merged
    }

    private suspend fun fetchToolsPage(
        directory: IrohAdminRpcAgentDirectory,
        request: ToolPageRequest,
    ): List<Tool> = directory.listTools(request.limit, request.offset)

    private suspend fun setToolAttachment(request: ToolAttachmentRequest) {
        directory().setToolAttached(request.agentId.value, request.toolId.value, request.attached)
    }

    private suspend fun updateToolById(toolId: ToolId, params: ToolUpdateParams): Tool =
        directory().updateTool(toolId.value, params)

    private suspend fun deleteToolById(toolId: ToolId) {
        directory().deleteTool(toolId.value)
        removeFromCache(toolId)
    }

    private fun upsertCache(tool: Tool) {
        toolsFlow.update { tools -> tools.filterNot { it.id == tool.id } + tool }
    }

    private fun removeFromCache(toolId: ToolId) {
        toolsFlow.update { tools -> tools.filterNot { it.id == toolId } }
    }

    private fun directory(): IrohAdminRpcAgentDirectory =
        directoryProvider() ?: error("Iroh admin RPC directory is unavailable for tools")

    private data class ToolPageRequest(val limit: Int, val offset: Int)

    private data class ToolPagingState(
        val merged: MutableList<Tool> = mutableListOf(),
        var offset: Int = 0,
    ) {
        fun nextRequest(): ToolPageRequest = ToolPageRequest(PAGE_SIZE, offset)

        fun advanceBy(count: Int) {
            offset += count
        }
    }

    private data class ToolAttachmentRequest(
        val agentId: AgentId,
        val toolId: ToolId,
        val attached: Boolean,
    )

    private companion object {
        const val PAGE_SIZE = 100
        const val DEFAULT_REFRESH_MAX_AGE_MS = 30_000L
    }
}
