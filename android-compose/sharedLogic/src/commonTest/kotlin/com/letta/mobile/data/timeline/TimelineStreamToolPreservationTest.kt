package com.letta.mobile.data.timeline


import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test


class TimelineStreamToolPreservationTest : TimelineStreamReducerFixture() {
    @Test
    fun `cursor re-emit preserves settled tool call when new list rotates the call id x13xi-broken-card`() {
        // letta-mobile-x13xi.13.1.2: cursor re-emits bring the same tool-call frame
        // with a rotated effectiveId (rare, observed in production). The old list
        // anchored the call-id -> toolReturnContentByCallId bond; the arg-score
        // branch (newScore >= oldScore) used to swap the list, severing the bond
        // and re-spinning the tool card. The fix preserves any settled call whose
        // effectiveId is not present in the new list.
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-A", name = "read", arguments = "{}"),
                ),
            ),
        ).next
        // First tool_return settles call-A.
        val withReturn = reduce(
            prev = seeded,
            frame = ToolReturnMessage(
                id = "return-A",
                toolCallId = "call-A",
                status = "success",
                toolReturnRaw = JsonPrimitive("done"),
            ),
        ).next
        withReturn.events.filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.serverId == "tool-batch" }
            .toolReturnContentByCallId["call-A"] shouldBe "done"

        // Cursor re-emit lands the same frame with a rotated effectiveId and
        // populated arguments. The mergedCalls branch used to swap the list,
        // dropping call-A and leaving the projection reading null for result.
        val afterReemit = reduce(
            prev = withReturn,
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-A-rotated", name = "read", arguments = "{ \"path\": \"/tmp\" }"),
                ),
            ),
        ).next

        val event = afterReemit.events.filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.serverId == "tool-batch" }
        val effectiveIds = event.toolCalls.map { it.effectiveId }.toSet()
        effectiveIds shouldBe setOf("call-A", "call-A-rotated")
        event.toolReturnContentByCallId["call-A"] shouldBe "done"
    }

    @Test
    fun `cursor re-emit does not duplicate a call id that is present in both lists x13xi-broken-card`() {
        // Same effectiveId in both oldCalls and newCalls must collapse to one
        // entry (no double-projection). The preserveSettledToolCalls helper must
        // skip entries whose id is already in newCalls, otherwise a re-emit would
        // double the call row and the tool card would render twice.
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-A", name = "read", arguments = "{}"),
                ),
            ),
        ).next
        val withReturn = reduce(
            prev = seeded,
            frame = ToolReturnMessage(
                id = "return-A",
                toolCallId = "call-A",
                status = "success",
                toolReturnRaw = JsonPrimitive("done"),
            ),
        ).next
        val afterReemit = reduce(
            prev = withReturn,
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-A", name = "read", arguments = "{ \"path\": \"/tmp\" }"),
                ),
            ),
        ).next

        val event = afterReemit.events.filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.serverId == "tool-batch" }
        event.toolCalls.size shouldBe 1
        event.toolCalls.single().effectiveId shouldBe "call-A"
        event.toolCalls.single().arguments shouldBe "{ \"path\": \"/tmp\" }"
        event.toolReturnContentByCallId["call-A"] shouldBe "done"
    }

    @Test
    fun `first frame with empty arguments still accepts subsequent tool return without losing it x13xi-broken-card`() {
        // The "args blank" branch of the mergedCalls fence (oldCalls.isEmpty()
        // || newCalls.isEmpty()) must keep the oldCalls list when newCalls is
        // empty, NOT swap it for newCalls (which would be empty and lose the
        // call entirely). Regression: a refactor that simplified the fence to
        // "always prefer newCalls on newScore >= oldScore" would break this.
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-A", name = "read", arguments = ""),
                ),
            ),
        ).next
        val withReturn = reduce(
            prev = seeded,
            frame = ToolReturnMessage(
                id = "return-A",
                toolCallId = "call-A",
                status = "success",
                toolReturnRaw = JsonPrimitive("done"),
            ),
        ).next
        // Re-emit with empty toolCalls (a degenerate frame).
        val afterEmptyReemit = reduce(
            prev = withReturn,
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-A", name = "read", arguments = ""),
                ),
            ),
        ).next

        val event = afterEmptyReemit.events.filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.serverId == "tool-batch" }
        event.toolCalls.single().effectiveId shouldBe "call-A"
        event.toolReturnContentByCallId["call-A"] shouldBe "done"
    }

    @Test
    fun `older non-settled tool call stays untouched by re-emit preservation x13xi-broken-card`() {
        // A tool call with no tool_return yet must NOT be carried into the new
        // list (the projection side has no settled bond to protect). Only calls
        // with a settled toolReturnContentByCallId entry are preserved. Otherwise
        // the projection would double-count unsent calls.
        //
        // Setup: old list has call-A and call-B, both populated; only call-A is
        // settled. Re-emit brings a new list that out-scores the old (so the
        // arg-score branch would otherwise swap). call-B has no settled bond
        // and must be dropped.
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-A", name = "read", arguments = "{}"),
                    ToolCall(toolCallId = "call-B", name = "write", arguments = "{}"),
                ),
            ),
        ).next
        // Settle only call-A.
        val withReturn = reduce(
            prev = seeded,
            frame = ToolReturnMessage(
                id = "return-A",
                toolCallId = "call-A",
                status = "success",
                toolReturnRaw = JsonPrimitive("done"),
            ),
        ).next
        // Re-emit: 3 calls, all with populated arguments. newScore=3 > oldScore=2,
        // so the arg-score branch fires and oldCalls is at risk of being dropped.
        val afterReemit = reduce(
            prev = withReturn,
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-C", name = "delete", arguments = "{ \"path\": \"/tmp/c\" }"),
                    ToolCall(toolCallId = "call-D", name = "edit", arguments = "{ \"x\": 1 }"),
                    ToolCall(toolCallId = "call-E", name = "list", arguments = "{ \"dir\": \"/\" }"),
                ),
            ),
        ).next

        val event = afterReemit.events.filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.serverId == "tool-batch" }
        val effectiveIds = event.toolCalls.map { it.effectiveId }
        // call-C/D/E come from the new list (arg-score branch). call-A is
        // preserved (settled, call-id -> result bond). call-B is dropped
        // (no settled bond, nothing to preserve).
        effectiveIds shouldBe listOf("call-C", "call-D", "call-E", "call-A")
        event.toolReturnContentByCallId["call-A"] shouldBe "done"
        event.toolCalls.none { it.effectiveId == "call-B" } shouldBe true
    }
}

