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
        val fold = LiveFold().apply { feed(Reply("lm-1").at(1, "Hel")) }

        assertEquals(listOf("lm-1"), fold.rows().map { it.logicalId })
        assertEquals("Hel", fold.row("lm-1").content)
    }

    @Test
    fun higherTextSeqReplacesText() {
        val reply = Reply("lm-1")
        val fold = LiveFold().apply { feed(reply.at(1, "Hel"), reply.at(2, "Hello"), reply.at(3, "Hello there")) }

        assertEquals(listOf("Hello there"), fold.rows().map { it.content })
        assertEquals(3, fold.row("lm-1").textSeq)
    }

    @Test
    fun replacingTextKeepsThePositionAndKeyOfTheRow() {
        val reply = Reply("lm-1")
        val thought = Reply("lm-2")
        val before = LiveFold().apply { feed(reply.at(1, "a"), thought.thought(1, "think")) }.rows()
        val after = LiveFold().apply { feed(reply.at(1, "a"), thought.thought(1, "think"), reply.at(2, "ab")) }.rows()

        assertEquals(before.map { it.position }, after.map { it.position })
        assertEquals(before.map { it.otid }, after.map { it.otid })
    }

    @Test
    fun lowerOrEqualTextSeqIsDropped() {
        Telemetry.clear()
        val reply = Reply("lm-1")
        val fold = LiveFold().apply {
            feed(reply.at(1, "Hel"), reply.at(3, "Hello there"))
            feed(reply.at(2, "Hello"), reply.at(3, "Hello there, rewritten"), reply.at(1, "Hel"))
        }

        assertEquals(listOf("Hello there"), fold.rows().map { it.content })
        assertEquals(3, telemetryCount(STALE))
    }

    @Test
    fun twoWritersOnOneIdCannotStack() {
        Telemetry.clear()
        val reply = Reply("lm-reply")
        val cumulative = (1..12).map { "This reply grows by one word at a time, word $it." }.runningReduce { held, next -> "$held $next" }
        // Writer A delivers the reply's cumulative snapshots. Writer B re-delivers a different body under
        // the same logical id with a seq that is always behind the snapshot it interleaves with.
        val frames = cumulative.flatMapIndexed { index, text ->
            listOf(reply.at(index * 2 + 2, text), reply.at(index * 2 + 1, "I rewrote the green box."))
        }
        val fold = LiveFold().apply { feed(*frames.toTypedArray()) }

        assertEquals(listOf("lm-reply"), fold.rows().map { it.logicalId })
        assertEquals(cumulative.last(), fold.row("lm-reply").content)
        assertEquals(cumulative.size, telemetryCount(STALE))
    }

    @Test
    fun toolReturnAttachesByCallIdExactly() {
        val date = Tool("call-1")
        val uptime = Tool("call-2")
        val fold = LiveFold().apply {
            feed(date.call(Args.DATE), uptime.call(Args.UPTIME))
            feed(uptime.returned("up 3 days"), date.returned("Sat Oct 3"))
        }

        assertEquals(listOf("tc-call-1", "tc-call-2"), fold.rows().map { it.logicalId })
        assertEquals("Sat Oct 3", fold.row("tc-call-1").toolReturnContentByCallId["call-1"])
        assertEquals("up 3 days", fold.row("tc-call-2").toolReturnContentByCallId["call-2"])
        assertTrue(fold.pending.isEmpty())
    }

    @Test
    fun aReturnBeforeItsCallIsParkedAndAttachedWhenTheCallAppends() {
        val tool = Tool("call-9")
        val fold = LiveFold().apply { feed(tool.returned("early")) }
        assertEquals(listOf("call-9"), fold.pending.keys.toList())
        assertTrue(fold.rows().isEmpty())

        fold.feed(tool.call())

        assertEquals("early", fold.row("tc-call-9").toolReturnContentByCallId["call-9"])
        assertTrue(fold.pending.isEmpty())
    }

    @Test
    fun aDuplicateToolCallEmissionWithEmptyArgumentsKeepsTheArguments() {
        val tool = Tool("call-1")
        val fold = LiveFold().apply { feed(tool.call(Args.DATE), tool.returned("Sat Oct 3"), tool.call(Args.EMPTY)) }

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
        assertEquals(2, telemetryCount(UNSTAMPED))
    }

    @Test
    fun redialSeqResetDoesNotShrinkText() {
        // A redial restarts the wire's event_seq / seq_id; the text sequence keeps growing, so the reply
        // never reads shorter and no frame of the old connection is mistaken for a newer one.
        val reply = Reply("lm-1")
        val fold = LiveFold().apply {
            feed(reply.at(1, "The", seqId = 90_001), reply.at(2, "The answer", seqId = 90_002))
            feed(reply.at(3, "The answer is", seqId = 1), reply.at(4, "The answer is 42", seqId = 2))
            feed(reply.at(2, "The answer", seqId = 3))
        }

        assertEquals(listOf("The answer is 42"), fold.rows().map { it.content })
        assertEquals(4, fold.row("lm-1").textSeq)
    }

    @Test
    fun appendOfAnExistingLogicalIdIsANoOpThatReportsIt() {
        Telemetry.clear()
        val reply = Reply("lm-1")
        val timeline = Timeline("conv").append(reply.row(1, "x"))

        val again = timeline.append(reply.row(2, "y").copy(otid = "other-otid", position = 9.0))

        assertEquals(timeline.events, again.events)
        assertEquals(1, telemetryCount(DUPLICATE_APPEND))
    }

    @Test
    fun randomInterleavingsKeepOneRowPerIdAndMonotonicText() {
        val random = Random(SEED)
        repeat(ITERATIONS) { iteration ->
            Telemetry.clear()
            val replies = (1..random.nextInt(1, 4)).map { Reply("lm-$it") }
            val frames = interleavedFrames(random, replies)
            val fold = LiveFold()
            val heldSeq = mutableMapOf<String, Int>()
            frames.forEach { frame ->
                fold.feed(frame)
                fold.rows().forEach { row ->
                    assertTrue(row.textSeq >= (heldSeq[row.logicalId] ?: 0), "iteration $iteration: text_seq went backwards")
                    heldSeq[row.logicalId] = row.textSeq
                }
            }
            val rows = fold.rows()
            assertEquals(replies.size, rows.count { it.logicalId.startsWith("lm-") }, "iteration $iteration: one row per logical id")
            assertEquals(rows.size, rows.map { it.logicalId }.toSet().size, "iteration $iteration")
            replies.forEach { reply ->
                assertEquals(finalText(frames, reply), fold.row(reply.id).content, "iteration $iteration ${reply.id}")
            }
            assertEquals(0, telemetryCount(DUPLICATE_APPEND), "iteration $iteration")
        }
    }

    /** Every id's snapshots 1..n, shuffled with duplicates and a tool round between the ids. */
    private fun interleavedFrames(random: Random, replies: List<Reply>): List<LettaMessage> {
        val perReply = replies.map { reply ->
            val snapshots = (1..random.nextInt(1, 7)).runningFold("") { held, n -> "$held w$n" }.drop(1)
            snapshots.mapIndexed { i, text -> reply.at(i + 1, text) as LettaMessage }
        }
        val tool = Tool("call-x")
        val pool = perReply.flatten().flatMap { frame -> List(random.nextInt(1, 3)) { frame } } + tool.call() + tool.returned("ok")
        return pool.shuffled(random) + perReply.map { it.last() }
    }

    private fun finalText(frames: List<LettaMessage>, reply: Reply): String =
        frames.filterIsInstance<AssistantMessage>().filter { it.logicalMessageId == reply.id }.maxBy { it.textSeq ?: 0 }.content

    private fun telemetryCount(event: String): Int = Telemetry.events.value.count { it.name == event }

    /** The frames of one streamed reply or thought: the host's logical id and a numbered cumulative text. */
    private class Reply(val id: String) {
        fun at(seq: Int, text: String, seqId: Int? = null) = AssistantMessage(
            id = id,
            contentRaw = JsonPrimitive(text),
            logicalMessageId = id,
            textSeq = seq,
            seqId = seqId,
        )

        fun thought(seq: Int, text: String) = ReasoningMessage(id = id, reasoning = text, logicalMessageId = id, textSeq = seq)

        fun row(seq: Int, text: String): TimelineEvent.Confirmed = checkNotNull(at(seq, text).toTimelineEvent(position = 1.0))
    }

    /** One tool call and its return, named the way the host names them: `tc-<call id>` and `tr-<call id>`. */
    private enum class Args(val json: String) {
        EMPTY("{}"),
        DATE("""{"command":"date"}"""),
        UPTIME("""{"command":"uptime"}"""),
    }

    private class Tool(private val callId: String) {
        fun call(args: Args = Args.EMPTY) = ToolCallMessage(
            id = "toolcall-$callId",
            toolCall = ToolCall(toolCallId = callId, name = "Bash", arguments = args.json),
            toolCalls = listOf(ToolCall(toolCallId = callId, name = "Bash", arguments = args.json)),
            logicalMessageId = "tc-$callId",
        )

        fun returned(body: String) = ToolReturnMessage(
            id = "toolreturn-$callId",
            toolReturnRaw = JsonPrimitive(body),
            toolCallId = callId,
            status = "success",
            logicalMessageId = "tr-$callId",
        )
    }

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
        const val STALE = "live.staleTextFrame"
        const val UNSTAMPED = "live.unstampedFrame"
        const val DUPLICATE_APPEND = "live.duplicateAppend"
    }
}
