package com.letta.mobile.data.repository

import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.TestData
import com.letta.mobile.data.model.Conversation
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ConversationManagerTest {

    @Test
    fun `setActiveConversation updates tracked state`() {
        val repository = ConversationRepository(FakeConversationApi(), mockk(relaxed = true))
        val manager = ConversationManager(repository)

        assertNull(manager.getActiveConversationId("agent-1"))

        manager.setActiveConversation("agent-1", "conv-123")

        assertEquals("conv-123", manager.getActiveConversationId("agent-1"))
    }

    @Test
    fun `different agents keep independent active conversations`() {
        val repository = ConversationRepository(FakeConversationApi(), mockk(relaxed = true))
        val manager = ConversationManager(repository)

        manager.setActiveConversation("agent-1", "conv-A")
        manager.setActiveConversation("agent-2", "conv-B")

        assertEquals("conv-A", manager.getActiveConversationId("agent-1"))
        assertEquals("conv-B", manager.getActiveConversationId("agent-2"))
    }

    @Test
    fun `observeActiveConversationId emits updates for matching agent`() = runTest {
        val repository = ConversationRepository(FakeConversationApi(), mockk(relaxed = true))
        val manager = ConversationManager(repository)

        manager.setActiveConversation("agent-1", "conv-1")

        assertEquals("conv-1", manager.observeActiveConversationId("agent-1").first())
    }

    @Test
    fun `resolveAndSetActiveConversation uses most recent cached conversation`() = runTest {
        val fakeApi = FakeConversationApi().apply {
            conversations.addAll(
                listOf(
                    Conversation(id = "conv-1", agentId = "agent-1", summary = "Older", createdAt = "2024-01-01T00:00:00Z"),
                    Conversation(id = "conv-2", agentId = "agent-1", summary = "Newer", lastMessageAt = "2024-01-03T00:00:00Z"),
                )
            )
        }
        val repository = ConversationRepository(fakeApi, mockk(relaxed = true))
        val manager = ConversationManager(repository)

        val resolved = manager.resolveAndSetActiveConversation("agent-1")

        assertEquals("conv-2", resolved)
        assertEquals("conv-2", manager.getActiveConversationId("agent-1"))
    }

    @Test
    fun `createAndSetActiveConversation stores created conversation as active`() = runTest {
        val repository = ConversationRepository(FakeConversationApi(), mockk(relaxed = true))
        val manager = ConversationManager(repository)

        val created = manager.createAndSetActiveConversation("agent-1", "Fresh summary")

        assertEquals(created, manager.getActiveConversationId("agent-1"))
    }
}
