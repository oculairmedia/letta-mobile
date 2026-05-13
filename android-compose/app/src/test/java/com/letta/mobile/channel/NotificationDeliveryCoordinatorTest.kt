package com.letta.mobile.channel

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import com.letta.mobile.bot.channel.NotificationReplyHandler
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.channel.NotificationCandidatePhase
import com.letta.mobile.data.channel.NotificationCandidateSource
import com.letta.mobile.data.channel.NotificationDeferralReason
import com.letta.mobile.data.channel.NotificationDeliveryCandidate
import com.letta.mobile.data.channel.NotificationDeliveryDecision
import com.letta.mobile.data.channel.NotificationSuppressionReason
import com.letta.mobile.util.Telemetry
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
    fun `defers low-signal streaming token instead of publishing it`() {
        Telemetry.clear()
        val fixture = createFixture()

        val decision = fixture.coordinator.submit(
            candidate(
                source = NotificationCandidateSource.WebsocketClientMode,
                phase = NotificationCandidatePhase.Partial,
                isFinal = false,
                previewText = "I",
            ),
        )

        assertEquals(
            NotificationDeliveryDecision.Deferred(NotificationDeferralReason.AwaitingFinalPreview),
            decision,
        )
        verify(exactly = 0) { fixture.publisher.publish(any()) }
        assertTrue(
            Telemetry.snapshot().any {
                it.tag == "NotificationDelivery" &&
                    it.name == "deferred" &&
                    it.attrs["previewFallbackReason"] == "TooShort"
            },
        )
    }

    @Test
    fun `final low-signal preview is published for fallback rendering`() {
        val fixture = createFixture()
        val captured = slot<ChannelNotification>()

        val decision = fixture.coordinator.submit(candidate(previewText = "I"))

        assertEquals(NotificationDeliveryDecision.Published("message-1"), decision)
        verify(exactly = 1) { fixture.publisher.publish(capture(captured)) }
        assertEquals("I", captured.captured.messagePreview)
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

    @Test
    fun `publisher rejection is visible and does not record dedupe state`() {
        Telemetry.clear()
        val fixture = createFixture(publisherAccepted = false)

        val decision = fixture.coordinator.submit(candidate())

        assertEquals(
            NotificationDeliveryDecision.Suppressed(NotificationSuppressionReason.PublishRejected),
            decision,
        )
        assertTrue(fixture.notified.isEmpty())
        assertTrue(
            Telemetry.snapshot().any {
                it.tag == "NotificationDelivery" &&
                    it.name == "suppressed" &&
                    it.attrs["reason"] == "PublishRejected"
            },
        )
    }

    private fun createFixture(
        currentConversationId: String? = null,
        activeReplyStreams: Set<String> = emptySet(),
        notified: MutableMap<String, String> = mutableMapOf(),
        publisherAccepted: Boolean = true,
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
        every { publisher.publish(any()) } returns publisherAccepted

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
