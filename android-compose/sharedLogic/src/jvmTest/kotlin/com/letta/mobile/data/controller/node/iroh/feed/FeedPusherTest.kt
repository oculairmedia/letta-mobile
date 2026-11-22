package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.controller.node.iroh.ConnectionRegistry
import com.letta.mobile.data.controller.node.iroh.ConversationViewers
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** The host's live push, behind the real journal, registry and route: when it speaks, to whom, and how much. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedPusherTest {
    private val conv = Conv.default
    private val budget = 16L

    private fun JsonObject.entrySeqs() = getValue("entries").jsonArray.map { it.jsonObject.getValue("seq").jsonPrimitive.long }

    private suspend fun subscribe(device: RouterFeedHost.Device, conversation: String = conv.conversation.value) {
        device.call(ConversationFeedWire.SUBSCRIBE, buildJsonObject { put(ConversationFeedWire.CONVERSATION_ID, conversation) })
    }

    @Test
    fun aBurstWithinOneFrameBudgetIsOneFrameNotOnePerWrite() = runTest {
        val host = RouterFeedHost(backgroundScope, frameBudgetMs = budget)
        val phone = host.device("phone").also { subscribe(it) }
        val writer = host.journal.writerFor(conv, TurnName("cm-1"))
        repeat(5) { writer.append(Delta.toolCall(CallId("t$it"))) }

        advanceTimeBy(budget + 1)
        runCurrent()

        assertEquals(1, phone.received.size, "five writes in one budget are one frame")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), phone.received.single().entrySeqs())
    }

    @Test
    fun aLaterWriteContinuesFromTheHeadTheLastFrameReached() = runTest {
        val host = RouterFeedHost(backgroundScope, frameBudgetMs = budget)
        val phone = host.device("phone").also { subscribe(it) }
        val writer = host.journal.writerFor(conv, TurnName("cm-1"))
        writer.append(Delta.toolCall(CallId("t1")))
        advanceTimeBy(budget + 1)
        runCurrent()
        writer.append(Delta.toolCall(CallId("t2")))
        advanceTimeBy(budget + 1)
        runCurrent()

        val (first, second) = phone.received
        assertEquals(0L, first.getValue("after_seq").jsonPrimitive.long)
        assertEquals(2L, second.getValue("after_seq").jsonPrimitive.long, "the second frame continues where the first ended")
        assertEquals(listOf(3L), second.entrySeqs())
    }

    @Test
    fun aBurstTooLargeForOneFrameBecomesAResyncMarkerAtTheHead() = runTest {
        val host = RouterFeedHost(backgroundScope, frameBudgetMs = budget)
        val phone = host.device("phone").also { subscribe(it) }
        val writer = host.journal.writerFor(conv, TurnName("cm-1"))
        repeat(200) { writer.append(Delta.toolCall(CallId("t$it"))) }

        advanceTimeBy(budget + 1)
        runCurrent()

        val marker = phone.received.single()
        assertTrue(marker.getValue("entries").jsonArray.isEmpty(), "a marker carries no entries")
        assertEquals(201L, marker.getValue("seq").jsonPrimitive.long)
        assertEquals(201L, marker.getValue("after_seq").jsonPrimitive.long, "after_seq at the head: any viewer below it resyncs")
    }

    @Test
    fun aConversationNobodyViewsCostsNoWorkAtAll() = runTest {
        val journal = FeedJournalStore(Files.createTempDirectory("feed-idle")) { 1000 }
        var asked = 0
        val viewers: ConversationViewers = { asked += 1; emptySet() }
        FeedPusher(backgroundScope, journal, viewers, ConnectionRegistry()::isViewed, budget)

        val writer = journal.writerFor(conv, TurnName("cm-1"))
        repeat(20) { writer.append(Delta.toolCall(CallId("t$it"))) }
        advanceTimeBy(10 * budget)
        runCurrent()

        assertEquals(0, asked, "with no viewer the pusher never even looks one up, and schedules no frame")
    }
}
