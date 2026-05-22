package com.letta.mobile.data.session

import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ArchiveRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.JobRepository
import com.letta.mobile.data.repository.RunRepository
import com.letta.mobile.data.repository.StepRepository
import kotlinx.coroutines.CoroutineScope

class SessionGraph internal constructor(
    val id: Long,
    val scope: CoroutineScope,
    val agentRepository: AgentRepository,
    val conversationRepository: ConversationRepository,
    val archiveRepository: ArchiveRepository,
    val runRepository: RunRepository,
    val jobRepository: JobRepository,
    val stepRepository: StepRepository,
)
