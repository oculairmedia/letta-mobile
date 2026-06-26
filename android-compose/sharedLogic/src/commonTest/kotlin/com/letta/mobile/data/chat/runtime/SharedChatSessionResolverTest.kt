package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedChatSessionResolverTest {

    @Test
    fun cachedAgentNameReturnsNonBlankCachedName() {
        val agents = FakeAgentRepository(
            cached = mapOf("agent-1" to agent("agent-1", "Cached Agent")),
        )
        val resolver = resolver(agents)

        assertEquals("Cached Agent", resolver.cachedAgentName("agent-1"))
    }

    @Test
    fun cachedAgentNameIgnoresMissingOrBlankNames() {
        val agents = FakeAgentRepository(
            cached = mapOf("agent-1" to agent("agent-1", "")),
        )
        val resolver = resolver(agents)

        assertNull(resolver.cachedAgentName("agent-1"))
    }

    @Test
    fun observeCachedAgentNameEmitsNamesForTargetAgentOnly() = runTest {
        val agentsFlow = MutableStateFlow(listOf(agent("agent-2", "Other")))
        val agents = FakeAgentRepository(agentsFlow = agentsFlow)
        val resolver = resolver(agents)
        val emitted = mutableListOf<String>()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            resolver.observeCachedAgentName("agent-1").collect { emitted += it }
        }
        agentsFlow.value = listOf(agent("agent-1", "Target"))
        runCurrent()
        job.cancel()

        // Other agent maps to "" for our target; updating to the target emits its name.
        assertEquals(listOf("", "Target"), emitted)
    }

    @Test
    fun resolveMostRecentConversationReturnsCachedMostRecent() = runTest {
        val conversations = FakeConversationRepository(
            cached = mapOf(
                "agent-1" to listOf(
                    conversation("older", createdAt = "2026-01-01T00:00:00Z", lastMessageAt = "2026-01-01T00:00:00Z"),
                    conversation("newer", createdAt = "2026-01-02T00:00:00Z", lastMessageAt = "2026-01-03T00:00:00Z"),
                ),
            ),
            fresh = mapOf("agent-1" to true),
        )
        val resolver = resolver(conversations = conversations)

        val resolved = resolver.resolveMostRecentConversation("agent-1", 30_000L)

        assertEquals("newer", resolved)
        assertEquals(0, conversations.refreshIfStaleCalls)
    }

    @Test
    fun resolveMostRecentConversationSkipsDefaultShimConversations() = runTest {
        val conversations = FakeConversationRepository(
            cached = mapOf(
                "agent-1" to listOf(
                    conversation(
                        "conv-default-agent-1",
                        createdAt = "2026-01-09T00:00:00Z",
                        lastMessageAt = "2026-01-09T00:00:00Z",
                    ),
                    conversation("real", createdAt = "2026-01-02T00:00:00Z", lastMessageAt = "2026-01-03T00:00:00Z"),
                ),
            ),
            fresh = mapOf("agent-1" to true),
        )
        val resolver = resolver(conversations = conversations)

        val resolved = resolver.resolveMostRecentConversation("agent-1", 30_000L)

        assertEquals("real", resolved)
    }

    @Test
    fun resolveMostRecentConversationRefreshesWhenCacheEmpty() = runTest {
        val conversations = FakeConversationRepository(
            cachedSequence = mapOf(
                "agent-1" to mutableListOf(
                    emptyList(),
                    listOf(conversation("after-refresh", createdAt = "2026-01-02T00:00:00Z")),
                ),
            ),
        )
        val resolver = resolver(conversations = conversations)

        val resolved = resolver.resolveMostRecentConversation("agent-1", 30_000L)

        assertEquals("after-refresh", resolved)
        assertEquals(1, conversations.refreshIfStaleCalls)
    }

    private fun resolver(
        agents: IAgentRepository = FakeAgentRepository(),
        conversations: IConversationRepository = FakeConversationRepository(),
    ): SharedChatSessionResolver = SharedChatSessionResolver(
        agentRepository = agents,
        conversationRepository = conversations,
    )

    private fun agent(id: String, name: String): Agent =
        Agent(id = AgentId(id), name = name)

    private fun conversation(
        id: String,
        createdAt: String,
        lastMessageAt: String? = null,
    ): Conversation = Conversation(
        id = ConversationId(id),
        agentId = AgentId("agent-1"),
        createdAt = createdAt,
        lastMessageAt = lastMessageAt,
    )
}

private class FakeAgentRepository(
    private val cached: Map<String, Agent> = emptyMap(),
    agentsFlow: MutableStateFlow<List<Agent>> = MutableStateFlow(emptyList()),
) : IAgentRepository {
    override val agents: StateFlow<List<Agent>> = agentsFlow
    override val isRefreshing: StateFlow<Boolean> = MutableStateFlow(false)
    override val refreshError: StateFlow<Throwable?> = MutableStateFlow(null)

    override fun getCachedAgent(id: AgentId): Agent? = cached[id.value]

    override suspend fun countAgents(): Int = unsupported()
    override suspend fun refreshAgents() = unsupported()
    override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = unsupported()
    override fun getAgent(id: AgentId): Flow<Agent> = emptyFlow()
    override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview = unsupported()
    override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) = unsupported()
    override suspend fun createAgent(params: AgentCreateParams): Agent = unsupported()
    override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent = unsupported()
    override suspend fun deleteAgent(id: AgentId) = unsupported()
    override suspend fun exportAgent(id: AgentId): String = unsupported()
    override suspend fun importAgent(
        fileName: String,
        fileBytes: ByteArray,
        overrideName: String?,
        overrideExistingTools: Boolean?,
        projectId: ProjectId?,
        stripMessages: Boolean?,
    ): ImportedAgentsResponse = unsupported()
    override suspend fun attachArchive(agentId: AgentId, archiveId: String) = unsupported()
    override suspend fun detachArchive(agentId: AgentId, archiveId: String) = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("unused in resolver tests")
}

private class FakeConversationRepository(
    private val cached: Map<String, List<Conversation>> = emptyMap(),
    private val cachedSequence: Map<String, MutableList<List<Conversation>>> = emptyMap(),
    private val fresh: Map<String, Boolean> = emptyMap(),
) : IConversationRepository {
    var refreshIfStaleCalls: Int = 0
        private set

    override fun getCachedConversations(agentId: AgentId): List<Conversation> {
        cachedSequence[agentId.value]?.let { queue ->
            if (queue.isNotEmpty()) return queue.removeAt(0)
        }
        return cached[agentId.value] ?: emptyList()
    }

    override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean =
        fresh[agentId.value] ?: false

    override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean {
        refreshIfStaleCalls++
        return true
    }

    override fun getConversations(agentId: AgentId): Flow<List<Conversation>> = emptyFlow()
    override suspend fun refreshConversations(agentId: AgentId) = unsupported()
    override suspend fun getConversation(id: ConversationId): Conversation = unsupported()
    override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation = unsupported()
    override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) = unsupported()
    override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) = unsupported()
    override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) = unsupported()
    override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) = unsupported()
    override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String = unsupported()
    override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("unused in resolver tests")
}
