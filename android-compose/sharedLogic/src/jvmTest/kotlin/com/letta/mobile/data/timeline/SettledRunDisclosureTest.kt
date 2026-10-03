package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.RunActivityProjection
import com.letta.mobile.data.chat.projection.RunActivityState
import com.letta.mobile.data.chat.projection.projectRunActivity
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.20 / letta-mobile-sibr8: the final live render and the settled render of one
 * turn must be identical - same rows, keys, run ids and header titles - so reconcile changes
 * nothing on screen. Runs a real Pager over the durable shape the device ledger held: the user
 * echo, reasoning, a tool call and its return, the reply, then stop_reason and usage, with no row
 * naming the run and the prompt dated by the server's clock rather than the device's.
 */
class SettledRunDisclosureTest {
    @Test fun settledToolTurnRendersExactlyAsItsFinalLiveRender() = runBlocking {
        val harness = CanonicalTurnHarness.open(scope, durableToolTurn)
        try {
            val live = harness.streamTurn(liveToolTurn)
            harness.settle()
            assertEquals(live.map(::signature), harness.rows().map(::signature))
            assertCompletedRun(live.first(), RunActivityState.Thought, LIVE_TURN_MILLIS, tools = 1)

            // The overlay drains and a later revision re-pages: nothing may revert.
            harness.drainAndRepage()
            assertEquals(live.map(::signature), harness.rows().map(::signature))
        } finally {
            harness.close()
        }
    }

    @Test fun settledReplyKeepsItsDisclosureUnderTheLiveRowsKey() = runBlocking {
        val harness = CanonicalTurnHarness.open(scope, durableReplyTurn)
        try {
            val live = harness.streamTurn(liveReplyTurn)
            harness.settle()
            assertEquals(live.map(::signature), harness.rows().map(::signature))
            assertCompletedRun(harness.rows().first(), RunActivityState.Thought, LIVE_TURN_MILLIS, tools = 0)
        } finally {
            harness.close()
        }
    }

    @Test fun relaunchRebuildsTheDisclosureFromDurableRowsAlone() = runBlocking {
        val harness = CanonicalTurnHarness.open(scope, durableReplyTurn)
        try {
            harness.settle()
            val (run, prompt) = harness.rows()
            assertEquals("run-${promptOwnedRunId(PROMPT_ID)}", run.key)
            assertCompletedRun(run, RunActivityState.Thought, DURABLE_TURN_MILLIS, tools = 0)
            assertEquals(listOf("user"), rowsOf(prompt).map { it.role })
        } finally {
            harness.close()
        }
    }

    private fun assertCompletedRun(item: ChatRenderItem, state: RunActivityState, millis: Long, tools: Int) {
        assertTrue(item.isRunItem, "the reply must render as a run: $item")
        assertEquals(RunActivityProjection(state, millis, tools, 0), activityOf(item))
    }

    /** Everything a render of one row depends on that reconcile could change. */
    private data class RowSignature(
        val key: String,
        val runId: String?,
        val rows: List<Pair<String, Boolean>>,
        val contents: List<String>,
        val activity: RunActivityProjection?,
    )

    private fun signature(item: ChatRenderItem) = RowSignature(
        key = item.key,
        runId = (item as? ChatRenderItem.RunBlock)?.runId ?: (item as? ChatRenderItem.Single)?.stableRunId,
        rows = rowsOf(item).map { it.role to it.isReasoning },
        contents = rowsOf(item).map { it.content + it.toolCalls.orEmpty().joinToString { call -> "${call.name}=${call.result}" } },
        activity = activityOf(item),
    )

    private fun activityOf(item: ChatRenderItem) =
        if (item.isRunItem) projectRunActivity(rowsOf(item), isActiveRunStreaming = false) else null

    private fun rowsOf(item: ChatRenderItem): List<UiMessage> = when (item) {
        is ChatRenderItem.RunBlock -> item.messages.map { it.first }
        is ChatRenderItem.Single -> listOf(item.message)
    }

    /** Streams the finished [frames] and returns the final live render (the reply and the prompt). */
    private suspend fun CanonicalTurnHarness.streamTurn(frames: List<LettaMessage>): List<ChatRenderItem> =
        stream(frames, finished = true) { live -> live.size == 2 && live.first().containsMessageId(reply.id) }

    private companion object {
        const val PROMPT_ID = "ui-msg-9173297"
        const val PROMPT_OTID = "cm-android-f4ba1c01"
        const val LIVE_RUN = "local-run-73"
        // The device stamped its echo at 03:58:56.147; the server stored the prompt at 03:58:54.988.
        const val LIVE_TURN_MILLIS = 881L
        const val DURABLE_TURN_MILLIS = 2_040L
        val scope = TimelineScope("backend", "local-conv-496", "agent")

        val prompt = UserMessage(
            id = PROMPT_ID, contentRaw = JsonPrimitive("bear with me"),
            date = "2026-09-25T03:58:54.988Z", otid = PROMPT_OTID,
        )
        val echo = prompt.copy(id = "cm-user-$PROMPT_OTID", date = "2026-09-25T03:58:56.147Z")
        val thought = ReasoningMessage(id = "ui-msg-9173298", reasoning = "Keep it light", date = "2026-09-25T03:58:56.300Z")
        val call = ToolCallMessage(
            id = "ui-msg-9173299",
            toolCalls = listOf(ToolCall(id = "call-73", name = "Bash", arguments = "date")),
            date = "2026-09-25T03:58:56.500Z",
        )
        val result = ToolReturnMessage(
            id = "ui-msg-9173300", toolCallId = "call-73", toolReturnRaw = JsonPrimitive("Thu"),
            status = "success", date = "2026-09-25T03:58:56.700Z",
        )
        val reply = AssistantMessage(
            id = "ui-msg-9173301", contentRaw = JsonPrimitive("Noted."), date = "2026-09-25T03:58:57.028Z",
        )
        val stop = StopReason(id = "stop-73", reason = "end_turn", date = reply.date)
        val usage = UsageStatistics(id = "usage-73", promptTokens = 49_355, completionTokens = 14, date = reply.date)

        val liveReplyTurn: List<LettaMessage> = listOf(echo, thought.copy(runId = LIVE_RUN), reply.copy(runId = LIVE_RUN))
        val liveToolTurn: List<LettaMessage> = listOf(
            echo, thought.copy(runId = LIVE_RUN), call.copy(runId = LIVE_RUN),
            result.copy(runId = LIVE_RUN), reply.copy(runId = LIVE_RUN),
        )
        val durableReplyTurn: List<LettaMessage> = listOf(usage, stop, reply, thought, prompt)
        val durableToolTurn: List<LettaMessage> = listOf(usage, stop, reply, result, call, thought, prompt)
    }
}
