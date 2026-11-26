package com.letta.mobile.data.feed.client

import com.letta.mobile.data.controller.node.iroh.feed.Conv
import com.letta.mobile.data.controller.node.iroh.feed.Delta
import com.letta.mobile.data.controller.node.iroh.feed.Ids
import com.letta.mobile.data.controller.node.iroh.feed.RouterFeedHost
import com.letta.mobile.data.controller.node.iroh.feed.TurnName
import com.letta.mobile.data.controller.node.iroh.feed.append
import com.letta.mobile.data.controller.node.iroh.feed.writerFor
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecordingTest
import com.letta.mobile.util.numberOrNull
import com.letta.mobile.util.textOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the feed client tells telemetry (and so the flight record) against one real host: the position it applied, the turns it
 * saw open and close, and pushes it could not read; a streaming turn reports its position a handful of times, not per frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedFlightTelemetryTest : TelemetryRecordingTest() {
    private val conv = Conv.default

    private fun text(turn: String, seq: Int) = Delta(
        buildJsonObject {
            put("message_type", "assistant_message")
            put("id", "srv-lm-$turn")
            put("logical_message_id", "lm-$turn")
            put("turn_id", turn)
            put("content", "x".repeat(seq))
            put("text_seq", seq)
        }.toString(),
    )

    private fun named(name: String) = Telemetry.snapshot().filter { it.tag == "FeedTimeline" && it.name == name }.asReversed()

    private suspend fun TestScope.settle(ms: Long = 100) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun aStreamingTurnReportsItsAppliedSeqAFewTimesEndingOnTheCursorItHolds() = runTest {
        val host = RouterFeedHost(backgroundScope)
        val phone = host.device("phone")
        val source = FeedTimelineSource(phone, Ids.agent, conv.conversation, backgroundScope).also { it.start() }
        runCurrent()
        val writer = host.journal.writerFor(conv, TurnName("cm-1"))

        repeat(200) { index ->
            writer.append(text("cm-1", index + 1))
            settle(50)
        }
        writer.close(true)
        settle(6_000)

        val applied = named("applied")
        assertTrue(source.metrics.value.framesApplied >= 100, "the turn arrived as pushes: ${source.metrics.value.framesApplied}")
        assertTrue(applied.size in 1..5, "about one line per five seconds of streaming, not one per frame: ${applied.size}")
        assertEquals(source.cursor?.seq, applied.last().attrs["seq"]?.numberOrNull?.toLong(), "the last line names the seq held")
        assertEquals(conv.conversation.value, applied.last().attrs["conversationId"]?.textOrNull)
        assertEquals(listOf("cm-1"), named("turn.opened").map { it.attrs["turnId"]?.textOrNull })
        assertEquals(listOf("true"), named("turn.closed").map { it.attrs["terminalObserved"].toString() })
    }

    @Test
    fun theFirstReadIsReportedWithItsPageAndAnUnreadablePushIsReported() = runTest {
        val host = RouterFeedHost(backgroundScope)
        val phone = host.device("phone")
        host.journal.writerFor(conv, TurnName("cm-1")).also { it.append(text("cm-1", 1)) }
        FeedTimelineSource(phone, Ids.agent, conv.conversation, backgroundScope).also { it.start() }
        runCurrent()

        val page = named("read.page").single()
        assertEquals("snapshot", page.attrs["mode"]?.textOrNull)
        assertTrue((page.attrs["seq"]?.numberOrNull?.toLong() ?: 0L) > 0L, "the page names the cursor it left: $page")

        phone.writeFrame(buildJsonObject { put("type", "feed_delta") }.toString())
        settle()
        assertEquals(1, named("frame.undecodable").size, "a push the client cannot read is on record")
    }
}
