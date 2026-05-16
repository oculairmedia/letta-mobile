package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.testutil.TestData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class ChatSessionResolverTest {
    private val agentRepository: AgentRepository = mockk(relaxed = true)
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)

    @Test
    fun `cachedAgentName returns nonblank cached name`() {
        every { agentRepository.getCachedAgent("agent-1") } returns TestData.agent(id = "agent-1", name = "Cached Agent")
        val resolver = resolver()

        assertEquals("Cached Agent", resolver.cachedAgentName("agent-1"))
    }

    @Test
    fun `cachedAgentName ignores missing or blank names`() {
        every { agentRepository.getCachedAgent("agent-1") } returns TestData.agent(id = "agent-1", name = "")
        val resolver = resolver()

        assertNull(resolver.cachedAgentName("agent-1"))
    }

    @Test
    fun `observeCachedAgentName emits names for the target agent only`() = runTest {
        val agents = MutableStateFlow(listOf(TestData.agent(id = "agent-2", name = "Other")))
        every { agentRepository.agents } returns agents
        val resolver = resolver()
        val emitted = mutableListOf<String>()

        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            resolver.observeCachedAgentName("agent-1").collect { emitted += it }
        }
        agents.value = listOf(TestData.agent(id = "agent-1", name = "Target"))
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("", "Target"), emitted)
    }

    @Test
    fun `resolveMostRecentConversation returns cached most recent and refreshes stale cache in background`() = runTest {
        every { conversationRepository.getCachedConversations("agent-1") } returns listOf(
            conversation(id = "older", createdAt = "2026-01-01T00:00:00Z", lastMessageAt = "2026-01-01T00:00:00Z"),
            conversation(id = "newer", createdAt = "2026-01-02T00:00:00Z", lastMessageAt = "2026-01-03T00:00:00Z"),
        )
        every { conversationRepository.hasFreshConversations("agent-1", 30_000L) } returns false
        coEvery { conversationRepository.refreshConversationsIfStale("agent-1", 30_000L) } returns true
        val resolver = resolver()

        val resolved = resolver.resolveMostRecentConversation("agent-1", 30_000L)
        advanceUntilIdle()

        assertEquals("newer", resolved)
        coVerify(exactly = 1) { conversationRepository.refreshConversationsIfStale("agent-1", 30_000L) }
    }

    @Test
    fun `resolveMostRecentConversation handles one thousand cached conversations`() = runTest {
        every { conversationRepository.getCachedConversations("agent-1") } returns List(1_000) { index ->
            conversation(
                id = "conversation-$index",
                createdAt = "2026-01-01T00:00:00Z",
                lastMessageAt = "2026-01-01T00:${(index / 60).toString().padStart(2, '0')}:${(index % 60).toString().padStart(2, '0')}Z",
            )
        }
        every { conversationRepository.hasFreshConversations("agent-1", 30_000L) } returns true
        val resolver = resolver()

        val resolved = resolver.resolveMostRecentConversation("agent-1", 30_000L)

        assertEquals("conversation-999", resolved)
        coVerify(exactly = 0) { conversationRepository.refreshConversationsIfStale(any(), any()) }
    }

    @Test
    fun `resolveMostRecentConversation refreshes when cache is empty`() = runTest {
        every { conversationRepository.getCachedConversations("agent-1") } returnsMany listOf(
            emptyList(),
            listOf(conversation(id = "after-refresh", createdAt = "2026-01-02T00:00:00Z")),
        )
        coEvery { conversationRepository.refreshConversationsIfStale("agent-1", 30_000L) } returns true
        val resolver = resolver()

        val resolved = resolver.resolveMostRecentConversation("agent-1", 30_000L)

        assertEquals("after-refresh", resolved)
        coVerify(exactly = 1) { conversationRepository.refreshConversationsIfStale("agent-1", 30_000L) }
        verify(exactly = 2) { conversationRepository.getCachedConversations("agent-1") }
    }

    private fun resolver(): ChatSessionResolver = ChatSessionResolver(
        agentRepository = agentRepository,
        conversationRepository = conversationRepository,
    )

    private fun conversation(
        id: String,
        createdAt: String,
        lastMessageAt: String? = null,
    ): Conversation = Conversation(
        id = id,
        agentId = "agent-1",
        createdAt = createdAt,
        lastMessageAt = lastMessageAt,
    )
}
