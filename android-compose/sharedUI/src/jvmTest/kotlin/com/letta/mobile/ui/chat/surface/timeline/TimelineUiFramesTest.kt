@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-29sxj (epic letta-mobile-4vtng): the UI-side invariants of a send, echo, stream and
 * settle, asserted frame by frame on the shared paged timeline. The domain side is the real
 * coordinator and presentation ([TimelineDomainRig]); the list is the real ChatTimeline. Runs on
 * the v2 compose test, whose effects are queued as on a device: the v1 default runs them inline
 * while a composition is applying, and the list's `scrollToItem(0)` then trips "pending
 * composition has not been applied".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineUiFramesTest {
    private val main = ManualMainDispatcher()

    /** Paging delivers pages on Dispatchers.Main, which a desktop test does not have ([ManualMainDispatcher]). */
    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun removeMain() {
        Dispatchers.resetMain()
    }

    /** The settled run block lands 26dp taller than it first draws. RED until the keys are intrinsic (C4). */
    @Ignore("RED until letta-mobile-7o8s5")
    @Test
    fun replyTurnKeepsRowHeightsStableAtSettle() = replyTurn { frames ->
        val atSettle = frames.dropWhile { it.step != "stream-done" }
        val jumps = heightJumps(atSettle, MAX_HEIGHT_JUMP_DP)
        assertTrue(jumps.isEmpty(), jumps.joinToString("\n") + "\n\n" + frames.joinToString("\n"))
    }

    @Test
    fun settleDoesNotFlashTheSpinner() = replyTurn { frames ->
        // Opening the history is allowed its loading state; everything after is not.
        val flashes = spinnerFlashes(frames.filter { it.step != "open" })
        assertTrue(flashes.isEmpty(), "spinner over rows:\n" + flashes.joinToString("\n"))
    }

    /** PagedTimelineList scrolls to the tail on every emission, not only when a new newest row appears. RED until P1. */
    @Ignore("RED until letta-mobile-v3h53")
    @Test
    fun streamingDoesNotScrollToTopPerToken() = replyTurn { frames ->
        val resets = scrollResetsWhileFollowing(frames).filter { it.step == "stream" }
        assertTrue(resets.isEmpty(), "left the newest edge while following:\n" + resets.joinToString("\n"))
    }

    /** The send's own glide is not the reader leaving the edge: the button never flashes up (letta-mobile-bglj6.1). */
    @Test
    fun sendAtTheNewestEdgeNeverOffersScrollToLatest() = replyTurn { frames ->
        val shown = frames.filter { it.step != "open" && it.scrollToLatestShown }
        assertTrue(shown.isEmpty(), "scroll-to-latest showed though the reader never scrolled:\n" + shown.joinToString("\n"))
    }

    @Test
    fun settledRowsDoNotRecomposePerToken()= replyTurn { frames ->
        val perFrame = recompositionsPerFrame(frames, "stream")
        assertTrue(perFrame.size >= tokens.size, "too few token frames to judge: $perFrame")
        assertTrue(perFrame.all { it <= MAX_ROW_COMPOSITIONS_PER_FRAME }, "row compositions per frame: $perFrame")
    }

    /** One reply turn over some history; [assertFrames] reads every frame it drew. */
    private fun replyTurn(assertFrames: (List<UiFrame>) -> Unit) = runComposeUiTest {
        val rig = runBlocking { TimelineDomainRig.open(scope) }
        TimelineUiFrameRecorder(this, rig, main).use { recorder ->
            try {
                runBlocking { rig.openOn(history) }
                recorder.mount()
                // Each step waits for its own outcome, drawn and quiet, before the next begins: a slow
                // runner takes more frames to get there, but never carries one step's work into the
                // frames another step is judged on (letta-mobile-8p8mj).
                recorder.advanceUntil("open", OPEN_FRAMES) { recorder.frames.last().rows.size > MIN_HISTORY_ROWS && recorder.isQuiet }
                recorder.writeFrameImage("reply-first")
                runBlocking { rig.send(echo) }
                recorder.advanceUntil("send", TimelineUiFrameRecorder.FRAMES_PER_STEP) {
                    recorder.frames.last().newestKey == OPTIMISTIC_PROMPT_KEY && recorder.isQuiet
                }
                val stream = runBlocking { rig.beginStream() }
                runBlocking { stream.emit(echo) }
                tokens.forEach { text ->
                    runBlocking { stream.emit(reply(text)) }
                    recorder.advance("stream", TOKEN_FRAMES)
                }
                runBlocking { stream.done() }
                recorder.advance("stream-done")
                runBlocking { rig.settle(listOf(usage, stop, reply(tokens.last()), prompt) + history) }
                recorder.advanceUntil("settle", SETTLE_FRAMES) { rig.drained }
                recorder.writeFrameImage("reply-last")
                recorder.writeFrameLog("reply-frames")
                assertTrue(rig.drained, "the overlay never handed over to the settled ledger")
                assertTrue(recorder.frames.any { it.rows.size > MIN_HISTORY_ROWS }, "history never drew")
                assertFrames(recorder.frames)
            } finally {
                runBlocking { rig.close() }
            }
        }
    }

    private companion object {
        const val PROMPT_OTID = "cm-android-5d1e"
        /** The optimistic prompt row: the pending send, keyed by its otid. */
        const val OPTIMISTIC_PROMPT_KEY = "msg-$PROMPT_OTID"
        const val MAX_HEIGHT_JUMP_DP = 2f
        const val MAX_ROW_COMPOSITIONS_PER_FRAME = 2
        /** Few enough that the oldest edge, and so the older-history footer, is on screen. */
        const val HISTORY_EXCHANGES = 3
        const val MIN_HISTORY_ROWS = 3
        const val OPEN_FRAMES = 30
        const val TOKEN_FRAMES = 2
        const val SETTLE_FRAMES = 20
        val scope = TimelineScope("backend", "conv-frames", "agent")

        val history: List<LettaMessage> = (0 until HISTORY_EXCHANGES).flatMap { i ->
            listOf(
                AssistantMessage(id = "old-reply-$i", contentRaw = JsonPrimitive("Earlier answer $i."), date = "2026-09-25T03:%02d:01.000Z".format(i)),
                UserMessage(id = "old-prompt-$i", contentRaw = JsonPrimitive("Earlier question $i"), date = "2026-09-25T03:%02d:00.000Z".format(i), otid = "cm-old-$i"),
            )
        }
        val prompt = UserMessage(
            id = "msg-prompt-5", contentRaw = JsonPrimitive("plan dinner"), date = "2026-09-25T03:58:54.988Z", otid = PROMPT_OTID,
        )
        val echo = prompt.copy(id = "cm-user-$PROMPT_OTID", date = "2026-09-25T03:58:56.147Z")
        val tokens = listOf("Ome", "Omelette", "Omelette, with", "Omelette, with chives", "Omelette, with chives and toast.")
        val stop = StopReason(id = "stop-5", reason = "end_turn", date = "2026-09-25T03:58:57.028Z")
        val usage = UsageStatistics(id = "usage-5", promptTokens = 1_000, completionTokens = 14, date = "2026-09-25T03:58:57.028Z")

        fun reply(text: String) =
            AssistantMessage(id = "msg-reply-5", contentRaw = JsonPrimitive(text), date = "2026-09-25T03:58:57.028Z")
    }
}
