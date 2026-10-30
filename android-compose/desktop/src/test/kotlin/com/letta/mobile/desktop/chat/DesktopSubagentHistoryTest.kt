package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.subagents.SubagentView
import com.letta.mobile.ui.chat.session.SubagentTimelineRoute
import com.letta.mobile.ui.chat.session.TimelineSubagentViewSessions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSubagentHistoryTest {
    @Test
    fun desktopDefaultChildReadsConversationHistoryInsteadOfGatedAgentHistory() = runTest {
        assertHistory { gateway, view -> desktopSubagentTimelineRoute(gateway, view) }
    }

    @Test
    fun sharedDefaultChildHydratesThroughItsAddressableConversation() = runTest {
        assertHistory { gateway, view -> SubagentTimelineRoute(gateway, view.conversationId) }
    }

    @Test
    fun ordinaryDefaultLoopStillSkipsHistoryHydration() = runTest {
        val gateway = FakeDesktopChatGateway()
        val loop = com.letta.mobile.data.timeline.TimelineSyncLoop(
            messageApi = gateway,
            conversationId = "conv-default-agent-local-child",
            scope = backgroundScope,
            agentId = "agent-local-child",
        )
        loop.hydrate()
        runCurrent()
        assertTrue(gateway.conversationMessageRequests.isEmpty())
        assertTrue(gateway.agentMessageRequests.isEmpty())
        loop.close()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertHistory(
        route: (FakeDesktopChatGateway, SubagentView) -> SubagentTimelineRoute,
    ) {
        val gateway = object : FakeDesktopChatGateway() {
            override suspend fun listConversationMessages(
                conversationId: ConversationId,
                limit: Int?,
                after: String?,
                order: String?,
            ): List<LettaMessage> = if (conversationId.value == "conv-default-agent-local-child") {
                super.listConversationMessages(conversationId, limit, after, order)
            } else {
                emptyList()
            }

            override suspend fun listAgentMessages(
                agentId: AgentId,
                limit: Int?,
                order: String?,
                conversationId: ConversationId?,
            ): List<LettaMessage> = emptyList()
        }
        val conversation = ConversationId("conv-default-agent-local-child")
        val view = SubagentView(AgentId("agent-local-child"), conversation)
        val session = requireNotNull(TimelineSubagentViewSessions { route(gateway, it) }.open(view, backgroundScope))
        backgroundScope.launch { session.port.uiState.collect {} }
        runCurrent()
        assertTrue(session.port.uiState.value.messages.isNotEmpty(), "Existing child history must appear in the pane")
        assertEquals(listOf(conversation.value), gateway.conversationMessageRequests)
        session.close()
    }
}
