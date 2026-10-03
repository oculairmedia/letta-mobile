package com.letta.mobile.data.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private data class RowId(val value: String)

private data class Otid(val value: String)

private data class Text(val value: String)

private data class CallId(val value: String)

@OptIn(ExperimentalCoroutinesApi::class)
class TurnIdentityLedgerTest {
    private val conversation = "conv-1"

    private class MemoryStore : TurnIdentityStore {
        val data = HashMap<String, MutableMap<StoredRowRef, RowIdentity>>()
        var appends = 0

        override suspend fun load(conversationId: String): Map<StoredRowRef, RowIdentity> =
            data[conversationId]?.toMap().orEmpty()

        override suspend fun append(conversationId: String, entries: Map<StoredRowRef, RowIdentity>) {
            appends++
            data.getOrPut(conversationId) { LinkedHashMap() }.putAll(entries)
        }
    }

    /** Rows oldest first, served newest first like the App Server. */
    private class Rows(var stored: List<JsonObject>, var gate: CompletableDeferred<Unit>? = null) : ConversationRowsSource {
        override suspend fun newestRows(conversationId: String, limit: Int): List<JsonObject> {
            gate?.await()
            return stored.takeLast(limit).reversed()
        }
    }

    private fun ledger(store: TurnIdentityStore, rows: ConversationRowsSource) = TurnIdentityLedger(store, rows) { 0L }

    private fun user(id: RowId, otid: Otid) = buildJsonObject {
        put("id", id.value)
        put("message_type", "user_message")
        put("otid", otid.value)
        put("content", "<system-reminder>x</system-reminder> hi")
    }

