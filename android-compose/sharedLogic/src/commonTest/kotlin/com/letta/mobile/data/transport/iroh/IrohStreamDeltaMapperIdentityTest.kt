package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.toTimelineEvent
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.util.Telemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-ys9it: the client mapper reads the identity the host stamped
 * (`logical_message_id`, `turn_id`, `text_seq`) and derives nothing of its own.
 */
class IrohStreamDeltaMapperIdentityTest {

    @Test
    fun mapperCopiesLogicalIdTurnIdAndTextSeq() {
        val frame = assertIs<ServerFrame.AssistantMessage>(
            map(assistantBody(logicalId = "lm-7", turnId = "cm-turn-1", textSeq = 4)).single(),
        )
        assertEquals("lm-7", frame.id)
        assertEquals("lm-7", frame.logicalMessageId)
        assertEquals("cm-turn-1", frame.turnId)
        assertEquals(4, frame.textSeq)

        val event = assertNotNull(WsFrameMapper.toLettaMessage(frame)?.toTimelineEvent(position = 1.0))
        assertEquals("lm-7", event.logicalId)
        assertEquals("lm-7", event.otid)
        assertEquals("cm-turn-1", event.turnId)
        assertEquals(4, event.textSeq)
    }

    @Test
    fun unstampedAssistantFrameIsDroppedWithTelemetry() {
        Telemetry.clear()
        val frames = map(assistantBody(logicalId = null, turnId = "cm-turn-1", textSeq = null))

        assertTrue(frames.isEmpty())
        assertTrue(Telemetry.events.value.any { it.name == "live.unstampedFrame" })
    }

    @Test
    fun observerContextNeverNamesAMessage() {
        val observerContext = IrohStreamDeltaServerFrameMapper.Context(
            agentId = "agent-1",
            conversationId = "conv-1",
            turnId = "iroh-observer-turn-conv-1",
            runId = "iroh-observer-run-conv-1",
            timestamp = "2026-10-03T00:00:00Z",
        )
        val stamped = map(assistantBody("lm-a", turnId = null, textSeq = 1), observerContext)
        val unstamped = map(assistantBody(null, turnId = null, textSeq = null), observerContext)

        val row = assertIs<ServerFrame.AssistantMessage>(stamped.single())
        assertEquals("lm-a", row.id)
        assertNull(row.turnId, "the context turn is not the row's turn")
        assertNull(row.otid, "no otid is derived from the context or the turn")
        assertTrue(unstamped.isEmpty(), "no id is derived from the context for an unstamped frame")
        val event = assertNotNull(WsFrameMapper.toLettaMessage(row)?.toTimelineEvent(position = 1.0))
        assertNull(event.turnId)
    }

    @Test
    fun userEchoKeepsClientMessageIdAsOtid() {
        val body = """{"type":"stream_delta","delta":{"message_type":"user_message","id":"cm-user-cmid-1","otid":"cmid-1","content":"hi","logical_message_id":"cmid-1","turn_id":"cmid-1"}}"""
        val frame = assertIs<ServerFrame.UserMessage>(map(body).single())
        assertEquals("cmid-1", frame.otid)

        val event = assertNotNull(WsFrameMapper.toLettaMessage(frame)?.toTimelineEvent(position = 1.0))
        assertEquals(TimelineMessageType.USER, event.messageType)
        assertEquals("cmid-1", event.otid)
        assertEquals("cmid-1", event.logicalId)
        assertEquals("cmid-1", event.turnId)
    }

    private fun assistantBody(logicalId: String?, turnId: String?, textSeq: Int?): String {
        val stamp = listOfNotNull(
            logicalId?.let { "\"logical_message_id\":\"$it\"" },
            turnId?.let { "\"turn_id\":\"$it\"" },
            textSeq?.let { "\"text_seq\":$it" },
        ).joinToString(",")
        val suffix = if (stamp.isEmpty()) "" else ",$stamp"
        return """{"type":"stream_delta","event_seq":1,"idempotency_key":"evt-1","delta":{"id":"letta-msg-1","message_type":"assistant_message","content":"hello"$suffix}}"""
    }

    private fun map(
        body: String,
        context: IrohStreamDeltaServerFrameMapper.Context = defaultContext,
    ): List<ServerFrame> = IrohStreamDeltaServerFrameMapper.map(
        payload = RuntimeEventPayload.RemoteStreamFrame(
            frameId = "frame-1",
            messageId = null,
            messageType = null,
            body = body,
        ),
        context = context,
    )

    private companion object {
        val defaultContext = IrohStreamDeltaServerFrameMapper.Context(
            agentId = "agent-1",
            conversationId = "conv-1",
            turnId = "turn-ctx",
            runId = "run-ctx",
            timestamp = "2026-10-03T00:00:00Z",
        )
    }
}
