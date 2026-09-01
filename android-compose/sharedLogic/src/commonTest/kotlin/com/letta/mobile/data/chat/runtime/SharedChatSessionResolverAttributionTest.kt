package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.TimelineCandidateListFreshness
import com.letta.mobile.data.timeline.TimelineCandidateListSource
import com.letta.mobile.data.timeline.TimelineConversationAttribution
import com.letta.mobile.data.timeline.TimelineConversationSelectionMode
import com.letta.mobile.data.timeline.TimelineProvenanceRedaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-grrhq: conversation-attribution capture at the resolver boundary.
 *
 * This is where outcome A (the child resolver picks a parent-only conversation)
 * is separated from outcome B (the backend legitimately attributes one
 * conversation to both). Both must be reachable and unambiguous.
 */
class SharedChatSessionResolverAttributionTest {

    private val child = "agent-child"
    private val parent = "agent-parent"
    private val shared = "local-conv-190"

    // ---------------------------------------------------------------- outcome A

    @Test
    fun child_resolving_a_parent_owned_conversation_reports_PARENT_AGENT_ONLY() = runTest {
        // The child's cache bucket contains a record whose OWN agentId is the parent.
        val repo = FakeConversations(
            cached = mapOf(
                child to listOf(conversation(shared, owner = parent, createdAt = "2026-08-31T22:00:00Z")),
                parent to listOf(conversation(shared, owner = parent, createdAt = "2026-08-31T22:00:00Z")),
            ),
            fresh = mapOf(child to true),
        )
        val selection = resolver(repo).resolveMostRecentConversationWithProvenance(child, 30_000L, parent)

        assertEquals(shared, selection.conversationId)
        assertEquals(TimelineConversationAttribution.PARENT_AGENT_ONLY, selection.capture.attribution)
        assertTrue(selection.capture.selectedAlsoAttributedToParent)
        assertEquals(parent, selection.capture.selectedRecordAgentId)
        assertEquals(TimelineConversationSelectionMode.MOST_RECENT_FALLBACK, selection.capture.selectionMode)
        assertEquals(TimelineCandidateListSource.AGENT_SCOPED_CACHE, selection.capture.candidateListSource)
        assertEquals(TimelineCandidateListFreshness.FRESH, selection.capture.candidateListFreshness)
    }

    // ---------------------------------------------------------------- outcome B

    @Test
    fun dual_attributed_conversation_reports_BOTH_not_a_resolver_defect() = runTest {
        // The SAME conversation legitimately appears under both agents, and the
        // record is attributed to the child. The resolver is behaving as designed.
        val repo = FakeConversations(
            cached = mapOf(
                child to listOf(conversation(shared, owner = child, createdAt = "2026-08-31T22:00:00Z")),
                parent to listOf(conversation(shared, owner = parent, createdAt = "2026-08-31T22:00:00Z")),
            ),
            fresh = mapOf(child to true),
        )
        val selection = resolver(repo).resolveMostRecentConversationWithProvenance(child, 30_000L, parent)

        assertEquals(shared, selection.conversationId)
        assertEquals(
            TimelineConversationAttribution.BOTH,
            selection.capture.attribution,
            "dual attribution must be reported as BOTH — this is outcome B, where the " +
                "resolver is NOT the defect and ownership precedence is the real problem",
        )
        assertTrue(selection.capture.selectedAlsoAttributedToParent)
    }

    // ----------------------------------------------------------- clean ownership

    @Test
    fun child_owned_conversation_reports_REQUESTED_AGENT_ONLY() = runTest {
        val repo = FakeConversations(
            cached = mapOf(
                child to listOf(conversation("local-conv-900", owner = child, createdAt = "2026-08-31T22:00:00Z")),
                parent to listOf(conversation(shared, owner = parent, createdAt = "2026-08-31T22:00:00Z")),
            ),
            fresh = mapOf(child to true),
        )
        val selection = resolver(repo).resolveMostRecentConversationWithProvenance(child, 30_000L, parent)

        assertEquals("local-conv-900", selection.conversationId)
        assertEquals(TimelineConversationAttribution.REQUESTED_AGENT_ONLY, selection.capture.attribution)
        assertFalse(selection.capture.selectedAlsoAttributedToParent)
    }

