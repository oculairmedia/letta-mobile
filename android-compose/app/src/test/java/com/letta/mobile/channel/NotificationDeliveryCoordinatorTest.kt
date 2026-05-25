package com.letta.mobile.channel

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.channel.NotificationCandidatePhase
import com.letta.mobile.data.channel.NotificationCandidateSource
import com.letta.mobile.data.channel.NotificationDeferralReason
import com.letta.mobile.data.channel.NotificationDeliveryCandidate
import com.letta.mobile.data.channel.NotificationDeliveryDecision
import com.letta.mobile.data.channel.NotificationSuppressionReason
import com.letta.mobile.testutil.FakeChannelNotificationPublisher
import com.letta.mobile.testutil.FakeChannelSyncStateStore
import com.letta.mobile.util.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("integration")
class NotificationDeliveryCoordinatorTest {

    @Test
    fun `publishes final candidate and records shared dedupe state`() {
        val fixture = createFixture()

        val decision = fixture.coordinator.submit(candidate())

        assertEquals(NotificationDeliveryDecision.Published("message-1"), decision)
        assertEquals("message-1", fixture.notified["conversation-1"])
        val published = fixture.publisher.published.single()
        assertEquals("agent-1", published.agentId)
        assertEquals("Ada", published.agentName)
        assertEquals("conversation-1", published.conversationId)
        assertEquals("message-1", published.messageId)
        assertEquals("Hello from the background", published.messagePreview)
    }

    @Test
    fun `suppresses duplicate candidates using shared notified message state`() {
        val fixture = createFixture(notified = mutableMapOf("conversation-1" to "message-1"))

        val decision = fixture.coordinator.submit(candidate())

        assertEquals(
            NotificationDeliveryDecision.Suppressed(NotificationSuppressionReason.DuplicateNotification),
            decision,
        )
        assertTrue(fixture.publisher.published.isEmpty())
    }

    @Test
    fun `suppresses candidates for the current foreground conversation`() {
        val fixture = createFixture(currentConversationId = "conversation-1")

        val decision = fixture.coordinator.submit(candidate())

        assertEquals(
            NotificationDeliveryDecision.Suppressed(NotificationSuppressionReason.ForegroundConversation),
            decision,
        )
        assertTrue(fixture.publisher.published.isEmpty())
    }


    @Test
    fun `defers partial websocket previews instead of publishing first delta`() {
        val fixture = createFixture()

        val decision = fixture.coordinator.submit(
            candidate(
                source = NotificationCandidateSource.TimelineIngestion,
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
        assertTrue(fixture.publisher.published.isEmpty())
    }

    @Test
    fun `defers low-signal streaming token instead of publishing it`() {
        Telemetry.clear()
        val fixture = createFixture()

        val decision = fixture.coordinator.submit(
            candidate(
                source = NotificationCandidateSource.TimelineIngestion,
                phase = NotificationCandidatePhase.Partial,
                isFinal = false,
                previewText = "I",
            ),
        )

        assertEquals(
            NotificationDeliveryDecision.Deferred(NotificationDeferralReason.AwaitingFinalPreview),
            decision,
        )
        assertTrue(fixture.publisher.published.isEmpty())
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

        val decision = fixture.coordinator.submit(candidate(previewText = "I"))

        assertEquals(NotificationDeliveryDecision.Published("message-1"), decision)
        assertEquals("I", fixture.publisher.published.single().messagePreview)
    }

    @Test
    fun `websocket stream publishes once when final preview arrives`() {
        val fixture = createFixture()

        val partial = fixture.coordinator.submit(
            candidate(
                source = NotificationCandidateSource.TimelineIngestion,
                phase = NotificationCandidatePhase.Partial,
                isFinal = false,
                previewText = "Hel",
            ),
        )
        val final = fixture.coordinator.submit(
            candidate(
                source = NotificationCandidateSource.TimelineIngestion,
                phase = NotificationCandidatePhase.Final,
                isFinal = true,
                previewText = "Hello from the background",
            ),
        )

        assertEquals(NotificationDeliveryDecision.Deferred(NotificationDeferralReason.AwaitingFinalPreview), partial)
        assertEquals(NotificationDeliveryDecision.Published("message-1"), final)
        assertEquals("Hello from the background", fixture.publisher.published.single().messagePreview)
    }

    @Test
    fun `uses run id as identity when message id is unavailable`() {
        val fixture = createFixture()

        val decision = fixture.coordinator.submit(
            candidate(messageId = null, runId = "run-1"),
        )

        assertEquals(NotificationDeliveryDecision.Published("run-1"), decision)
        assertEquals("run-1", fixture.notified["conversation-1"])
        assertEquals("run-1", fixture.publisher.published.single().messageId)
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
        @Suppress("UNUSED_PARAMETER") activeReplyStreams: Set<String> = emptySet(),
        notified: MutableMap<String, String> = mutableMapOf(),
        publisherAccepted: Boolean = true,
    ): Fixture {
        val tracker = CurrentConversationTracker().apply { setCurrent(currentConversationId) }
        val stateStore = FakeChannelSyncStateStore(initialNotified = notified)
        val publisher = FakeChannelNotificationPublisher(accepted = publisherAccepted)

        return Fixture(
            coordinator = NotificationDeliveryCoordinator(
                currentConversationTracker = tracker,
                syncStateStore = stateStore,
                publisher = publisher,
            ),
            publisher = publisher,
            notified = stateStore.notified,
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
        val publisher: FakeChannelNotificationPublisher,
        val notified: MutableMap<String, String>,
    )
}
