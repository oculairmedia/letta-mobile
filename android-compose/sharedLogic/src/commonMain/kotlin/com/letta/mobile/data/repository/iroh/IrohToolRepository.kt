package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.repository.api.IToolRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

class IrohToolRepository(
    directoryProvider: () -> IrohAdminRpcAgentDirectory?,
) : IToolRepository {
    private val toolsFlow = MutableStateFlow<List<Tool>>(emptyList())
    private val ops = IrohToolRepositoryOps(directoryProvider, toolsFlow)

    override fun getTools(): StateFlow<List<Tool>> = toolsFlow

    override fun getAgentTools(agentId: String): Flow<List<Tool>> =
        flowOf(emptyList())

    override suspend fun countTools(): Int {
        ops.refreshToolsIfStale(CacheMaxAgeMs(IrohToolRepositoryOps.DEFAULT_REFRESH_MAX_AGE_MS))
        return toolsFlow.value.size
    }

    override suspend fun refreshTools() = ops.refreshTools()

    override suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean =
        ops.refreshToolsIfStale(CacheMaxAgeMs(maxAgeMs))

    override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> =
        ops.fetchToolsPage(ToolPageRequest(ToolPageLimit(limit), ToolPageOffset(offset)))

    override suspend fun attachTool(agentId: String, toolId: String) =
        ops.setToolAttachment(ToolAttachmentRequest(AgentId(agentId), ToolId(toolId), attached = true))

    override suspend fun detachTool(agentId: String, toolId: String) =
        ops.setToolAttachment(ToolAttachmentRequest(AgentId(agentId), ToolId(toolId), attached = false))

    override suspend fun upsertTool(params: ToolCreateParams): Tool =
        ops.upsertTool(params)

    override suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool =
        ops.updateToolById(ToolUpdateByIdRequest(ToolId(toolId), params))

    override suspend fun deleteTool(toolId: String) =
        ops.deleteToolById(ToolId(toolId))
}
