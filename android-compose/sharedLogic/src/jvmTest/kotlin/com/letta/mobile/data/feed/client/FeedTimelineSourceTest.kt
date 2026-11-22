package com.letta.mobile.data.feed.client

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.controller.node.iroh.feed.Conv
import com.letta.mobile.data.controller.node.iroh.feed.Delta
import com.letta.mobile.data.controller.node.iroh.feed.Ending
import com.letta.mobile.data.controller.node.iroh.feed.Ids
import com.letta.mobile.data.controller.node.iroh.feed.RouterFeedHost
import com.letta.mobile.data.controller.node.iroh.feed.TurnName
import com.letta.mobile.data.controller.node.iroh.feed.TurnScript
import com.letta.mobile.data.controller.node.iroh.feed.append
import com.letta.mobile.data.controller.node.iroh.feed.writerFor
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.model.LettaMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The client of the host feed against one real host (journal, registry, route, push): what a viewer does on first read, on a
 * push, after a dropped connection, after a host restart, against a host without the feed and out of scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedTimelineSourceTest {
    private val conv = Conv.default
    private val conversationId = conv.conversation

    private fun text(turn: String, message: String, content: String, seq: Int) = Delta(
        buildJsonObject {
            put("message_type", "assistant_message")
            put("id", "srv-$message")
            put("logical_message_id", message)
            put("turn_id", turn)
            put("content", content)
            put("text_seq", seq)
        }.toString(),
    )

    private suspend fun TestScope.settle() {
        advanceTimeBy(100)
        runCurrent()
    }

    private fun TestScope.source(host: RouterFeedHost, device: RouterFeedHost.Device, sink: MutableList<LettaMessage> = mutableListOf()) =
        FeedTimelineSource(device, Ids.agent, conversationId, backgroundScope) { sink += it }.also { it.start() }
            .also { host.hashCode() }

    private fun ChatRenderItem.text(): String = messagesInOrder().joinToString { it.content }

    @Test
    fun aFirstReadBuildsTheRowsAndLaterPushesKeepThemCurrent() = runTest {
        val host = RouterFeedHost(backgroundScope)
        val phone = host.device("phone")
        host.journal.writerFor(conv, TurnName("cm-1")).also { it.append(text("cm-1", "lm-1", "Hello", 1)) }
        val source = source(host, phone)
        runCurrent()

        assertEquals(listOf("Hello"), source.rows.value.map { it.text() })

        host.journal.writerFor(conv, TurnName("cm-2")).also { it.append(text("cm-2", "lm-2", "World", 1)) }
        settle()

        assertEquals(setOf("Hello", "World"), source.rows.value.map { it.text() }.toSet())
        assertEquals(1L, source.metrics.value.pagesRead, "the push carried the second turn: no second read")
        assertTrue(source.metrics.value.framesApplied >= 1)
    }

    @Test
    fun aSnapshotReplacesItsPredecessorInPlaceAndEveryOtherRowKeepsItsIdentity() = runTest {
        val host = RouterFeedHost(backgroundScope)
        val phone = host.device("phone")
        host.journal.writerFor(conv, TurnName("cm-1")).also {
            it.append(text("cm-1", "lm-1", "Done earlier", 1))
            it.close(true)
        }
        val running = host.journal.writerFor(conv, TurnName("cm-2")).also { it.append(text("cm-2", "lm-2", "Wor", 1)) }
        val source = source(host, phone)
        settle()
        val before = source.rows.value
        val untouched = before.single { it.text() == "Done earlier" }
        val replacementsBefore = source.metrics.value.listReplacements

        running.append(text("cm-2", "lm-2", "World", 2))
        settle()

        val after = source.rows.value
        assertEquals(before.size, after.size, "a replaced snapshot adds no row")
        assertSame(untouched, after.single { it.text() == "Done earlier" }, "the row nobody touched is the same instance")
        assertEquals("World", after.first { it.key == before.first { row -> row.text() == "Wor" }.key }.text())
        assertEquals(replacementsBefore, source.metrics.value.listReplacements, "an in-place update is no list replacement")
        assertEquals(1L, source.metrics.value.pagesRead)
    }

    @Test
    fun reconnectingFromTheCursorYieldsExactlyTheMissedEntriesAndNoneTwice() = runTest {
        val host = RouterFeedHost(backgroundScope)
        val phone = host.device("phone")
        val delivered = mutableListOf<LettaMessage>()
        host.journal.writerFor(conv, TurnName("cm-1")).also { it.append(text("cm-1", "lm-1", "First", 1)) }
        val source = source(host, phone, delivered)
        settle()
        val cursorBeforeCut = checkNotNull(source.cursor)

        phone.cut()
        host.journal.writerFor(conv, TurnName("cm-2")).also { it.append(text("cm-2", "lm-2", "Missed", 1)) }
        settle()
        assertEquals(listOf("First"), source.rows.value.map { it.text() }, "nothing arrives while the link is down")
        phone.restore()
        settle()

        val resume = host.reads.last()
        val from = resume.getValue(ConversationFeedWire.FROM).toString()
        assertTrue("\"seq\":${cursorBeforeCut.seq}" in from, "the read resumes from the cursor it held, was $from")
        assertEquals(listOf("lm-1", "lm-2"), delivered.map { it.id.removePrefix("srv-") }.distinct())
        assertEquals(delivered.size, delivered.map { it.id to it.textOf() }.distinct().size, "no entry is applied twice")
        assertEquals(setOf("First", "Missed"), source.rows.value.map { it.text() }.toSet())
    }

    private fun LettaMessage.textOf(): String = (this as? com.letta.mobile.data.model.AssistantMessage)?.content.orEmpty()

    @Test
    fun aHostRestartIsANewEpochAndTheClientResyncsInsteadOfGuessing() = runTest {
        val host = RouterFeedHost(backgroundScope)
        val phone = host.device("phone")
        host.journal.writerFor(conv, TurnName("cm-1")).also { it.append(text("cm-1", "lm-1", "Before", 1)) }
        val source = source(host, phone)
        settle()
        assertEquals(1000L, source.cursor?.epoch)

        host.restart()
        host.journal.writerFor(conv, TurnName("cm-2")).also { it.append(text("cm-2", "lm-2", "After", 1)) }
        settle()

        assertEquals(2000L, source.cursor?.epoch, "the cursor moved to the host's new epoch")
        assertEquals(1L, source.metrics.value.resyncs, "one resync, forced by the frame of the other epoch")
        assertEquals(setOf("Before", "After"), source.rows.value.map { it.text() }.toSet())
        val resyncFrom = host.reads.last()[ConversationFeedWire.FROM]?.toString().orEmpty()
        assertTrue("\"epoch\":1000" in resyncFrom, "the resync read named the old cursor")
    }

    @Test
    fun aHostWithoutThePushCapabilityOrWithoutTheFeedIsNotUsed() = runTest {
        val host = RouterFeedHost(backgroundScope).withoutPush()
        val readsOnly = host.device("reader")
        val cut = host.device("cut").also { it.cut() }

        assertFalse(FeedTimelineSource.isAvailable(readsOnly), "a feed that cannot push leaves the old path in place")
        assertFalse(FeedTimelineSource.isAvailable(cut), "no capabilities at all while disconnected")
        assertTrue(ConversationFeedWire.CAPABILITY in readsOnly.hostCapabilities)
        assertFalse(ConversationFeedWire.PUSH_CAPABILITY in readsOnly.hostCapabilities)
        assertTrue(FeedTimelineSource.isAvailable(RouterFeedHost(backgroundScope).device("full")))
    }

    @Test
    fun aViewerOutOfScopeIsRefusedIsNotRegisteredAndReceivesNothing() = runTest {
        val host = RouterFeedHost(backgroundScope)
        host.journal.writerFor(conv, TurnName("cm-1")).also { it.append(text("cm-1", "lm-1", "Secret", 1)) }
        val stranger = host.device("stranger", agents = setOf(Ids.otherAgent.value))
        val source = source(host, stranger)
        settle()
        host.journal.writerFor(conv, TurnName("cm-2")).also { it.append(text("cm-2", "lm-2", "Later", 1)) }
        settle()

        assertTrue(source.rows.value.isEmpty(), "the host refused the read: no rows")
        assertFalse(host.registry.isViewed(conversationId), "a refused read leaves no viewer behind")
        assertTrue(stranger.received.isEmpty(), "nothing was pushed to it")
    }

    @Test
    fun theUserPromptAndTheReplyBothReachTheFeedRows() = runTest {
        val host = RouterFeedHost(backgroundScope)
        val phone = host.device("phone")
        val user = Delta(
            buildJsonObject {
                put("message_type", "user_message")
                put("id", "cm-user-cm-1")
                put("otid", "cm-1")
                put("turn_id", "cm-1")
                put("logical_message_id", "um-1")
                put("content", JsonPrimitive("Hi there"))
            }.toString(),
        )
        host.write(conv, TurnScript(TurnName("cm-1"), listOf(user, text("cm-1", "lm-1", "Hello", 1)), Ending.Terminal))
        val source = source(host, phone)
        settle()

        val texts = source.rows.value.flatMap { it.messagesInOrder() }.map { it.content }
        assertTrue("Hi there" in texts && "Hello" in texts, "rows were $texts")
        assertEquals("agent-a", Ids.agent.value.also { host.hashCode() })
        assertEquals("a", JsonPrimitive("a").jsonPrimitive.content)
    }
}
