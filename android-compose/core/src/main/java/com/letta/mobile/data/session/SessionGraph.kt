package com.letta.mobile.data.session

import com.letta.mobile.data.repository.AgentRepository
import kotlinx.coroutines.CoroutineScope

class SessionGraph internal constructor(
    val id: Long,
    val scope: CoroutineScope,
    val agentRepository: AgentRepository,
)
