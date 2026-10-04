package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.PersistentMap
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-nbha6: the live reducer names a row by the host-stamped logical id alone. Text is
 * replaced by `text_seq` (never concatenated), an id appends once, and a tool return joins `tc-<id>`.
 */
class LiveTurnReducerTest {

    @Test
    fun firstFrameAppendsOnce() {
        val fold = LiveFold().apply { feed(reply("lm-1", 1, "Hel")) }

        assertEquals(listOf("lm-1"), fold.rows().map { it.logicalId })
        assertEquals("Hel", fold.row("lm-1").content)
    }

    @Test
    fun higherTextSeqReplacesText() {
        val fold = LiveFold().apply { feed(reply("lm-1", 1, "Hel"), reply("lm-1", 2, "Hello"), reply("lm-1", 3, "Hello there")) }

        assertEquals(listOf("Hello there"), fold.rows().map { it.content })
        assertEquals(3, fold.row("lm-1").textSeq)
    }

    @Test
    fun replacingTextKeepsThePositionAndKeyOfTheRow() {
        val fold = LiveFold().apply { feed(reply("lm-1", 1, "a"), reasoning("lm-2", 1, "think"), reply("lm-1", 2, "ab")) }

        val before = LiveFold().apply { feed(reply("lm-1", 1, "a"), reasoning("lm-2", 1, "think")) }.rows()
        assertEquals(before.map { it.position }, fold.rows().map { it.position })
        assertEquals(before.map { it.otid }, fold.rows().map { it.otid })
    }

    @Test
    fun lowerOrEqualTextSeqIsDropped() {
        Telemetry.clear()
        val fold = LiveFold().apply {
            feed(reply("lm-1", 1, "Hel"), reply("lm-1", 3, "Hello there"))
            feed(reply("lm-1", 2, "Hello"), reply("lm-1", 3, "Hello there, rewritten"), reply("lm-1", 1, "Hel"))
        }

        assertEquals(listOf("Hello there"), fold.rows().map { it.content })
        assertEquals(3, telemetryCount("live.staleTextFrame"))
    }

    @Test
    fun twoWritersOnOneIdCannotStack() {
        Telemetry.clear()
        val answer = (1..12).map { "This reply grows by one word at a time, word ${it}." }
        val cumulative = answer.runningReduce { held, next -> "$held $next" }
        // Writer A delivers the reply's cumulative snapshots 1..n. Writer B re-delivers a different body under
        // the same logical id with a seq that is always behind the snapshot it interleaves with.
        val frames = cumulative.mapIndexed { index, text ->
            listOf(reply("lm-reply", index * 2 + 2, text), reply("lm-reply", index * 2 + 1, "I rewrote the green box."))
        }.flatten()
        val fold = LiveFold().apply { feed(*frames.toTypedArray()) }

        assertEquals(listOf("lm-reply"), fold.rows().map { it.logicalId })
        assertEquals(cumulative.last(), fold.row("lm-reply").content)
        assertEquals(cumulative.size, telemetryCount("live.staleTextFrame"))
    }

    @Test
    fun toolReturnAttachesByCallIdExactly() {
        val fold = LiveFold().apply {
            feed(call("tc-call-1", "call-1", """{"command":"date"}"""), call("tc-call-2", "call-2", """{"command":"uptime"}"""))
            feed(returned("call-2", "up 3 days"), returned("call-1", "Sat Oct 3"))
        }

        assertEquals(listOf("tc-call-1", "tc-call-2"), fold.rows().map { it.logicalId })
        assertEquals("Sat Oct 3", fold.row("tc-call-1").toolReturnContentByCallId["call-1"])
        assertEquals("up 3 days", fold.row("tc-call-2").toolReturnContentByCallId["call-2"])
        assertTrue(fold.pending.isEmpty())
    }

    @Test
    fun aReturnBeforeItsCallIsParkedAndAttachedWhenTheCallAppends() {
        val fold = LiveFold().apply { feed(returned("call-9", "early")) }
        assertEquals(listOf("call-9"), fold.pending.keys.toList())
        assertTrue(fold.rows().isEmpty())

        fold.feed(call("tc-call-9", "call-9", "{}"))

        assertEquals("early", fold.row("tc-call-9").toolReturnContentByCallId["call-9"])
        assertTrue(fold.pending.isEmpty())
    }

    @Test
    fun aDuplicateToolCallEmissionWithEmptyArgumentsKeepsTheArguments() {
        val fold = LiveFold().apply {
            feed(call("tc-call-1", "call-1", """{"command":"date"}"""), returned("call-1", "Sat Oct 3"), call("tc-call-1", "call-1", "{}"))
        }

        val row = fold.row("tc-call-1")
        assertEquals(1, fold.rows().size)
        assertTrue(row.content.contains("date"))
        assertEquals("Sat Oct 3", row.toolReturnContent)
    }

    @Test
    fun unstampedTextFrameIsDropped() {
        Telemetry.clear()
        val fold = LiveFold().apply {
            feed(AssistantMessage(id = "letta-msg-1", contentRaw = JsonPrimitive("no stamp")))
            feed(ReasoningMessage(id = "letta-msg-2", reasoning = "no stamp either"))
        }

        assertTrue(fold.rows().isEmpty())
        assertEquals(2, telemetryCount("live.unstampedFrame"))
    }

