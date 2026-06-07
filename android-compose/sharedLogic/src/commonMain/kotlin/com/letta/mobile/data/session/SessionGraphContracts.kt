package com.letta.mobile.data.session

import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IArchiveRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.repository.api.IFolderRepository
import com.letta.mobile.data.repository.api.IGroupRepository
import com.letta.mobile.data.repository.api.IIdentityRepository
import com.letta.mobile.data.repository.api.IJobRepository
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.IPassageRepository
import com.letta.mobile.data.repository.api.IProjectRepository
import com.letta.mobile.data.repository.api.IProjectWorkRepository
import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import com.letta.mobile.data.repository.api.IStepRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.IVibesyncEventStreamRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.LettaBackend
import kotlinx.coroutines.flow.StateFlow

/**
 * KMP-safe session graph surface for chat, timeline, and runtime wiring.
 *
 * Android-only list/paging repositories remain platform-side until a paging
 * neutral contract exists; this graph intentionally includes only contracts
 * already safe for commonMain consumers.
 */
interface SessionRepositoryGraph {
    val id: Long
    val backendDescriptor: BackendDescriptor
    val localRuntimeBackend: LettaBackend?
    val agentRepository: IAgentRepository
    val channelTransport: IChannelTransport
    val conversationRepository: IConversationRepository
    val cronRepository: ICronRepository
    val archiveRepository: IArchiveRepository
    val folderRepository: IFolderRepository
    val groupRepository: IGroupRepository
    val identityRepository: IIdentityRepository
    val mcpServerRepository: IMcpServerRepository
    val modelRepository: IModelRepository
    val passageRepository: IPassageRepository
    val projectRepository: IProjectRepository
    val projectWorkRepository: IProjectWorkRepository
    val runRepository: IRunRepository
    val jobRepository: IJobRepository
    val providerRepository: IProviderRepository
    val scheduleRepository: IScheduleRepository
    val selfTodoRepository: ISelfTodoRepository
    val stepRepository: IStepRepository
    val subagentRepository: ISubagentRepository
    val toolRepository: IToolRepository
    val vibesyncEventStreamRepository: IVibesyncEventStreamRepository

    fun close()
}

interface SessionRepositoryGraphFactory<out Graph : SessionRepositoryGraph> {
    fun create(): Graph
}

interface SessionRepositoryGraphProvider<Graph : SessionRepositoryGraph> {
    val currentGraph: StateFlow<Graph>
    val sessionError: StateFlow<Throwable?>
    val current: Graph

    fun rebuild(): Graph

    suspend fun <T> withCurrentSession(block: suspend (Graph) -> T): T
}
