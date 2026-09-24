package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-qygvv.6: `resume_queue` / `remove_queue_item` and the `update_queue` item shape,
 * pinned against @letta-ai/letta-code 0.32.3 (`queue-update-protocol.d.ts`,
 * `task-control-protocol.d.ts`, `protocol_v2.d.ts` QueueMessage).
 */
class AppServerQueueProtocolTest {
    @Test
    fun encodesResumeQueueAndRemoveQueueItem() {
        val resume = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(AppServerCommand.ResumeQueue(runtime = runtime, requestId = "resume-1")),
        ).jsonObject
        assertEquals("resume_queue", resume["type"]?.jsonPrimitive?.content)
        assertEquals("resume-1", resume["request_id"]?.jsonPrimitive?.content)
        assertEquals("conv-1", resume["runtime"]?.jsonObject?.get("conversation_id")?.jsonPrimitive?.content)

        val bare = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(AppServerCommand.ResumeQueue(runtime = runtime)),
        ).jsonObject
        assertNull(bare["request_id"], "request_id is optional upstream and must not be sent as null")

        val remove = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(
                AppServerCommand.RemoveQueueItem(requestId = "remove-1", runtime = runtime, itemId = "q-7"),
            ),
        ).jsonObject
        assertEquals("remove_queue_item", remove["type"]?.jsonPrimitive?.content)
        assertEquals("q-7", remove["item_id"]?.jsonPrimitive?.content)
        assertEquals("agent-1", remove["runtime"]?.jsonObject?.get("agent_id")?.jsonPrimitive?.content)
    }

    @Test
    fun decodesQueueResponsesAsTypedControlFrames() {
        val resumed = AppServerProtocol.decodeFrame(
            """{"type":"resume_queue_response","request_id":"resume-1","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"resumed":2,"success":true,"extra":1}""",
        )
        assertEquals(AppServerChannel.Control, resumed.channel)
        val resume = assertIs<AppServerInboundFrame.ResumeQueueResponse>(resumed.frame)
        assertEquals(2, resume.resumed)
        assertEquals(runtime, resume.runtime)

        val removed = AppServerProtocol.decodeFrame(
            """{"type":"remove_queue_item_response","request_id":"remove-1","success":false,"item_id":"q-7"}""",
        )
        assertEquals(AppServerChannel.Control, removed.channel)
        val remove = assertIs<AppServerInboundFrame.RemoveQueueItemResponse>(removed.frame)
        assertFalse(remove.success)
        assertEquals("q-7", remove.itemId)
    }

    @Test
    fun updateQueueSurfacesPausedItemsAndToleratesUnknownFields() {
        val received = AppServerProtocol.decodeFrame(
            """{"type":"update_queue","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":9,"emitted_at":"2026-09-24T00:00:00Z","idempotency_key":"q-9","future_envelope":true,""" +
                """"queue":[{"id":"q-1","client_message_id":"local-1","kind":"message","source":"user","content":"hi","enqueued_at":"2026-09-24T00:00:01Z","paused":true,"future_item_field":{"x":1}},""" +
                """{"id":"q-2","client_message_id":"cm-q-2","kind":"cron_prompt","source":"cron","content":[{"type":"text","text":"tick"}],"enqueued_at":"2026-09-24T00:00:02Z"},""" +
                """{"client_message_id":"no-id"}],"removed":[]}""",
        )
        val frame = assertIs<AppServerInboundFrame.UpdateQueue>(received.frame)

        assertEquals(listOf("q-1", "q-2"), frame.items.map { it.id }, "an entry without an id is skipped, not fatal")
        val first = frame.items.first()
        assertEquals("local-1", first.clientMessageId)
        assertEquals("user", first.source)
        assertEquals("2026-09-24T00:00:01Z", first.enqueuedAt)
        assertTrue(first.paused)
        assertFalse(frame.items[1].paused)
        assertTrue(frame.paused)
    }

    @Test
    fun updateQueueWithoutPausedItemsIsNotPaused() {
        val received = AppServerProtocol.decodeFrame(
            """{"type":"update_queue","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":10,"emitted_at":"2026-09-24T00:00:00Z","idempotency_key":"q-10",""" +
                """"queue":[{"id":"q-1","client_message_id":"local-1","kind":"message","source":"user","content":"hi","enqueued_at":"2026-09-24T00:00:01Z"}]}""",
        )
        val frame = assertIs<AppServerInboundFrame.UpdateQueue>(received.frame)
        assertFalse(frame.paused)
        assertTrue(frame.removed.isEmpty())
    }

    @Test
    fun queueCommandsAreAmbiguousMutations() {
        assertEquals(
            AppServerCommandRetryClass.AmbiguousMutation(dedupKey = "q-7"),
            AppServerCommandRetryClass.of(AppServerCommand.RemoveQueueItem("remove-1", runtime, "q-7")),
        )
        assertEquals(
            AppServerCommandRetryClass.AmbiguousMutation(dedupKey = null),
            AppServerCommandRetryClass.of(AppServerCommand.ResumeQueue(runtime, "resume-1")),
        )
    }

    private companion object {
        val runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-1")
    }
}
