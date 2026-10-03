package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1.12: the owner's desktop panel showed one streaming reply as a stack of
 * near-copies of itself, each a little further along ("...the injectionI rewrote the green box",
 * "...adds aI rewrote...", "...a second routeI rewrote..."), ending in one clean copy.
 *
 * Each copy is a whole cumulative snapshot of the reply. App Server snapshots of one reply share an
 * otid; when one stops being a prefix extension of the text held (the body was rewritten upstream),
 * the stream merge appended it, and every later snapshot stacked onto the last. Replayed here through
 * the real coordinator and presentation that the desktop and the phone both draw from, frame by
 * frame, and checked on every frame rather than at the end.
 */
class StreamRewriteFramesTest {
    @Test fun rewrittenSnapshotsReplaceTheReplyInsteadOfStackingCopies() = runBlocking {
        val recorder = TimelineFrameRecorder.open(scope)
        try {
            recorder.stream(listOf(prompt) + snapshots(firstDraft) + snapshots(rewrite), finished = false)
            val stacked = recorder.frames.filter { frame ->
                frame.rows.any { row -> row.contents.any { it.occurrencesOf(OPENING) > 1 } }
            }
            assertTrue(stacked.isEmpty(), "a reply stacked copies of itself:\n" + stacked.joinToString("\n"))
            assertEquals(listOf(rewrite.last()), recorder.lastFrame().rows.first().contents)
        } finally {
            recorder.close()
        }
    }

    /**
     * The structural review's first hypothesis, replayed: two writers alternate on one identity. One
     * delivers the reply's cumulative snapshots A[0:k]; the other re-delivers a different body B under
     * the same otid (the observer path stamps one synthetic otid per conversation when a frame has no
     * stable id). Before the fix every snapshot appended after B, and the row read A1 B A2 B ... An -
     * the screenshot's shape, down to the last copy being A alone.
     */
    @Test fun alternatingWriterOnOneOtidDoesNotStackTheReply() = runBlocking {
        val recorder = TimelineFrameRecorder.open(scope)
        try {
            val frames = rewrite.map { LEAD + it.removePrefix(LEAD).removeSuffix(TAIL) }
                .flatMap { snapshot -> listOf(chunk(sequence++, snapshot), chunk(sequence++, TAIL)) }
                .dropLast(1)
            recorder.stream(listOf(prompt) + frames, finished = false)
            val stacked = recorder.frames.filter { frame ->
                frame.rows.any { row -> row.contents.any { it.occurrencesOf(OPENING) > 1 } }
            }
            assertTrue(stacked.isEmpty(), "a reply stacked copies of itself:\n" + stacked.joinToString("\n"))
            assertEquals(listOf(frames.last().content), recorder.lastFrame().rows.first().contents)
        } finally {
            recorder.close()
        }
    }

    @Test fun incrementalTokensStillAppend() = runBlocking {
        val recorder = TimelineFrameRecorder.open(scope)
        try {
            val tokens = listOf("Your", " threat", " box", " already", " says", " so.")
            recorder.stream(listOf(prompt) + tokens.mapIndexed { i, token -> chunk(i, token) }, finished = false)
            assertEquals(listOf(tokens.joinToString("")), recorder.lastFrame().rows.first().contents)
        } finally {
            recorder.close()
        }
    }

    private fun snapshots(texts: List<String>): List<LettaMessage> = texts.map { chunk(sequence++, it) }

    private var sequence = 0

    /** One App Server assistant frame: a fresh backend id per chunk, the reply's stable otid. */
    private fun chunk(index: Int, text: String) = AssistantMessage(
        id = "letta-msg-${1_300 + index}",
        contentRaw = JsonPrimitive(text),
        date = "2026-10-03T12:00:${(10 + index % 50).toString().padStart(2, '0')}.000Z",
        otid = REPLY_OTID,
        runId = RUN,
    )

    private fun String.occurrencesOf(needle: String): Int = windowed(needle.length).count { it == needle }

    private companion object {
        const val REPLY_OTID = "otid-reply-27"
        const val RUN = "run-27"
        const val OPENING = "Your threat box already says"
        val scope = TimelineScope("backend", "conv-rewrite", "agent")
        val prompt = UserMessage(
            id = "msg-prompt-27", contentRaw = JsonPrimitive("is this a risk?"), date = "2026-10-03T12:00:00.000Z",
            otid = "cm-27", runId = RUN,
        )

        private const val LEAD = "$OPENING \"a perfectly benign user could create a piece of code.\" That fits what you describe, and "
        private const val TAIL = "I rewrote the green box. Point 2 now says the agent writes HTML and the user runs it on " +
            "their own device.\n\nMy earlier suggestion to run a test also assumed the agent runs the code, so drop it."

        /** The reply as first streamed, growing by prefix. */
        val firstDraft = listOf(LEAD, LEAD + "I rewrote the green", LEAD + TAIL)

        /** The same reply rewritten mid-sentence: each snapshot diverges from the text held. */
        val rewrite = listOf(
            "the injection", "the injection path adds a", "the injection path adds a second",
            "the injection path adds a second route", "the injection path adds a second route to the same outcome. ",
        ).map { LEAD + it + TAIL }
    }
}
