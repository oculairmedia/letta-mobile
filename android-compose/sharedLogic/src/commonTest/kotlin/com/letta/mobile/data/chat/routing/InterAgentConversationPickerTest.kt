package com.letta.mobile.data.chat.routing

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationClass
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-i9h61.3 — the picker delegates to
 * [com.letta.mobile.data.messaging.IrohAgentMessageRouter.pickMostRecentInteractive],
 * so these tests pin the ROUTER's policy as seen from the picker:
 * most-recent INTERACTIVE conversation (never AUTONOMOUS), ordered by
 * `lastMessageAt ?: updatedAt ?: createdAt`. They do NOT assert any
 * guarantee that the picked conversation is "the one carrying this
 * specific message" — that remains the appserver-side
 * `routingConversationId` follow-up tracked in the bead.
 */
class InterAgentConversationPickerTest {

    private class FakeRepo(
        private val agentScopedConversations: List<Conversation>,
        private val throwOnList: Throwable? = null,
    ) : IConversationRepository {
        var listCount = 0
            private set

        override fun getConversations(agentId: AgentId): Flow<List<Conversation>> =
            flowOf(agentScopedConversations)

        override fun getCachedConversations(agentId: AgentId): List<Conversation> = agentScopedConversations

        override suspend fun listConversationsForAgent(agentId: AgentId, limit: Int): List<Conversation> {
            listCount++
            throwOnList?.let { throw it }
            return agentScopedConversations
        }

        override suspend fun refreshConversations(agentId: AgentId) = Unit
        override suspend fun refreshConversations(agentId: String) = Unit

        // Other interface members are not used by the picker; default
        // impls from IConversationRepository (emptyList / Unit) cover
        // them, so we don't need to stub each one.
        override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean = false
        override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean = false
        override suspend fun getConversation(id: ConversationId): Conversation = error("unused by picker")
        override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation = error("unused by picker")
        override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) = error("unused")
        override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) = error("unused")
        override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) = error("unused")
        override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) = error("unused")
        override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String = error("unused")
        override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation = error("unused")
    }

    private val testAgentId = AgentId("agent-meridian")
    private val convOldId = ConversationId("conv-old")
    private val convRecentId = ConversationId("conv-recent")
    private val convMidId = ConversationId("conv-mid")
    private val convInteractiveId = ConversationId("conv-interactive")
    private val convAutonomousId = ConversationId("conv-autonomous")
    private val convAutonomousNewestId = ConversationId("conv-autonomous-newest")
    private val convByUpdatedId = ConversationId("conv-by-updated")
    private val convByCreatedId = ConversationId("conv-by-created")

    private const val TIME_OLD = "2026-08-10T12:00:00.000Z"
    private const val TIME_RECENT = "2026-08-17T18:00:00.000Z"
    private const val TIME_MID = "2026-08-15T12:00:00.000Z"
    private const val TIME_AUTONOMOUS = "2026-08-18T00:00:00.000Z"
    private const val TIME_UPDATED = "2026-08-15T00:00:00.000Z"
    private const val TIME_CREATED = "2026-08-17T00:00:00.000Z"

    private fun conv(
        id: ConversationId,
        lastMessageAt: String,
        klass: ConversationClass? = null,
    ) = Conversation(
        id = id,
        agentId = testAgentId,
        lastMessageAt = lastMessageAt,
        conversationClass = klass,
    )

    private fun convWithFallbackTimes(
        id: ConversationId,
        updatedAt: String? = null,
        createdAt: String? = null,
    ) = Conversation(
        id = id,
        agentId = testAgentId,
        updatedAt = updatedAt,
        createdAt = createdAt,
    )

    @Test
    fun returnsMostRecentInteractiveConversationId() = runBlocking {
        val conversations = listOf(
            conv(convOldId, TIME_OLD),
            conv(convRecentId, TIME_RECENT),
            conv(convMidId, TIME_MID),
        )
        val repo = FakeRepo(agentScopedConversations = conversations)
        assertEquals(
            convRecentId.value,
            pickOtherAgentConversation(repo, testAgentId),
        )
        assertEquals(1, repo.listCount)
    }

    @Test
    fun skipsAutonomousConversationsEvenIfMostRecent() = runBlocking {
        val conversations = listOf(
            conv(convInteractiveId, TIME_RECENT),
            // AUTONOMOUS with a NEWER timestamp must NOT win — the router
            // never routes to heartbeat/goal conversations.
            conv(convAutonomousNewestId, TIME_AUTONOMOUS, klass = ConversationClass.AUTONOMOUS),
        )
        assertEquals(
            convInteractiveId.value,
            pickOtherAgentConversation(FakeRepo(agentScopedConversations = conversations), testAgentId),
        )
    }

    @Test
    fun fallsBackToUpdatedAtThenCreatedAtWhenLastMessageAtMissing() = runBlocking {
        val conversations = listOf(
            // No lastMessageAt; updatedAt is older.
            convWithFallbackTimes(convByUpdatedId, updatedAt = TIME_UPDATED),
            // No lastMessageAt/updatedAt; createdAt is newest of the fallbacks.
            convWithFallbackTimes(convByCreatedId, createdAt = TIME_CREATED),
        )
        // updatedAt (08-15) vs createdAt (08-17): the created-only one is
        // lex-newer, so it wins under lastMessageAt?:updatedAt?:createdAt.
        assertEquals(
            convByCreatedId.value,
            pickOtherAgentConversation(FakeRepo(agentScopedConversations = conversations), testAgentId),
        )
    }

    @Test
    fun returnsNullWhenNoConversationsExist() = runBlocking {
        assertNull(
            pickOtherAgentConversation(
                FakeRepo(agentScopedConversations = emptyList()),
                testAgentId,
            ),
        )
    }

    @Test
    fun returnsNullWhenAllConversationsAreAutonomous() = runBlocking {
        val only = listOf(
            conv(convAutonomousId, TIME_AUTONOMOUS, klass = ConversationClass.AUTONOMOUS),
        )
        assertNull(
            pickOtherAgentConversation(
                FakeRepo(agentScopedConversations = only),
                testAgentId,
            ),
        )
    }

    @Test
    fun listFailureIsSwallowedAndReturnsNull() = runBlocking {
        // If the appserver / wrapper is unreachable, the picker must not
        // crash the chat. Fall through to null and the existing null
        // handling opens a fresh conversation on the target agent.
        val repo = FakeRepo(
            agentScopedConversations = emptyList(),
            throwOnList = IllegalStateException("network unreachable"),
        )
        assertNull(pickOtherAgentConversation(repo, testAgentId))
    }
}
