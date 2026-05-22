package com.letta.mobile.data.session

import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ArchiveRepository
import com.letta.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.CoroutineScope

class SessionGraph internal constructor(
    val id: Long,
    val scope: CoroutineScope,
    val agentRepository: AgentRepository,
    val conversationRepository: ConversationRepository,
    val archiveRepository: ArchiveRepository,
)
