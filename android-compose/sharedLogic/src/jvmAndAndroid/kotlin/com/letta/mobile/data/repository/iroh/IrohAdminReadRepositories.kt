package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.ModelCatalogNormalizer
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.data.model.ProviderUpdateParams
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.repository.IrohAdminRpcMcpSource
import com.letta.mobile.data.repository.IrohAdminRpcModelSource
import com.letta.mobile.data.repository.IrohAdminRpcPassageSource
import com.letta.mobile.data.repository.IrohAdminRpcProviderSource
import com.letta.mobile.data.repository.IrohAdminRpcRunSource
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.IPassageRepository
import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Phase 4c — thin Iroh-backed admin read repositories for the desktop session graph.
 *
 * Each implementation holds list [StateFlow] state and refreshes via the matching
 * [IrohAdminRpc*Source]. Mutations and reads without an
 * admin_rpc surface throw [UnsupportedOperationException] instead of silent no-ops.
 */
data class IrohAdminReadRepositories(
    val mcpServerRepository: IMcpServerRepository,
    val modelRepository: IModelRepository,
    val passageRepository: IPassageRepository,
    val providerRepository: IProviderRepository,
    val runRepository: IRunRepository,
)

fun buildIrohAdminReadRepositories(
    channelTransport: IChannelTransport,
    settingsRepository: ISettingsRepository,
): IrohAdminReadRepositories {
    return IrohAdminReadRepositories(
        mcpServerRepository = IrohMcpServerRepository(
            IrohAdminRpcMcpSource(channelTransport, settingsRepository),
        ),
        modelRepository = IrohModelRepository(
            IrohAdminRpcModelSource(channelTransport, settingsRepository),
        ),
        passageRepository = IrohPassageRepository(
            IrohAdminRpcPassageSource(channelTransport, settingsRepository),
        ),
        providerRepository = IrohProviderRepository(
            IrohAdminRpcProviderSource(channelTransport, settingsRepository),
        ),
        runRepository = IrohRunRepository(
            IrohAdminRpcRunSource(channelTransport, settingsRepository),
        ),
    )
}

private fun unsupported(operation: String): Nothing =
    throw UnsupportedOperationException(
        "Iroh admin_rpc does not support $operation yet",
    )

internal class IrohMcpServerRepository(
    private val source: IrohAdminRpcMcpSource,
) : IMcpServerRepository {
    private val _servers = MutableStateFlow<List<McpServer>>(emptyList())
    override val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    private val _toolsByServer = MutableStateFlow<Map<McpServerId, List<Tool>>>(emptyMap())

    override fun getServers(): Flow<List<McpServer>> = servers

    override fun getServerTools(serverId: McpServerId): Flow<List<Tool>> =
        _toolsByServer.map { it[serverId].orEmpty() }

    override suspend fun refreshServers() {
        _servers.value = source.listMcpServers()
    }

    override suspend fun refreshServerTools(serverId: McpServerId) =
        unsupported("mcp.refreshServerTools(${serverId.value})")

    override suspend fun resyncServerTools(serverId: McpServerId): McpServerResyncResult =
        unsupported("mcp.resyncServerTools(${serverId.value})")

    override suspend fun runServerTool(
        serverId: McpServerId,
        toolId: ToolId,
        params: McpToolExecuteParams,
    ): McpToolExecutionResult = unsupported("mcp.runServerTool(${serverId.value}, ${toolId.value})")

    override suspend fun fetchAllMcpTools(): List<Tool> {
        // Tools-per-server RPC is not wired; refresh servers then return empty
        // so ToolLibraryController does not fail hard.
        refreshServers()
        return emptyList()
    }

    override suspend fun createServer(params: McpServerCreateParams): McpServer =
        unsupported("mcp.createServer")

    override suspend fun updateServer(id: McpServerId, params: McpServerUpdateParams): McpServer =
        unsupported("mcp.updateServer(${id.value})")

    override suspend fun deleteServer(id: McpServerId) =
        unsupported("mcp.deleteServer(${id.value})")
}

