package com.letta.mobile.channel

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("integration")
class NotificationDeliveryCoordinatorTest {

    @Test
    fun `publishes final candidate and records shared dedupe state`() {
        val fixture = createFixture()
        val captured = slot<ChannelNotification>()

        val decision = fixture.coordinator.submit(candidate())

        assertEquals(NotificationDeliveryDecision.Published("message-1"), decision)
        assertEquals("message-1", fixture.notified["conversation-1"])
        verify(exactly = 1) { fixture.publisher.publish(capture(captured)) }
        assertEquals("agent-1", captured.captured.agentId)
        assertEquals("Ada", captured.captured.agentName)
        assertEquals("conversation-1", captured.captured.conversationId)
        assertEquals("message-1", captured.captured.messageId)
        assertEquals("Hello from the background", captured.captured.messagePreview)
    }

    @Test
    fun `suppresses duplicate candidates using shared notified message state`() {
        val fixture = createFixture(notified = mutableMapOf("conversation-1" to "message-1"))

        val decision = fixture.coordinator.submit(candidate())

        assertEquals(
            NotificationDeliveryDecision.Suppressed(NotificationSuppressionReason.DuplicateNotification),
            decision,
        )
        verify(exactly = 0) { fixture.publisher.publish(any()) }
    }

    @Test
    fun `suppresses candidates for the current foreground conversation`() {
        val fixture = createFixture(currentConversationId = "conversation-1")

        val decision = fixture.coordinator.submit(candidate())

        assertEquals(
            NotificationDeliveryDecision.Suppressed(NotificationSuppressionReason.ForegroundConversation),
            decision,
        )
        verify(exactly = 0) { fixture.publisher.publish(any()) }
    }

    @Test
    fun `suppresses candidates while notification reply stream is active`() {
        val fixture = createFixture(activeReplyStreams = setOf("conversation-1"))

        val decision = fixture.coordinator.submit(
            candidate(source = NotificationCandidateSource.TimelineIngestion),
        )

        assertEquals(
            NotificationDeliveryDecision.Suppressed(NotificationSuppressionReason.ActiveNotificationReplyStream),
            decision,
        )
        verify(exactly = 0) { fixture.publisher.publish(any()) }
    }

    @Test
    fun `allows notification reply stream source to notify while reply stream is active`() {
        val fixture = createFixture(activeReplyStreams = setOf("conversation-1"))

        val decision = fixture.coordinator.submit(
            candidate(source = NotificationCandidateSource.NotificationReplyStream),
        )

        assertEquals(NotificationDeliveryDecision.Published("message-1"), decision)
        verify(exactly = 1) { fixture.publisher.publish(match { it.messageId == "message-1" }) }
    }

    @Test
    fun `defers partial websocket previews instead of publishing first delta`() {
        val fixture = createFixture()

        val decision = fixture.coordinator.submit(
            candidate(
                source = NotificationCandidateSource.WebsocketClientMode,
                phase = NotificationCandidatePhase.Partial,
                isFinal = false,
                previewText = "Hel",
            ),
        )

        assertEquals(
            NotificationDeliveryDecision.Deferred(NotificationDeferralReason.AwaitingFinalPreview),
            decision,
        )
        assertTrue(fixture.notified.isEmpty())
        verify(exactly = 0) { fixture.publisher.publish(any()) }
    }

    @Test
    fun `websocket stream publishes once when final preview arrives`() {
        val fixture = createFixture()

        val partial = fixture.coordinator.submit(
            candidate(
                source = NotificationCandidateSource.WebsocketClientMode,
                phase = NotificationCandidatePhase.Partial,
                isFinal = false,
                previewText = "Hel",
            ),
        )
        val final = fixture.coordinator.submit(
            candidate(
                source = NotificationCandidateSource.WebsocketClientMode,
                phase = NotificationCandidatePhase.Final,
                isFinal = true,
                previewText = "Hello from the background",
            ),
        )

        assertEquals(NotificationDeliveryDecision.Deferred(NotificationDeferralReason.AwaitingFinalPreview), partial)
        assertEquals(NotificationDeliveryDecision.Published("message-1"), final)
        verify(exactly = 1) { fixture.publisher.publish(match { it.messagePreview == "Hello from the background" }) }
    }

    @Test
    fun `uses run id as identity when message id is unavailable`() {
        val fixture = createFixture()

        val decision = fixture.coordinator.submit(
            candidate(messageId = null, runId = "run-1"),
        )

        assertEquals(NotificationDeliveryDecision.Published("run-1"), decision)
        assertEquals("run-1", fixture.notified["conversation-1"])
        verify(exactly = 1) { fixture.publisher.publish(match { it.messageId == "run-1" }) }
    }

    private fun createFixture(
        currentConversationId: String? = null,
        activeReplyStreams: Set<String> = emptySet(),
        notified: MutableMap<String, String> = mutableMapOf(),
    ): Fixture {
        val tracker = CurrentConversationTracker().apply { setCurrent(currentConversationId) }
        val replyHandler = mockk<NotificationReplyHandler>()
        val stateStore = mockk<ChannelSyncStateStore>()
        val publisher = mockk<ChannelNotificationPublisher>()

        every { replyHandler.activeReplyStreams } returns MutableStateFlow(activeReplyStreams)
        every { stateStore.getLastNotifiedMessageId(any()) } answers { notified[firstArg()] }
        every { stateStore.setLastNotifiedMessageId(any(), any()) } answers {
            notified[firstArg()] = secondArg()
            Unit
        }
        every { publisher.publish(any()) } just runs

        return Fixture(
            coordinator = NotificationDeliveryCoordinator(
                currentConversationTracker = tracker,
                notificationReplyHandler = replyHandler,
                syncStateStore = stateStore,
                publisher = publisher,
            ),
            publisher = publisher,
            notified = notified,
        )
    }

    private fun candidate(
        messageId: String? = "message-1",
        runId: String? = null,
        source: NotificationCandidateSource = NotificationCandidateSource.HeartbeatFallback,
        phase: NotificationCandidatePhase = NotificationCandidatePhase.Settled,
        previewText: String = "Hello from the background",
        isFinal: Boolean = true,
    ) = NotificationDeliveryCandidate(
        conversationId = "conversation-1",
        agentId = "agent-1",
        agentName = "Ada",
        conversationSummary = "Main thread",
        messageId = messageId,
        runId = runId,
        source = source,
        phase = phase,
        previewText = previewText,
        isFinal = isFinal,
    )

    private data class Fixture(
        val coordinator: NotificationDeliveryCoordinator,
        val publisher: ChannelNotificationPublisher,
        val notified: MutableMap<String, String>,
    )
}
