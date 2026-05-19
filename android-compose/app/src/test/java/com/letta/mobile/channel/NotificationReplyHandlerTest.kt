package com.letta.mobile.channel

import com.letta.mobile.bot.channel.NotificationReplyHandler
import com.letta.mobile.bot.chat.ClientModeChatSender
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.data.channel.NotificationCandidatePhase
import com.letta.mobile.data.channel.NotificationCandidateSource
import com.letta.mobile.data.channel.NotificationDelivery
import com.letta.mobile.data.channel.NotificationDeliveryCandidate
import com.letta.mobile.data.channel.NotificationDeliveryDecision
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.testutil.FakeAgentRepository
import com.letta.mobile.testutil.TestData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("integration")
class NotificationReplyHandlerTest {

    @Test
    fun `notification reply stream submits final assistant response to coordinator`() = runTest {
        val chatSender = mockk<ClientModeChatSender>()
        val timelineRepository = mockk<TimelineRepository>()
        val agentRepository = FakeAgentRepository(
            initialAgents = listOf(TestData.agent(id = "agent-1", name = "Ada")),
        )
        val coordinator = mockk<NotificationDelivery>()
        val coordinatorProvider = Provider { coordinator }
        val captured = slot<NotificationDeliveryCandidate>()

        every { coordinator.submit(capture(captured)) } returns NotificationDeliveryDecision.Published("reply-message")
        coEvery { timelineRepository.appendClientModeLocal(any(), any(), any()) } returns "local-user"
        coEvery { timelineRepository.upsertClientModeLocalAssistantChunk(any(), any(), any(), any()) } answers {
            thirdArg<() -> TimelineEvent.Local>().invoke()
            secondArg()
        }
        coEvery { timelineRepository.postHandlerCollapse(any()) } returns Unit
        every { chatSender.streamMessage("agent-1", "hello", "conv-1") } returns flow {
            emit(BotStreamChunk(text = "Final ", conversationId = "conv-1"))
            emit(BotStreamChunk(text = "answer", conversationId = "conv-1"))
            emit(BotStreamChunk(conversationId = "conv-1", done = true))
        }

        val handler = NotificationReplyHandler(
            clientModeChatSender = chatSender,
            timelineRepository = timelineRepository,
            agentRepository = agentRepository,
            notificationDeliveryProvider = coordinatorProvider,
        )

        val job = handler.sendReply("agent-1", "conv-1", "hello")
        job.join()

        assertTrue(handler.activeReplyStreams.value.isEmpty())
        verify(exactly = 1) { coordinator.submit(any()) }
        assertEquals("conv-1", captured.captured.conversationId)
        assertEquals("agent-1", captured.captured.agentId)
        assertEquals("Ada", captured.captured.agentName)
        assertEquals(NotificationCandidateSource.NotificationReplyStream, captured.captured.source)
        assertEquals(NotificationCandidatePhase.Final, captured.captured.phase)
        assertEquals("Final answer", captured.captured.previewText)
        assertTrue(captured.captured.isFinal)
    }
}
