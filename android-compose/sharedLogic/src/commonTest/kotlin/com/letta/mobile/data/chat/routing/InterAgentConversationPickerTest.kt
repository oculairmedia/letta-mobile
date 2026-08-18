package com.letta.mobile.data.chat.routing

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationClass
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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

    private data class ConversationTime(
        val lastMessageAt: String? = null,
        val updatedAt: String? = null,
        val createdAt: String? = null,
    )

    private val timeOld = ConversationTime(lastMessageAt = "2026-08-10T12:00:00.000Z")
    private val timeRecent = ConversationTime(lastMessageAt = "2026-08-17T18:00:00.000Z")
    private val timeMid = ConversationTime(lastMessageAt = "2026-08-15T12:00:00.000Z")
    private val timeAutonomous = ConversationTime(lastMessageAt = "2026-08-18T00:00:00.000Z")
    private val timeUpdated = ConversationTime(updatedAt = "2026-08-15T00:00:00.000Z")
    private val timeCreated = ConversationTime(createdAt = "2026-08-17T00:00:00.000Z")

    private fun testConv(
        id: ConversationId,
        time: ConversationTime,
        klass: ConversationClass? = null,
    ) = Conversation(
        id = id,
        agentId = testAgentId,
        lastMessageAt = time.lastMessageAt,
        updatedAt = time.updatedAt,
        createdAt = time.createdAt,
        conversationClass = klass,
    )

    @Test
    fun returnsMostRecentInteractiveConversationId() = runBlocking {
        val conversations = listOf(
            testConv(convOldId, timeOld),
            testConv(convRecentId, timeRecent),
            testConv(convMidId, timeMid),
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
            testConv(convInteractiveId, timeRecent),
            // AUTONOMOUS with a NEWER timestamp must NOT win — the router
            // never routes to heartbeat/goal conversations.
            testConv(convAutonomousNewestId, timeAutonomous, klass = ConversationClass.AUTONOMOUS),
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
            testConv(convByUpdatedId, timeUpdated),
            // No lastMessageAt/updatedAt; createdAt is newest of the fallbacks.
            testConv(convByCreatedId, timeCreated),
        )
        // updatedAt (08-15) vs createdAt (08-17): the created-only one is
        // lex-newer, so it wins under lastMessageAt?:updatedAt?:createdAt.
        assertEquals(
            convByCreatedId.value,
            pickOtherAgentConversation(FakeRepo(agentScopedConversations = conversations), testAgentId),
        )
    }

    @Test
    fun returnsNullWhenNoConversationsExist() = runTest {
        assertNull(
            pickOtherAgentConversation(
                FakeRepo(agentScopedConversations = emptyList()),
                testAgentId,
            ),
        )
    }

    @Test
    fun returnsNullWhenAllConversationsAreAutonomous() = runTest {
        val only = listOf(
            testConv(convAutonomousId, timeAutonomous, klass = ConversationClass.AUTONOMOUS),
        )
        assertNull(
            pickOtherAgentConversation(
                FakeRepo(agentScopedConversations = only),
                testAgentId,
            ),
        )
    }

    @Test
    fun listFailureIsSwallowedAndReturnsNull() = runTest {
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
