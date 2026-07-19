package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import kotlin.jvm.JvmInline
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class IrohToolRepositoryOps(
    private val directoryProvider: () -> IrohAdminRpcAgentDirectory?,
    private val toolsFlow: MutableStateFlow<List<Tool>>,
) {
    private var lastRefreshMs = 0L

    suspend fun refreshTools() {
        toolsFlow.value = fetchAllTools(directory())
        lastRefreshMs = Clock.System.now().toEpochMilliseconds()
    }

    suspend fun refreshToolsIfStale(maxAge: CacheMaxAgeMs): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        if (toolsFlow.value.isNotEmpty() && now - lastRefreshMs <= maxAge.value) return false
        refreshTools()
        return true
    }

    suspend fun fetchToolsPage(request: ToolPageRequest): List<Tool> =
        fetchToolsPage(directory(), request)

    suspend fun setToolAttachment(request: ToolAttachmentRequest) {
        directory().setToolAttached(request.agentId.value, request.toolId.value, request.attached)
    }

    suspend fun upsertTool(params: ToolCreateParams): Tool =
        directory().createTool(params).also(::upsertCache)

    suspend fun updateToolById(request: ToolUpdateByIdRequest): Tool =
        directory().updateTool(request.toolId.value, request.params).also(::upsertCache)

    suspend fun deleteToolById(toolId: ToolId) {
        directory().deleteTool(toolId.value)
        removeFromCache(toolId)
    }

    private suspend fun fetchAllTools(directory: IrohAdminRpcAgentDirectory): List<Tool> {
        val paging = ToolPagingState()
        while (true) {
            val page = fetchToolsPage(directory, paging.nextRequest())
            val newTools = page.filterNot { candidate -> paging.merged.any { it.id == candidate.id } }
            if (newTools.isEmpty()) break
            paging.merged += newTools
            if (page.size < PAGE_SIZE.value) break
            paging.advanceBy(ToolPageOffset(page.size))
        }
        return paging.merged
    }

    private suspend fun fetchToolsPage(
        directory: IrohAdminRpcAgentDirectory,
        request: ToolPageRequest,
    ): List<Tool> = directory.listTools(request.limit.value, request.offset.value)

    private fun upsertCache(tool: Tool) {
        toolsFlow.update { tools -> tools.filterNot { it.id == tool.id } + tool }
    }

    private fun removeFromCache(toolId: ToolId) {
        toolsFlow.update { tools -> tools.filterNot { it.id == toolId } }
    }

    private fun directory(): IrohAdminRpcAgentDirectory =
        directoryProvider() ?: error(DIRECTORY_UNAVAILABLE.value)

    companion object {
        val PAGE_SIZE = ToolPageLimit(100)
        const val DEFAULT_REFRESH_MAX_AGE_MS = 30_000L
        val DIRECTORY_UNAVAILABLE =
            DirectoryErrorMessage("Iroh admin RPC directory is unavailable for tools")
    }
}

internal data class ToolPageRequest(val limit: ToolPageLimit, val offset: ToolPageOffset)

internal data class ToolPagingState(
    val merged: MutableList<Tool> = mutableListOf(),
    var offset: ToolPageOffset = ToolPageOffset(0),
) {
    fun nextRequest(): ToolPageRequest = ToolPageRequest(IrohToolRepositoryOps.PAGE_SIZE, offset)

    fun advanceBy(delta: ToolPageOffset) {
        offset = ToolPageOffset(offset.value + delta.value)
    }
}

internal data class ToolAttachmentRequest(
    val agentId: AgentId,
    val toolId: ToolId,
    val attached: Boolean,
)

internal data class ToolUpdateByIdRequest(
    val toolId: ToolId,
    val params: ToolUpdateParams,
)

@JvmInline
internal value class CacheMaxAgeMs(val value: Long)

@JvmInline
internal value class ToolPageLimit(val value: Int)

@JvmInline
internal value class ToolPageOffset(val value: Int)

@JvmInline
internal value class DirectoryErrorMessage(val value: String)
