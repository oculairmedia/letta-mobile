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
        val scope = TimelineScope(backendId = "backend-1", conversationId = "conv-abc", agentId = "agent-xyz")
        val date = parseTimelineInstant("2026-08-24T00:00:00Z")

        val event1 = TimelineEvent.Confirmed(
            position = 1.0,
            otid = "otid-1",
            content = "User message",
            serverId = "srv-1",
            messageType = TimelineMessageType.USER,
            date = date,
            runId = null,
            stepId = null,
            agentId = "agent-xyz",
            seqId = 1,
            attachments = persistentListOf(MessageContentPart.Image(base64 = "base64data", mediaType = "image/png")),
        )

        val event2 = TimelineEvent.Confirmed(
            position = 2.0,
            otid = "otid-2",
            content = "Tool call message",
            serverId = "srv-2",
            messageType = TimelineMessageType.TOOL_CALL,
            date = date,
            runId = "run-1",
            stepId = "step-1",
            agentId = "agent-xyz",
            seqId = 2,
            toolCalls = persistentListOf(ToolCall(id = "call-1", name = "bash", arguments = "{\"cmd\":\"ls\"}")),
            approvalRequestId = "req-1",
            approvalDecided = true,
            approvalDecision = ApprovalDecision.APPROVED,
            toolReturnContent = "file.txt",
            toolReturnIsError = false,
            toolReturnContentByCallId = persistentMapOf("call-1" to "file.txt"),
            toolReturnIsErrorByCallId = persistentMapOf("call-1" to false),
            toolReturnTruncationByCallId = persistentMapOf("call-1" to ToolReturnTruncation("srv-ret-1", 1024L)),
        )

        val originalTimeline = Timeline(
            conversationId = "conv-abc",
            events = persistentListOf(event1, event2),
            liveCursor = "srv-2",
            backfillCursor = "srv-1",
            releasedOlderCount = 5,
        )

        val envelope = TimelineSnapshotCodec.timelineToStoredEnvelope(
            timeline = originalTimeline,
            scope = scope,
            revision = 42L,
            writtenAtMillis = 1700000000000L,
        )

        val json = TimelineSnapshotCodec.encode(envelope)
        val decodedEnvelope = TimelineSnapshotCodec.decode(json)

        assertNotNull(decodedEnvelope)
        assertEquals(StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION, decodedEnvelope.schemaVersion)
        assertEquals(scope, decodedEnvelope.scope)
        assertEquals(42L, decodedEnvelope.revision)
        assertEquals("srv-2", decodedEnvelope.liveCursor)
        assertEquals("srv-1", decodedEnvelope.backfillCursor)
        assertEquals(5, decodedEnvelope.releasedOlderCount)
        assertEquals(2, decodedEnvelope.events.size)

        val restoredTimeline = TimelineSnapshotCodec.storedEnvelopeToTimeline(decodedEnvelope)
        assertEquals("conv-abc", restoredTimeline.conversationId)
        assertEquals("srv-2", restoredTimeline.liveCursor)
        assertEquals("srv-1", restoredTimeline.backfillCursor)
        assertEquals(5, restoredTimeline.releasedOlderCount)
        assertEquals(2, restoredTimeline.events.size)

        val restoredEvent1 = restoredTimeline.events[0] as TimelineEvent.Confirmed
        assertEquals(1.0, restoredEvent1.position)
        assertEquals("otid-1", restoredEvent1.otid)
        assertEquals("User message", restoredEvent1.content)
        assertEquals("srv-1", restoredEvent1.serverId)
        assertEquals(TimelineMessageType.USER, restoredEvent1.messageType)
        assertEquals("agent-xyz", restoredEvent1.agentId)
        assertEquals(1, restoredEvent1.seqId)

        val restoredEvent2 = restoredTimeline.events[1] as TimelineEvent.Confirmed
        assertEquals(2.0, restoredEvent2.position)
        assertEquals("otid-2", restoredEvent2.otid)
        assertEquals(TimelineMessageType.TOOL_CALL, restoredEvent2.messageType)
        assertEquals("run-1", restoredEvent2.runId)
        assertEquals("step-1", restoredEvent2.stepId)
        assertEquals("req-1", restoredEvent2.approvalRequestId)
        assertTrue(restoredEvent2.approvalDecided)
        assertEquals(ApprovalDecision.APPROVED, restoredEvent2.approvalDecision)
        assertEquals("file.txt", restoredEvent2.toolReturnContent)
        assertFalse(restoredEvent2.toolReturnIsError)
        assertEquals("file.txt", restoredEvent2.toolReturnContentByCallId["call-1"])
        assertEquals(false, restoredEvent2.toolReturnIsErrorByCallId["call-1"])
        assertEquals(ToolReturnTruncation("srv-ret-1", 1024L), restoredEvent2.toolReturnTruncationByCallId["call-1"])
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
    fun corruptPayloadReturnsNullSafely() {
        assertNull(TimelineSnapshotCodec.decode(""))
        assertNull(TimelineSnapshotCodec.decode("   "))
        assertNull(TimelineSnapshotCodec.decode("not valid json at all"))
        assertNull(TimelineSnapshotCodec.decode("{\"schemaVersion\": \"invalid\"}"))
    }
}
