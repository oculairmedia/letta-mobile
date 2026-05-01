package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.repository.ConversationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRouteSessionResolverTest {

    private val conversationManager: ConversationManager = mockk(relaxed = true)
    private val resolver = ChatRouteSessionResolver(conversationManager)

    @Test
    fun `routeState normalizes explicit conversation and fresh route`() {
        assertEquals(
            ChatRouteState(explicitConversationId = "conv-1", isFreshRoute = false),
            resolver.routeState(requestedConversationArg = "conv-1", freshRouteKey = null),
        )
        assertEquals(
            ChatRouteState(explicitConversationId = null, isFreshRoute = true),
            resolver.routeState(requestedConversationArg = "", freshRouteKey = null),
        )
    }

    @Test
    fun `client mode explicit conversation wins over saved or active state`() = runTest {
        val result = resolver.resolve(
            ChatConversationResolveRequest(
                agentId = "agent-1",
                routeState = ChatRouteState(explicitConversationId = "conv-explicit", isFreshRoute = false),
                clientModeEnabled = true,
                activeConversationId = "conv-active",
                savedClientModeConversationId = "conv-saved",
                maxConversationAgeMs = 30_000L,
            )
        )

        assertEquals(ChatConversationResolution.Ready("conv-explicit"), result)
        coVerify(exactly = 0) { conversationManager.resolveAndSetActiveConversation(any(), any()) }
    }

    @Test
    fun `client mode fresh route beats saved conversation restoration`() = runTest {
        val result = resolver.resolve(
            ChatConversationResolveRequest(
                agentId = "agent-1",
                routeState = ChatRouteState(explicitConversationId = null, isFreshRoute = true),
                clientModeEnabled = true,
                activeConversationId = "conv-active",
                savedClientModeConversationId = "conv-saved",
                maxConversationAgeMs = 30_000L,
            )
        )

        assertEquals(ChatConversationResolution.FreshConversation, result)
        coVerify(exactly = 0) { conversationManager.resolveAndSetActiveConversation(any(), any()) }
    }

    @Test
    fun `client mode restores saved conversation before falling back to most recent`() = runTest {
        val result = resolver.resolve(
            ChatConversationResolveRequest(
                agentId = "agent-1",
                routeState = ChatRouteState(explicitConversationId = null, isFreshRoute = false),
                clientModeEnabled = true,
                activeConversationId = null,
                savedClientModeConversationId = "conv-saved",
                maxConversationAgeMs = 30_000L,
            )
        )

        assertEquals(ChatConversationResolution.Ready("conv-saved"), result)
        coVerify(exactly = 0) { conversationManager.resolveAndSetActiveConversation(any(), any()) }
    }

    @Test
    fun `client mode defaults to most recent when route is not fresh and no saved conversation exists`() = runTest {
        coEvery { conversationManager.resolveAndSetActiveConversation("agent-1", 30_000L) } returns "conv-most-recent"

        val result = resolver.resolve(
            ChatConversationResolveRequest(
                agentId = "agent-1",
                routeState = ChatRouteState(explicitConversationId = null, isFreshRoute = false),
                clientModeEnabled = true,
                activeConversationId = null,
                savedClientModeConversationId = null,
                maxConversationAgeMs = 30_000L,
            )
        )

        assertEquals(ChatConversationResolution.Ready("conv-most-recent"), result)
        coVerify(exactly = 1) { conversationManager.resolveAndSetActiveConversation("agent-1", 30_000L) }
    }

    @Test
    fun `non client explicit conversation becomes active and wins`() = runTest {
        every { conversationManager.setActiveConversation("agent-1", "conv-explicit") } returns Unit

        val result = resolver.resolve(
            ChatConversationResolveRequest(
                agentId = "agent-1",
                routeState = ChatRouteState(explicitConversationId = "conv-explicit", isFreshRoute = false),
                clientModeEnabled = false,
                activeConversationId = "conv-active",
                savedClientModeConversationId = null,
                maxConversationAgeMs = 30_000L,
            )
        )

        assertEquals(ChatConversationResolution.Ready("conv-explicit"), result)
        verify(exactly = 1) { conversationManager.setActiveConversation("agent-1", "conv-explicit") }
    }
}
