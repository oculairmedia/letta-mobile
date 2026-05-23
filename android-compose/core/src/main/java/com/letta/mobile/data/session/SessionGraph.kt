package com.letta.mobile.data.session

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
import kotlinx.coroutines.CoroutineScope

class SessionGraph internal constructor(
    val id: Long,
    val scope: CoroutineScope,
    val agentRepository: AgentRepository,
    val conversationRepository: ConversationRepository,
    val archiveRepository: ArchiveRepository,
    val folderRepository: FolderRepository,
    val groupRepository: GroupRepository,
    val identityRepository: IdentityRepository,
    val runRepository: RunRepository,
    val jobRepository: JobRepository,
    val providerRepository: ProviderRepository,
    val stepRepository: StepRepository,
)
