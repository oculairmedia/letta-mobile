package com.letta.mobile.data.feed.client

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedDelta
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * TLA+ NoPendingRow: The host journals `turn_closed` after every frame it relayed
 * for the turn, so a parent tool call still unanswered at that entry has no result coming in this turn. A feed
 * conversation runs no ledger sweep (the indexed maintenance skips it), so before the fix the row stayed
 * pending forever. A real result journaled later (async tool, replay in a later turn) still wins.
 */
class FeedTurnCloseSettlementTest {
    private val projector = FeedRowProjector(AgentId("parent"), ConversationId("conversation"))
    private var seq = 0L

    private fun entry(turn: String, kind: String, payload: JsonObject) =
        FeedDelta(epoch = 1, seq = ++seq, kind = kind, turn = turn, payload = payload)

    private fun call(turn: String, id: String) = entry(turn, ConversationFeedWire.KIND_STREAM_DELTA, buildJsonObject {
        put("message_type", "tool_call_message")
        put("run_id", "run-$turn")
        put("turn_id", turn)
        put("tool_call", buildJsonObject {
            put("tool_call_id", id)
            put("name", "Bash")
            put("arguments", "{\"command\":\"date\"}")
        })
    })

    private fun result(turn: String, id: String, body: String) = entry(turn, ConversationFeedWire.KIND_STREAM_DELTA, buildJsonObject {
        put("message_type", "tool_return_message")
        put("id", "return-$id")
        put("run_id", "run-$turn")
        put("turn_id", turn)
        put("status", "success")
        put("tool_call_id", id)
        put("tool_return", body)
    })

    private fun closed(turn: String, terminalObserved: Boolean = true) =
        entry(turn, ConversationFeedWire.KIND_TURN_CLOSED, buildJsonObject { put("terminal_observed", terminalObserved) })

    private fun toolCall(id: String): UiToolCall? = projector.items()
        .flatMap { it.messagesInOrder() }
        .flatMap { it.toolCalls.orEmpty() }
        .singleOrNull { it.toolCallId == id }

    @Test
    fun normalCloseSettlesEveryUnansweredParentToolCallVisibly() {
        listOf(call("turn-a", "call-answered"), result("turn-a", "call-answered", "ok"), call("turn-a", "call-pending"))
            .forEach { projector.apply(it) }
        assertNull(assertNotNull(toolCall("call-pending")).result, "still running before the turn closes")

        projector.apply(closed("turn-a"))

        val settled = assertNotNull(toolCall("call-pending"))
        assertNotNull(settled.result, "a closed turn leaves no pending tool row")
        assertEquals("error", settled.status)
        assertEquals("success", assertNotNull(toolCall("call-answered")).status, "answered calls are untouched")
    }

    @Test
    fun aRealResultJournaledAfterTheCloseStillReplacesTheSettlement() {
        listOf(call("turn-a", "call-late"), closed("turn-a")).forEach { projector.apply(it) }
        assertEquals("error", assertNotNull(toolCall("call-late")).status)

        projector.apply(result("turn-b", "call-late", "finished late"))

        val answered = assertNotNull(toolCall("call-late"))
        assertEquals("finished late", answered.result)
        assertEquals("success", answered.status)
    }

    @Test
    fun closingOneTurnNeverSettlesAnotherTurnsCalls() {
        listOf(call("turn-a", "call-a"), call("turn-b", "call-b"), closed("turn-a")).forEach { projector.apply(it) }

        assertEquals("error", assertNotNull(toolCall("call-a")).status)
        assertNull(assertNotNull(toolCall("call-b")).result, "turn-b is still open")
    }
}
