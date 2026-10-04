package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** letta-mobile-4vtng.2: reconcile replaces persisted garbled text with the server's text. */
class TimelineServerTextHealTest {
    private val reply = (1..80).joinToString(" ") { "Sentence number $it of a long assistant reply." }

    /** The pre-#1768 garble: M[0:k1] + M[0:k2] + ... + M. */
    private val garbled = listOf(400, 1200, 2400, 3000).joinToString("") { reply.take(it) } + reply

    private fun row(
        type: TimelineMessageType,
        content: String,
        runId: String? = "run-real-1",
    ) = TimelineEvent.Confirmed(
        position = 1.0,
        otid = "ui-msg-9184765",
        serverId = "ui-msg-9184765",
        content = content,
        messageType = type,
        date = timelineNow(),
        runId = runId,
        stepId = null,
    )

    private fun timelineOf(event: TimelineEvent.Confirmed) =
        Timeline(conversationId = "conv-heal", events = persistentListOf(event))

    private fun assistantFromServer(text: String, runId: String? = "run-real-1"): LettaMessage =
        AssistantMessage(id = "ui-msg-9184765", contentRaw = JsonPrimitive(text), date = "2026-10-03T00:00:00Z", runId = runId, otid = "ui-msg-9184765")

    private fun reasoningFromServer(text: String): LettaMessage =
        ReasoningMessage(id = "ui-msg-9184765", reasoning = text, date = "2026-10-03T00:00:00Z", runId = "run-real-1", otid = "ui-msg-9184765")

    @Test
    fun `persisted garbled assistant row is replaced by the server text`() {
        assertTrue(garbled.length > reply.length * 2)
        val (merged, _) = timelineOf(row(TimelineMessageType.ASSISTANT, garbled))
            .mergeServerMessages(listOf(assistantFromServer(reply)))
        assertEquals(1, merged.events.size)
        assertEquals(reply, merged.events.single().content)
    }

    @Test
    fun `persisted garbled reasoning row is replaced by the server text`() {
        val (merged, _) = timelineOf(row(TimelineMessageType.REASONING, garbled))
            .mergeServerMessages(listOf(reasoningFromServer(reply)))
        assertEquals(1, merged.events.size)
        assertEquals(reply, merged.events.single().content)
    }

    @Test
    fun `streaming row on a synthetic live run is never overwritten`() {
        val live = row(TimelineMessageType.ASSISTANT, garbled, runId = "iroh-run-turn-1")
        val timeline = timelineOf(live)
        val (merged, _) = timeline.mergeServerMessages(listOf(assistantFromServer(reply, runId = null)))
        assertEquals(garbled, merged.events.single().content)
    }

    @Test
    fun `live row ahead of a stale server prefix is never overwritten`() {
        val ahead = reply
        val timeline = timelineOf(row(TimelineMessageType.ASSISTANT, ahead))
        val (merged, _) = timeline.mergeServerMessages(listOf(assistantFromServer(reply.take(100))))
        assertEquals(ahead, merged.events.single().content)
    }

    @Test
    fun `unchanged rows cause no write and no persistence delta`() {
        val timeline = timelineOf(row(TimelineMessageType.ASSISTANT, reply))
        val (merged, _) = timeline.mergeServerMessages(listOf(assistantFromServer(reply)))
        assertSame(timeline, merged)
        val reduction = reduceProductionMutation(
            TimelineReducerState(timeline = timeline),
            TimelineMutation.RecentMessagesSnapshot(1L, 1L, listOf(assistantFromServer(reply))),
        )
        assertEquals(TimelineMutationDelta.None, reduction.persistenceDelta)
    }

    @Test
    fun `healing is idempotent and persists through the reducer`() {
        val reduction = reduceProductionMutation(
            TimelineReducerState(timeline = timelineOf(row(TimelineMessageType.ASSISTANT, garbled))),
            TimelineMutation.RecentMessagesSnapshot(1L, 1L, listOf(assistantFromServer(reply))),
        )
        assertTrue(reduction.persistenceDelta is TimelineMutationDelta.RequiresFullRescan)
        assertEquals(reply, reduction.next.timeline.events.single().content)
        val again = reduceProductionMutation(
            reduction.next,
            TimelineMutation.RecentMessagesSnapshot(2L, 2L, listOf(assistantFromServer(reply))),
        )
        assertEquals(TimelineMutationDelta.None, again.persistenceDelta)
    }
}
