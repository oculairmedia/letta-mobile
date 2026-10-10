package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.SummaryMessage
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import com.letta.mobile.data.timeline.snapshot.toStoredTimelineEvent
import com.letta.mobile.data.timeline.toTimelineEvent
import com.letta.mobile.data.transport.appserver.decodeAppServerMessageList
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-kr39h: a compaction's `summary_message`, as letta-code's local backend lists it in
 * history (`projectLocalMessageToStoredMessages`), becomes one divider row carrying the summary.
 */
class CompactionTimelineProjectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val historyRow = """
        {"id":"local-msg-41","date":"2026-10-09T10:00:00.000Z","agent_id":"agent-1","conversation_id":"conv-1",
         "message_type":"summary_message","summary":"Set up the repo and fixed the build.",
         "compaction_stats":{"trigger":"manual","context_tokens_before":150000,"context_tokens_after":20000,
         "context_window":200000,"messages_count_before":48,"messages_count_after":12}}
    """.trimIndent()

    @Test
    fun aHistorySummaryDecodesAsASummaryMessage() {
        val message = assertIs<SummaryMessage>(json.decodeFromString(LettaMessage.serializer(), historyRow))
        assertEquals("local-msg-41", message.id)
        assertEquals("Set up the repo and fixed the build.", message.summary)
        assertTrue(message.hasServerId)
    }

    @Test
    fun itBecomesACompactionEventAndADividerMessage() {
        val message = json.decodeFromString(LettaMessage.serializer(), historyRow)
        val event = requireNotNull(message.toTimelineEvent(1.0, agentId = "agent-1"))
        assertEquals(TimelineMessageType.COMPACTION, event.messageType)
        assertEquals("Set up the repo and fixed the build.", event.content)

        val ui = requireNotNull(timelineEventToUiMessage(event, ownAgentId = "agent-1"))
        assertTrue(ui.isCompaction)
        assertEquals("system", ui.role)
        assertEquals("Set up the repo and fixed the build.", ui.content)
        assertEquals("local-msg-41", ui.id)
    }

    @Test
    fun theDividerSurvivesTheSnapshotRoundTrip() {
        val event = requireNotNull(json.decodeFromString(LettaMessage.serializer(), historyRow).toTimelineEvent(1.0))
        val restored = event.toStoredTimelineEvent().toConfirmedTimelineEvent()
        assertEquals(TimelineMessageType.COMPACTION, restored.messageType)
        assertTrue(requireNotNull(timelineEventToUiMessage(restored)).isCompaction)
    }

    @Test
    fun aSummaryWithoutAnIdDecodesButIsNeverRendered() {
        val message = assertIs<SummaryMessage>(
            json.decodeFromString(LettaMessage.serializer(), """{"message_type":"summary_message","summary":"x"}"""),
        )
        assertTrue(!message.hasServerId)
        assertNull(message.toTimelineEvent(1.0))
    }

    @Test
    fun oneOddSummaryDoesNotFailAHistoryPage() {
        val page = Json.parseToJsonElement(
            """[{"message_type":"summary_message","compaction_stats":"not-an-object"},
               {"id":"m-2","message_type":"assistant_message","content":"hi"}]""",
        )
        assertEquals(2, decodeAppServerMessageList(page).size)
    }
}
