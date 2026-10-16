package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.ToolRemoteSource
import com.letta.mobile.data.repository.api.ToolUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * The tool library: served by an Iroh host's `admin_rpc` relay (`tool.list` / `tool.get`).
 * letta-mobile-by4vm.103: the one tool repository Android and desktop sessions build (through
 * [com.letta.mobile.data.session.graph.adminRepositories]).
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

    private val catalog = ToolCatalogCache()
    private val refreshMutex = Mutex()

    override fun getTools(): StateFlow<List<Tool>> = catalog.tools

    override fun getAgentTools(agentId: AgentId): Flow<List<Tool>> = catalog.agentTools(agentId)

    /**
     * admin_rpc has no count method, so on an iroh:// backend the count is the size of the
     * `tool.list` catalog (refreshed when stale).
     */
    override suspend fun countTools(): Int {
        if (activeIroh() == null) return remote.source().countTools()
        refreshToolsIfStale(IROH_COUNT_MAX_AGE_MS)
        return catalog.tools.value.size
    }

    override suspend fun refreshTools() = refreshMutex.withLock { refreshToolsLocked() }

    private suspend fun refreshToolsLocked() {
        catalog.replace(irohOrRemote(iroh = { it.listTools() }, remote = { it.listTools() }))
    }

    fun hasFreshTools(maxAgeMs: Long): Boolean = catalog.isFresh(maxAgeMs)

    override suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshTools(maxAgeMs)) return@withLock false
        refreshToolsLocked()
        true
    }

    override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> = irohOrRemote(
        iroh = { it.listTools(limit = limit, offset = offset) },
        remote = { it.listTools(limit = limit, offset = offset) },
    )

    /**
     * Under iroh:// the tool comes from `tool.get`, falling back to the cached `tool.list` catalog
     * when the host does not expose it. A miss is a typed NOT_FOUND, not a generic failure.
     */
    override suspend fun getTool(toolId: String): Tool {
        val id = ToolId(toolId)
        val irohSource = activeIroh() ?: return remote.source().getTool(id)
        // tool.get first so a tool changed/deleted since the catalog loaded is not served stale;
        // the cached catalog is only the fallback for hosts without tool.get.
        return irohGetOrNull(irohSource, id)
            ?: catalog.find(id)
            ?: refreshAndFind(id)
    }

    private suspend fun refreshAndFind(toolId: ToolId): Tool {
        refreshMutex.withLock { refreshToolsLocked() }
        return catalog.find(toolId) ?: throw toolNotFound(toolId)
    }

    override suspend fun attachTool(agentId: AgentId, toolId: ToolId) {
        irohOrRemote(iroh = { it.attachTool(agentId, toolId) }, remote = { it.attachTool(agentId, toolId) })
        catalog.attach(agentId, toolId)
    }

    override suspend fun detachTool(agentId: AgentId, toolId: ToolId) {
        irohOrRemote(iroh = { it.detachTool(agentId, toolId) }, remote = { it.detachTool(agentId, toolId) })
        catalog.detach(agentId, toolId)
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool =
        irohOrRemote(iroh = { it.createTool(params) }, remote = { it.upsertTool(params) })
            .also(catalog::upsert)

    override suspend fun updateTool(toolId: ToolId, params: ToolUpdateParams): Tool =
        irohOrRemote(iroh = { it.updateTool(toolId, params) }, remote = { it.updateTool(toolId, params) })
            .also(catalog::replaceEverywhere)

    override suspend fun deleteTool(toolId: ToolId) {
        irohOrRemote(iroh = { it.deleteTool(toolId) }, remote = { it.deleteTool(toolId) })
        catalog.remove(toolId)
    }

    /** The Iroh source while the active backend is Iroh, else null (the remote route serves). */
    private fun activeIroh(): IrohAdminRpcToolSource? = irohToolSource?.takeIf { it.shouldUseIroh() }

    private suspend inline fun <T> irohOrRemote(
        iroh: (IrohAdminRpcToolSource) -> T,
        remote: (ToolRemoteSource) -> T,
    ): T {
        val irohSource = activeIroh()
        return if (irohSource != null) iroh(irohSource) else remote(this.remote.source())
    }

    private companion object {
        /** How old the cached catalog may be before an iroh:// [countTools] lists the tools again. */
        const val IROH_COUNT_MAX_AGE_MS = 60_000L
    }
}

/** `tool.get`, or null when the host cannot answer it (caller falls back to the catalog). */
private suspend fun irohGetOrNull(source: IrohAdminRpcToolSource, toolId: ToolId): Tool? =
    try {
        source.getTool(toolId)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        val detail = e.message.orEmpty()
        when {
            detail.contains("not found", ignoreCase = true) -> throw toolNotFound(toolId, e)
            detail.contains("capability_unavailable") || detail.contains("unknown method", ignoreCase = true) -> null
            else -> throw e
        }
    }

private fun toolNotFound(toolId: ToolId, cause: Throwable? = null) =
    ToolUnavailableException(ToolUnavailableException.Reason.NOT_FOUND, "Tool ${toolId.value} was not found", cause)

/** The cached `tool.list` catalog and the per-agent attachments this session has made. */
private class ToolCatalogCache {
    private val catalog = MutableStateFlow<List<Tool>>(emptyList())
    private val byAgent = MutableStateFlow<Map<AgentId, List<Tool>>>(emptyMap())
    private var lastRefreshAtMillis: Long = 0L

    val tools: StateFlow<List<Tool>> = catalog.asStateFlow()

    fun agentTools(agentId: AgentId): Flow<List<Tool>> = byAgent.map { it[agentId].orEmpty() }

    fun replace(tools: List<Tool>) {
        catalog.value = tools
        lastRefreshAtMillis = nowMillis()
    }

    fun isFresh(maxAgeMs: Long): Boolean =
        catalog.value.isNotEmpty() && nowMillis() - lastRefreshAtMillis <= maxAgeMs

    fun find(toolId: ToolId): Tool? = catalog.value.firstOrNull { it.id == toolId }

    fun attach(agentId: AgentId, toolId: ToolId) {
        val tool = catalog.value.find { it.id == toolId } ?: return
        byAgent.update { current -> current + (agentId to current[agentId].orEmpty() + tool) }
    }

    fun detach(agentId: AgentId, toolId: ToolId) {
        byAgent.update { current -> current + (agentId to current[agentId].orEmpty().filter { it.id != toolId }) }
    }

    fun upsert(tool: Tool) {
        catalog.update { current ->
            val index = current.indexOfFirst { it.id == tool.id }
            if (index >= 0) current.toMutableList().apply { this[index] = tool } else current + tool
        }
    }

    fun replaceEverywhere(tool: Tool) {
        catalog.update { current -> current.map { if (it.id == tool.id) tool else it } }
        byAgent.update { current -> current.mapValues { (_, tools) -> tools.map { if (it.id == tool.id) tool else it } } }
    }

    fun remove(toolId: ToolId) {
        catalog.update { current -> current.filterNot { it.id == toolId } }
        byAgent.update { current -> current.mapValues { (_, tools) -> tools.filterNot { it.id == toolId } } }
    }
}

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
