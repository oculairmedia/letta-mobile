package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * letta-mobile-r5v5t: a record the conversation does not show is dropped, never presented as a
 * placeholder bubble apologising for itself.
 */
class TimelineSettledPresentationTest {

    @Test fun anOpaqueProtocolRecordIsDropped() {
        val record = record(
            body = """{"id":"ui-msg-9150215","message_type":"summary_message"}""".encodeToByteArray(),
            contentType = "application/vnd.letta.message+json;version=1",
        )
        assertEquals(TimelineSettledPresentation.Drop, record.presentation())
    }

    @Test fun aSystemSeedIsDropped() {
        assertEquals(TimelineSettledPresentation.Drop, record(event("system_message", "base instructions")).presentation())
    }

    @Test fun aStandaloneToolReturnIsDropped() {
        assertEquals(TimelineSettledPresentation.Drop, record(event("tool_return_message", "ok")).presentation())
    }

    @Test fun anAssistantMessageRenders() {
        val rendered = assertIs<TimelineSettledPresentation.Render>(record(event("assistant_message", "hello")).presentation())
        assertEquals("hello", rendered.event.content)
    }

    @Test fun completeRenderableRecordDecodesAndProjectsOnce() {
        var decodes = 0
        var projections = 0
        val adapter = TimelineSettledProjectionAdapter(
            decode = { timeline ->
                decodes++
                DefaultTimelineSettledProjectionAdapter.decode(timeline)
            },
            project = { timeline, decoded, ownAgentId ->
                projections++
                DefaultTimelineSettledProjectionAdapter.project(timeline, decoded, ownAgentId)
            },
        )

        val rendered = assertIs<TimelineSettledPresentation.Render>(
            record(event("assistant_message", "hello")).presentationWithAdapter(null, adapter),
        )
        assertEquals("otid-1", rendered.event.otid)
        assertEquals(1, decodes)
        assertEquals(1, projections)
    }

    @Test fun droppedAndDeferredRecordsDoNotDecodeOrProject() {
        val adapter = TimelineSettledProjectionAdapter(
            decode = { error("must not decode") },
            project = { _, _, _ -> error("must not project") },
        )
        val opaque = record(event("assistant_message", "hello"), "application/vnd.letta.message+json;version=1")
        val deferred = record(event("assistant_message", "hello")).copy(pointer = TimelineBodyPointer("body-1", 4_096))

        assertEquals(TimelineSettledPresentation.Drop, opaque.presentationWithAdapter(null, adapter))
        assertEquals(TimelineSettledPresentation.Defer, deferred.presentationWithAdapter(null, adapter))
    }

    @Test fun malformedCompleteRecordStillFailsBeforeProjection() {
        var projections = 0
        val adapter = TimelineSettledProjectionAdapter(
            decode = { DefaultTimelineSettledProjectionAdapter.decode(it) },
            project = { _, _, _ -> projections++; error("must not project") },
        )

        assertFailsWith<Exception> {
            record("not-json".encodeToByteArray()).presentationWithAdapter(null, adapter)
        }
        assertEquals(0, projections)
    }

    @Test fun aBodyHeldBackFromInlineDecodingIsDeferred() {
        val body = event("assistant_message", "hello")
        val record = record(body).copy(pointer = TimelineBodyPointer("body-1", body.size + 4096L))
        assertEquals(TimelineSettledPresentation.Defer, record.presentation())
    }

    @Test fun anOpaqueRecordIsDroppedEvenWhenItsBodyIsHeldBack() {
        val body = """{"message_type":"summary_message"}""".encodeToByteArray()
        val record = record(body, "application/vnd.letta.message+json;version=1")
            .copy(pointer = TimelineBodyPointer("body-1", body.size + 4096L))
        assertEquals(TimelineSettledPresentation.Drop, record.presentation())
    }

    private fun event(messageType: String, content: String): ByteArray =
        TimelineSnapshotCodec.json.encodeToString(
            StoredTimelineEvent.serializer(),
            StoredTimelineEvent(
                position = 1.0, otid = "otid-1", content = content, serverId = "server-1",
                messageType = messageType, dateIso = "2026-01-01T00:00:00Z",
            ),
        ).encodeToByteArray()

    private fun record(body: ByteArray, contentType: String = TIMELINE_EVENT_CONTENT_TYPE) = TimelineSettledRecord(
        key = TimelinePageKey(1, TimelineMessageId("id-1")),
        contentType = contentType,
        body = body,
        revision = 1,
        pointer = TimelineBodyPointer("body-1", body.size.toLong()),
    )
}
