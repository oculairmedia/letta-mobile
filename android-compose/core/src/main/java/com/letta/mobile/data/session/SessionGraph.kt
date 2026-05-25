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
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.data.repository.VibesyncEventStreamRepository
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.LocalLettaBackend
import kotlinx.coroutines.CoroutineScope

class SessionGraph internal constructor(
    val id: Long,
    val backendDescriptor: BackendDescriptor,
    val localRuntimeBackend: LocalLettaBackend?,
    val scope: CoroutineScope,
    val agentRepository: AgentRepository,
    val allConversationsRepository: AllConversationsRepository,
    val channelTransport: ChannelTransport,
    val conversationRepository: ConversationRepository,
    val cronRepository: CronRepository,
    val archiveRepository: ArchiveRepository,
    val folderRepository: FolderRepository,
    val groupRepository: GroupRepository,
    val identityRepository: IdentityRepository,
    val mcpServerRepository: McpServerRepository,
    val modelRepository: ModelRepository,
    val passageRepository: PassageRepository,
    val projectRepository: ProjectRepository,
    val projectWorkRepository: ProjectWorkRepository,
    val runRepository: RunRepository,
    val jobRepository: JobRepository,
    val providerRepository: ProviderRepository,
    val scheduleRepository: ScheduleRepository,
    val stepRepository: StepRepository,
    val toolRepository: ToolRepository,
    val vibesyncEventStreamRepository: VibesyncEventStreamRepository,
)
