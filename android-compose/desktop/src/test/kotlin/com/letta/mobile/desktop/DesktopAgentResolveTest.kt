package com.letta.mobile.desktop

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.repository.api.IAgentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A conversation can outlive its agent — deleted, or a stale `agent-local-*`
 * left over from an earlier backend. The repository reports that by THROWING
 * from `getAgent(id).first()` (NoSuchElementException), not by returning null.
 *
 * Unguarded, that threw straight out of the resolve loop and the caller
 * swallowed it into an empty map, so one dead agent among 23 blanked every
 * conversation label down to its raw `agent-<uuid>` fallback.
 */
class DesktopAgentResolveTest {

    @Test
    fun `a missing agent does not discard the names that resolved`() = runTest {
        val repository = FakeAgentRepository(known = mapOf("agent-a" to "Meridian", "agent-c" to "Atlas"))

        val names = resolveDesktopAgentNames(
            agentIds = setOf("agent-a", "agent-b-deleted", "agent-c"),
            agentRepository = repository,
        )

        assertEquals(mapOf("agent-a" to "Meridian", "agent-c" to "Atlas"), names)
    }

    @Test
    fun `a refresh failure still resolves per-id lookups`() = runTest {
        val repository = FakeAgentRepository(known = mapOf("agent-a" to "Meridian"), refreshThrows = true)

        assertEquals(mapOf("agent-a" to "Meridian"), resolveDesktopAgentNames(setOf("agent-a"), repository))
    }

    @Test
    fun `every agent missing yields an empty map rather than throwing`() = runTest {
        val repository = FakeAgentRepository(known = emptyMap())

        assertEquals(emptyMap(), resolveDesktopAgentNames(setOf("gone-1", "gone-2"), repository))
    }

    @Test
    fun `a blank agent name is not resolved`() = runTest {
        val repository = FakeAgentRepository(known = mapOf("agent-a" to "   ", "agent-b" to "Atlas"))

        assertEquals(mapOf("agent-b" to "Atlas"), resolveDesktopAgentNames(setOf("agent-a", "agent-b"), repository))
    }
}

private class FakeAgentRepository(
    private val known: Map<String, String>,
    private val refreshThrows: Boolean = false,
) : IAgentRepository {
    override val agents: StateFlow<List<Agent>> = MutableStateFlow(emptyList())
    override val isRefreshing: StateFlow<Boolean> = MutableStateFlow(false)
    override val refreshError: StateFlow<Throwable?> = MutableStateFlow(null)

    override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean {
        if (refreshThrows) error("backend unreachable")
        return true
    }

    override fun getCachedAgent(id: AgentId): Agent? = null

    /** Mirrors the real repository: a missing agent throws, it does not return null. */
    override fun getAgent(id: AgentId): Flow<Agent> = flow {
        val name = known[id.value]
            ?: throw NoSuchElementException("Agent ${id.value} not found over iroh admin_rpc")
        emit(Agent(id = id, name = name))
    }

    override suspend fun countAgents(): Int = known.size
    override suspend fun refreshAgents() = Unit
    override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?) =
        ContextWindowOverview()
    override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) = operation()
    override suspend fun createAgent(params: AgentCreateParams): Agent = unsupported()
    override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent = unsupported()
    override suspend fun deleteAgent(id: AgentId) = unsupported()
    override suspend fun exportAgent(id: AgentId): String = unsupported()
    override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse = unsupported()
    override suspend fun attachArchive(agentId: AgentId, archiveId: String) = unsupported()
    override suspend fun detachArchive(agentId: AgentId, archiveId: String) = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")
}
