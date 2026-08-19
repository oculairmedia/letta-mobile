package com.letta.mobile.di

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
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.api.LocalRuntimeAgentSource
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource
import com.letta.mobile.data.session.DefaultSessionRepositoryGraphFactory
import com.letta.mobile.data.session.SessionGraphAssembler
import com.letta.mobile.data.session.SessionRepositoryGraphFactory
import com.letta.mobile.data.session.SessionGraph
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for session-graph assembly (letta-mobile-l2ew9.1).
 *
 * Repositories consumed by the app are already bound as SessionScoped*
 * proxies in [AppModule]; this module owns the graph generation machinery that
 * replaced SessionGraphFactory.
 */
@Module
@InstallIn(SingletonComponent::class)
object SessionModule {
    @Provides
    @Singleton
    fun provideSessionGraphAssembler(
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
        blockRepository: BlockRepository,
        localConversationSource: LocalRuntimeConversationSource,
        localAgentSource: LocalRuntimeAgentSource,
        localModelSource: LocalRuntimeModelSource,
    ): SessionGraphAssembler = SessionGraphAssembler(
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
    )
}
