package com.letta.mobile.data.session

import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.api.ArchiveApi
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.api.FolderApi
import com.letta.mobile.data.api.GroupApi
import com.letta.mobile.data.api.IdentityApi
import com.letta.mobile.data.api.JobApi
import com.letta.mobile.data.api.ProviderApi
import com.letta.mobile.data.api.RunApi
import com.letta.mobile.data.api.StepApi
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ArchiveRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.data.repository.GroupRepository
import com.letta.mobile.data.repository.IdentityRepository
import com.letta.mobile.data.repository.JobRepository
import com.letta.mobile.data.repository.ProviderRepository
import com.letta.mobile.data.repository.RunRepository
import com.letta.mobile.data.repository.StepRepository
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
    private val runApi: RunApi,
    private val jobApi: JobApi,
    private val providerApi: ProviderApi,
    private val stepApi: StepApi,
) {
    private val nextId = AtomicLong(0L)

    fun create(): SessionGraph {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val agentRepository = AgentRepository(
            agentApi = agentApi,
            agentDao = agentDao,
            repositoryScope = scope,
        )
        return SessionGraph(
            id = nextId.incrementAndGet(),
            scope = scope,
            agentRepository = agentRepository,
            conversationRepository = ConversationRepository(
                conversationApi = conversationApi,
                agentRepository = agentRepository,
                conversationDao = conversationDao,
                repositoryScope = scope,
            ),
            archiveRepository = ArchiveRepository(archiveApi),
            folderRepository = FolderRepository(folderApi),
            groupRepository = GroupRepository(groupApi),
            identityRepository = IdentityRepository(identityApi),
            runRepository = RunRepository(runApi),
            jobRepository = JobRepository(jobApi),
            providerRepository = ProviderRepository(providerApi),
            stepRepository = StepRepository(stepApi),
        )
    }
}
