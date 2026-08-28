package com.letta.mobile.data.timeline.snapshot

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.timeline.ApprovalDecision
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.ToolReturnTruncation
import com.letta.mobile.data.timeline.parseTimelineInstant
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineSnapshotCodecTest {

    @Test
    fun roundTripFidelityPreservesDomainFacts() {
        val decoded = assertNotNull(TimelineSnapshotCodec.decode(TimelineSnapshotCodec.encode(roundTripEnvelope())))
        assertEnvelopeFacts(decoded)
        assertRestoredTimelineFacts(TimelineSnapshotCodec.storedEnvelopeToTimeline(decoded))
    }

    private fun roundTripEnvelope() = TimelineSnapshotCodec.timelineToStoredEnvelope(
        timeline = Timeline(
            conversationId = "conv-abc",
            events = persistentListOf(userEvent(), toolCallEvent()),
            liveCursor = "srv-2",
            backfillCursor = "srv-1",
            releasedOlderCount = 5,
        ),
        scope = TimelineScope(backendId = "backend-1", conversationId = "conv-abc", agentId = "agent-xyz"),
        revision = 42L,
        writtenAtMillis = 1_700_000_000_000L,
    )

    private fun userEvent() = TimelineEvent.Confirmed(
        position = 1.0, otid = "otid-1", content = "User message", serverId = "srv-1",
        messageType = TimelineMessageType.USER, date = parseTimelineInstant("2026-08-24T00:00:00Z"),
        runId = null, stepId = null, agentId = "agent-xyz", seqId = 1,
        attachments = persistentListOf(MessageContentPart.Image(base64 = "base64data", mediaType = "image/png")),
    )

    private fun toolCallEvent() = TimelineEvent.Confirmed(
        position = 2.0, otid = "otid-2", content = "Tool call message", serverId = "srv-2",
        messageType = TimelineMessageType.TOOL_CALL, date = parseTimelineInstant("2026-08-24T00:00:00Z"),
        runId = "run-1", stepId = "step-1", agentId = "agent-xyz", seqId = 2,
        toolCalls = persistentListOf(ToolCall(id = "call-1", name = "bash", arguments = "{\"cmd\":\"ls\"}")),
        approvalRequestId = "req-1", approvalDecided = true, approvalDecision = ApprovalDecision.APPROVED,
        toolReturnContent = "file.txt", toolReturnIsError = false,
        toolReturnContentByCallId = persistentMapOf("call-1" to "file.txt"),
        toolReturnIsErrorByCallId = persistentMapOf("call-1" to false),
        toolReturnTruncationByCallId = persistentMapOf("call-1" to ToolReturnTruncation("srv-ret-1", 1024L)),
    )

    private fun assertEnvelopeFacts(envelope: StoredTimelineEnvelope) {
        assertEquals(StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION, envelope.schemaVersion)
        assertEquals(TimelineScope("backend-1", "conv-abc", "agent-xyz"), envelope.scope)
        assertEquals(42L, envelope.revision)
        assertEquals("srv-2", envelope.liveCursor)
        assertEquals("srv-1", envelope.backfillCursor)
        assertEquals(5, envelope.releasedOlderCount)
        assertEquals(2, envelope.events.size)
    }

    private fun assertRestoredTimelineFacts(timeline: Timeline) {
        assertEquals("conv-abc", timeline.conversationId)
        assertEquals("srv-2", timeline.liveCursor)
        assertEquals("srv-1", timeline.backfillCursor)
        assertEquals(5, timeline.releasedOlderCount)
        assertEquals(2, timeline.events.size)
        assertUserEventFacts(timeline.events[0] as TimelineEvent.Confirmed)
        assertToolCallEventFacts(timeline.events[1] as TimelineEvent.Confirmed)
    }

    private fun assertUserEventFacts(event: TimelineEvent.Confirmed) {
        assertEquals(1.0, event.position)
        assertEquals("otid-1", event.otid)
        assertEquals("User message", event.content)
        assertEquals("srv-1", event.serverId)
        assertEquals(TimelineMessageType.USER, event.messageType)
        assertEquals("agent-xyz", event.agentId)
        assertEquals(1, event.seqId)
    }

    private fun assertToolCallEventFacts(event: TimelineEvent.Confirmed) {
        assertEquals(2.0, event.position)
        assertEquals("otid-2", event.otid)
        assertEquals(TimelineMessageType.TOOL_CALL, event.messageType)
        assertEquals("run-1", event.runId)
        assertEquals("step-1", event.stepId)
        assertEquals("req-1", event.approvalRequestId)
        assertTrue(event.approvalDecided)
        assertEquals(ApprovalDecision.APPROVED, event.approvalDecision)
        assertEquals("file.txt", event.toolReturnContent)
        assertFalse(event.toolReturnIsError)
        assertEquals("file.txt", event.toolReturnContentByCallId["call-1"])
        assertEquals(false, event.toolReturnIsErrorByCallId["call-1"])
        assertEquals(ToolReturnTruncation("srv-ret-1", 1024L), event.toolReturnTruncationByCallId["call-1"])
    }

    @Test
    fun unknownMessageTypeAndDecisionsFallbackGracefully() {
        val jsonWithUnknowns = """
            {
                "schemaVersion": 1,
                "scope": {
                    "backendId": "b1",
                    "conversationId": "c1",
                    "agentId": "a1"
                },
                "revision": 1,
                "events": [
                    {
                        "position": 1.0,
                        "otid": "otid-unknown",
                        "content": "some text",
                        "serverId": "srv-unknown",
                        "messageType": "FUTURE_UNKNOWN_TYPE",
                        "dateIso": "2026-08-24T00:00:00Z",
                        "approvalDecision": "FUTURE_UNKNOWN_DECISION"
                    }
                ]
            }
        """.trimIndent()

        val envelope = TimelineSnapshotCodec.decode(jsonWithUnknowns)
        assertNotNull(envelope)
        val timeline = TimelineSnapshotCodec.storedEnvelopeToTimeline(envelope)
        assertEquals(1, timeline.events.size)
        val event = timeline.events[0] as TimelineEvent.Confirmed
        assertEquals(TimelineMessageType.OTHER, event.messageType)
        assertNull(event.approvalDecision)
    }

    @Test
    fun schemaOneSnapshotFromBeforeProcessorOwnershipRestoresWithoutMigrationLoss() {
        val legacyFixture = """
            {
                "schemaVersion": 1,
                "scope": {"backendId": "legacy", "conversationId": "conversation"},
                "revision": 17,
                "liveCursor": "server-2",
                "backfillCursor": "server-1",
                "releasedOlderCount": 3,
                "events": [
                    {
                        "position": 1.0,
                        "otid": "otid-1",
                        "content": "persisted before bridge retirement",
                        "serverId": "server-1",
                        "messageType": "USER",
                        "dateIso": "2026-08-24T00:00:00Z"
                    }
                ]
            }
        """.trimIndent()

        val envelope = assertNotNull(TimelineSnapshotCodec.decode(legacyFixture))
        val timeline = TimelineSnapshotCodec.storedEnvelopeToTimeline(envelope)

        assertEquals(17L, envelope.revision)
        assertEquals("server-2", timeline.liveCursor)
        assertEquals("server-1", timeline.backfillCursor)
        assertEquals(3, timeline.releasedOlderCount)
        assertEquals("persisted before bridge retirement", timeline.events.single().content)
    }

    @Test
    fun corruptPayloadReturnsNullSafely() {
        assertNull(TimelineSnapshotCodec.decode(""))
        assertNull(TimelineSnapshotCodec.decode("   "))
        assertNull(TimelineSnapshotCodec.decode("not valid json at all"))
        assertNull(TimelineSnapshotCodec.decode("{\"schemaVersion\": \"invalid\"}"))
    }
}
