package com.letta.mobile.channel

import com.letta.mobile.bot.channel.NotificationReplyHandler
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.data.channel.NotificationCandidatePhase
import com.letta.mobile.data.channel.NotificationCandidateSource
import com.letta.mobile.data.channel.NotificationDelivery
import com.letta.mobile.data.channel.NotificationDeliveryDecision
import com.letta.mobile.testutil.FakeAgentRepository
import com.letta.mobile.testutil.FakeClientModeChatSender
import com.letta.mobile.testutil.FakeNotificationDelivery
import com.letta.mobile.testutil.FakeTimelineClientModeWriter
import com.letta.mobile.testutil.TestData
import javax.inject.Provider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("integration")
class NotificationReplyHandlerTest {

    @Test
    fun `notification reply stream submits final assistant response to coordinator`() = runTest {
        val chatSender = FakeClientModeChatSender()
        val timelineRepository = FakeTimelineClientModeWriter()
        val agentRepository = FakeAgentRepository(
            initialAgents = listOf(TestData.agent(id = "agent-1", name = "Ada")),
        )
        val coordinator = FakeNotificationDelivery(
            decision = NotificationDeliveryDecision.Published("reply-message"),
        )
        val coordinatorProvider = Provider<NotificationDelivery> { coordinator }

        chatSender.stream = flowOf(
            BotStreamChunk(text = "Final ", conversationId = "conv-1"),
            BotStreamChunk(text = "answer", conversationId = "conv-1"),
            BotStreamChunk(conversationId = "conv-1", done = true),
        )

        val handler = NotificationReplyHandler(
            clientModeChatSender = chatSender,
            timelineRepository = timelineRepository,
            agentRepository = agentRepository,
            notificationDeliveryProvider = coordinatorProvider,
        )

        val job = handler.sendReply("agent-1", "conv-1", "hello")
        job.join()

        assertTrue(handler.activeReplyStreams.value.isEmpty())
        assertEquals(1, chatSender.requests.size)
        assertEquals(FakeClientModeChatSender.Request("agent-1", "hello", "conv-1", emptyList()), chatSender.requests.single())
        assertEquals(1, coordinator.submitted.size)
        val submitted = coordinator.submitted.single()
        assertEquals("conv-1", submitted.conversationId)
        assertEquals("agent-1", submitted.agentId)
        assertEquals("Ada", submitted.agentName)
        assertEquals(NotificationCandidateSource.NotificationReplyStream, submitted.source)
        assertEquals(NotificationCandidatePhase.Final, submitted.phase)
        assertEquals("Final answer", submitted.previewText)
        assertTrue(submitted.isFinal)
        assertEquals(listOf("conv-1"), timelineRepository.collapsedConversationIds)
    }
}
