package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveUpdateParams
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderId
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupId
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityId
import com.letta.mobile.data.model.IdentityProperty
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.model.IdentityUpsertParams
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ModelCatalogNormalizer
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.ProjectId
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
import com.letta.mobile.data.repository.IrohAdminRpcClient
import com.letta.mobile.data.repository.IrohAdminRpcFolderSource
import com.letta.mobile.data.repository.IrohAdminRpcGroupSource
import com.letta.mobile.data.repository.IrohAdminRpcIdentitySource
import com.letta.mobile.data.repository.IrohAdminRpcJobSource
import com.letta.mobile.data.repository.IrohAdminRpcMcpSource
import com.letta.mobile.data.repository.IrohAdminRpcModelSource
import com.letta.mobile.data.repository.IrohAdminRpcPassageSource
import com.letta.mobile.data.repository.IrohAdminRpcProviderSource
import com.letta.mobile.data.repository.IrohAdminRpcRunSource
import com.letta.mobile.data.repository.api.IArchiveRepository
import com.letta.mobile.data.repository.api.IFolderRepository
import com.letta.mobile.data.repository.api.IGroupRepository
import com.letta.mobile.data.repository.api.IIdentityRepository
import com.letta.mobile.data.repository.api.IJobRepository
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.IPassageRepository
import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import io.ktor.http.ContentType
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * Phase 4c — thin Iroh-backed admin read repositories for the desktop session graph.
 *
 * Each implementation holds list [StateFlow] state and refreshes via the matching
 * [IrohAdminRpc*Source] / [IrohAdminRpcClient]. Mutations and reads without an
 * admin_rpc surface throw [UnsupportedOperationException] instead of silent no-ops.
 */
data class IrohAdminReadRepositories(
    val archiveRepository: IArchiveRepository,
    val folderRepository: IFolderRepository,
    val groupRepository: IGroupRepository,
    val identityRepository: IIdentityRepository,
    val mcpServerRepository: IMcpServerRepository,
    val modelRepository: IModelRepository,
    val passageRepository: IPassageRepository,
    val providerRepository: IProviderRepository,
    val jobRepository: IJobRepository,
    val runRepository: IRunRepository,
)

