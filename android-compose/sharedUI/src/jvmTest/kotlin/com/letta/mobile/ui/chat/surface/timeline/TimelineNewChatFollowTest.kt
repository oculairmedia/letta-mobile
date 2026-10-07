@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.letta.mobile.data.model.AssistantMessage
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
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1: the first send in a brand-new conversation. The page opens on the welcome
 * (no history), the user's prompt swaps it for the list, and the reply streams in until it is far
 * taller than the viewport. The reader never scrolled, so the list must follow the reply down to
 * its newest line and the scroll-to-latest button must never show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineNewChatFollowTest {
    private val main = ManualMainDispatcher()

    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun removeMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun firstSendInANewChatFollowsTheStreamingReply() = runComposeUiTest {
        val rig = runBlocking { TimelineDomainRig.open(scope) }
        TimelineUiFrameRecorder(this, rig, main).use { recorder ->
            try {
                runBlocking { rig.openOn(emptyList()) }
                recorder.mount()
                recorder.advance("open", OPEN_FRAMES)
                assertTrue(recorder.frames.last().rows.isEmpty(), "a new chat opens on the welcome, not a list")

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
                recorder.advance("stream-done", TimelineUiFrameRecorder.FRAMES_PER_STEP * 2)
                recorder.writeFrameLog("new-chat-follow-frames")

                val frames = recorder.frames
                val log = frames.joinToString("\n")
                val tall = frames.filter { it.step.startsWith("stream") }.any { f -> f.rows.sumOf { it.height.toDouble() } > TimelineUiFrameRecorder.HEIGHT }
                assertTrue(tall, "the reply never outgrew the viewport, so this proves nothing:\n$log")
                val buttons = frames.filter { it.scrollToLatestShown }
                assertTrue(buttons.isEmpty(), "scroll-to-latest showed while the reader never scrolled:\n" + buttons.joinToString("\n") + "\n\n$log")
                val last = frames.last()
                assertTrue(last.atNewestEdge, "the list stopped following the reply:\n$log")
            } finally {
                runBlocking { rig.close() }
            }
        }
    }

    private companion object {
        const val PROMPT_OTID = "cm-new-chat-1"
        const val OPTIMISTIC_PROMPT_KEY = "msg-$PROMPT_OTID"
        const val OPEN_FRAMES = 20
        const val TOKEN_FRAMES = 3
        val scope = TimelineScope("backend", "conv-new-chat", "agent")

        val prompt = UserMessage(
            id = "msg-prompt-1", contentRaw = JsonPrimitive("tell me a long story"), date = "2026-09-25T04:00:00.000Z", otid = PROMPT_OTID,
        )
        val echo = prompt.copy(id = "cm-user-$PROMPT_OTID", date = "2026-09-25T04:00:01.000Z")
        private val sentence = "Once upon a time a very long reply kept streaming in, line after line after line. "

        /** Grows well past one 720dp viewport. */
        val tokens: List<String> = (1..16).map { n -> sentence.repeat(n * 3) }

        fun reply(text: String) =
            AssistantMessage(id = "msg-reply-1", contentRaw = JsonPrimitive(text), date = "2026-09-25T04:00:02.000Z")
    }
}
