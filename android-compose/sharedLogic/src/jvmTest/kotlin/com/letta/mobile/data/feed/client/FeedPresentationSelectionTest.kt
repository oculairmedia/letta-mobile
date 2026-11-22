package com.letta.mobile.data.feed.client

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.controller.node.iroh.feed.Conv
import com.letta.mobile.data.controller.node.iroh.feed.Ids
import com.letta.mobile.data.controller.node.iroh.feed.RouterFeedHost
import com.letta.mobile.data.controller.node.iroh.feed.TurnName
import com.letta.mobile.data.controller.node.iroh.feed.writerFor
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.InMemoryTimelineStore
import com.letta.mobile.data.timeline.TimelineEnginePageOutcome
import com.letta.mobile.data.timeline.TimelinePageProgress
import com.letta.mobile.data.timeline.TimelineRemotePageRequest
import com.letta.mobile.data.timeline.TimelineRemotePageResult
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Which source the shared presentation draws a conversation from, against one real host (journal, route, registry, push):
 * the host feed when the host serves and pushes it ([FeedTimelineSource.rowsFor]), the ledger otherwise, never both. A
 * feed-backed conversation performs no ledger reconcile (no `message.list` merge), a growing reply redraws only its own row,
 * and switching conversations rebinds the feed to the new one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedPresentationSelectionTest {
    /** The ledger's page source. Counts every `message.list` read; it has no history to give. */
    private class LedgerPages : TimelineTransport {
        var reads = 0

        override suspend fun listConversationMessagePage(
            request: TimelineRemotePageRequest,
            progress: TimelinePageProgress?,
        ): TimelineRemotePageResult {
            reads += 1
            return TimelineRemotePageResult.Page(request.requestId, request.selectionGeneration, emptyList(), null, false, 0)
        }

        override suspend fun sendConversationMessage(conversationId: ConversationId, request: MessageCreateRequest): Flow<LettaMessage> =
            error("no send here")

        override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> = error("no stream here")

        override suspend fun listConversationMessages(
            conversationId: ConversationId, limit: Int?, after: String?, order: String?,
        ): List<LettaMessage> = error("no legacy hydration")

        override suspend fun listAgentMessages(
            agentId: AgentId, limit: Int?, order: String?, conversationId: ConversationId?,
        ): List<LettaMessage> = error("no legacy hydration")
    }

    private fun text(turn: String, message: String, content: String, seq: Int) = buildJsonObject {
        put("message_type", "assistant_message")
        put("id", "srv-$message")
        put("logical_message_id", message)
        put("turn_id", turn)
        put("content", content)
        put("text_seq", seq)
    }.toString()

    private suspend fun TestScope.settle() {
        advanceTimeBy(100)
        runCurrent()
    }

    private fun ChatRenderItem.text(): String = messagesInOrder().joinToString { it.content }

    private class Opened(val coordinator: CanonicalTimelineCoordinator, val owner: CanonicalTimelineCoordinator.Owner, val ledger: LedgerPages)

    private suspend fun ledgerFor(conversation: ConversationId): Opened {
        val ledger = LedgerPages()
        val coordinator = CanonicalTimelineCoordinator(InMemoryTimelineStore(), ledger)
        return Opened(coordinator, coordinator.acquire(TimelineScope("backend", conversation.value, Ids.agent.value)), ledger)
    }

    private suspend fun TestScope.open(opened: Opened, link: FeedLink, conversation: ConversationId) =
        CanonicalTimelinePresentation.open(
            opened.coordinator, opened.owner, backgroundScope, feed = FeedTimelineSource.rowsFor(link, Ids.agent, conversation),
        )

    @Test
    fun aFeedBackedConversationDrawsTheFeedRedrawsOnlyTheGrowingRowAndNeverReconcilesTheLedger() = runTest {
        val host = RouterFeedHost(backgroundScope)
        val conv = Conv.default
        host.journal.writerFor(conv, TurnName("cm-1")).also {
            it.append(text("cm-1", "lm-1", "Done earlier", 1))
            it.close(true)
        }
        val running = host.journal.writerFor(conv, TurnName("cm-2")).also { it.append(text("cm-2", "lm-2", "Wor", 1)) }
        val opened = ledgerFor(conv.conversation)
        val presentation = open(opened, host.device("phone"), conv.conversation)
        settle()

        assertTrue(presentation.isFeedBacked)
        val before = presentation.live.value
        assertEquals(setOf("Done earlier", "Wor"), before.map { it.text() }.toSet())
        val untouched = before.single { it.text() == "Done earlier" }

        running.append(text("cm-2", "lm-2", "World", 2))
        settle()

        val after = presentation.live.value
        assertEquals(before.map { it.key }, after.map { it.key }, "a growing reply keeps every row's key and place")
        assertSame(untouched, after.single { it.text() == "Done earlier" }, "the row nobody touched is the same instance")
        assertEquals(1, after.indices.count { after[it] !== before[it] }, "only the growing row is a new instance")
        assertEquals("World", after.single { it.key == before.single { row -> row.text() == "Wor" }.key }.text())

        assertEquals(TimelineEnginePageOutcome.Stale, opened.coordinator.reconcileRecent(opened.owner))
        assertEquals(0, opened.ledger.reads, "a feed-backed conversation never merges a message.list page into the ledger")
        presentation.close()
    }

    @Test
    fun aHostWithoutThePushCapabilityKeepsTheLedgerPathUnchanged() = runTest {
        val host = RouterFeedHost(backgroundScope).withoutPush()
        val conv = Conv.default
        val device = host.device("phone")
        assertNull(FeedTimelineSource.rowsFor(device, Ids.agent, conv.conversation), "no push capability: no feed rows")
        val opened = ledgerFor(conv.conversation)
        val presentation = open(opened, device, conv.conversation)
        settle()

        assertFalse(presentation.isFeedBacked)
        assertTrue(host.reads.isEmpty(), "the ledger path never reads the feed")
        assertEquals(TimelineEnginePageOutcome.Applied, opened.coordinator.reconcileRecent(opened.owner))
        assertEquals(1, opened.ledger.reads, "the ledger path reconciles as it always did")
        presentation.close()
    }

    @Test
    fun aPromptTheFeedEchoesLeavesThePendingStoreAndIsDrawnOnce() = runTest {
        val host = RouterFeedHost(backgroundScope)
        val conv = Conv.default
        val opened = ledgerFor(conv.conversation)
        val presentation = open(opened, host.device("phone"), conv.conversation)
        settle()
        opened.coordinator.appendPending(opened.owner, CanonicalPendingLocalStore.Record("cm-9", "Hi there", emptyList(), "2026-01-01T00:00:00Z"))
        settle()
        assertEquals(listOf("Hi there"), presentation.live.value.map { it.text() }, "an unsent prompt is drawn from pending")

        host.journal.writerFor(conv, TurnName("cm-9")).append(
            buildJsonObject {
                put("message_type", "user_message"); put("id", "cm-user-cm-9"); put("otid", "cm-9"); put("turn_id", "cm-9")
                put("logical_message_id", "um-9"); put("content", "Hi there")
            }.toString(),
        )
        settle()

        assertTrue(opened.owner.session.pending.value.none { it.otid == "cm-9" }, "the feed's echo confirmed the send")
        assertEquals(listOf("Hi there"), presentation.live.value.map { it.text() }, "the prompt is drawn once, from the feed")
        presentation.close()
    }

    @Test
    fun switchingConversationsRebindsTheFeedToTheNewOne() = runTest {
        val host = RouterFeedHost(backgroundScope)
        val first = Conv.default
        val second = Conv.of("conv-2")
        host.journal.writerFor(first, TurnName("cm-1")).append(text("cm-1", "lm-1", "First", 1))
        host.journal.writerFor(second, TurnName("cm-2")).append(text("cm-2", "lm-2", "Second", 1))
        val phone = host.device("phone")

        val one = open(ledgerFor(first.conversation), phone, first.conversation)
        settle()
        assertEquals(listOf("First"), one.live.value.map { it.text() })
        one.close()

        val two = open(ledgerFor(second.conversation), phone, second.conversation)
        settle()
        assertEquals(listOf("Second"), two.live.value.map { it.text() })
        assertTrue(host.registry.isViewed(second.conversation), "the device now views the conversation it switched to")
        assertFalse(host.registry.isViewed(first.conversation), "and no longer the one it left")

        host.journal.writerFor(first, TurnName("cm-3")).append(text("cm-3", "lm-3", "Elsewhere", 1))
        host.journal.writerFor(second, TurnName("cm-4")).append(text("cm-4", "lm-4", "Here", 1))
        settle()
        assertEquals(setOf("Second", "Here"), two.live.value.map { it.text() }.toSet(), "only the new conversation's pushes land")
        assertEquals(listOf("First"), one.live.value.map { it.text() }, "the closed presentation stopped following")
        assertNotNull(two)
        two.close()
    }
}
