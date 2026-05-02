package com.letta.mobile.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatConversationResolverTest {

    private lateinit var conversationManager: ConversationManager
    private lateinit var resolver: ChatConversationResolver

    @Before
    fun setup() {
        conversationManager = mockk()
        resolver = ChatConversationResolver(conversationManager)
    }

    @Test
    fun `client mode with explicit conversationId returns Ready`() = runTest {
        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = "conv123",
            freshRouteKey = null,
            isClientMode = true,
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.Ready)
        assertEquals("conv123", (result as ChatConversationResolver.ResolutionState.Ready).conversationId)
    }

    @Test
    fun `client mode with saved conversationId returns Ready`() = runTest {
        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = null,
            freshRouteKey = null,
            isClientMode = true,
            savedClientModeConversationId = "saved456",
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.Ready)
        assertEquals("saved456", (result as ChatConversationResolver.ResolutionState.Ready).conversationId)
    }

    @Test
    fun `client mode with freshRouteKey returns NoConversation`() = runTest {
        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = null,
            freshRouteKey = System.currentTimeMillis(),
            isClientMode = true,
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.NoConversation)
    }

    @Test
    fun `client mode with blank conversationId returns NoConversation`() = runTest {
        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = "",
            freshRouteKey = null,
            isClientMode = true,
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.NoConversation)
    }

    @Test
    fun `client mode resolves most recent when no explicit args`() = runTest {
        coEvery { 
            conversationManager.resolveAndSetActiveConversation("agent1", 30_000L) 
        } returns "recent789"

        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = null,
            freshRouteKey = null,
            isClientMode = true,
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.Ready)
        assertEquals("recent789", (result as ChatConversationResolver.ResolutionState.Ready).conversationId)
        coVerify { conversationManager.resolveAndSetActiveConversation("agent1", 30_000L) }
    }

    @Test
    fun `client mode returns NoConversation when resolution returns null`() = runTest {
        coEvery { 
            conversationManager.resolveAndSetActiveConversation("agent1", 30_000L) 
        } returns null

        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = null,
            freshRouteKey = null,
            isClientMode = true,
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.NoConversation)
    }

    @Test
    fun `non-client mode with explicit conversationId sets active and returns Ready`() = runTest {
        coEvery { 
            conversationManager.setActiveConversation("agent1", "conv123") 
        } returns Unit

        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = "conv123",
            freshRouteKey = null,
            isClientMode = false,
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.Ready)
        assertEquals("conv123", (result as ChatConversationResolver.ResolutionState.Ready).conversationId)
        coVerify { conversationManager.setActiveConversation("agent1", "conv123") }
    }

    @Test
    fun `non-client mode with active conversationId returns Ready`() = runTest {
        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = null,
            freshRouteKey = null,
            isClientMode = false,
            activeConversationId = "active456",
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.Ready)
        assertEquals("active456", (result as ChatConversationResolver.ResolutionState.Ready).conversationId)
    }

    @Test
    fun `non-client mode resolves most recent when no explicit args`() = runTest {
        coEvery { 
            conversationManager.resolveAndSetActiveConversation("agent1", 30_000L) 
        } returns "recent789"

        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = null,
            freshRouteKey = null,
            isClientMode = false,
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.Ready)
        assertEquals("recent789", (result as ChatConversationResolver.ResolutionState.Ready).conversationId)
        coVerify { conversationManager.resolveAndSetActiveConversation("agent1", 30_000L) }
    }

    @Test
    fun `non-client mode returns NoConversation when resolution returns null`() = runTest {
        coEvery { 
            conversationManager.resolveAndSetActiveConversation("agent1", 30_000L) 
        } returns null

        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = null,
            freshRouteKey = null,
            isClientMode = false,
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.NoConversation)
    }

    @Test
    fun `explicit conversationId takes precedence over saved in client mode`() = runTest {
        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = "explicit123",
            freshRouteKey = null,
            isClientMode = true,
            savedClientModeConversationId = "saved456",
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.Ready)
        assertEquals("explicit123", (result as ChatConversationResolver.ResolutionState.Ready).conversationId)
    }

    @Test
    fun `explicit conversationId takes precedence over active in non-client mode`() = runTest {
        coEvery { 
            conversationManager.setActiveConversation("agent1", "explicit123") 
        } returns Unit

        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = "explicit123",
            freshRouteKey = null,
            isClientMode = false,
            activeConversationId = "active456",
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.Ready)
        assertEquals("explicit123", (result as ChatConversationResolver.ResolutionState.Ready).conversationId)
        coVerify { conversationManager.setActiveConversation("agent1", "explicit123") }
    }

    @Test
    fun `custom maxAgeMs is passed to conversation manager`() = runTest {
        coEvery { 
            conversationManager.resolveAndSetActiveConversation("agent1", 60_000L) 
        } returns "recent789"

        val result = resolver.resolve(
            agentId = "agent1",
            routeConversationId = null,
            freshRouteKey = null,
            isClientMode = true,
            maxAgeMs = 60_000L,
        )

        assertTrue(result is ChatConversationResolver.ResolutionState.Ready)
        coVerify { conversationManager.resolveAndSetActiveConversation("agent1", 60_000L) }
    }
}
