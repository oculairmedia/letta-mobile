package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1.12 / letta-mobile-so5sd: the owner saw the shared timeline flicker on a
 * Pixel 9 Pro - a split-second overlap right after sending, and a flash when the reply finished.
 *
 * Each test drives one turn through the real coordinator and presentation - the optimistic send,
 * the server's echo, the stream, and the durable page that settles it - and checks EVERY frame the
 * list could draw on the way ([TimelineFrameRecorder]), not just where it ends up.
 */
class TimelineHandoverFramesTest {
    @Test fun replyTurnHandsOverWithoutAFlicker() = turn(
        live = listOf(echo, thought.copy(runId = LIVE_RUN), reply.copy(runId = LIVE_RUN)),
        durable = listOf(usage, stop, reply, thought, prompt),
    )

    @Test fun toolTurnHandsOverWithoutAFlicker() = turn(
        live = listOf(echo, thought.copy(runId = LIVE_RUN), call.copy(runId = LIVE_RUN), result.copy(runId = LIVE_RUN), reply.copy(runId = LIVE_RUN)),
        durable = listOf(usage, stop, reply, result, call, thought, prompt),
    )

    /** Two tool rounds, each with its own narration: several bubbles in one run. */
    @Test fun multiBubbleTurnHandsOverWithoutAFlicker() = turn(
        live = listOf(
            echo, thought.copy(runId = LIVE_RUN), narration.copy(runId = LIVE_RUN), call.copy(runId = LIVE_RUN),
            result.copy(runId = LIVE_RUN), secondCall.copy(runId = LIVE_RUN), secondResult.copy(runId = LIVE_RUN),
            reply.copy(runId = LIVE_RUN),
        ),
        durable = listOf(usage, stop, reply, secondResult, secondCall, result, call, narration, thought, prompt),
    )

    /** The stream never named the run; the durable page does (the settled shape is a run, live was not). */
    @Test fun replyStreamedWithoutARunSettlesWithoutAFlicker() = turn(
        live = listOf(echo, reply),
        durable = listOf(usage, stop, reply.copy(runId = DURABLE_RUN), prompt),
    )

    /** Reasoning and reply streamed with no run: two live bubbles settle as one run. */
    @Test fun runlessReasoningAndReplySettleWithoutAFlicker() = turn(
        live = listOf(echo, thought, reply),
        durable = listOf(usage, stop, reply, thought, prompt),
    )

    /**
     * A turn this device did not send (another device, or the agent waking itself): the prompt is
     * already settled, and only the run-less reply streams here.
     */
    @Test fun replyToAnotherDevicesPromptSettlesWithoutAFlicker() = turn(
        live = listOf(reply),
        durable = listOf(usage, stop, reply, prompt),
        before = listOf(prompt) + history,
        sent = false,
    )

    private fun turn(
        live: List<LettaMessage>,
        durable: List<LettaMessage>,
        before: List<LettaMessage> = history,
        sent: Boolean = true,
    ) = runBlocking {
        val recorder = TimelineFrameRecorder.open(scope)
        try {
            recorder.openOn(before)
            if (sent) recorder.send(PROMPT_OTID, prompt.content, echo.date!!)
            recorder.stream(live)
            val final = recorder.lastFrame()
            recorder.settle(durable + history)
            val frames = recorder.frames
            val flickers = handoverFlickers(frames)
            assertTrue(flickers.isEmpty(), flickers.joinToString("\n") + "\n\nframes:\n" + frames.joinToString("\n"))
            // Settled output equals the live render: the same keys over the same messages.
            assertEquals(final.rows.map { it.key to it.messages }, recorder.lastFrame().rows.map { it.key to it.messages })
        } finally {
            recorder.close()
        }
    }

    private companion object {
        const val PROMPT_OTID = "cm-android-5d1e"
        const val LIVE_RUN = "local-run-5"
        const val DURABLE_RUN = "run-durable-5"
        val scope = TimelineScope("backend", "conv-flicker", "agent")

        val history: List<LettaMessage> = listOf(
            AssistantMessage(id = "old-reply", contentRaw = JsonPrimitive("Earlier answer."), date = "2026-09-25T03:50:01.000Z"),
            UserMessage(id = "old-prompt", contentRaw = JsonPrimitive("Earlier question"), date = "2026-09-25T03:50:00.000Z", otid = "cm-old"),
        )
        val prompt = UserMessage(
            id = "msg-prompt-5", contentRaw = JsonPrimitive("plan dinner"), date = "2026-09-25T03:58:54.988Z", otid = PROMPT_OTID,
        )
        val echo = prompt.copy(id = "cm-user-$PROMPT_OTID", date = "2026-09-25T03:58:56.147Z")
        val thought = ReasoningMessage(id = "msg-thought-5", reasoning = "Something light", date = "2026-09-25T03:58:56.300Z")
        val narration = AssistantMessage(id = "msg-narrate-5", contentRaw = JsonPrimitive("Checking the fridge."), date = "2026-09-25T03:58:56.400Z")
        val call = ToolCallMessage(
            id = "msg-call-5", toolCalls = listOf(ToolCall(id = "call-5", name = "Bash", arguments = "ls fridge")),
            date = "2026-09-25T03:58:56.500Z",
        )
        val result = ToolReturnMessage(
            id = "msg-result-5", toolCallId = "call-5", toolReturnRaw = JsonPrimitive("eggs"), status = "success",
            date = "2026-09-25T03:58:56.700Z",
        )
        val secondCall = ToolCallMessage(
            id = "msg-call-6", toolCalls = listOf(ToolCall(id = "call-6", name = "Read", arguments = "recipes.md")),
            date = "2026-09-25T03:58:56.800Z",
        )
        val secondResult = ToolReturnMessage(
            id = "msg-result-6", toolCallId = "call-6", toolReturnRaw = JsonPrimitive("omelette"), status = "success",
            date = "2026-09-25T03:58:56.900Z",
        )
        val reply = AssistantMessage(id = "msg-reply-5", contentRaw = JsonPrimitive("Omelette."), date = "2026-09-25T03:58:57.028Z")
        val stop = StopReason(id = "stop-5", reason = "end_turn", date = reply.date)
        val usage = UsageStatistics(id = "usage-5", promptTokens = 1_000, completionTokens = 14, date = reply.date)
    }
}
