package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.send.ChatSendCoordinator
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.transport.WsChatBridge
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

class UnboundTimelineWriterTest {
    @Test fun constructionAcceptsRetryBindingButRealWritesStillFail() = runTest {
        val bridge = mockk<WsChatBridge>(relaxed = true) {
            every { redialWhileTurnActive } returns emptyFlow()
        }
        val conversations = object : IConversationRepository {
            override fun getConversations(agentId: AgentId) = emptyFlow<List<Conversation>>()
            override fun getCachedConversations(agentId: AgentId) = emptyList<Conversation>()
        }
        ChatSendCoordinator(
            scope = backgroundScope, agentId = "agent", activeConfig = { null },
            wsChatBridge = bridge, timelineRepository = UnboundTimelineWriter,
            conversationRepository = conversations, ui = mockk(relaxed = true),
            clearComposerAfterSend = {}, activeConversationId = { null }, setActiveConversationId = {},
            startTimelineObserver = {}, clientVersion = { "test" }, otidGenerator = { "unused" },
        )
        try {
            UnboundTimelineWriter.appendExternalTransportLocal("conversation", "prompt", "otid")
            fail("Unbound writer accepted a real write")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("No chat runtime"))
        }
        assertEquals(RecentMessagesReconcileOutcome.Skipped("no_runtime"),
            UnboundTimelineWriter.reconcileRecentMessages(null, "conversation", "test"))
    }
}