fun buildIrohAdminReadRepositories(
    channelTransport: IChannelTransport,
    settingsRepository: ISettingsRepository,
): IrohAdminReadRepositories {
    val client = IrohAdminRpcClient(channelTransport, settingsRepository)
    return IrohAdminReadRepositories(
        archiveRepository = IrohArchiveRepository(client),
        folderRepository = IrohFolderRepository(
            IrohAdminRpcFolderSource(channelTransport, settingsRepository),
        ),
        groupRepository = IrohGroupRepository(
            IrohAdminRpcGroupSource(channelTransport, settingsRepository),
        ),
        identityRepository = IrohIdentityRepository(
            IrohAdminRpcIdentitySource(channelTransport, settingsRepository),
        ),
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
        jobRepository = IrohJobRepository(
            IrohAdminRpcJobSource(channelTransport, settingsRepository),
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

internal class IrohArchiveRepository(
    private val client: IrohAdminRpcClient,
) : IArchiveRepository {
    private val _archives = MutableStateFlow<List<Archive>>(emptyList())
    override val archives: StateFlow<List<Archive>> = _archives.asStateFlow()

    override suspend fun refreshArchives(name: String?, agentId: String?) {
        // archive.list is registered; name/agentId filters are not wired on the
        // Iroh client path (matches Android ArchiveRepository iroh branch).
        _archives.value = client.callList("archive.list", "/v1/archives", "{}")
    }

    override suspend fun getArchive(archiveId: String): Archive =
        unsupported("archive.get($archiveId)")

    override suspend fun createArchive(params: ArchiveCreateParams): Archive =
        unsupported("archive.create")

    override suspend fun updateArchive(archiveId: String, params: ArchiveUpdateParams): Archive =
        unsupported("archive.update($archiveId)")

    override suspend fun deleteArchive(archiveId: String): Archive =
        unsupported("archive.delete($archiveId)")

    override suspend fun listAgentsForArchive(archiveId: String): List<Agent> =
        unsupported("archive.listAgents($archiveId)")

    override suspend fun deletePassageFromArchive(archiveId: String, passageId: String) =
        unsupported("archive.deletePassage($archiveId, $passageId)")
}

internal class IrohFolderRepository(
    private val source: IrohAdminRpcFolderSource,
) : IFolderRepository {
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    override val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    override suspend fun refreshFolders(name: String?) {
        _folders.value = source.listFolders(name)
    }

    override suspend fun countFolders(): Int = unsupported("folder.count")

    override suspend fun getFolder(folderId: FolderId): Folder =
        unsupported("folder.get(${folderId.value})")

    override suspend fun getFolderMetadata(includeDetailedPerSourceMetadata: Boolean): OrganizationSourcesStats =
        unsupported("folder.getMetadata")

    override suspend fun createFolder(params: FolderCreateParams): Folder =
        unsupported("folder.create")

    override suspend fun updateFolder(folderId: FolderId, params: FolderUpdateParams): Folder =
        unsupported("folder.update(${folderId.value})")

    override suspend fun deleteFolder(folderId: FolderId) =
        unsupported("folder.delete(${folderId.value})")

    override suspend fun uploadFileToFolder(
        folderId: FolderId,
        fileName: String,
        fileBytes: ByteArray,
        duplicateHandling: String?,
        customName: String?,
        contentType: ContentType,
    ): FileMetadata = unsupported("folder.uploadFile(${folderId.value})")

    override suspend fun listAgentsForFolder(folderId: FolderId): List<String> =
        unsupported("folder.listAgents(${folderId.value})")

    override suspend fun listFolderPassages(folderId: FolderId): List<Passage> =
        unsupported("folder.listPassages(${folderId.value})")

    override suspend fun listFolderFiles(folderId: FolderId, includeContent: Boolean): List<FileMetadata> =
        unsupported("folder.listFiles(${folderId.value})")

    override suspend fun deleteFileFromFolder(folderId: FolderId, fileId: String) =
        unsupported("folder.deleteFile(${folderId.value}, $fileId)")
}

internal class IrohGroupRepository(
    private val source: IrohAdminRpcGroupSource,
) : IGroupRepository {
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    override suspend fun refreshGroups(
        managerType: String?,
        projectId: ProjectId?,
        showHiddenGroups: Boolean?,
    ) {
        _groups.value = source.listGroups(managerType, projectId?.value, showHiddenGroups)
    }

    override suspend fun countGroups(): Int = unsupported("group.count")

    override suspend fun getGroup(groupId: GroupId): Group =
        unsupported("group.get(${groupId.value})")

    override suspend fun createGroup(params: GroupCreateParams): Group =
        unsupported("group.create")

    override suspend fun updateGroup(groupId: GroupId, params: GroupUpdateParams): Group =
        unsupported("group.update(${groupId.value})")

    override suspend fun deleteGroup(groupId: GroupId) =
        unsupported("group.delete(${groupId.value})")

    override suspend fun sendGroupMessage(groupId: GroupId, request: MessageCreateRequest): LettaResponse =
        unsupported("group.sendMessage(${groupId.value})")

    override suspend fun sendGroupMessageStream(
        groupId: GroupId,
        request: MessageCreateRequest,
    ): ByteReadChannel = unsupported("group.sendMessageStream(${groupId.value})")

    override suspend fun updateGroupMessage(
        groupId: GroupId,
        messageId: String,
        request: JsonElement,
    ): LettaMessage = unsupported("group.updateMessage(${groupId.value}, $messageId)")

    override suspend fun listGroupMessages(groupId: GroupId): List<LettaMessage> =
        unsupported("group.listMessages(${groupId.value})")

    override suspend fun resetGroupMessages(groupId: GroupId) =
        unsupported("group.resetMessages(${groupId.value})")
}

internal class IrohIdentityRepository(
    private val source: IrohAdminRpcIdentitySource,
) : IIdentityRepository {
    private val _identities = MutableStateFlow<List<Identity>>(emptyList())
    override val identities: StateFlow<List<Identity>> = _identities.asStateFlow()

    override suspend fun refreshIdentities() {
        _identities.value = source.listIdentities()
    }

    override suspend fun countIdentities(): Int = unsupported("identity.count")

    override suspend fun getIdentity(identityId: IdentityId): Identity =
        source.getIdentity(identityId.value)

    override suspend fun createIdentity(params: IdentityCreateParams): Identity =
        unsupported("identity.create")

    override suspend fun upsertIdentity(params: IdentityUpsertParams): Identity =
        unsupported("identity.upsert")

    override suspend fun updateIdentity(identityId: IdentityId, params: IdentityUpdateParams): Identity =
        unsupported("identity.update(${identityId.value})")

    override suspend fun upsertIdentityProperties(
        identityId: IdentityId,
        properties: List<IdentityProperty>,
    ): Identity = unsupported("identity.upsertProperties(${identityId.value})")

    override suspend fun deleteIdentity(identityId: IdentityId) =
        unsupported("identity.delete(${identityId.value})")

    override suspend fun attachIdentity(agentId: AgentId, identityId: IdentityId) =
        unsupported("identity.attach(${agentId.value}, ${identityId.value})")

    override suspend fun detachIdentity(agentId: AgentId, identityId: IdentityId) =
        unsupported("identity.detach(${agentId.value}, ${identityId.value})")

    override suspend fun listAgentsForIdentity(identityId: IdentityId): List<Agent> =
        unsupported("identity.listAgents(${identityId.value})")

    override suspend fun listBlocksForIdentity(identityId: IdentityId): List<Block> =
        unsupported("identity.listBlocks(${identityId.value})")
}

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
        replaceCached(agentId, source.listPassages(agentId))
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

    private fun replaceCached(agentId: String, passages: List<Passage>) {
        mutateCached(agentId) { passages }
    }

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

internal class IrohJobRepository(
    private val source: IrohAdminRpcJobSource,
) : IJobRepository {
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    override val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    override suspend fun refreshJobs(params: JobListParams) {
        // job.list ignores JobListParams on the Iroh source (server has no filters).
        _jobs.value = source.listJobs()
    }

    override suspend fun getJob(jobId: String): Job = source.getJob(jobId)

    override suspend fun cancelJob(jobId: String): Job = unsupported("job.cancel($jobId)")

    override suspend fun deleteJob(jobId: String): Job = unsupported("job.delete($jobId)")

    override fun upsertJob(job: Job) {
        _jobs.update { current ->
            val index = current.indexOfFirst { it.id == job.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = job }
            } else {
                current + job
            }
        }
    }
}

internal class IrohRunRepository(
    private val source: IrohAdminRpcRunSource,
) : IRunRepository {
    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    override val runs: StateFlow<List<Run>> = _runs.asStateFlow()

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
