package com.letta.mobile.data.session

import android.content.Context
import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.api.ArchiveApi
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.api.FolderApi
import com.letta.mobile.data.api.GroupApi
import com.letta.mobile.data.api.IdentityApi
import com.letta.mobile.data.api.JobApi
import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.api.McpServerApi
import com.letta.mobile.data.api.ModelApi
import com.letta.mobile.data.api.PassageApi
import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.api.ProjectWorkApi
import com.letta.mobile.data.api.ProviderApi
import com.letta.mobile.data.api.RunApi
import com.letta.mobile.data.api.ScheduleApi
import com.letta.mobile.data.api.StepApi
import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeAgentSource
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource
import com.letta.mobile.data.timeline.ConversationCursorStore
import com.letta.mobile.data.timeline.NoOpConversationCursorStore
import com.letta.mobile.data.transport.RunCursorStore
import com.letta.mobile.runtime.LocalLettaBackend
import com.letta.mobile.runtime.MemFsStore
import com.letta.mobile.runtime.RuntimeEventOutbox
import dagger.Lazy
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Default [SessionRepositoryGraphFactory] for Android.
 *
 * Replaces the former SessionGraphFactory: Hilt injects the assembler and
 * transport binder, and [create] produces a fresh [SessionGraph] generation
 * when [SessionManager] rebuilds on backend change.
 */
@Singleton
class DefaultSessionRepositoryGraphFactory internal constructor(
    private val assembler: SessionGraphAssembler,
    private val channelTransportFactory: SessionChannelTransportFactory,
    private val settingsRepository: ISettingsRepository? = null,
    private val localRuntimeOptions: LocalRuntimeOptions = LocalRuntimeOptions.Disabled,
) : SessionRepositoryGraphFactory<SessionGraph> {
    @Inject
    constructor(
        assembler: SessionGraphAssembler,
        channelTransportFactory: SessionChannelTransportFactory,
        runtimeEventOutbox: RuntimeEventOutbox,
        memFsStore: MemFsStore,
        localRuntimeProviders: Set<@JvmSuppressWildcards LocalRuntimeProvider>,
        settingsRepository: ISettingsRepository,
    ) : this(
        assembler = assembler,
        channelTransportFactory = channelTransportFactory,
        settingsRepository = settingsRepository,
        localRuntimeOptions = LocalRuntimeOptions.Enabled(
            runtimeEventOutbox = runtimeEventOutbox,
            memFsStore = memFsStore,
            providers = localRuntimeProviders,
        ),
    )

    private val nextId = AtomicLong(0L)

    override fun create(): SessionGraph {
        val graphId = nextId.incrementAndGet()
        val activeConfig = settingsRepository?.activeConfig?.value
        val localRuntimeBackend = localRuntimeOptions.createBackend(activeConfig)
        runBlocking(Dispatchers.IO) {
            assembler.clearCachesForNewSession()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channelTransport = channelTransportFactory.create(
            scope = scope,
            activeConfig = activeConfig,
            localRuntimeBackend = localRuntimeBackend,
            settingsRepository = settingsRepository,
        )
        return assembler.assemble(
            graphId = graphId,
            activeConfig = activeConfig,
            localRuntimeBackend = localRuntimeBackend,
            scope = scope,
            channelTransport = channelTransport,
            settingsRepository = settingsRepository,
        )
    }
}

/**
 * Test helper that mirrors the old SessionGraphFactory wiring without Hilt.
 */
internal fun createDefaultSessionRepositoryGraphFactory(
    agentApi: AgentApi,
    agentDao: Lazy<AgentDao>,
    conversationApi: ConversationApi,
    conversationDao: Lazy<ConversationDao>,
    archiveApi: ArchiveApi,
    folderApi: FolderApi,
    groupApi: GroupApi,
    identityApi: IdentityApi,
    lettaApiClient: LettaApiClient,
    mcpServerApi: McpServerApi,
    modelApi: ModelApi,
    passageApi: PassageApi,
    projectApi: ProjectApi,
    projectWorkApi: ProjectWorkApi,
    runApi: RunApi,
    jobApi: JobApi,
    providerApi: ProviderApi,
    scheduleApi: ScheduleApi,
    stepApi: StepApi,
    toolApi: ToolApi,
    appContext: Context,
    blockRepository: BlockRepository? = null,
    runCursorStore: RunCursorStore = RunCursorStore.inMemory(),
    conversationCursorStore: ConversationCursorStore = NoOpConversationCursorStore,
    settingsRepository: ISettingsRepository? = null,
    localRuntimeOptions: LocalRuntimeOptions = LocalRuntimeOptions.Disabled,
    localConversationSource: LocalRuntimeConversationSource? = null,
    localAgentSource: LocalRuntimeAgentSource? = null,
    localModelSource: LocalRuntimeModelSource? = null,
): DefaultSessionRepositoryGraphFactory = DefaultSessionRepositoryGraphFactory(
    assembler = SessionGraphAssembler(
        agentApi = agentApi,
        agentDao = agentDao,
        conversationApi = conversationApi,
        conversationDao = conversationDao,
        archiveApi = archiveApi,
        folderApi = folderApi,
        groupApi = groupApi,
        identityApi = identityApi,
        lettaApiClient = lettaApiClient,
        mcpServerApi = mcpServerApi,
        modelApi = modelApi,
        passageApi = passageApi,
        projectApi = projectApi,
        projectWorkApi = projectWorkApi,
        runApi = runApi,
        jobApi = jobApi,
        providerApi = providerApi,
        scheduleApi = scheduleApi,
        stepApi = stepApi,
        toolApi = toolApi,
        blockRepository = blockRepository,
        localConversationSource = localConversationSource,
        localAgentSource = localAgentSource,
        localModelSource = localModelSource,
    ),
    channelTransportFactory = SessionChannelTransportFactory(
        appContext = appContext,
        runCursorStore = runCursorStore,
        conversationCursorStore = conversationCursorStore,
    ),
    settingsRepository = settingsRepository,
    localRuntimeOptions = localRuntimeOptions,
)

internal fun LocalRuntimeOptions.createBackend(config: LettaConfig?): LocalLettaBackend? {
    if (config?.mode != LettaConfig.Mode.LOCAL) {
        return null
    }
    return when (this) {
        LocalRuntimeOptions.Disabled -> null
        is LocalRuntimeOptions.Enabled -> {
            val provider = providers
                .filter { it.supports(config) }
                .maxWithOrNull(compareBy<LocalRuntimeProvider> { it.priority }.thenBy { it.providerId })
                ?: return null
            LocalLettaBackend(
                descriptor = provider.descriptor(config),
                engine = provider.turnEngine(config),
                outbox = runtimeEventOutbox,
                memFsStore = memFsStore,
                onInterrupt = provider::interruptActiveTurn,
            )
        }
    }
}
