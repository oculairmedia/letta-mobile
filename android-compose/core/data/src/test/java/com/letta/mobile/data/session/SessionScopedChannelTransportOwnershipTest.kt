package com.letta.mobile.data.session

import com.letta.mobile.testutil.FakeChannelTransport
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dir4k (z5vfy PR-2): SessionScopedChannelTransport is the production wrapper that
 * WsChatBridge holds. It must delegate hasActiveChatTurn THROUGH to the live
 * transport — otherwise it inherits IChannelTransport's default (false) and
 * swallows the real Iroh/WS ownership signal, making ChatSendCoordinator's
 * stale-presence self-heal fire on every send (the "legacy WS misclassification").
 */
class SessionScopedChannelTransportOwnershipTest {

    private fun wrapperOver(fake: FakeChannelTransport): SessionScopedChannelTransport {
        val graph = mockk<SessionGraph>(relaxed = true)
        every { graph.channelTransport } returns fake
        val sessionManager = mockk<SessionManager>(relaxed = true)
        every { sessionManager.current } returns graph
        every { sessionManager.currentGraph } returns MutableStateFlow(graph)
        return SessionScopedChannelTransport(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher()),
        )
    }

    @Test
    fun `hasActiveChatTurn reflects the wrapped transport when a turn is active`() = runTest {
        val fake = FakeChannelTransport().apply { hasActiveChatTurn = true }
        val wrapper = wrapperOver(fake)
        assertTrue("wrapper must delegate the live transport's active-turn ownership", wrapper.hasActiveChatTurn)
    }

    @Test
    fun `hasActiveChatTurn is false when the wrapped transport has no active turn`() = runTest {
        val fake = FakeChannelTransport().apply { hasActiveChatTurn = false }
        val wrapper = wrapperOver(fake)
        assertFalse("wrapper must reflect the live transport reporting no active turn", wrapper.hasActiveChatTurn)
    }

    @Test
    fun `hasActiveChatTurn tracks live changes on the wrapped transport`() = runTest {
        val fake = FakeChannelTransport().apply { hasActiveChatTurn = false }
        val wrapper = wrapperOver(fake)
        assertFalse("delegation is by-getter, so it must see the current live value", wrapper.hasActiveChatTurn)
        fake.hasActiveChatTurn = true
        assertTrue(wrapper.hasActiveChatTurn)
    }
}