    @Test
    fun without_a_known_parent_the_class_is_UNKNOWN() = runTest {
        val repo = FakeConversations(
            cached = mapOf(child to listOf(conversation(shared, owner = child, createdAt = "2026-08-31T22:00:00Z"))),
            fresh = mapOf(child to true),
        )
        val selection = resolver(repo).resolveMostRecentConversationWithProvenance(child, 30_000L, parentAgentId = null)
        assertEquals(TimelineConversationAttribution.UNKNOWN, selection.capture.attribution)
    }

    // ------------------------------------------- explicit route vs the fallback

    @Test
    fun explicit_selection_and_fallback_selection_are_distinguishable() = runTest {
        val repo = FakeConversations(
            cached = mapOf(
                child to listOf(conversation(shared, owner = parent, createdAt = "2026-08-31T22:00:00Z")),
                parent to listOf(conversation(shared, owner = parent, createdAt = "2026-08-31T22:00:00Z")),
            ),
            fresh = mapOf(child to true),
        )
        val resolver = resolver(repo)

        val fallback = resolver.resolveMostRecentConversationWithProvenance(child, 30_000L, parent).capture
        val explicit = resolver.captureAttribution(
            requestedAgentId = child,
            selectedConversationId = shared,
            parentAgentId = parent,
            selectionMode = TimelineConversationSelectionMode.EXPLICIT_CONVERSATION_ID,
        )

        assertEquals(TimelineConversationSelectionMode.MOST_RECENT_FALLBACK, fallback.selectionMode)
        assertEquals(TimelineConversationSelectionMode.EXPLICIT_CONVERSATION_ID, explicit.selectionMode)
        // Same conversation, same attribution class — only the route differs.
        assertEquals(fallback.attribution, explicit.attribution)
    }

    /**
     * A retained child route re-binding is, at this seam, a selection made from
     * already-resolved route state rather than from the fallback.
     */
    @Test
    fun retained_route_state_is_representable_and_distinct() = runTest {
        val repo = FakeConversations(
            cached = mapOf(
                child to listOf(conversation(shared, owner = parent, createdAt = "2026-08-31T22:00:00Z")),
                parent to listOf(conversation(shared, owner = parent, createdAt = "2026-08-31T22:00:00Z")),
            ),
        )
        val capture = resolver(repo).captureAttribution(
            requestedAgentId = child,
            selectedConversationId = shared,
            parentAgentId = parent,
            selectionMode = TimelineConversationSelectionMode.ROUTE_STATE,
        )
        assertEquals(TimelineConversationSelectionMode.ROUTE_STATE, capture.selectionMode)
        assertEquals(TimelineConversationAttribution.PARENT_AGENT_ONLY, capture.attribution)
    }

    // ------------------------------------------------------- behaviour neutrality

    @Test
    fun the_provenance_overload_selects_exactly_what_the_plain_call_selects() = runTest {
        fun repo() = FakeConversations(
            cached = mapOf(
                child to listOf(
                    conversation("older", owner = child, createdAt = "2026-08-30T10:00:00Z"),
                    conversation("newer", owner = child, createdAt = "2026-08-31T10:00:00Z"),
                    conversation("conv-default-skipme", owner = child, createdAt = "2026-09-01T10:00:00Z"),
                ),
            ),
            fresh = mapOf(child to true),
        )
        val plainRepo = repo()
        val provRepo = repo()
        val plain = resolver(plainRepo).resolveMostRecentConversation(child, 30_000L)
        val prov = resolver(provRepo).resolveMostRecentConversationWithProvenance(child, 30_000L, parent)

        assertEquals("newer", plain, "the default-shim conversation must still be skipped")
        assertEquals(plain, prov.conversationId)
        assertEquals(
            plainRepo.refreshIfStaleCalls, provRepo.refreshIfStaleCalls,
            "the provenance overload must not add or drop a repository refresh call",
        )
        assertEquals(
            plainRepo.hasFreshCalls, provRepo.hasFreshCalls,
            "the provenance overload must not add or drop a freshness check",
        )
    }