internal class IrohModelRepository(
    private val source: IrohAdminRpcModelSource,
) : IModelRepository {
    private val _llmModels = MutableStateFlow<List<LlmModel>>(emptyList())
    override val llmModels: StateFlow<List<LlmModel>> = _llmModels.asStateFlow()

    private val _embeddingModels = MutableStateFlow<List<EmbeddingModel>>(emptyList())
    override val embeddingModels: StateFlow<List<EmbeddingModel>> = _embeddingModels.asStateFlow()

    override suspend fun refreshLlmModels() {
        _llmModels.value = ModelCatalogNormalizer.normalize(source.listLlmModels())
    }

    override suspend fun refreshEmbeddingModels() {
        _embeddingModels.value = source.listEmbeddingModels()
    }
}

internal class IrohPassageRepository(
    private val source: IrohAdminRpcPassageSource,
) : IPassageRepository {
    private val cacheLock = Any()
    private val passagesByAgent = MutableStateFlow<Map<String, List<Passage>>>(emptyMap())
    private val flowsByAgent = mutableMapOf<String, MutableStateFlow<List<Passage>>>()

    override fun getPassages(agentId: String): StateFlow<List<Passage>> =
        synchronized(cacheLock) {
            flowsByAgent
                .getOrPut(agentId) { MutableStateFlow(passagesByAgent.value[agentId].orEmpty()) }
                .asStateFlow()
        }

    override suspend fun refreshPassages(agentId: String) {
        val passages = source.listPassages(agentId)
        mutateCached(agentId) { passages }
    }

    override suspend fun createPassage(agentId: String, text: String): Passage {
        val passage = source.createPassage(agentId, text)
        mutateCached(agentId) { it + passage }
        return passage
    }

    override suspend fun deletePassage(agentId: String, passageId: String) {
        source.deletePassage(agentId, passageId)
        mutateCached(agentId) { current -> current.filterNot { it.id == passageId } }
    }

    override suspend fun searchArchival(agentId: String, query: String): List<Passage> =
        unsupported("passage.searchArchival($agentId)")

    private fun mutateCached(agentId: String, transform: (List<Passage>) -> List<Passage>) {
        synchronized(cacheLock) {
            val next = transform(passagesByAgent.value[agentId].orEmpty())
            passagesByAgent.update { it + (agentId to next) }
            flowsByAgent[agentId]?.value = next
        }
    }
}

internal class IrohProviderRepository(
    private val source: IrohAdminRpcProviderSource,
) : IProviderRepository {
    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    override val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    override suspend fun refreshProviders(name: String?, providerType: String?) {
        // provider.list has no name/type filters on the Iroh source.
        _providers.value = source.listProviders()
    }

    override suspend fun getProvider(providerId: ProviderId): Provider =
        unsupported("provider.get(${providerId.value})")

    override suspend fun createProvider(params: ProviderCreateParams): Provider =
        unsupported("provider.create")

    override suspend fun updateProvider(providerId: ProviderId, params: ProviderUpdateParams): Provider =
        unsupported("provider.update(${providerId.value})")

    override suspend fun checkProvider(params: ProviderCheckParams) =
        unsupported("provider.check")

    override suspend fun checkExistingProvider(providerId: ProviderId) =
        unsupported("provider.checkExisting(${providerId.value})")

    override suspend fun deleteProvider(providerId: ProviderId) =
        unsupported("provider.delete(${providerId.value})")
}

internal class IrohRunRepository(
    private val source: IrohAdminRpcRunSource,
) : IRunRepository {
    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    override val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    override val supportsRunDetail: Boolean = false

    override suspend fun refreshRuns(params: RunListParams) {
        _runs.value = source.listRuns(params)
    }

    override suspend fun getRecentRuns(limit: Int): List<Run> =
        source.listRuns(
            RunListParams(
                limit = limit,
                order = "desc",
                orderBy = "created_at",
            ),
        )

    override suspend fun getRun(runId: String): Run = source.getRun(runId)

    override suspend fun getRunMessages(runId: String): List<LettaMessage> =
        unsupported("run.getMessages($runId)")

    override suspend fun getRunUsage(runId: String): UsageStatistics =
        unsupported("run.getUsage($runId)")

    override suspend fun getRunMetrics(runId: String): RunMetrics =
        unsupported("run.getMetrics($runId)")

    override suspend fun getRunSteps(runId: String): List<Step> = source.getRunSteps(runId)

    override suspend fun cancelRun(run: Run): Run = unsupported("run.cancel(${run.id})")

    override suspend fun deleteRun(runId: String) = unsupported("run.delete($runId)")

    override fun upsertRun(run: Run) {
        _runs.update { current ->
            val index = current.indexOfFirst { it.id == run.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = run }
            } else {
                current + run
            }
        }
    }
}