    private fun assistant(id: RowId, text: Text) = buildJsonObject {
        put("id", id.value)
        put("message_type", "assistant_message")
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text.value) }) })
    }

    private fun toolCall(id: RowId, callId: CallId) = buildJsonObject {
        put("id", "${id.value}:tool:${callId.value}:request")
        put("message_type", "approval_request_message")
        put("tool_call", buildJsonObject { put("tool_call_id", callId.value); put("name", "Bash") })
    }

    private fun toolReturn(id: RowId, callId: CallId) = buildJsonObject {
        put("id", id.value)
        put("message_type", "tool_return_message")
        put("tool_call_id", callId.value)
        put("tool_return", "ok")
    }

    private fun ref(row: JsonObject) = checkNotNull(row.ref())

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private val turn = SettledTurn(
        "cmid-1",
        listOf(
            SettledMessage("cmid-1", "user_message", otid = "cmid-1"),
            SettledMessage("tc-call-1", "tool_call_message", toolCallId = "call-1"),
            SettledMessage("tr-call-1", "tool_return_message", toolCallId = "call-1"),
            SettledMessage("lm-a", "assistant_message", finalText = "All done."),
        ),
    )

    private val turnRows = listOf(
        user(RowId("ui-msg-1"), Otid("cmid-1")),
        toolCall(RowId("ui-msg-2"), CallId("call-1")),
        toolReturn(RowId("ui-msg-3"), CallId("call-1")),
        assistant(RowId("ui-msg-4"), Text("All done.")),
    )

    @Test
    fun settleMapsUserToolAndAssistantRowsExactly() = runTest {
        val store = MemoryStore()
        val report = ledger(store, Rows(turnRows)).settleTurn(conversation, turn)

        assertEquals(TurnMatchReport(4, emptyList()), report)
        val saved = store.data.getValue(conversation)
        assertEquals(RowIdentity("cmid-1", "cmid-1"), saved[ref(turnRows[0])])
        assertEquals(RowIdentity("tc-call-1", "cmid-1"), saved[ref(turnRows[1])])
        assertEquals(RowIdentity("tr-call-1", "cmid-1"), saved[ref(turnRows[2])])
        assertEquals(RowIdentity("lm-a", "cmid-1"), saved[ref(turnRows[3])])
    }

    @Test
    fun assistantJoinsOnTheStableUpstreamIdBeforeText() = runTest {
        val store = MemoryStore()
        val rows = listOf(assistant(RowId("ui-msg-7"), Text("same")), assistant(RowId("ui-msg-8"), Text("same")))
        val settled = SettledTurn("t", listOf(SettledMessage("lm-first", "assistant_message", "same", upstreamId = "ui-msg-7")))

        ledger(store, Rows(rows)).settleTurn(conversation, settled)

        assertEquals(mapOf(ref(rows[0]) to RowIdentity("lm-first", "t")), store.data.getValue(conversation))
    }

    @Test
    fun identicalTextsPairByPosition() = runTest {
        val store = MemoryStore()
        val rows = listOf(assistant(RowId("ui-msg-1"), Text("Done.")), assistant(RowId("ui-msg-2"), Text("Done.")), assistant(RowId("ui-msg-3"), Text("Done.")))
        val settled = SettledTurn(
            "t",
            listOf(
                SettledMessage("lm-first", "assistant_message", "Done."),
                SettledMessage("lm-second", "assistant_message", "Done."),
            ),
        )

        val report = ledger(store, Rows(rows)).settleTurn(conversation, settled)

        assertEquals(2, report.matched)
        val saved = store.data.getValue(conversation)
        assertNull(saved[ref(rows[0])])
        assertEquals("lm-first", saved.getValue(ref(rows[1])).logicalMessageId)
        assertEquals("lm-second", saved.getValue(ref(rows[2])).logicalMessageId)
    }

    @Test
    fun unmatchedRowsAreReportedNotGuessed() = runTest {
        val store = MemoryStore()
        val rows = listOf(assistant(RowId("ui-msg-1"), Text("something else")), toolCall(RowId("ui-msg-2"), CallId("other-call")))
        val settled = SettledTurn(
            "t",
            listOf(
                SettledMessage("lm-a", "assistant_message", "Done."),
                SettledMessage("tc-call-1", "tool_call_message", toolCallId = "call-1"),
            ),
        )

        val report = ledger(store, Rows(rows)).settleTurn(conversation, settled)

        assertEquals(TurnMatchReport(0, listOf("lm-a", "tc-call-1")), report)
        assertEquals(0, store.appends)
    }

    @Test
    fun enrichWaitsForPendingSettleThenAddsIds() = runTest {
        val rows = Rows(turnRows, gate = CompletableDeferred())
        val ledger = ledger(MemoryStore(), rows)
        launch { ledger.settleTurn(conversation, turn) }
        runCurrent()
        var enriched: List<JsonObject>? = null
        launch { enriched = ledger.enrich(conversation, turnRows.reversed()) }
        runCurrent()
        assertNull(enriched)

        checkNotNull(rows.gate).complete(Unit)
        advanceUntilIdle()

        val assistantRow = checkNotNull(enriched).first { it.text("id") == "ui-msg-4" }
        assertEquals("lm-a", assistantRow.text("logical_message_id"))
        assertEquals("cmid-1", assistantRow.text("turn_id"))
    }

    @Test
    fun enrichTimesOutAndServesUnmappedRows() = runTest {
        val store = MemoryStore()
        val ledger = ledger(store, Rows(turnRows))
        ledger.expectSettle(conversation)
        var enriched: List<JsonObject>? = null
        launch { enriched = ledger.enrich(conversation, turnRows) }
        advanceTimeBy(1_999)
        runCurrent()
        assertNull(enriched)

        advanceTimeBy(2)
        runCurrent()

        assertEquals(turnRows, enriched)
        assertEquals(0, store.appends)
    }

    @Test
    fun backfillAssignsTurnIdsByPromptAdjacencyOnce() = runTest {
        val store = MemoryStore()
        val history = listOf(
            user(RowId("ui-msg-1"), Otid("cm-a")),
            assistant(RowId("ui-msg-2"), Text("A")),
            user(RowId("ui-msg-3"), Otid("cm-b")),
            toolCall(RowId("ui-msg-4"), CallId("c")),
            assistant(RowId("ui-msg-5"), Text("B")),
        )
        val ledger = ledger(store, Rows(history))

        ledger.backfillHistory(conversation, history)
        ledger.backfillHistory(conversation, history)

        assertEquals(1, store.appends)
        val enriched = ledger.enrich(conversation, history)
        assertEquals(listOf("cm-a", "cm-a", "cm-b", "cm-b", "cm-b"), enriched.map { it.text("turn_id") })
        assertEquals("ui-msg-5:assistant_message", enriched.last().text("logical_message_id"))
    }

    @Test
    fun firstListingOfUnmappedHistoryBackfillsItsRows() = runTest {
        val history = listOf(user(RowId("ui-msg-1"), Otid("cm-a")), assistant(RowId("ui-msg-2"), Text("A")))

        val enriched = ledger(MemoryStore(), Rows(history)).enrich(conversation, history.reversed())

        assertEquals(listOf("cm-a", "cm-a"), enriched.map { it.text("turn_id") })
    }

    @Test
    fun settleReplacesAHistoryProvisionalIdentity() = runTest {
        val ledger = ledger(MemoryStore(), Rows(turnRows))
        ledger.enrich(conversation, turnRows)

        val report = ledger.settleTurn(conversation, turn)

        assertEquals(4, report.matched)
        assertEquals("lm-a", ledger.enrich(conversation, turnRows).last().text("logical_message_id"))
    }

    @Test
    fun reloadedStoreServesSameIds() = runTest {
        val store = MemoryStore()
        val rows = Rows(turnRows)
        val first = ledger(store, rows)
        first.settleTurn(conversation, turn)
        val before = first.enrich(conversation, turnRows)

        val after = ledger(store, rows).enrich(conversation, turnRows)

        assertEquals(before, after)
        assertFalse(after.any { it["logical_message_id"] == null })
    }

    @Test
    fun storedRowArrivesAfterFirstAttemptJoinsByIdNotByOlderIdenticalText() = runTest {
        val older = assistant(RowId("ui-msg-1"), Text("Done."))
        val newer = assistant(RowId("ui-msg-9"), Text("Done."))
        var attempts = 0
        val rows = ConversationRowsSource { _, _ -> if (++attempts == 1) listOf(older) else listOf(newer, older) }
        val store = MemoryStore()
        val settled = SettledTurn(
            "t",
            listOf(SettledMessage("ui-msg-9", "assistant_message", "Done.", upstreamId = "ui-msg-9")),
        )

        val report = ledger(store, rows).settleTurn(conversation, settled)

        assertEquals(TurnMatchReport(1, emptyList()), report)
        assertEquals(mapOf(ref(newer) to RowIdentity("ui-msg-9", "t")), store.data.getValue(conversation))
    }

    @Test
    fun aMessageWithAnUpstreamIdNeverFallsBackToText() = runTest {
        val store = MemoryStore()
        val settled = SettledTurn(
            "t",
            listOf(SettledMessage("ui-msg-9", "assistant_message", "Done.", upstreamId = "ui-msg-9")),
        )

        val report = ledger(store, Rows(listOf(assistant(RowId("ui-msg-1"), Text("Done."))))).settleTurn(conversation, settled)

        assertEquals(TurnMatchReport(0, listOf("ui-msg-9")), report)
        assertEquals(0, store.appends)
    }
}