    @Test
    fun capture_is_bounded_for_a_large_candidate_list() = runTest {
        val many = (1..200).map { conversation("local-conv-$it", owner = child, createdAt = "2026-08-0${it % 9 + 1}T10:00:00Z") }
        val repo = FakeConversations(cached = mapOf(child to many), fresh = mapOf(child to true))
        val capture = resolver(repo).resolveMostRecentConversationWithProvenance(child, 30_000L, parent).capture

        assertEquals(200, capture.candidateCount)
        assertEquals(TimelineProvenanceRedaction.MAX_CANDIDATE_SAMPLE, capture.candidateIdSample.size)
        capture.candidateIdSample.forEach {
            assertTrue(it.length <= TimelineProvenanceRedaction.MAX_IDENTIFIER_LENGTH)
        }
        assertTrue(capture.candidateIdDigest.isNotEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun resolver(conversations: IConversationRepository) = SharedChatSessionResolver(
        agentRepository = StubAgents,
        conversationRepository = conversations,
    )

    private fun conversation(id: String, owner: String, createdAt: String) = Conversation(
        id = ConversationId(id),
        agentId = AgentId(owner),
        createdAt = createdAt,
    )

    private object StubAgents : IAgentRepository {
        override val agents: StateFlow<List<Agent>> = MutableStateFlow(emptyList())
        override val isRefreshing: StateFlow<Boolean> = MutableStateFlow(false)
        override val refreshError: StateFlow<Throwable?> = MutableStateFlow(null)
        override fun getCachedAgent(id: AgentId): Agent? = null
        override fun getAgent(id: AgentId): Flow<Agent> = emptyFlow()
        override suspend fun countAgents(): Int = nope()
        override suspend fun refreshAgents() = nope()
        override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = nope()
        override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?) = nope()
        override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) = nope()
        override suspend fun createAgent(params: com.letta.mobile.data.model.AgentCreateParams) = nope()
        override suspend fun updateAgent(id: AgentId, params: com.letta.mobile.data.model.AgentUpdateParams) = nope()
        override suspend fun deleteAgent(id: AgentId) = nope()
        override suspend fun exportAgent(id: AgentId): String = nope()
        override suspend fun importAgent(params: com.letta.mobile.data.model.AgentImportParams) = nope()
        override suspend fun attachArchive(agentId: AgentId, archiveId: String) = nope()
        override suspend fun detachArchive(agentId: AgentId, archiveId: String) = nope()
        private fun nope(): Nothing = throw UnsupportedOperationException("unused")
    }

    private class FakeConversations(
        private val cached: Map<String, List<Conversation>> = emptyMap(),
        private val fresh: Map<String, Boolean> = emptyMap(),
    ) : IConversationRepository {
        var refreshIfStaleCalls: Int = 0
            private set
        var hasFreshCalls: Int = 0
            private set

        override fun getCachedConversations(agentId: AgentId): List<Conversation> =
            cached[agentId.value] ?: emptyList()

        override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean {
            hasFreshCalls++
            return fresh[agentId.value] ?: false
        }

        override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean {
            refreshIfStaleCalls++
            return true
        }

        override fun getConversations(agentId: AgentId): Flow<List<Conversation>> = emptyFlow()
        override suspend fun refreshConversations(agentId: AgentId) = nope()
        override suspend fun getConversation(id: ConversationId): Conversation = nope()
        override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation = nope()
        override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) = nope()
        override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) = nope()
        override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) = nope()
        override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) = nope()
        override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String = nope()
        override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation = nope()
        private fun nope(): Nothing = throw UnsupportedOperationException("unused")
    }
}
