package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-by4vm.46: a tool return with no func_response (a failure that only wrote stderr)
 * completes its call on the live path; reloading the same turn from a snapshot must agree rather
 * than reverting the call to running.
 */
class StderrOnlyToolReturnTest {
    private val call = ToolCallMessage(
        id = "toolcall-call-1",
        toolCall = ToolCall(toolCallId = CALL_ID, name = "Bash", arguments = """{"command":"false"}"""),
        toolCalls = listOf(ToolCall(toolCallId = CALL_ID, name = "Bash", arguments = """{"command":"false"}""")),
        logicalMessageId = "tc-$CALL_ID",
    )

    private val stderrOnly = ToolReturnMessage(
        id = "toolreturn-call-1",
        toolCallId = CALL_ID,
        status = "error",
        stderr = listOf("bash: command failed"),
        logicalMessageId = "tr-$CALL_ID",
    )

    @Test
    fun theReturnCarriesNoFuncResponse() {
        assertNull(stderrOnly.toolReturn.funcResponse)
    }

    @Test
    fun liveCallThenReturnCompletesAsError() {
        val row = live(call, stderrOnly)
        assertEquals("", row.toolReturnContentByCallId[CALL_ID])
        assertEquals("error", projectedStatus(row))
    }

    @Test
    fun liveReturnBeforeItsCallCompletesAsError() {
        val row = live(stderrOnly, call)
        assertEquals("", row.toolReturnContentByCallId[CALL_ID])
        assertEquals("error", projectedStatus(row))
    }

    @Test
    fun snapshotReloadCompletesAsErrorLikeTheLivePath() {
        val callOnly = TimelineReducerState(Timeline("conv")).reduceLive(call, agentId = null).timeline
        assertNull(projectedStatus(callOnly.toolRow()), "the call alone is still running")

        val reloaded = enrichTimelineFromSnapshot(callOnly, listOf(call, stderrOnly)).toolRow()

        assertEquals("", reloaded.toolReturnContentByCallId[CALL_ID])
        assertEquals(projectedStatus(live(call, stderrOnly)), projectedStatus(reloaded))
        assertEquals("error", projectedStatus(reloaded))
    }

    @Test
    fun aBodylessReturnNeverClobbersABodyAlreadyHeld() {
        val fold = foldToolReturnBodies(
            existingContent = mapOf(CALL_ID to "partial output"),
            existingTruncation = emptyMap(),
            matchingReturns = listOf(CALL_ID to stderrOnly),
        )
        assertEquals("partial output", fold.contentByCallId.getValue(CALL_ID))
    }

    private fun live(vararg frames: LettaMessage): TimelineEvent.Confirmed {
        var state = TimelineReducerState(Timeline("conv"))
        frames.forEach { state = state.reduceLive(it, agentId = null) }
        return state.timeline.toolRow()
    }

    private fun Timeline.toolRow(): TimelineEvent.Confirmed =
        events.filterIsInstance<TimelineEvent.Confirmed>().single { it.logicalId == "tc-$CALL_ID" }

    private fun projectedStatus(row: TimelineEvent.Confirmed): String? =
        timelineEventToUiMessage(row)?.toolCalls?.single()?.status

    private companion object {
        const val CALL_ID = "call-1"
    }
}