    @Test
    fun redialSeqResetDoesNotShrinkText() {
        // A redial restarts the wire's event_seq / seq_id; the text sequence keeps growing, so the reply
        // never reads shorter and no frame of the old connection is mistaken for a newer one.
        val fold = LiveFold().apply {
            feed(reply("lm-1", 1, "The", seqId = 90_001), reply("lm-1", 2, "The answer", seqId = 90_002))
            feed(reply("lm-1", 3, "The answer is", seqId = 1), reply("lm-1", 4, "The answer is 42", seqId = 2))
            feed(reply("lm-1", 2, "The answer", seqId = 3))
        }

        assertEquals(listOf("The answer is 42"), fold.rows().map { it.content })
        assertEquals(4, fold.row("lm-1").textSeq)
    }

    @Test
    fun appendOfAnExistingLogicalIdIsANoOpThatReportsIt() {
        Telemetry.clear()
        val timeline = Timeline("conv").append(row("lm-1", 1, "x"))

        val again = timeline.append(row("lm-1", 2, "y").copy(otid = "other-otid", position = 9.0))

        assertEquals(timeline.events, again.events)
        assertEquals(1, telemetryCount("live.duplicateAppend"))
    }

    @Test
    fun randomInterleavingsKeepOneRowPerIdAndMonotonicText() {
        val random = Random(SEED)
        repeat(ITERATIONS) { iteration ->
            Telemetry.clear()
            val ids = (1..random.nextInt(1, 4)).map { "lm-$it" }
            val frames = interleavedFrames(random, ids)
            val fold = LiveFold()
            val held = mutableMapOf<String, Pair<Int, String>>()
            frames.forEach { frame ->
                fold.feed(frame)
                fold.rows().filter { it.logicalId in ids }.forEach { row ->
                    val before = held[row.logicalId]
                    assertTrue(before == null || row.textSeq >= before.first, "iteration $iteration: text_seq went backwards")
                    held[row.logicalId] = row.textSeq to row.content
                }
            }
            assertEquals(ids.size, fold.rows().count { it.logicalId in ids }, "iteration $iteration: one row per logical id")
            assertEquals(fold.rows().size, fold.rows().map { it.logicalId }.toSet().size, "iteration $iteration")
            ids.forEach { id -> assertEquals(finalText(frames, id), fold.row(id).content, "iteration $iteration $id") }
            assertEquals(0, telemetryCount("live.duplicateAppend"), "iteration $iteration")
        }
    }

    /** Every id's snapshots 1..n, shuffled with duplicates and a tool round between the ids. */
    private fun interleavedFrames(random: Random, ids: List<String>): List<LettaMessage> {
        val perId = ids.map { id ->
            val snapshots = (1..random.nextInt(1, 7)).runningFold("") { held, n -> "$held w$n" }.drop(1)
            snapshots.mapIndexed { i, text -> reply(id, i + 1, text) as LettaMessage }
        }
        val tool = listOf(call("tc-call-x", "call-x", "{}"), returned("call-x", "ok"))
        val pool = perId.flatten().flatMap { frame -> List(random.nextInt(1, 3)) { frame } } + tool
        return pool.shuffled(random).let { shuffled -> shuffled + perId.map { it.last() } }
    }

    private fun finalText(frames: List<LettaMessage>, id: String): String =
        frames.filterIsInstance<AssistantMessage>().filter { it.logicalMessageId == id }.maxBy { it.textSeq ?: 0 }.content

    private fun telemetryCount(name: String): Int = Telemetry.events.value.count { it.name == name }

    private fun reply(id: String, seq: Int, text: String, seqId: Int? = null) = AssistantMessage(
        id = id,
        contentRaw = JsonPrimitive(text),
        logicalMessageId = id,
        textSeq = seq,
        seqId = seqId,
    )

    private fun reasoning(id: String, seq: Int, text: String) =
        ReasoningMessage(id = id, reasoning = text, logicalMessageId = id, textSeq = seq)

    private fun call(logicalId: String, callId: String, arguments: String) = ToolCallMessage(
        id = "toolcall-$callId",
        toolCall = ToolCall(toolCallId = callId, name = "Bash", arguments = arguments),
        toolCalls = listOf(ToolCall(toolCallId = callId, name = "Bash", arguments = arguments)),
        logicalMessageId = logicalId,
    )

    private fun returned(callId: String, body: String) = ToolReturnMessage(
        id = "toolreturn-$callId",
        toolReturnRaw = JsonPrimitive(body),
        toolCallId = callId,
        status = "success",
        logicalMessageId = "tr-$callId",
    )

    private fun row(logicalId: String, seq: Int, text: String) = checkNotNull(
        reply(logicalId, seq, text).toTimelineEvent(position = 1.0),
    )

    private class LiveFold {
        private var state = TimelineReducerState(Timeline("conv"))
        val timeline: Timeline get() = state.timeline
        val pending: PersistentMap<String, ToolReturnMessage> get() = state.pendingToolReturnsByCallId

        fun feed(vararg frames: LettaMessage) = frames.forEach { frame -> state = state.reduceLive(frame, agentId = null) }

        fun rows(): List<TimelineEvent.Confirmed> = timeline.events.filterIsInstance<TimelineEvent.Confirmed>()

        fun row(logicalId: String): TimelineEvent.Confirmed = rows().single { it.logicalId == logicalId }
    }

    private companion object {
        const val SEED = 20_261_003
        const val ITERATIONS = 200
    }
}
