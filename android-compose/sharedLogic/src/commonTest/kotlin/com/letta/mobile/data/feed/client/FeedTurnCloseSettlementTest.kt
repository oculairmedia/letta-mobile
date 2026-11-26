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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * TLA+ NoPendingRow: The host journals `turn_closed` after every frame it relayed
 * for the turn, so a parent tool call still unanswered at that entry has no result coming in this turn. A feed
 * conversation runs no ledger sweep (the indexed maintenance skips it), so before the fix the row stayed
 * pending forever. A real result journaled later (async tool, replay in a later turn) still wins.
 *
 * Fixtures carry the stamp the host writes before journaling (ConversationTurnFanout.broadcastDeltaBody ->
 * TurnStreamIdentity.stampDelta): `logical_message_id` `tc-<id>` on a call and `tr-<id>` on its return.
 */
class FeedTurnCloseSettlementTest {
    private val projector = FeedRowProjector(AgentId("parent"), ConversationId("conversation"))
    private var seq = 0L

    private fun entry(turn: String, kind: String, payload: JsonObject) =
        FeedDelta(epoch = 1, seq = ++seq, kind = kind, turn = turn, payload = payload)

    private fun call(turn: String, id: String, stamped: Boolean = true) = stream(turn, buildJsonObject {
        put("message_type", "tool_call_message")
        if (stamped) put("logical_message_id", "tc-$id")
        put("run_id", "run-$turn")
        put("turn_id", turn)
        put("tool_call", buildJsonObject {
            put("tool_call_id", id)
            put("name", "Bash")
            put("arguments", "{\"command\":\"date\"}")
        })
    })

    private fun result(turn: String, id: String, body: String, stamped: Boolean = true) = stream(turn, buildJsonObject {
        put("message_type", "tool_return_message")
        if (stamped) put("logical_message_id", "tr-$id")
        put("id", "return-$id")
        put("run_id", "run-$turn")
        put("turn_id", turn)
        put("status", "success")
        put("tool_call_id", id)
        put("tool_return", body)
    })

    private fun stream(turn: String, payload: JsonObject) = entry(turn, ConversationFeedWire.KIND_STREAM_DELTA, payload)

    private fun closed(turn: String, terminalObserved: Boolean = true) =
        entry(turn, ConversationFeedWire.KIND_TURN_CLOSED, buildJsonObject { put("terminal_observed", terminalObserved) })

    private fun toolCall(id: String): UiToolCall? = projector.items()
        .flatMap { it.messagesInOrder() }
        .flatMap { it.toolCalls.orEmpty() }
        .singleOrNull { it.toolCallId == id }

    @Test
    fun normalCloseSettlesEveryUnansweredParentToolCallVisibly() {
        listOf(
            call("turn-a", "call-answered"),
            result("turn-a", "call-answered", "ok"),
            call("turn-a", "call-pending"),
        ).forEach { projector.apply(it) }
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

    /**
     * A parallel call message is stamped by its FIRST call only (TurnStreamIdentity.toolCallRowId: `tc-<first>`), yet
     * each return joins the row that lists its call while the turn runs: both results show before `turn_closed`, each
     * on its own call, and the close changes neither (nothing was held for it to repair).
     */
    @Test
    fun aParallelCallsResultsShowLiveAndSurviveTheClose() {
        projector.apply(parallelCalls("turn-a", "call-first", "call-second"))
        projector.apply(result("turn-a", "call-second", "second ok"))
        assertEquals("second ok", assertNotNull(toolCall("call-second")).result, "the second return shows while the turn runs")
        assertNull(assertNotNull(toolCall("call-first")).result, "the first call is still running")

        projector.apply(result("turn-a", "call-first", "first ok"))
        val live = projector.items()
        assertEquals("first ok", assertNotNull(toolCall("call-first")).result)
        assertEquals("success", assertNotNull(toolCall("call-second")).status)

        projector.apply(closed("turn-a"))

        assertEquals(live, projector.items(), "a turn whose calls all answered live is not changed by its close")
        val second = assertNotNull(toolCall("call-second"))
        assertEquals("second ok", second.result)
        assertEquals("success", second.status)
        assertEquals("success", assertNotNull(toolCall("call-first")).status)
    }

    /** A return journaled before its call's row (any call of a parallel message) attaches when the row arrives. */
    @Test
    fun aParallelCallsReturnJournaledBeforeItsRowAttachesWhenTheRowArrives() {
        projector.apply(result("turn-a", "call-second", "early"))
        projector.apply(parallelCalls("turn-a", "call-first", "call-second"))

        assertEquals("early", assertNotNull(toolCall("call-second")).result)
        assertNull(assertNotNull(toolCall("call-first")).result)
    }

    /**
     * The host names an approval row with a minted id, not `tc-<call>` (TurnStreamIdentity.rowIdentity mints for
     * every type but user, tool call and tool return), so its result is found by call id and shows while the turn runs.
     */
    @Test
    fun anApprovalRowsResultShowsLiveThoughTheRowIsNotNamedAfterItsCall() {
        projector.apply(stream("turn-a", buildJsonObject {
            put("message_type", "approval_request_message")
            put("id", "approval-call-asked")
            put("logical_message_id", "lm-approval")
            put("run_id", "run-turn-a")
            put("turn_id", "turn-a")
            put("tool_call", buildJsonObject {
                put("tool_call_id", "call-asked")
                put("name", "Bash")
                put("arguments", "{\"command\":\"date\"}")
            })
        }))
        projector.apply(result("turn-a", "call-asked", "approved and ran"))

        val answered = assertNotNull(toolCall("call-asked"))
        assertEquals("approved and ran", answered.result, "shown before turn_closed")
        assertEquals("success", answered.status)
    }

    private fun parallelCalls(turn: String, first: String, second: String) = stream(turn, buildJsonObject {
        put("message_type", "tool_call_message")
        put("logical_message_id", "tc-$first")
        put("run_id", "run-$turn")
        put("turn_id", turn)
        put("tool_calls", buildJsonArray {
            listOf(first, second).forEach { id ->
                add(buildJsonObject {
                    put("tool_call_id", id)
                    put("name", "Bash")
                    put("arguments", "{\"command\":\"date\"}")
                })
            }
        })
    })
}
