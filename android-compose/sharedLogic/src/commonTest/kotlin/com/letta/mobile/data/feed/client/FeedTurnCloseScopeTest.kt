package com.letta.mobile.data.feed.client

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedDelta
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Where turn-close settlement must not reach: an approval the App Server may still hold, history before a resume, and
 * turns whose close never arrives. Fixtures carry the host stamp (`tc-` / `tr-<id>`) and the `turn_closed` payload the
 * host journals (FeedLogCodec.closedPayload: `terminal_observed`, plus `host_restarted` when the host restarted).
 */
class FeedTurnCloseScopeTest {
    private val projector = FeedRowProjector(AgentId("parent"), ConversationId("conversation"))
    private var seq = 0L

    private fun entry(turn: String, kind: String, payload: JsonObject) =
        FeedDelta(epoch = 1, seq = ++seq, kind = kind, turn = turn, payload = payload)

    private fun approval(turn: String, id: String) = entry(turn, ConversationFeedWire.KIND_STREAM_DELTA, buildJsonObject {
        put("message_type", "approval_request_message")
        put("id", "approval-$id")
        put("logical_message_id", "tc-$id")
        put("run_id", "run-$turn")
        put("turn_id", turn)
        put("tool_call", buildJsonObject {
            put("tool_call_id", id)
            put("name", "Bash")
            put("arguments", "{\"command\":\"rm -rf build\"}")
        })
    })

    private fun call(turn: String, id: String) = entry(turn, ConversationFeedWire.KIND_STREAM_DELTA, buildJsonObject {
        put("message_type", "tool_call_message")
        put("logical_message_id", "tc-$id")
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
        put("logical_message_id", "tr-$id")
        put("run_id", "run-$turn")
        put("turn_id", turn)
        put("status", "success")
        put("tool_call_id", id)
        put("tool_return", body)
    })

    private fun closed(turn: String, terminalObserved: Boolean, hostRestarted: Boolean = false) =
        entry(turn, ConversationFeedWire.KIND_TURN_CLOSED, buildJsonObject {
            put("terminal_observed", terminalObserved)
            if (hostRestarted) put("host_restarted", true)
        })

    private fun messages(): List<UiMessage> = projector.items().flatMap { it.messagesInOrder() }

    private fun toolCall(id: String): UiToolCall? =
        messages().flatMap { it.toolCalls.orEmpty() }.singleOrNull { it.toolCallId == id }

    private fun approvalStillOffered(id: String): Boolean =
        messages().any { message -> message.approvalRequest?.toolCalls?.any { it.toolCallId == id } == true }

    @Test
    fun anApprovalPendingAtANonTerminalCloseStaysPendingAndAnswerable() {
        listOf(approval("turn-a", "call-approve"), closed("turn-a", terminalObserved = false, hostRestarted = true))
            .forEach { projector.apply(it) }

        val pending = assertNotNull(toolCall("call-approve"))
        assertNull(pending.result, "the App Server may still hold this approval: it is not failed")
        assertEquals(true, approvalStillOffered("call-approve"), "the approval is still offered")
    }

    @Test
    fun anApprovalPendingAtATerminalCloseIsSettled() {
        listOf(approval("turn-a", "call-approve"), closed("turn-a", terminalObserved = true)).forEach { projector.apply(it) }

        assertEquals("error", assertNotNull(toolCall("call-approve")).status)
    }

    @Test
    fun anApprovalAnsweredAfterANonTerminalCloseResolvesNormally() {
        listOf(
            approval("turn-a", "call-approve"),
            closed("turn-a", terminalObserved = false, hostRestarted = true),
            result("turn-b", "call-approve", "removed"),
        ).forEach { projector.apply(it) }

        val answered = assertNotNull(toolCall("call-approve"))
        assertEquals("removed", answered.result)
        assertEquals("success", answered.status)
    }

    @Test
    fun anApprovalLeftPendingIsSettledByTheNextTerminalClose() {
        listOf(
            approval("turn-a", "call-approve"),
            closed("turn-a", terminalObserved = false, hostRestarted = true),
            call("turn-b", "call-next"),
            result("turn-b", "call-next", "ok"),
            closed("turn-b", terminalObserved = true),
        ).forEach { projector.apply(it) }

        assertEquals("error", assertNotNull(toolCall("call-approve")).status, "no row stays pending once a turn really ends")
    }

    @Test
    fun aPlainCallPendingAtANonTerminalCloseIsStillSettled() {
        listOf(call("turn-a", "call-plain"), closed("turn-a", terminalObserved = false, hostRestarted = true))
            .forEach { projector.apply(it) }

        assertEquals("error", assertNotNull(toolCall("call-plain")).status)
    }

    /**
     * A resumed reader starts from a snapshot page. Retention drops only finished turns from the front
     * (FeedLog.dropOldestFinishedTurn), so the open turn's entries all reach the new reader before its close.
     */
    @Test
    fun aResumeFromAHorizonStillSettlesTheOpenTurnsCall() {
        listOf(call("turn-old", "call-old"), closed("turn-old", terminalObserved = true), call("turn-open", "call-open"))
            .forEach { projector.apply(it) }
        projector.reset()
        // The snapshot page from the compaction horizon: turn-old was compacted away, turn-open is whole.
        listOf(call("turn-open", "call-open"), closed("turn-open", terminalObserved = true)).forEach { projector.apply(it) }

        assertEquals("error", assertNotNull(toolCall("call-open")).status)
        assertNull(toolCall("call-old"), "a compacted turn has no row here to be pending")
    }

    @Test
    fun turnsWhoseCloseNeverArrivesAreBounded() {
        val turns = FeedRowProjector.MAX_OPEN_TURNS + 5
        repeat(turns) { index -> projector.apply(call("turn-$index", "call-$index")) }

        assertEquals(FeedRowProjector.MAX_OPEN_TURNS, projector.openTurnCount)
        // The newest open turn is still tracked and settles on its close.
        projector.apply(closed("turn-${turns - 1}", terminalObserved = true))
        assertEquals("error", assertNotNull(toolCall("call-${turns - 1}")).status)
    }
}
