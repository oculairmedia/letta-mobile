package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.parseTimestampEpochMillis
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.handover.HandedOverPrompts
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The prompt the user sent stays on screen, under one key, from the moment it is sent until its turn
 * has settled: in every frame the list could draw ([TimelineFrameRecorder]), through the optimistic
 * row, the server's echo, the live run and the durable page that settles it.
 */
class SentPromptThroughSettleTest {
    @Test fun aPromptWithAnEchoedRunStaysThroughSettle() = turn(
        live = listOf(echo, thought.copy(runId = RUN), reply.copy(runId = RUN)),
        durable = listOf(usage, stop, reply.copy(runId = RUN), thought.copy(runId = RUN), prompt),
    )

    /** The App Server's durable rows carry no run: the settled prompt names the turn. */
    @Test fun aPromptWhoseDurableTurnHasNoRunStaysThroughSettle() = turn(
        live = listOf(echo, thought.copy(runId = RUN), reply.copy(runId = RUN)),
        durable = listOf(usage, stop, reply, thought, prompt),
    )

    /** The stream never echoes the prompt (only the run's own rows arrive live). */
    @Test fun aPromptTheStreamNeverEchoedStaysThroughSettle() = turn(
        live = listOf(thought.copy(runId = RUN), reply.copy(runId = RUN)),
        durable = listOf(usage, stop, reply, thought, prompt),
    )

    @Test fun aPromptAnsweredWithoutARunStaysThroughSettle() = turn(
        live = listOf(echo, reply),
        durable = listOf(usage, stop, reply, prompt),
    )

    /** The overlay's hold: a prompt that left the store stays until it is resident; a dismissed one leaves. */
    @Test fun aLeavingPromptIsHeldUntilResidentButADismissedOneIsNot() {
        val record = CanonicalPendingLocalStore.Record(PROMPT_OTID, "hi", emptyList(), "2026-11-18T09:05:00.100Z")
        val old = 1_794_992_401_000L
        val prompts = newHold()
        prompts.hold(listOf(record), emptySet(), old)
        assertEquals(listOf(record), prompts.hold(emptyList(), emptySet(), old), "held while its durable copy is not on screen")
        assertEquals(emptyList(), prompts.hold(emptyList(), setOf(PROMPT_OTID), old), "released once resident")

        val dismissed = newHold()
        dismissed.hold(listOf(record), emptySet(), old)
        dismissed.forget(PROMPT_OTID)
        assertEquals(emptyList(), dismissed.hold(emptyList(), emptySet(), old), "a dismissed prompt is meant to leave")

        val lost = newHold()
        lost.hold(listOf(record), emptySet(), old)
        lost.hold(emptyList(), emptySet(), old)
        assertEquals(emptyList(), lost.hold(emptyList(), emptySet(), old + 10 * 60_000), "released once history is past its send")
    }

    private fun newHold() = HandedOverPrompts<CanonicalPendingLocalStore.Record>(
        otidOf = { it.otid },
        sentAtMillisOf = { parseTimestampEpochMillis(it.sentAt) },
    )

    private fun turn(live: List<LettaMessage>, durable: List<LettaMessage>) = runBlocking {
        val recorder = TimelineFrameRecorder.open(scope)
        try {
            recorder.openOn(history)
            recorder.send(PROMPT_OTID, prompt.content, echo.date!!)
            recorder.accept(PROMPT_OTID)
            recorder.stream(live)
            recorder.settle(durable + history)
            val frames = recorder.frames
            val gaps = promptGaps(frames, PROMPT_OTID)
            assertTrue(gaps.isEmpty(), gaps.joinToString("\n") + "\n\nframes:\n" + frames.joinToString("\n"))
        } finally {
            recorder.close()
        }
    }

    private companion object {
        const val PROMPT_OTID = "cm-voice-7a2c"
        const val RUN = "local-run-7"
        val scope = TimelineScope("backend", "conv-voice-settle", "agent")

        val history: List<LettaMessage> = listOf(
            AssistantMessage(id = "old-reply", contentRaw = JsonPrimitive("Earlier answer."), date = "2026-11-18T09:00:01.000Z"),
            UserMessage(
                id = "old-prompt",
                contentRaw = JsonPrimitive("Earlier question"),
                date = "2026-11-18T09:00:00.000Z",
                otid = "cm-old",
            ),
        )
        val prompt = UserMessage(
            id = "msg-prompt-7", contentRaw = JsonPrimitive("what's on today"), date = "2026-11-18T09:05:00.100Z", otid = PROMPT_OTID,
        )
        val echo = prompt.copy(id = "cm-user-$PROMPT_OTID", date = "2026-11-18T09:05:00.900Z")
        val thought = ReasoningMessage(id = "msg-thought-7", reasoning = "Check the calendar", date = "2026-11-18T09:05:01.000Z")
        val reply = AssistantMessage(id = "msg-reply-7", contentRaw = JsonPrimitive("Two meetings."), date = "2026-11-18T09:05:02.000Z")
        val stop = StopReason(id = "stop-7", reason = "end_turn", date = reply.date)
        val usage = UsageStatistics(id = "usage-7", promptTokens = 900, completionTokens = 9, date = reply.date)
    }
}

/**
 * Every frame from the first that draws [promptId] onward in which it is missing, and every change of
 * the key it is drawn under: each one is the prompt leaving the screen (or remounting) for a frame.
 */
internal fun promptGaps(frames: List<TimelineFrameRecorder.Frame>, promptId: String): List<String> {
    val first = frames.indexOfFirst { frame -> frame.rows.any { promptId in it.messages } }
    if (first < 0) return listOf("the prompt $promptId never drew")
    var key: String? = null
    return frames.drop(first).mapNotNull { frame ->
        val row = frame.rows.firstOrNull { promptId in it.messages }
        when {
            row == null -> "frame ${frame.index} [${frame.step}]: the prompt is not drawn"
            key != null && row.key != key -> "frame ${frame.index} [${frame.step}]: the prompt moved from key $key to ${row.key}"
                .also { key = row.key }
            else -> null.also { key = row.key }
        }
    }
}
