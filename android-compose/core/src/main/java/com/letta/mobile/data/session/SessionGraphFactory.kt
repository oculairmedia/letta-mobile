package com.letta.mobile.data.session

import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.api.ArchiveApi
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ArchiveRepository
import com.letta.mobile.data.repository.ConversationRepository
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
        )
    }
}
