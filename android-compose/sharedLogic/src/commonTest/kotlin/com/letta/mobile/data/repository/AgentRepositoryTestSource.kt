package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.repository.api.AgentRemoteSource
import com.letta.mobile.data.repository.api.AgentLocalCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Clock
import kotlin.time.Instant
import com.letta.mobile.data.transport.api.IChannelTransport

internal class AgentRepositoryTestSource : AgentRemoteSource {
    val getCalls = mutableListOf<AgentId>()
    val updates = mutableListOf<AgentUpdateParams>()
    var roster = listOf(Agent(id = AgentId("agent-1"), name = "Agent"))
    var listCalls = 0
    var listGate: CompletableDeferred<Unit>? = null
    var ignoreListCancellation = false
    var read: suspend (AgentId) -> Agent = { id -> roster.first { it.id == id } }
    override suspend fun getAgent(agentId: AgentId): Agent {
        getCalls += agentId
        return read(agentId)
    }
    override suspend fun listAgents(limit: Int?, offset: Int?, tags: List<String>?): List<Agent> {
        listCalls += 1
        val snapshot = roster
        val gate = listGate
        if (ignoreListCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
        return snapshot
    }
    override suspend fun listAgentsSlim(limit: Int?, offset: Int?, tags: List<String>?) =
        roster.map { AgentSummary(it.id, it.name, it.description) }
    override suspend fun countAgents() = roster.size
    override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?) = ContextWindowOverview()
    override suspend fun updateAgent(agentId: AgentId, params: AgentUpdateParams): Agent =
        Agent(id = agentId, name = params.name ?: "Updated").also { updated ->
            updates += params
            roster = roster.map { if (it.id == agentId) updated else it }
        }
    override suspend fun deleteAgent(agentId: AgentId) { roster = roster.filterNot { it.id == agentId } }
    override suspend fun createAgent(params: AgentCreateParams): Agent = error("unused")
    override suspend fun exportAgent(agentId: AgentId): String = error("unused")
    override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse = error("unused")
    override suspend fun attachArchive(agentId: AgentId, archiveId: String) = Unit
    override suspend fun detachArchive(agentId: AgentId, archiveId: String) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.cachedAgentRepository(
    remote: AgentRepositoryTestSource,
    transport: IChannelTransport? = null,
    local: AgentLocalCache? = null,
): CachedAgentRepository = CachedAgentRepository(
    remote.bound(), localCache = local?.let { { it } }, repositoryScope = backgroundScope, transport = transport,
).also { repository ->
    repository.clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_000_000 + testScheduler.currentTime)
    }
}
