package com.letta.mobile.data.session

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
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.ArchiveRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.CronRepository
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.data.repository.GroupRepository
import com.letta.mobile.data.repository.IdentityRepository
import com.letta.mobile.data.repository.JobRepository
import com.letta.mobile.data.repository.McpServerRepository
import com.letta.mobile.data.repository.ModelRepository
import com.letta.mobile.data.repository.PassageRepository
import com.letta.mobile.data.repository.ProjectRepository
import com.letta.mobile.data.repository.ProjectWorkRepository
import com.letta.mobile.data.repository.ProviderRepository
import com.letta.mobile.data.repository.RunRepository
import com.letta.mobile.data.repository.ScheduleRepository
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.data.repository.VibesyncEventStreamRepository
import com.letta.mobile.data.transport.ChannelTransport
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Singleton
class SessionGraphFactory @Inject constructor(
    private val agentApi: AgentApi,
    private val agentDao: AgentDao,
    private val conversationApi: ConversationApi,
    private val conversationDao: ConversationDao,
    private val archiveApi: ArchiveApi,
    private val folderApi: FolderApi,
    private val groupApi: GroupApi,
    private val identityApi: IdentityApi,
    private val lettaApiClient: LettaApiClient,
    private val mcpServerApi: McpServerApi,
    private val modelApi: ModelApi,
    private val passageApi: PassageApi,
    private val projectApi: ProjectApi,
    private val projectWorkApi: ProjectWorkApi,
    private val runApi: RunApi,
    private val jobApi: JobApi,
    private val providerApi: ProviderApi,
    private val scheduleApi: ScheduleApi,
    private val stepApi: StepApi,
    private val toolApi: ToolApi,
) {
    private val nextId = AtomicLong(0L)

    fun create(): SessionGraph {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val agentRepository = AgentRepository(
            agentApi = agentApi,
            agentDao = agentDao,
            repositoryScope = scope,
        )
        val channelTransport = ChannelTransport(scope)
        return SessionGraph(
            id = nextId.incrementAndGet(),
            scope = scope,
            agentRepository = agentRepository,
            allConversationsRepository = AllConversationsRepository(
                conversationApi = conversationApi,
                conversationDao = conversationDao,
                repositoryScope = scope,
            ),
            channelTransport = channelTransport,
            conversationRepository = ConversationRepository(
                conversationApi = conversationApi,
                agentRepository = agentRepository,
                conversationDao = conversationDao,
                repositoryScope = scope,
            ),
            cronRepository = CronRepository(
                transport = channelTransport,
                scope = scope,
            ),
            archiveRepository = ArchiveRepository(archiveApi),
            folderRepository = FolderRepository(folderApi),
            groupRepository = GroupRepository(groupApi),
            identityRepository = IdentityRepository(identityApi),
            mcpServerRepository = McpServerRepository(mcpServerApi),
            modelRepository = ModelRepository(modelApi),
            passageRepository = PassageRepository(passageApi),
            projectRepository = ProjectRepository(projectApi),
            projectWorkRepository = ProjectWorkRepository(projectWorkApi),
            runRepository = RunRepository(runApi),
            jobRepository = JobRepository(jobApi),
            providerRepository = ProviderRepository(providerApi),
            scheduleRepository = ScheduleRepository(scheduleApi),
            stepRepository = StepRepository(stepApi),
            toolRepository = ToolRepository(toolApi),
            vibesyncEventStreamRepository = VibesyncEventStreamRepository(
                apiClient = lettaApiClient,
                scope = scope,
            ),
        )
    }
}
