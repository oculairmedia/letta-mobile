package com.letta.mobile.data.session

import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.repository.AgentRepository
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
) {
    private val nextId = AtomicLong(0L)

    fun create(): SessionGraph {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return SessionGraph(
            id = nextId.incrementAndGet(),
            scope = scope,
            agentRepository = AgentRepository(
                agentApi = agentApi,
                agentDao = agentDao,
                repositoryScope = scope,
            ),
        )
    }
}
