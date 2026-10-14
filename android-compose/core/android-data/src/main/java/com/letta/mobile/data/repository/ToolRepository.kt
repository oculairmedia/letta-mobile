package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.repository.api.ToolRemoteSource
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolUpdateParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.ToolUnavailableException
import kotlinx.coroutines.CancellationException

/**
 * The tool library: served by an Iroh host's `admin_rpc` relay (`tool.list` / `tool.get`).
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed), so without an Iroh host tool calls fail with a
 * no-route error.
 */
open class ToolRepository(
    private val remote: RemoteRoute<ToolRemoteSource> = RemoteRoute.Absent(SessionRoute.TOOLS),
    private val irohToolSource: IrohAdminRpcToolSource? = null,
) : IToolRepository {
    /** A repository over a bound [remote] (tests bind a fake). */
    constructor(remote: ToolRemoteSource, irohToolSource: IrohAdminRpcToolSource? = null) : this(remote.bound(), irohToolSource)

    private val _tools = MutableStateFlow<List<Tool>>(emptyList())
    private val _toolsByAgent = MutableStateFlow<Map<String, List<Tool>>>(emptyMap())
    private val refreshMutex = Mutex()
    private var lastRefreshAtMillis: Long = 0L

    override fun getTools(): StateFlow<List<Tool>> = _tools.asStateFlow()

    override fun getAgentTools(agentId: AgentId): Flow<List<Tool>> {
        return _toolsByAgent.map { it[agentId.value] ?: emptyList() }
    }

    /**
     * admin_rpc has no count method, so on an iroh:// backend the count is the size of the
     * `tool.list` catalog (refreshed when stale).
     */
    override suspend fun countTools(): Int {
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            refreshToolsIfStale(IROH_COUNT_MAX_AGE_MS)
            return _tools.value.size
        }
        return remote.source().countTools()
    }

    override suspend fun refreshTools() = refreshMutex.withLock {
        refreshToolsLocked()
    }

    private suspend fun refreshToolsLocked() {
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _tools.update { irohSource.listTools() }
            lastRefreshAtMillis = System.currentTimeMillis()
            return
        }
        _tools.update { remote.source().listTools() }
        lastRefreshAtMillis = System.currentTimeMillis()
    }

    fun hasFreshTools(maxAgeMs: Long): Boolean {
        return _tools.value.isNotEmpty() && System.currentTimeMillis() - lastRefreshAtMillis <= maxAgeMs
    }

    override suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshTools(maxAgeMs)) return@withLock false
        refreshToolsLocked()
        true
    }

    override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> {
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            return irohSource.listTools(limit = limit, offset = offset)
        }
        return remote.source().listTools(limit = limit, offset = offset)
    }

    /**
     * Under iroh:// the tool comes from `tool.get`, falling back to the cached `tool.list` catalog
     * when the host does not expose it. A miss is a typed NOT_FOUND, not a generic failure.
     */
    override suspend fun getTool(toolId: String): Tool {
        val irohSource = irohToolSource
        if (irohSource == null || !irohSource.shouldUseIroh()) return remote.source().getTool(ToolId(toolId))
        // tool.get first so a tool changed/deleted since the catalog loaded is not served stale;
        // the cached catalog is only the fallback for hosts without tool.get.
        return irohGetOrNull(irohSource, toolId)
            ?: findCached(toolId)
            ?: refreshAndFind(toolId)
    }

    private fun findCached(toolId: String): Tool? = _tools.value.firstOrNull { it.id.value == toolId }

    private suspend fun refreshAndFind(toolId: String): Tool {
        refreshMutex.withLock { refreshToolsLocked() }
        return findCached(toolId) ?: throw toolNotFound(toolId)
    }

    /** `tool.get`, or null when the host cannot answer it (caller falls back to the catalog). */
    private suspend fun irohGetOrNull(source: IrohAdminRpcToolSource, toolId: String): Tool? =
        try {
            source.getTool(ToolId(toolId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = e.message.orEmpty()
            when {
                detail.contains("not found", ignoreCase = true) -> throw toolNotFound(toolId, e)
                detail.contains("capability_unavailable") || detail.contains("unknown method", ignoreCase = true) -> null
                else -> throw e
            }
        }

    private fun toolNotFound(toolId: String, cause: Throwable? = null) =
        ToolUnavailableException(ToolUnavailableException.Reason.NOT_FOUND, "Tool $toolId was not found", cause)

    override suspend fun attachTool(agentId: AgentId, toolId: ToolId) {
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.attachTool(agentId, toolId)
        } else {
            remote.source().attachTool(agentId, toolId)
        }
        val tool = _tools.value.find { it.id == toolId }
        if (tool != null) {
            _toolsByAgent.update { current -> current.toMutableMap().apply {
                        val existing = get(agentId.value) ?: emptyList()
                        put(agentId.value, existing + tool)
                    } }
        }
    }

    override suspend fun detachTool(agentId: AgentId, toolId: ToolId) {
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.detachTool(agentId, toolId)
        } else {
            remote.source().detachTool(agentId, toolId)
        }
        _toolsByAgent.update { current -> current.toMutableMap().apply {
                    val existing = get(agentId.value) ?: emptyList()
                    put(agentId.value, existing.filter { it.id != toolId })
                } }
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool {
        val irohSource = irohToolSource
        val tool = if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.createTool(params)
        } else {
            remote.source().upsertTool(params)
        }
        _tools.update { current ->
            val index = current.indexOfFirst { it.id == tool.id }
            if (index >= 0) current.toMutableList().apply { this[index] = tool } else current + tool
        }
        return tool
    }

    override suspend fun updateTool(toolId: ToolId, params: ToolUpdateParams): Tool {
        val irohSource = irohToolSource
        val tool = if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.updateTool(toolId, params)
        } else {
            remote.source().updateTool(toolId, params)
        }
        _tools.update { current ->
            current.map { existing -> if (existing.id == tool.id) tool else existing }
        }
        _toolsByAgent.update { current ->
            current.mapValues { (_, tools) ->
                tools.map { existing -> if (existing.id == tool.id) tool else existing }
            }
        }
        return tool
    }

    override suspend fun deleteTool(toolId: ToolId) {
        val irohSource = irohToolSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.deleteTool(toolId)
        } else {
            remote.source().deleteTool(toolId)
        }
        _tools.update { current -> current.filterNot { it.id == toolId } }
        _toolsByAgent.update { current ->
            current.mapValues { (_, tools) -> tools.filterNot { it.id == toolId } }
        }
    }

    private companion object {
        /** How old the cached catalog may be before an iroh:// [countTools] lists the tools again. */
        const val IROH_COUNT_MAX_AGE_MS = 60_000L
    }
}
