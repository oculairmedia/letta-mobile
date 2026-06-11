package com.letta.mobile.data.session

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
import com.letta.mobile.data.repository.SelfTodoRepository
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.data.repository.VibesyncEventStreamRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.LocalLettaBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

class SessionGraph internal constructor(
    override val id: Long,
    override val backendDescriptor: BackendDescriptor,
    override val localRuntimeBackend: LocalLettaBackend?,
    val scope: CoroutineScope,
    override val agentRepository: AgentRepository,
    val allConversationsRepository: AllConversationsRepository,
    override val channelTransport: IChannelTransport,
    override val conversationRepository: ConversationRepository,
    override val cronRepository: CronRepository,
    override val archiveRepository: ArchiveRepository,
    override val folderRepository: FolderRepository,
    override val groupRepository: GroupRepository,
    override val identityRepository: IdentityRepository,
    override val mcpServerRepository: McpServerRepository,
    override val modelRepository: ModelRepository,
    override val passageRepository: PassageRepository,
    override val projectRepository: ProjectRepository,
    override val projectWorkRepository: ProjectWorkRepository,
    override val runRepository: RunRepository,
    override val jobRepository: JobRepository,
    override val providerRepository: ProviderRepository,
    override val scheduleRepository: ScheduleRepository,
    override val selfTodoRepository: SelfTodoRepository,
    override val stepRepository: StepRepository,
    override val subagentRepository: SubagentRepository,
    override val toolRepository: ToolRepository,
    override val vibesyncEventStreamRepository: VibesyncEventStreamRepository,
) : SessionRepositoryGraph {
    override fun close() {
        scope.cancel()
    }
}
